// SPDX-License-Identifier: GPL-3.0-or-later
//! exFAT formatting (Microsoft exFAT specification §3, §4, §7.1–§7.3).
//! Layout follows what Windows writes [oracle]: FAT at sector 128, cluster
//! heap aligned to the cluster size, bitmap from cluster 2, then the
//! up-case table, then the root directory.
use coffer_vfs::CofferError;
use coffer_volume::BlockDevice;

/// Microsoft's recommended up-case table (spec §7.2.5.1), compressed, as
/// Windows writes it (taken from a Windows-formatted volume [oracle]).
pub(crate) const UPCASE_TABLE: &[u8] = include_bytes!("upcase.bin");
/// Checksum of `UPCASE_TABLE` (§7.2.2), the value Windows records.
pub const UPCASE_CHECKSUM: u32 = 0xE619_D30D;

const SECTOR: u64 = 512;
const FAT_OFFSET: u64 = 128;
const BOOT_SECTORS: usize = 12;

/// Cluster size Windows picks for a volume of `bytes` (Microsoft defaults).
pub fn default_cluster_size(bytes: u64) -> u64 {
    if bytes <= 256 << 20 {
        4096
    } else if bytes <= 32 << 30 {
        32 * 1024
    } else {
        128 * 1024
    }
}

/// Table checksum (§7.2.2): rotate-right-add over every byte.
pub fn table_checksum(data: &[u8]) -> u32 {
    data.iter().fold(0u32, |sum, b| {
        let high: u32 = if sum & 1 != 0 { 0x8000_0000 } else { 0 };
        high.wrapping_add(sum >> 1).wrapping_add(u32::from(*b))
    })
}

/// Boot checksum (§3.4) over sectors 0–10, skipping VolumeFlags and
/// PercentInUse.
fn boot_checksum(region: &[u8]) -> u32 {
    region.iter().enumerate().fold(0u32, |sum, (i, b)| {
        if i == 106 || i == 107 || i == 112 {
            return sum;
        }
        let high: u32 = if sum & 1 != 0 { 0x8000_0000 } else { 0 };
        high.wrapping_add(sum >> 1).wrapping_add(u32::from(*b))
    })
}

struct Geometry {
    spc: u64,
    fat_length: u64,
    heap_offset: u64,
    cluster_count: u64,
}

fn geometry(total_sectors: u64, cluster: u64) -> Result<Geometry, CofferError> {
    let spc = cluster / SECTOR;
    let mut fat_length = 1;
    // Grow the FAT until it covers every cluster of the heap that follows it.
    loop {
        let heap_offset = (FAT_OFFSET + fat_length).div_ceil(spc) * spc;
        if heap_offset >= total_sectors {
            return Err(CofferError::InvalidSize(
                "volume too small for exFAT".into(),
            ));
        }
        let cluster_count = (total_sectors - heap_offset) / spc;
        let need = ((cluster_count + 2) * 4).div_ceil(SECTOR);
        if need <= fat_length {
            return Ok(Geometry {
                spc,
                fat_length,
                heap_offset,
                cluster_count,
            });
        }
        fat_length = need;
    }
}

/// Formats `dev` (a decrypted volume) as exFAT. `label` is cut to 11
/// UTF-16 units; empty means no label. `serial` is the volume serial number.
pub fn format(dev: &dyn BlockDevice, label: &str, serial: u32) -> Result<(), CofferError> {
    let total_sectors = dev.len() / SECTOR;
    let cluster = default_cluster_size(dev.len());
    let g = geometry(total_sectors, cluster)?;
    if g.cluster_count < 16 || g.cluster_count > 0xFFFF_FFF5 {
        return Err(CofferError::InvalidSize(format!(
            "{} clusters cannot hold an exFAT volume",
            g.cluster_count
        )));
    }
    let cs = g.spc * SECTOR;
    let bitmap_len = g.cluster_count.div_ceil(8);
    let bitmap_clusters = bitmap_len.div_ceil(cs);
    let upcase_len = UPCASE_TABLE.len() as u64;
    let upcase_clusters = upcase_len.div_ceil(cs);
    let bitmap_first = 2u64;
    let upcase_first = bitmap_first + bitmap_clusters;
    let root = upcase_first + upcase_clusters;
    let used = root - 1; // clusters 2..=root
    if used + 1 > g.cluster_count {
        return Err(CofferError::InvalidSize(
            "volume too small for exFAT".into(),
        ));
    }
    let to_u32 =
        |v: u64| u32::try_from(v).map_err(|_| CofferError::InvalidSize("volume too large".into()));

    // Boot region (main and backup).
    let mut region = vec![0u8; BOOT_SECTORS * SECTOR as usize];
    {
        let b = &mut region;
        b[0..3].copy_from_slice(&[0xEB, 0x76, 0x90]);
        b[3..11].copy_from_slice(b"EXFAT   ");
        b[72..80].copy_from_slice(&total_sectors.to_le_bytes());
        b[80..84].copy_from_slice(&to_u32(FAT_OFFSET)?.to_le_bytes());
        b[84..88].copy_from_slice(&to_u32(g.fat_length)?.to_le_bytes());
        b[88..92].copy_from_slice(&to_u32(g.heap_offset)?.to_le_bytes());
        b[92..96].copy_from_slice(&to_u32(g.cluster_count)?.to_le_bytes());
        b[96..100].copy_from_slice(&to_u32(root)?.to_le_bytes());
        b[100..104].copy_from_slice(&serial.to_le_bytes());
        b[104..106].copy_from_slice(&0x0100u16.to_le_bytes());
        b[108] = 9;
        b[109] = g.spc.trailing_zeros() as u8;
        b[110] = 1;
        b[111] = 0x80;
        b[112] = u8::try_from(used * 100 / g.cluster_count).unwrap_or(100);
        b[120..510].fill(0xF4); // BootCode: "hlt", as Windows writes
        b[510] = 0x55;
        b[511] = 0xAA;
        for s in 1..=8 {
            let o = s * SECTOR as usize + SECTOR as usize - 4;
            b[o..o + 4].copy_from_slice(&0xAA55_0000u32.to_le_bytes());
        }
        let sum = boot_checksum(&b[..11 * SECTOR as usize]);
        for w in b[11 * SECTOR as usize..].as_chunks_mut::<4>().0 {
            *w = sum.to_le_bytes();
        }
    }
    dev.write_at(0, &region)?;
    dev.write_at(BOOT_SECTORS as u64 * SECTOR, &region)?;

    // FAT: media entries, then chains for bitmap, up-case table and root.
    let mut fat = vec![0u8; (g.fat_length * SECTOR) as usize];
    let mut set = |c: u64, v: u32| {
        let o = (c * 4) as usize;
        fat[o..o + 4].copy_from_slice(&v.to_le_bytes());
    };
    set(0, 0xFFFF_FFF8);
    set(1, 0xFFFF_FFFF);
    for (first, n) in [
        (bitmap_first, bitmap_clusters),
        (upcase_first, upcase_clusters),
        (root, 1),
    ] {
        for c in first..first + n {
            let next = if c + 1 < first + n {
                to_u32(c + 1)?
            } else {
                0xFFFF_FFFF
            };
            set(c, next);
        }
    }
    dev.write_at(FAT_OFFSET * SECTOR, &fat)?;

    let heap = g.heap_offset * SECTOR;
    let at = |c: u64| heap + (c - 2) * cs;
    // Allocation bitmap: clusters 2..=root in use.
    let mut bitmap = vec![0u8; (bitmap_clusters * cs) as usize];
    for i in 0..used {
        bitmap[(i / 8) as usize] |= 1 << (i % 8);
    }
    dev.write_at(at(bitmap_first), &bitmap)?;
    let mut upcase = vec![0u8; (upcase_clusters * cs) as usize];
    upcase[..UPCASE_TABLE.len()].copy_from_slice(UPCASE_TABLE);
    dev.write_at(at(upcase_first), &upcase)?;

    // Root directory: label (or "no label"), bitmap and up-case entries.
    let mut dir = vec![0u8; cs as usize];
    let name: Vec<u16> = label.encode_utf16().take(11).collect();
    if name.is_empty() {
        dir[0] = 0x03;
    } else {
        dir[0] = 0x83;
        dir[1] = name.len() as u8;
        for (i, u) in name.iter().enumerate() {
            dir[2 + 2 * i..4 + 2 * i].copy_from_slice(&u.to_le_bytes());
        }
    }
    dir[32] = 0x81;
    dir[52..56].copy_from_slice(&to_u32(bitmap_first)?.to_le_bytes());
    dir[56..64].copy_from_slice(&bitmap_len.to_le_bytes());
    dir[64] = 0x82;
    dir[68..72].copy_from_slice(&UPCASE_CHECKSUM.to_le_bytes());
    dir[84..88].copy_from_slice(&to_u32(upcase_first)?.to_le_bytes());
    dir[88..96].copy_from_slice(&upcase_len.to_le_bytes());
    dev.write_at(at(root), &dir)?;
    dev.flush()
}
