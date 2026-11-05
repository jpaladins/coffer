// SPDX-License-Identifier: GPL-3.0-or-later
//! Main boot region (Microsoft exFAT specification §3.1, §3.4).
use coffer_vfs::CofferError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BootInfo {
    pub bytes_per_sector: u32,
    pub sectors_per_cluster: u32,
    pub fat_offset: u32,
    pub fat_length: u32,
    pub cluster_heap_offset: u32,
    pub cluster_count: u32,
    pub root_cluster: u32,
    pub volume_length: u64,
    /// Index of the active FAT (VolumeFlags.ActiveFat, TexFAT volumes).
    pub active_fat: u32,
}

impl BootInfo {
    pub fn cluster_size(&self) -> u64 {
        u64::from(self.bytes_per_sector) * u64::from(self.sectors_per_cluster)
    }

    /// Valid data clusters are 2 ..= cluster_count + 1.
    pub fn is_valid_cluster(&self, c: u32) -> bool {
        c >= 2 && u64::from(c) <= u64::from(self.cluster_count) + 1
    }

    pub fn cluster_offset(&self, c: u32) -> u64 {
        u64::from(self.cluster_heap_offset) * u64::from(self.bytes_per_sector)
            + u64::from(c - 2) * self.cluster_size()
    }

    pub fn fat_entry_offset(&self, c: u32) -> u64 {
        (u64::from(self.fat_offset) + u64::from(self.active_fat) * u64::from(self.fat_length))
            * u64::from(self.bytes_per_sector)
            + u64::from(c) * 4
    }
}

fn corrupted(msg: &str) -> CofferError {
    CofferError::Corrupted(format!("exFAT boot: {msg}"))
}

fn le32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

/// Byte size of the main boot region given its first sector.
pub fn boot_region_len(sector0: &[u8]) -> Result<usize, CofferError> {
    if sector0.len() < 512 {
        return Err(corrupted("short boot sector"));
    }
    let shift = sector0[108];
    if !(9..=12).contains(&shift) {
        return Err(corrupted("BytesPerSectorShift out of range"));
    }
    Ok(12usize << shift)
}

/// Parses and validates the main boot region (12 sectors).
pub fn parse_boot(region: &[u8]) -> Result<BootInfo, CofferError> {
    let len = boot_region_len(region)?;
    if region.len() < len {
        return Err(corrupted("short boot region"));
    }
    let bps = 1usize << region[108];
    let b = &region[..len];
    if &b[3..11] != b"EXFAT   " || b[510] != 0x55 || b[511] != 0xAA {
        return Err(corrupted("missing signature"));
    }
    if b[11..64].iter().any(|x| *x != 0) {
        return Err(corrupted("MustBeZero field is not zero"));
    }
    let mut sum: u32 = 0;
    for (i, x) in b[..11 * bps].iter().enumerate() {
        if i == 106 || i == 107 || i == 112 {
            continue;
        }
        let high: u32 = if sum & 1 != 0 { 0x8000_0000 } else { 0 };
        sum = high.wrapping_add(sum >> 1).wrapping_add(u32::from(*x));
    }
    if b[11 * bps..12 * bps].chunks(4).any(|c| le32(c, 0) != sum) {
        return Err(corrupted("BootChecksum mismatch"));
    }
    let spc_shift = u32::from(b[109]);
    if spc_shift > 25 - u32::from(b[108]) {
        return Err(corrupted("SectorsPerClusterShift out of range"));
    }
    let nfats = u32::from(b[110]);
    if nfats != 1 && nfats != 2 {
        return Err(corrupted("NumberOfFats must be 1 or 2"));
    }
    let info = BootInfo {
        bytes_per_sector: bps as u32,
        sectors_per_cluster: 1 << spc_shift,
        fat_offset: le32(b, 80),
        fat_length: le32(b, 84),
        cluster_heap_offset: le32(b, 88),
        cluster_count: le32(b, 92),
        root_cluster: le32(b, 96),
        volume_length: u64::from_le_bytes([b[72], b[73], b[74], b[75], b[76], b[77], b[78], b[79]]),
        active_fat: if nfats == 2 { u32::from(b[106] & 1) } else { 0 },
    };
    if info.fat_offset < 24
        || info.cluster_count == 0
        || info.cluster_count > 0xFFFF_FFF5
        || u64::from(info.fat_length) * u64::from(info.bytes_per_sector)
            < (u64::from(info.cluster_count) + 2) * 4
        || u64::from(info.cluster_heap_offset)
            < u64::from(info.fat_offset) + u64::from(nfats) * u64::from(info.fat_length)
        || !info.is_valid_cluster(info.root_cluster)
    {
        return Err(corrupted("inconsistent geometry"));
    }
    let heap_end = u64::from(info.cluster_heap_offset)
        + u64::from(info.cluster_count) * u64::from(info.sectors_per_cluster);
    if heap_end > info.volume_length {
        return Err(corrupted("cluster heap extends beyond VolumeLength"));
    }
    Ok(info)
}
