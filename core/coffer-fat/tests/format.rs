// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_fat::{format, FatFs};
use coffer_vfs::{Filesystem, FsKind};
use coffer_volume::{BlockDevice, MemDevice};
use std::sync::Arc;

#[test]
fn format_then_write_read_roundtrip() {
    for (mib, kind) in [
        (1u64, FsKind::Fat12),
        (16, FsKind::Fat16),
        (64, FsKind::Fat16),
    ] {
        let dev: Arc<dyn BlockDevice> =
            Arc::new(MemDevice::new(vec![0x5Au8; (mib << 20) as usize]));
        format(dev.clone(), "Coffer", 0xCAFE_F00D).unwrap();
        let fs = FatFs::open_rw(dev.clone()).unwrap();
        assert_eq!(fs.kind(), kind, "{mib} MiB");
        assert!(
            fs.list("/").unwrap().is_empty(),
            "fresh volume lists garbage"
        );
        fs.mkdir("/Foto").unwrap();
        fs.create_file("/Foto/a.bin").unwrap();
        let a: Vec<u8> = (0..70_000).map(|i| (i % 251) as u8).collect();
        fs.write("/Foto/a.bin", 0, &a).unwrap();
        fs.sync().unwrap();
        let again = FatFs::open(dev).unwrap();
        let mut b = vec![0u8; a.len()];
        assert_eq!(again.read("/Foto/a.bin", 0, &mut b).unwrap(), a.len());
        assert_eq!(a, b);
    }
}

#[test]
fn unclean_close_is_reported_on_next_open() {
    let dev: Arc<dyn BlockDevice> = Arc::new(MemDevice::new(vec![0u8; 16 << 20]));
    format(dev.clone(), "", 1).unwrap();
    assert!(!FatFs::open(dev.clone()).unwrap().was_dirty());
    // FAT16: bit 15 of FAT[1] cleared = volume not cleanly unmounted.
    let mut boot = [0u8; 512];
    dev.read_at(0, &mut boot).unwrap();
    let fat = u64::from(u16::from_le_bytes([boot[14], boot[15]])) * 512;
    let mut e = [0u8; 2];
    dev.read_at(fat + 2, &mut e).unwrap();
    let v = u16::from_le_bytes(e) & !0x8000;
    dev.write_at(fat + 2, &v.to_le_bytes()).unwrap();
    assert!(FatFs::open(dev).unwrap().was_dirty());
}
