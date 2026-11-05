// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_vfs::{detect, FsKind};

/// Builds a FAT boot sector whose BPB yields exactly `clusters` clusters.
fn fat_bpb(clusters: u32, fat32: bool) -> [u8; 512] {
    let mut s = [0u8; 512];
    s[11..13].copy_from_slice(&512u16.to_le_bytes()); // BytsPerSec
    s[13] = 1; // SecPerClus
    s[14..16].copy_from_slice(&1u16.to_le_bytes()); // RsvdSecCnt
    s[16] = 2; // NumFATs
    let fat_sz: u32 = 600;
    let (root_ent, root_secs) = if fat32 { (0u16, 0u32) } else { (512u16, 32u32) };
    s[17..19].copy_from_slice(&root_ent.to_le_bytes());
    let tot = 1 + 2 * fat_sz + root_secs + clusters;
    if fat32 {
        s[32..36].copy_from_slice(&tot.to_le_bytes()); // TotSec32
        s[36..40].copy_from_slice(&fat_sz.to_le_bytes()); // FATSz32
    } else {
        s[22..24].copy_from_slice(&(fat_sz as u16).to_le_bytes()); // FATSz16
        if tot < 0x10000 {
            s[19..21].copy_from_slice(&(tot as u16).to_le_bytes());
        } else {
            s[32..36].copy_from_slice(&tot.to_le_bytes());
        }
    }
    s[510] = 0x55;
    s[511] = 0xAA;
    s
}

#[test]
fn fat_type_by_cluster_count() {
    assert_eq!(detect(&fat_bpb(4084, false)), FsKind::Fat12);
    assert_eq!(detect(&fat_bpb(4085, false)), FsKind::Fat16);
    assert_eq!(detect(&fat_bpb(65524, false)), FsKind::Fat16);
    assert_eq!(detect(&fat_bpb(65525, true)), FsKind::Fat32);
}

#[test]
fn exfat_and_ntfs_by_oem_name() {
    let mut s = [0u8; 512];
    s[510] = 0x55;
    s[511] = 0xAA;
    s[3..11].copy_from_slice(b"EXFAT   ");
    assert_eq!(detect(&s), FsKind::ExFat);
    s[3..11].copy_from_slice(b"NTFS    ");
    assert_eq!(detect(&s), FsKind::Ntfs);
}

#[test]
fn garbage_is_unknown_without_panicking() {
    assert_eq!(detect(&[0u8; 512]), FsKind::Unknown);
    let mut s = fat_bpb(5000, false);
    s[11] = 0;
    s[12] = 0; // BytsPerSec = 0
    assert_eq!(detect(&s), FsKind::Unknown);
    let mut s = fat_bpb(5000, false);
    s[13] = 0; // SecPerClus = 0
    assert_eq!(detect(&s), FsKind::Unknown);
    let mut s = fat_bpb(5000, false);
    s[22] = 0xFF;
    s[23] = 0xFF; // FAT bigger than the volume
    assert_eq!(detect(&s), FsKind::Unknown);
    assert_eq!(detect(&[0xFFu8; 512]), FsKind::Unknown);
}
