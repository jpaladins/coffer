// SPDX-License-Identifier: GPL-3.0-or-later
use crate::boot::{boot_region_len, parse_boot, BootInfo};
use crate::dir::{parse_dir, ExEntry};
use crate::fat::{chain_for_len, chain_until_end};
use coffer_vfs::{names_equal, split_path, CofferError, DirEntry, Filesystem, FsKind, FsStats};
use coffer_volume::BlockDevice;
use std::sync::{Arc, Mutex};

/// Spec: a directory is at most 256 MiB.
const MAX_DIR_BYTES: u64 = 256 * 1024 * 1024;
/// Upper bound on path depth (resolution work stays bounded on loops).
const MAX_DEPTH: usize = 255;

/// (first cluster, data length) and the clusters of that chain.
type CachedChain = ((u32, u64), Arc<Vec<u32>>);

/// Read-only exFAT filesystem.
pub struct ExFatFs {
    dev: Arc<dyn BlockDevice>,
    boot: BootInfo,
    /// Last resolved cluster chain (first cluster, length) -> clusters, so
    /// sequential reads of a FAT-chained file do not re-walk the FAT.
    chain_cache: Mutex<Option<CachedChain>>,
}

enum Node {
    Root,
    Entry(ExEntry),
}

impl ExFatFs {
    pub fn open(dev: Arc<dyn BlockDevice>) -> Result<Self, CofferError> {
        let mut s0 = vec![0u8; 512];
        dev.read_at(0, &mut s0)?;
        let mut region = vec![0u8; boot_region_len(&s0)?];
        dev.read_at(0, &mut region)?;
        let boot = parse_boot(&region)?;
        let needed = boot
            .volume_length
            .saturating_mul(u64::from(boot.bytes_per_sector));
        if needed > dev.len() {
            return Err(CofferError::Corrupted(
                "exFAT: VolumeLength exceeds the volume".into(),
            ));
        }
        Ok(Self {
            dev,
            boot,
            chain_cache: Mutex::new(None),
        })
    }

    fn chain(&self, e: &ExEntry) -> Result<Arc<Vec<u32>>, CofferError> {
        let key = (e.first_cluster, e.data_length);
        let mut cache = self.chain_cache.lock().unwrap_or_else(|p| p.into_inner());
        if let Some((k, v)) = cache.as_ref() {
            if *k == key {
                return Ok(v.clone());
            }
        }
        let v = Arc::new(chain_for_len(
            self.dev.as_ref(),
            &self.boot,
            e.first_cluster,
            e.no_fat_chain,
            e.data_length,
        )?);
        *cache = Some((key, v.clone()));
        Ok(v)
    }

    /// Reads `buf.len()` bytes at `offset` of a cluster list (runs of
    /// consecutive clusters are read in one call).
    fn read_clusters(
        &self,
        clusters: &[u32],
        offset: u64,
        buf: &mut [u8],
    ) -> Result<(), CofferError> {
        let cs = self.boot.cluster_size();
        let mut done = 0usize;
        while done < buf.len() {
            let pos = offset + done as u64;
            let idx = (pos / cs) as usize;
            let first = *clusters
                .get(idx)
                .ok_or_else(|| CofferError::Corrupted("exFAT: read beyond chain".into()))?;
            let mut run = 1;
            while idx + run < clusters.len() && clusters[idx + run] == first + run as u32 {
                run += 1;
            }
            let in_run = run as u64 * cs - pos % cs;
            let n = (buf.len() - done).min(in_run as usize);
            self.dev.read_at(
                self.boot.cluster_offset(first) + pos % cs,
                &mut buf[done..done + n],
            )?;
            done += n;
        }
        Ok(())
    }

    fn dir_entries(&self, node: &Node, path: &str) -> Result<Vec<ExEntry>, CofferError> {
        let (clusters, len) = match node {
            Node::Root => {
                let c = chain_until_end(self.dev.as_ref(), &self.boot, self.boot.root_cluster)?;
                let len = c.len() as u64 * self.boot.cluster_size();
                (Arc::new(c), len)
            }
            Node::Entry(e) if e.is_dir => (self.chain(e)?, e.data_length),
            Node::Entry(_) => return Err(CofferError::NotADirectory(path.to_string())),
        };
        if len > MAX_DIR_BYTES {
            return Err(CofferError::Corrupted(
                "exFAT: directory larger than 256 MiB".into(),
            ));
        }
        let mut raw = vec![0u8; len as usize];
        self.read_clusters(&clusters, 0, &mut raw)?;
        parse_dir(&raw)
    }

    fn resolve(&self, path: &str) -> Result<Node, CofferError> {
        let parts = split_path(path)?;
        if parts.len() > MAX_DEPTH {
            return Err(CofferError::InvalidPath(format!(
                "{path}: deeper than {MAX_DEPTH}"
            )));
        }
        let mut node = Node::Root;
        let mut walked = String::new();
        for part in parts {
            let here = if walked.is_empty() {
                "/".to_string()
            } else {
                walked.clone()
            };
            let entries = self.dir_entries(&node, &here)?;
            walked.push('/');
            walked.push_str(part);
            let e = entries
                .into_iter()
                .find(|e| names_equal(&e.name, part))
                .ok_or_else(|| CofferError::NotFound(walked.clone()))?;
            node = Node::Entry(e);
        }
        Ok(node)
    }

    fn to_dir_entry(e: &ExEntry) -> DirEntry {
        DirEntry {
            name: e.name.clone(),
            is_dir: e.is_dir,
            size: if e.is_dir { 0 } else { e.data_length },
            modified: e.modified,
        }
    }

    /// Finds the allocation bitmap entry (type 0x81) in the root directory.
    fn bitmap(&self) -> Result<Option<(u32, u64)>, CofferError> {
        let c = chain_until_end(self.dev.as_ref(), &self.boot, self.boot.root_cluster)?;
        let mut raw =
            vec![0u8; (c.len() as u64 * self.boot.cluster_size()).min(MAX_DIR_BYTES) as usize];
        self.read_clusters(&c, 0, &mut raw)?;
        for e in raw.chunks(32) {
            match e[0] {
                0x00 => break,
                0x81 => {
                    let first = u32::from_le_bytes([e[20], e[21], e[22], e[23]]);
                    let mut l = [0u8; 8];
                    l.copy_from_slice(&e[24..32]);
                    return Ok(Some((first, u64::from_le_bytes(l))));
                }
                _ => {}
            }
        }
        Ok(None)
    }
}

impl Filesystem for ExFatFs {
    fn kind(&self) -> FsKind {
        FsKind::ExFat
    }

    fn stat(&self, path: &str) -> Result<DirEntry, CofferError> {
        Ok(match self.resolve(path)? {
            Node::Root => DirEntry {
                name: String::new(),
                is_dir: true,
                size: 0,
                modified: None,
            },
            Node::Entry(e) => Self::to_dir_entry(&e),
        })
    }

    fn list(&self, path: &str) -> Result<Vec<DirEntry>, CofferError> {
        let node = self.resolve(path)?;
        Ok(self
            .dir_entries(&node, path)?
            .iter()
            .map(Self::to_dir_entry)
            .collect())
    }

    fn read(&self, path: &str, offset: u64, buf: &mut [u8]) -> Result<usize, CofferError> {
        let e = match self.resolve(path)? {
            Node::Entry(e) if !e.is_dir => e,
            _ => return Err(CofferError::IsADirectory(path.to_string())),
        };
        if offset >= e.data_length || buf.is_empty() {
            return Ok(0);
        }
        let want = buf.len().min((e.data_length - offset) as usize);
        let buf = &mut buf[..want];
        // Bytes at or beyond ValidDataLength read as zero (spec §7.6.4).
        let valid = e.valid_data_length.saturating_sub(offset).min(want as u64) as usize;
        if valid > 0 {
            let chain = self.chain(&e)?;
            self.read_clusters(&chain, offset, &mut buf[..valid])?;
        }
        buf[valid..].fill(0);
        Ok(want)
    }

    fn stats(&self) -> Result<FsStats, CofferError> {
        let cs = self.boot.cluster_size();
        let total = u64::from(self.boot.cluster_count) * cs;
        let free = match self.bitmap()? {
            Some((first, len)) if len >= u64::from(self.boot.cluster_count).div_ceil(8) => {
                let chain = chain_until_end(self.dev.as_ref(), &self.boot, first)
                    .or_else(|_| chain_for_len(self.dev.as_ref(), &self.boot, first, true, len))?;
                let mut raw = vec![0u8; len as usize];
                self.read_clusters(&chain, 0, &mut raw)?;
                let n = self.boot.cluster_count as usize;
                let used = (0..n).filter(|i| raw[i / 8] & (1 << (i % 8)) != 0).count() as u64;
                Some(total - used * cs)
            }
            _ => None,
        };
        Ok(FsStats {
            total_bytes: total,
            free_bytes: free,
            cluster_size: cs as u32,
        })
    }
}
