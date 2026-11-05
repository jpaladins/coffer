// SPDX-License-Identifier: GPL-3.0-or-later
use crate::io::{SectorCache, VolumeIo};
use coffer_vfs::{
    names_equal, split_parent, split_path, validate_name, CofferError, DirEntry, Filesystem,
    FsKind, FsStats,
};
use coffer_volume::BlockDevice;
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::{Arc, Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard};

type Fs = fatfs::FileSystem<VolumeIo>;
type FatEntry<'a> = fatfs::DirEntry<'a, VolumeIo>;

/// FAT spec: a directory holds at most 65,536 32-byte entries. Iterating
/// further means a cyclic or corrupted cluster chain.
const MAX_DIR_ENTRIES: usize = 65_536;
/// Bytes a metadata operation (stat, list, path resolution) may read.
const META_BUDGET: u64 = 64 * 1024 * 1024;

/// FAT12/16/32 filesystem (read-only unless opened with `open_rw`). A
/// fatfs `FileSystem` is not `Sync`
/// (it holds non-`Sync` global converters), so one is built per operation;
/// that costs one boot-sector read.
pub struct FatFs {
    dev: Arc<dyn BlockDevice>,
    kind: FsKind,
    cache: Arc<Mutex<SectorCache>>,
    writable: bool,
    /// Every operation mounts its own fatfs instance, which caches FAT and
    /// directory state: two writers (or a reader during a write) would work
    /// from stale copies and cross-link clusters. Writes are exclusive.
    ops: RwLock<()>,
}

/// 8192 sectors = 4 MiB of decrypted metadata and small-cluster cache.
const CACHE_SECTORS: usize = 8192;

/// Maps fatfs/io errors back to Coffer errors. Device errors were wrapped
/// whole by `VolumeIo`, so their variant survives.
pub(crate) fn map_io(e: std::io::Error) -> CofferError {
    if let Some(c) = e.get_ref().and_then(|r| r.downcast_ref::<CofferError>()) {
        return c.clone();
    }
    let msg = e.to_string();
    if msg.contains(crate::io::BUDGET_EXCEEDED) {
        CofferError::Corrupted("FAT: cluster chain too long (cyclic or corrupted)".into())
    } else if msg.contains("No space left") {
        CofferError::NoSpace
    } else if msg.contains("not empty") {
        CofferError::NotEmpty(String::new())
    } else if e.kind() == std::io::ErrorKind::AlreadyExists {
        CofferError::AlreadyExists(String::new())
    } else {
        CofferError::Corrupted(format!("FAT: {msg}"))
    }
}

fn corrupted(e: std::io::Error) -> CofferError {
    map_io(e)
}

impl FatFs {
    pub fn open(dev: Arc<dyn BlockDevice>) -> Result<Self, CofferError> {
        Self::open_with(dev, false)
    }

    /// Opens for reading and writing (the device must be writable).
    pub fn open_rw(dev: Arc<dyn BlockDevice>) -> Result<Self, CofferError> {
        Self::open_with(dev, true)
    }

    fn open_with(dev: Arc<dyn BlockDevice>, writable: bool) -> Result<Self, CofferError> {
        let cache = Arc::new(Mutex::new(SectorCache::new(CACHE_SECTORS)));
        let fs = Self::mount(&dev, META_BUDGET, &cache, false)?;
        let kind = match fs.fat_type() {
            fatfs::FatType::Fat12 => FsKind::Fat12,
            fatfs::FatType::Fat16 => FsKind::Fat16,
            fatfs::FatType::Fat32 => FsKind::Fat32,
        };
        Ok(Self {
            dev,
            kind,
            cache,
            writable,
            ops: RwLock::new(()),
        })
    }

    fn mount(
        dev: &Arc<dyn BlockDevice>,
        budget: u64,
        cache: &Arc<Mutex<SectorCache>>,
        writable: bool,
    ) -> Result<Fs, CofferError> {
        let options = fatfs::FsOptions::new()
            .time_provider(&crate::clock::CLOCK)
            .update_accessed_date(false);
        fatfs::FileSystem::new(
            VolumeIo::new(dev.clone(), budget, cache.clone(), writable),
            options,
        )
        .map_err(corrupted)
    }

    fn shared(&self) -> RwLockReadGuard<'_, ()> {
        self.ops.read().unwrap_or_else(|p| p.into_inner())
    }

    fn exclusive(&self) -> RwLockWriteGuard<'_, ()> {
        self.ops.write().unwrap_or_else(|p| p.into_inner())
    }

    fn lock(&self) -> Result<Fs, CofferError> {
        Self::mount(&self.dev, META_BUDGET, &self.cache, false)
    }

    /// A writable mount for one operation; the caller unmounts it so the
    /// FSInfo sector and dirty flag are written before returning.
    fn rw(&self) -> Result<Fs, CofferError> {
        if !self.writable {
            return Err(CofferError::ReadOnly);
        }
        // Writes walk chains too; bound the work by a multiple of the volume.
        Self::mount(
            &self.dev,
            self.dev.len().saturating_mul(4),
            &self.cache,
            true,
        )
    }
}

/// The node a path resolves to: the root directory or a directory entry.
enum Node<'a> {
    Root,
    Entry(FatEntry<'a>),
}

fn entries<'a>(dir: &fatfs::Dir<'a, VolumeIo>) -> Result<Vec<FatEntry<'a>>, CofferError> {
    let mut out = Vec::new();
    for (i, e) in dir.iter().enumerate() {
        if i >= MAX_DIR_ENTRIES {
            return Err(CofferError::Corrupted(
                "FAT: directory exceeds 65536 entries (cyclic cluster chain?)".into(),
            ));
        }
        let e = e.map_err(corrupted)?;
        let name = e.file_name();
        if name != "." && name != ".." {
            out.push(e);
        }
    }
    Ok(out)
}

fn as_dir<'a>(
    fs: &'a Fs,
    node: &Node<'a>,
    path: &str,
) -> Result<fatfs::Dir<'a, VolumeIo>, CofferError> {
    match node {
        Node::Root => Ok(fs.root_dir()),
        Node::Entry(e) if e.is_dir() => Ok(e.to_dir()),
        Node::Entry(_) => Err(CofferError::NotADirectory(path.to_string())),
    }
}

fn resolve<'a>(fs: &'a Fs, path: &str) -> Result<Node<'a>, CofferError> {
    let parts = split_path(path)?;
    let mut node = Node::Root;
    let mut walked = String::new();
    for part in parts {
        let dir = as_dir(fs, &node, if walked.is_empty() { "/" } else { &walked })?;
        walked.push('/');
        walked.push_str(part);
        let found = entries(&dir)?
            .into_iter()
            .find(|e| names_equal(&e.file_name(), part))
            .ok_or_else(|| CofferError::NotFound(walked.clone()))?;
        node = Node::Entry(found);
    }
    Ok(node)
}

/// Days since 1970-01-01 for a proleptic Gregorian date.
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn unix_time(dt: fatfs::DateTime) -> Option<i64> {
    let (d, t) = (dt.date, dt.time);
    if !(1..=12).contains(&d.month) || !(1..=31).contains(&d.day) || t.hour > 23 || t.min > 59 {
        return None;
    }
    let days = days_from_civil(i64::from(d.year), i64::from(d.month), i64::from(d.day));
    Some(days * 86_400 + i64::from(t.hour) * 3600 + i64::from(t.min) * 60 + i64::from(t.sec))
}

fn to_entry(e: &FatEntry<'_>) -> DirEntry {
    DirEntry {
        name: e.file_name(),
        is_dir: e.is_dir(),
        size: if e.is_dir() { 0 } else { e.len() },
        modified: unix_time(e.modified()),
    }
}

impl Filesystem for FatFs {
    fn kind(&self) -> FsKind {
        self.kind
    }

    fn stat(&self, path: &str) -> Result<DirEntry, CofferError> {
        let _ops = self.shared();
        let fs = self.lock()?;
        Ok(match resolve(&fs, path)? {
            Node::Root => DirEntry {
                name: String::new(),
                is_dir: true,
                size: 0,
                modified: None,
            },
            Node::Entry(e) => to_entry(&e),
        })
    }

    fn list(&self, path: &str) -> Result<Vec<DirEntry>, CofferError> {
        let _ops = self.shared();
        let fs = self.lock()?;
        let node = resolve(&fs, path)?;
        let dir = as_dir(&fs, &node, path)?;
        Ok(entries(&dir)?.iter().map(to_entry).collect())
    }

    fn read(&self, path: &str, offset: u64, buf: &mut [u8]) -> Result<usize, CofferError> {
        let _ops = self.shared();
        let fs = Self::mount(
            &self.dev,
            META_BUDGET.saturating_add(buf.len() as u64),
            &self.cache,
            false,
        )?;
        let e = match resolve(&fs, path)? {
            Node::Root => return Err(CofferError::IsADirectory(path.to_string())),
            Node::Entry(e) if e.is_dir() => {
                return Err(CofferError::IsADirectory(path.to_string()))
            }
            Node::Entry(e) => e,
        };
        if offset >= e.len() || buf.is_empty() {
            return Ok(0);
        }
        let short = || CofferError::Corrupted("FAT: cluster chain shorter than file size".into());
        let mut f = e.to_file();
        // fatfs clamps a seek to the end of the chain instead of failing.
        if f.seek(SeekFrom::Start(offset)).map_err(corrupted)? != offset {
            return Err(short());
        }
        // Clamp in u64 before narrowing: usize is 32-bit on armeabi-v7a.
        let want = (buf.len() as u64).min(e.len() - offset) as usize;
        let mut done = 0;
        while done < want {
            match f.read(&mut buf[done..want]).map_err(corrupted)? {
                0 => return Err(short()),
                n => done += n,
            }
        }
        Ok(done)
    }

    fn is_writable(&self) -> bool {
        self.writable
    }

    fn create_file(&self, path: &str) -> Result<(), CofferError> {
        let _ops = self.exclusive();
        let (parent, name) = split_parent(path)?;
        validate_name(name)?;
        let fs = self.rw()?;
        {
            let dir = dir_at(&fs, &parent)?;
            if entries(&dir)?
                .iter()
                .any(|e| names_equal(&e.file_name(), name))
            {
                return Err(CofferError::AlreadyExists(path.to_string()));
            }
            dir.create_file(name).map_err(map_io)?;
        }
        fs.unmount().map_err(map_io)
    }

    fn mkdir(&self, path: &str) -> Result<(), CofferError> {
        let _ops = self.exclusive();
        let (parent, name) = split_parent(path)?;
        validate_name(name)?;
        let fs = self.rw()?;
        {
            let dir = dir_at(&fs, &parent)?;
            if entries(&dir)?
                .iter()
                .any(|e| names_equal(&e.file_name(), name))
            {
                return Err(CofferError::AlreadyExists(path.to_string()));
            }
            dir.create_dir(name).map_err(map_io)?;
        }
        fs.unmount().map_err(map_io)
    }

    fn write(&self, path: &str, offset: u64, data: &[u8]) -> Result<(), CofferError> {
        let _ops = self.exclusive();
        let fs = self.rw()?;
        {
            let e = file_at(&fs, path)?;
            let len = e.len();
            let mut f = e.to_file();
            if offset > len {
                f.seek(SeekFrom::End(0)).map_err(map_io)?;
                write_zeros(&mut f, offset - len)?;
            } else {
                f.seek(SeekFrom::Start(offset)).map_err(map_io)?;
            }
            f.write_all(data).map_err(map_io)?;
            f.flush().map_err(map_io)?;
        }
        fs.unmount().map_err(map_io)
    }

    fn set_len(&self, path: &str, new_len: u64) -> Result<(), CofferError> {
        let _ops = self.exclusive();
        let fs = self.rw()?;
        {
            let e = file_at(&fs, path)?;
            let len = e.len();
            let mut f = e.to_file();
            if new_len < len {
                f.seek(SeekFrom::Start(new_len)).map_err(map_io)?;
                f.truncate().map_err(map_io)?;
            } else if new_len > len {
                f.seek(SeekFrom::End(0)).map_err(map_io)?;
                write_zeros(&mut f, new_len - len)?;
            }
            f.flush().map_err(map_io)?;
        }
        fs.unmount().map_err(map_io)
    }

    fn remove(&self, path: &str) -> Result<(), CofferError> {
        let _ops = self.exclusive();
        let (parent, _) = split_parent(path)?;
        let fs = self.rw()?;
        {
            let e = match resolve(&fs, path)? {
                Node::Root => return Err(CofferError::InvalidPath(path.to_string())),
                Node::Entry(e) => e,
            };
            if e.is_dir() && !entries(&e.to_dir())?.is_empty() {
                return Err(CofferError::NotEmpty(path.to_string()));
            }
            dir_at(&fs, &parent)?
                .remove(&e.file_name())
                .map_err(map_io)?;
        }
        fs.unmount().map_err(map_io)
    }

    fn rename(&self, from: &str, to: &str) -> Result<(), CofferError> {
        let _ops = self.exclusive();
        let (src_parent, _) = split_parent(from)?;
        let (dst_parent, dst_name) = split_parent(to)?;
        validate_name(dst_name)?;
        let from_parts = split_path(from)?;
        let to_parts = split_path(to)?;
        // A directory cannot move inside itself.
        if to_parts.len() > from_parts.len()
            && from_parts
                .iter()
                .zip(&to_parts)
                .all(|(a, b)| names_equal(a, b))
        {
            return Err(CofferError::InvalidPath(to.to_string()));
        }
        let fs = self.rw()?;
        {
            let src = match resolve(&fs, from)? {
                Node::Root => return Err(CofferError::InvalidPath(from.to_string())),
                Node::Entry(e) => e,
            };
            let dst_dir = dir_at(&fs, &dst_parent)?;
            let src_name = src.file_name();
            let same_dir = {
                let a = split_path(&src_parent)?;
                let b = split_path(&dst_parent)?;
                a.len() == b.len() && a.iter().zip(&b).all(|(x, y)| names_equal(x, y))
            };
            if same_dir && names_equal(&src_name, dst_name) {
                if src_name == dst_name {
                    return Ok(());
                }
                // Case-only change: fatfs sees the same entry and does nothing,
                // so go through a temporary name.
                let tmp = format!("~coffer-rename-{}.tmp", std::process::id());
                dst_dir.rename(&src_name, &dst_dir, &tmp).map_err(map_io)?;
                dst_dir.rename(&tmp, &dst_dir, dst_name).map_err(map_io)?;
            } else {
                if entries(&dst_dir)?
                    .iter()
                    .any(|e| names_equal(&e.file_name(), dst_name))
                {
                    return Err(CofferError::AlreadyExists(to.to_string()));
                }
                dir_at(&fs, &src_parent)?
                    .rename(&src_name, &dst_dir, dst_name)
                    .map_err(map_io)?;
            }
        }
        fs.unmount().map_err(map_io)
    }

    fn sync(&self) -> Result<(), CofferError> {
        self.dev.flush()
    }

    fn stats(&self) -> Result<FsStats, CofferError> {
        let _ops = self.shared();
        // Counting free clusters reads the whole FAT.
        let fs = Self::mount(&self.dev, self.dev.len(), &self.cache, false)?;
        let s = fs.stats().map_err(corrupted)?;
        let cs = u64::from(s.cluster_size());
        Ok(FsStats {
            total_bytes: u64::from(s.total_clusters()) * cs,
            free_bytes: Some(u64::from(s.free_clusters()) * cs),
            cluster_size: s.cluster_size(),
        })
    }
}

fn dir_at<'a>(fs: &'a Fs, path: &str) -> Result<fatfs::Dir<'a, VolumeIo>, CofferError> {
    let node = resolve(fs, path)?;
    as_dir(fs, &node, path)
}

fn file_at<'a>(fs: &'a Fs, path: &str) -> Result<FatEntry<'a>, CofferError> {
    match resolve(fs, path)? {
        Node::Entry(e) if !e.is_dir() => Ok(e),
        _ => Err(CofferError::IsADirectory(path.to_string())),
    }
}

fn write_zeros(f: &mut fatfs::File<'_, VolumeIo>, mut n: u64) -> Result<(), CofferError> {
    let zeros = [0u8; 64 * 1024];
    while n > 0 {
        let k = n.min(zeros.len() as u64) as usize;
        f.write_all(&zeros[..k]).map_err(map_io)?;
        n -= k as u64;
    }
    Ok(())
}
