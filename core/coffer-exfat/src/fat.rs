// SPDX-License-Identifier: GPL-3.0-or-later
//! Cluster chains (exFAT specification §4: FAT, NoFatChain).
use crate::boot::BootInfo;
use coffer_vfs::CofferError;
use coffer_volume::BlockDevice;
use std::collections::HashSet;

const END_OF_CHAIN: u32 = 0xFFFF_FFFF;

fn corrupted(msg: String) -> CofferError {
    CofferError::Corrupted(format!("exFAT chain: {msg}"))
}

/// Reads FAT entries with a one-sector cache.
struct FatReader<'a> {
    dev: &'a dyn BlockDevice,
    boot: &'a BootInfo,
    cached: Option<(u64, Vec<u8>)>,
}

impl FatReader<'_> {
    fn next(&mut self, c: u32) -> Result<u32, CofferError> {
        let off = self.boot.fat_entry_offset(c);
        let bps = u64::from(self.boot.bytes_per_sector);
        let sector = off / bps;
        if self.cached.as_ref().map(|(s, _)| *s) != Some(sector) {
            let mut buf = vec![0u8; bps as usize];
            self.dev.read_at(sector * bps, &mut buf)?;
            self.cached = Some((sector, buf));
        }
        let i = (off % bps) as usize;
        match &self.cached {
            Some((_, buf)) => Ok(u32::from_le_bytes([
                buf[i],
                buf[i + 1],
                buf[i + 2],
                buf[i + 3],
            ])),
            None => Err(corrupted("FAT cache".into())),
        }
    }
}

fn clusters_for(boot: &BootInfo, len_bytes: u64) -> Result<u64, CofferError> {
    let n = len_bytes.div_ceil(boot.cluster_size());
    if n > u64::from(boot.cluster_count) {
        return Err(corrupted(format!(
            "length {len_bytes} exceeds the cluster heap"
        )));
    }
    Ok(n)
}

/// Clusters holding `len_bytes` bytes starting at `first`. `contiguous`
/// is the stream's NoFatChain flag. Cycles, out-of-range clusters and
/// chains shorter than the length are `Corrupted`.
pub fn chain_for_len(
    dev: &dyn BlockDevice,
    boot: &BootInfo,
    first: u32,
    contiguous: bool,
    len_bytes: u64,
) -> Result<Vec<u32>, CofferError> {
    let n = clusters_for(boot, len_bytes)?;
    if n == 0 {
        return Ok(Vec::new());
    }
    if !boot.is_valid_cluster(first) {
        return Err(corrupted(format!("first cluster {first} out of range")));
    }
    if contiguous {
        let last = u64::from(first) + n - 1;
        if last > u64::from(boot.cluster_count) + 1 {
            return Err(corrupted("contiguous run beyond the cluster heap".into()));
        }
        return Ok((0..n as u32).map(|i| first + i).collect());
    }
    let mut fat = FatReader {
        dev,
        boot,
        cached: None,
    };
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(n as usize);
    let mut c = first;
    loop {
        if !boot.is_valid_cluster(c) {
            return Err(corrupted(format!("cluster {c:#x} out of range")));
        }
        if !seen.insert(c) {
            return Err(corrupted(format!("cycle at cluster {c}")));
        }
        out.push(c);
        if out.len() as u64 == n {
            return Ok(out);
        }
        c = fat.next(c)?;
        if c == END_OF_CHAIN {
            return Err(corrupted("chain ends before the declared length".into()));
        }
    }
}

/// Clusters of a chain followed until its end marker (root directory).
pub fn chain_until_end(
    dev: &dyn BlockDevice,
    boot: &BootInfo,
    first: u32,
) -> Result<Vec<u32>, CofferError> {
    let mut fat = FatReader {
        dev,
        boot,
        cached: None,
    };
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    let mut c = first;
    while c != END_OF_CHAIN {
        if !boot.is_valid_cluster(c) {
            return Err(corrupted(format!("cluster {c:#x} out of range")));
        }
        if !seen.insert(c) {
            return Err(corrupted(format!("cycle at cluster {c}")));
        }
        out.push(c);
        c = fat.next(c)?;
    }
    Ok(out)
}
