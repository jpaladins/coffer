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
