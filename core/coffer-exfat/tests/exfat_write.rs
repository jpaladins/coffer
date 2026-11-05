// SPDX-License-Identifier: GPL-3.0-or-later
mod builder;
use builder::{Img, Layout, ROOT};
use coffer_exfat::{name_hash, ExFatFs};
use coffer_vfs::{CofferError, Filesystem};
use coffer_volume::{BlockDevice, MemDevice};
use std::sync::Arc;

fn data(n: usize, seed: u8) -> Vec<u8> {
    (0..n)
        .map(|i| ((i * 17 + seed as usize) % 253) as u8)
        .collect()
}

fn dev(img: Img) -> Arc<dyn BlockDevice> {
    Arc::new(MemDevice::new(img.bytes))
}

fn read_all(fs: &ExFatFs, path: &str) -> Vec<u8> {
    let size = fs.stat(path).unwrap().size as usize;
    let mut b = vec![0u8; size];
    assert_eq!(fs.read(path, 0, &mut b).unwrap(), size);
    b
}

#[test]
fn create_write_patch_and_reopen() {
    let d = dev(Img::new(4096, 1)); // 1 KiB clusters
    let fs = ExFatFs::open_rw(d.clone()).unwrap();
    assert!(fs.is_writable());
    let a = data(70_000, 1);
    fs.create_file("/a.bin").unwrap();
    fs.write("/a.bin", 0, &a).unwrap();
    fs.write("/a.bin", 1000, b"PATCH").unwrap();
    let mut want = a.clone();
    want[1000..1005].copy_from_slice(b"PATCH");
    assert_eq!(read_all(&fs, "/a.bin"), want);
    fs.sync().unwrap();
    let again = ExFatFs::open(d).unwrap();
    assert_eq!(read_all(&again, "/a.bin"), want);
}

#[test]
fn write_past_end_zero_fills_gap() {
    let fs = ExFatFs::open_rw(dev(Img::new(4096, 1))).unwrap();
    fs.create_file("/g").unwrap();
    fs.write("/g", 0, b"head").unwrap();
    fs.write("/g", 5000, b"tail").unwrap();
    let g = read_all(&fs, "/g");
    assert_eq!(g.len(), 5004);
    assert_eq!(&g[..4], b"head");
    assert!(g[4..5000].iter().all(|x| *x == 0));
    assert_eq!(&g[5000..], b"tail");
}

#[test]
fn directory_grows_past_one_cluster() {
    let d = dev(Img::new(8192, 1));
    let fs = ExFatFs::open_rw(d.clone()).unwrap();
    fs.mkdir("/many").unwrap();
    for i in 0..300 {
        let p = format!("/many/f{i:03}.txt");
        fs.create_file(&p).unwrap();
        fs.write(&p, 0, format!("file {i}").as_bytes()).unwrap();
    }
    fs.sync().unwrap();
    let again = ExFatFs::open(d).unwrap();
    let list = again.list("/many").unwrap();
    assert_eq!(list.len(), 300);
    for i in [0, 149, 299] {
        assert_eq!(
            read_all(&again, &format!("/many/f{i:03}.txt")),
            format!("file {i}").as_bytes()
        );
    }
}

#[test]
fn contiguous_file_converts_to_fat_chain_when_blocked() {
    let fs = ExFatFs::open_rw(dev(Img::new(4096, 1))).unwrap();
    fs.create_file("/a").unwrap();
    fs.write("/a", 0, &data(1024, 2)).unwrap(); // one cluster
    fs.create_file("/b").unwrap();
    fs.write("/b", 0, &data(1024, 3)).unwrap(); // the next cluster
    let more = data(4000, 4);
    fs.write("/a", 1024, &more).unwrap(); // cannot grow in place
    let mut want = data(1024, 2);
    want.extend_from_slice(&more);
    assert_eq!(read_all(&fs, "/a"), want);
    assert_eq!(read_all(&fs, "/b"), data(1024, 3));
}

#[test]
fn remove_frees_space_and_checks_emptiness() {
    let fs = ExFatFs::open_rw(dev(Img::new(4096, 1))).unwrap();
    let free0 = fs.stats().unwrap().free_bytes.unwrap();
    fs.mkdir("/d").unwrap();
    fs.create_file("/d/f").unwrap();
    fs.write("/d/f", 0, &data(50_000, 5)).unwrap();
    assert!(fs.stats().unwrap().free_bytes.unwrap() < free0);
    assert!(matches!(fs.remove("/d"), Err(CofferError::NotEmpty(_))));
    fs.remove("/d/f").unwrap();
    fs.remove("/d").unwrap();
    assert_eq!(fs.stats().unwrap().free_bytes.unwrap(), free0);
    assert!(matches!(fs.stat("/d"), Err(CofferError::NotFound(_))));
}

#[test]
fn rename_move_and_conflicts() {
    let fs = ExFatFs::open_rw(dev(Img::new(4096, 1))).unwrap();
    fs.mkdir("/a").unwrap();
    fs.mkdir("/b").unwrap();
    fs.create_file("/a/f.txt").unwrap();
    fs.write("/a/f.txt", 0, b"payload").unwrap();
    fs.rename("/a/f.txt", "/a/a much longer name than before.txt")
        .unwrap();
    fs.rename("/a/a much longer name than before.txt", "/b/àèìòù €.txt")
        .unwrap();
    assert_eq!(read_all(&fs, "/b/ÀÈÌÒÙ €.TXT"), b"payload");
    assert!(fs.list("/a").unwrap().is_empty());
    fs.create_file("/b/taken").unwrap();
    assert!(matches!(
        fs.rename("/b/àèìòù €.txt", "/b/TAKEN"),
        Err(CofferError::AlreadyExists(_))
    ));
    assert!(matches!(
        fs.create_file("/b/taken"),
        Err(CofferError::AlreadyExists(_))
    ));
    fs.mkdir("/a/inner").unwrap();
    assert!(matches!(
        fs.rename("/a", "/a/inner/x"),
        Err(CofferError::InvalidPath(_))
    ));
    fs.rename("/a", "/b/moved").unwrap();
    assert!(fs.stat("/b/moved/inner").unwrap().is_dir);
}

#[test]
fn set_len_shrinks_and_grows() {
    let fs = ExFatFs::open_rw(dev(Img::new(4096, 1))).unwrap();
    fs.create_file("/f").unwrap();
    fs.write("/f", 0, &data(10_000, 6)).unwrap();
    fs.set_len("/f", 100).unwrap();
    assert_eq!(read_all(&fs, "/f"), data(10_000, 6)[..100].to_vec());
    fs.set_len("/f", 3000).unwrap();
    let r = read_all(&fs, "/f");
    assert_eq!(r.len(), 3000);
    assert!(r[100..].iter().all(|x| *x == 0));
}

#[test]
fn no_space_is_reported_and_volume_stays_usable() {
    let d = dev(Img::new(64, 1)); // 64 KiB of clusters
    let fs = ExFatFs::open_rw(d.clone()).unwrap();
    fs.create_file("/big").unwrap();
    assert!(matches!(
        fs.write("/big", 0, &data(200_000, 7)),
        Err(CofferError::NoSpace)
    ));
    fs.remove("/big").unwrap();
    fs.create_file("/ok").unwrap();
    fs.write("/ok", 0, b"fine").unwrap();
    fs.sync().unwrap();
    assert_eq!(read_all(&ExFatFs::open(d).unwrap(), "/ok"), b"fine");
}

#[test]
fn volume_dirty_flag_set_while_writing_and_cleared_by_sync() {
    let d = dev(Img::new(4096, 1));
    let fs = ExFatFs::open_rw(d.clone()).unwrap();
    let flags = |d: &Arc<dyn BlockDevice>| {
        let mut b = [0u8; 2];
        d.read_at(106, &mut b).unwrap();
        u16::from_le_bytes(b)
    };
    assert_eq!(flags(&d) & 2, 0);
    fs.create_file("/x").unwrap();
    assert_eq!(flags(&d) & 2, 2);
    fs.sync().unwrap();
    assert_eq!(flags(&d) & 2, 0);
    // Boot checksum excludes VolumeFlags: the volume still opens.
    assert!(ExFatFs::open(d).is_ok());
}

#[test]
fn name_hash_uses_upcase_table() {
    // Spec §7.6.4: hash over the up-cased name, UTF-16LE bytes.
    let upcase: Vec<u16> = (0u16..128)
        .map(|i| {
            if (0x61..=0x7a).contains(&i) {
                i - 0x20
            } else {
                i
            }
        })
        .collect();
    let mut want: u16 = 0;
    for b in "ABC.TXT".encode_utf16().flat_map(|u| u.to_le_bytes()) {
        let h: u16 = if want & 1 != 0 { 0x8000 } else { 0 };
        want = h.wrapping_add(want >> 1).wrapping_add(u16::from(b));
    }
    assert_eq!(name_hash("abc.txt", &upcase), want);
    assert_eq!(name_hash("ABC.txt", &upcase), want);
}

#[test]
fn read_only_open_rejects_writes() {
    let mut img = Img::new(1024, 1);
    img.add_file(ROOT, "x", b"x", Layout::Contiguous);
    let fs = ExFatFs::open(dev(img)).unwrap();
    assert!(!fs.is_writable());
    assert!(matches!(fs.create_file("/y"), Err(CofferError::ReadOnly)));
    assert!(matches!(
        fs.write("/x", 0, b"z"),
        Err(CofferError::ReadOnly)
    ));
}
