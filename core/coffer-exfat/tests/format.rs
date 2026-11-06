// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_exfat::{
    default_cluster_size, format, parse_boot, table_checksum, ExFatFs, UPCASE_CHECKSUM,
};
use coffer_vfs::Filesystem;
use coffer_volume::{BlockDevice, MemDevice};
use std::sync::Arc;

fn formatted(bytes: u64, label: &str) -> Arc<dyn BlockDevice> {
    let dev: Arc<dyn BlockDevice> = Arc::new(MemDevice::new(vec![0xC3u8; bytes as usize]));
    format(dev.as_ref(), label, 0x1234_5678).unwrap();
    dev
}

fn data(n: usize, seed: u8) -> Vec<u8> {
    (0..n)
        .map(|i| ((i * 31 + seed as usize) % 251) as u8)
        .collect()
}

#[test]
fn geometry_matches_windows_for_the_same_size() {
    // corpus aes-sha512-exfat.hc: a 64 MiB container formatted by Windows
    // has a 66,846,720-byte volume with this layout [oracle].
    let dev = formatted(66_846_720, "");
    let mut region = vec![0u8; 12 * 512];
    dev.read_at(0, &mut region).unwrap();
    let b = parse_boot(&region).unwrap();
    assert_eq!(
        (b.fat_offset, b.fat_length, b.cluster_heap_offset),
        (128, 128, 256)
    );
    assert_eq!(
        (b.cluster_count, b.root_cluster, b.sectors_per_cluster),
        (16288, 5, 8)
    );
    let mut backup = vec![0u8; 12 * 512];
    dev.read_at(12 * 512, &mut backup).unwrap();
    assert_eq!(region, backup);
}

#[test]
fn format_then_write_read_roundtrip() {
    for size in [4u64 << 20, 64 << 20] {
        let dev = formatted(size, "Coffer");
        let fs = ExFatFs::open_rw(dev.clone()).unwrap();
        assert!(fs.list("/").unwrap().is_empty());
        fs.mkdir("/Foto").unwrap();
        fs.create_file("/Foto/a.bin").unwrap();
        let a = data(300_000, 1);
        fs.write("/Foto/a.bin", 0, &a).unwrap();
        for i in 0..200 {
            fs.create_file(&format!("/n{i:03}.txt")).unwrap();
        }
        fs.sync().unwrap();
        let again = ExFatFs::open(dev).unwrap();
        let mut b = vec![0u8; a.len()];
        assert_eq!(again.read("/Foto/a.bin", 0, &mut b).unwrap(), a.len());
        assert_eq!(a, b);
        assert_eq!(again.list("/").unwrap().len(), 201);
        // Lookups are case-insensitive through the volume's up-case table.
        assert!(again.stat("/foto/A.BIN").is_ok());
        let st = again.stats().unwrap();
        assert!(st.free_bytes.unwrap() > 0 && st.free_bytes.unwrap() < size);
    }
}

#[test]
fn upcase_table_is_microsofts() {
    assert_eq!(UPCASE_CHECKSUM, 0xE619_D30D);
    let dev = formatted(4 << 20, "");
    let mut region = vec![0u8; 12 * 512];
    dev.read_at(0, &mut region).unwrap();
    let b = parse_boot(&region).unwrap();
    let cs = b.cluster_size();
    let heap = u64::from(b.cluster_heap_offset) * 512;
    let mut root = vec![0u8; 96];
    dev.read_at(heap + u64::from(b.root_cluster - 2) * cs, &mut root)
        .unwrap();
    assert_eq!(root[64], 0x82);
    let first = u32::from_le_bytes(root[84..88].try_into().unwrap());
    let len = u64::from_le_bytes(root[88..96].try_into().unwrap());
    assert_eq!(len, 5836);
    let mut t = vec![0u8; len as usize];
    dev.read_at(heap + u64::from(first - 2) * cs, &mut t)
        .unwrap();
    assert_eq!(table_checksum(&t), UPCASE_CHECKSUM);
    assert_eq!(
        u32::from_le_bytes(root[68..72].try_into().unwrap()),
        UPCASE_CHECKSUM
    );
}

#[test]
fn label_entry() {
    let dev = formatted(4 << 20, "Coffer");
    let fs = ExFatFs::open(dev).unwrap();
    assert!(fs.list("/").unwrap().is_empty()); // the label is not a file
}

#[test]
fn cluster_size_by_volume_size() {
    assert_eq!(default_cluster_size(4 << 20), 4096);
    assert_eq!(default_cluster_size(256 << 20), 4096);
    assert_eq!(default_cluster_size((256 << 20) + 512), 32 * 1024);
    assert_eq!(default_cluster_size(32 << 30), 32 * 1024);
    assert_eq!(default_cluster_size((32 << 30) + 512), 128 * 1024);
}

#[test]
fn too_small_is_rejected() {
    let dev: Arc<dyn BlockDevice> = Arc::new(MemDevice::new(vec![0u8; 64 * 1024]));
    assert!(format(dev.as_ref(), "", 1).is_err());
}
