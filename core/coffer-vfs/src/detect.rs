// SPDX-License-Identifier: GPL-3.0-or-later
//! Filesystem detection from the first sector of a volume. FAT type follows
//! the cluster-count rule of Microsoft's FAT specification (fatgen103).
use crate::FsKind;

fn le16(s: &[u8], o: usize) -> u32 {
    u32::from(u16::from_le_bytes([s[o], s[o + 1]]))
}
fn le32(s: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([s[o], s[o + 1], s[o + 2], s[o + 3]])
}

pub fn detect(s: &[u8; 512]) -> FsKind {
    if s[510] != 0x55 || s[511] != 0xAA {
        return FsKind::Unknown;
    }
    match &s[3..11] {
        b"EXFAT   " => return FsKind::ExFat,
        b"NTFS    " => return FsKind::Ntfs,
        _ => {}
    }
    fat_kind(s).unwrap_or(FsKind::Unknown)
}

fn fat_kind(s: &[u8; 512]) -> Option<FsKind> {
    let bytes_per_sec = le16(s, 11);
    let sec_per_clus = u32::from(s[13]);
    let rsvd = le16(s, 14);
    let num_fats = u32::from(s[16]);
    let root_ent = le16(s, 17);
    if !matches!(bytes_per_sec, 512 | 1024 | 2048 | 4096)
        || sec_per_clus == 0
        || !sec_per_clus.is_power_of_two()
        || rsvd == 0
        || num_fats == 0
    {
        return None;
    }
    let root_dir_secs = (root_ent * 32).div_ceil(bytes_per_sec);
    let fat_sz = match le16(s, 22) {
        0 => le32(s, 36),
        n => n,
    };
    let tot = match le16(s, 19) {
        0 => le32(s, 32),
        n => n,
    };
    if fat_sz == 0 || tot == 0 {
        return None;
    }
    let meta = u64::from(rsvd) + u64::from(num_fats) * u64::from(fat_sz) + u64::from(root_dir_secs);
    let data_secs = u64::from(tot).checked_sub(meta)?;
    let clusters = data_secs / u64::from(sec_per_clus);
    Some(if clusters < 4085 {
        FsKind::Fat12
    } else if clusters < 65525 {
        FsKind::Fat16
    } else {
        FsKind::Fat32
    })
}
