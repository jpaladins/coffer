// SPDX-License-Identifier: GPL-3.0-or-later
use crate::io::{SectorCache, VolumeIo};
use coffer_vfs::{names_equal, split_path, CofferError, DirEntry, Filesystem, FsKind, FsStats};
use coffer_volume::BlockDevice;
use std::io::{Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex};

type Fs = fatfs::FileSystem<VolumeIo>;
type FatEntry<'a> = fatfs::DirEntry<'a, VolumeIo>;

/// FAT spec: a directory holds at most 65,536 32-byte entries. Iterating
/// further means a cyclic or corrupted cluster chain.
const MAX_DIR_ENTRIES: usize = 65_536;
/// Bytes a metadata operation (stat, list, path resolution) may read.
const META_BUDGET: u64 = 64 * 1024 * 1024;

/// Read-only FAT12/16/32 filesystem. A fatfs `FileSystem` is not `Sync`
/// (it holds non-`Sync` global converters), so one is built per operation;
/// that costs one boot-sector read.
pub struct FatFs {
    dev: Arc<dyn BlockDevice>,
    kind: FsKind,
    cache: Arc<Mutex<SectorCache>>,
}

/// 8192 sectors = 4 MiB of decrypted metadata and small-cluster cache.
const CACHE_SECTORS: usize = 8192;

fn corrupted(e: impl std::fmt::Display) -> CofferError {
    let msg = e.to_string();
    if msg.contains(crate::io::BUDGET_EXCEEDED) {
        CofferError::Corrupted("FAT: cluster chain too long (cyclic or corrupted)".into())
    } else {
        CofferError::Corrupted(format!("FAT: {msg}"))
    }
}

impl FatFs {
    pub fn open(dev: Arc<dyn BlockDevice>) -> Result<Self, CofferError> {
        let cache = Arc::new(Mutex::new(SectorCache::new(CACHE_SECTORS)));
        let fs = Self::mount(&dev, META_BUDGET, &cache)?;
        let kind = match fs.fat_type() {
            fatfs::FatType::Fat12 => FsKind::Fat12,
            fatfs::FatType::Fat16 => FsKind::Fat16,
            fatfs::FatType::Fat32 => FsKind::Fat32,
        };
        Ok(Self { dev, kind, cache })
    }

    fn mount(
        dev: &Arc<dyn BlockDevice>,
        budget: u64,
        cache: &Arc<Mutex<SectorCache>>,
    ) -> Result<Fs, CofferError> {
        fatfs::FileSystem::new(
            VolumeIo::new(dev.clone(), budget, cache.clone()),
            fatfs::FsOptions::new(),
        )
        .map_err(corrupted)
    }

    fn lock(&self) -> Result<Fs, CofferError> {
        Self::mount(&self.dev, META_BUDGET, &self.cache)
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
        let fs = self.lock()?;
        let node = resolve(&fs, path)?;
        let dir = as_dir(&fs, &node, path)?;
        Ok(entries(&dir)?.iter().map(to_entry).collect())
    }

    fn read(&self, path: &str, offset: u64, buf: &mut [u8]) -> Result<usize, CofferError> {
        let fs = Self::mount(
            &self.dev,
            META_BUDGET.saturating_add(buf.len() as u64),
            &self.cache,
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

    fn stats(&self) -> Result<FsStats, CofferError> {
        // Counting free clusters reads the whole FAT.
        let fs = Self::mount(&self.dev, self.dev.len(), &self.cache)?;
        let s = fs.stats().map_err(corrupted)?;
        let cs = u64::from(s.cluster_size());
        Ok(FsStats {
            total_bytes: u64::from(s.total_clusters()) * cs,
            free_bytes: Some(u64::from(s.free_clusters()) * cs),
            cluster_size: s.cluster_size(),
        })
    }
}
