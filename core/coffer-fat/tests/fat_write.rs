// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_fat::FatFs;
use coffer_vfs::{CofferError, Filesystem};
use coffer_volume::{BlockDevice, MemDevice};
use std::io::Cursor;
use std::sync::Arc;

fn blank(mib: usize, t: fatfs::FatType) -> Arc<dyn BlockDevice> {
    let mut cur = Cursor::new(vec![0u8; mib * 1024 * 1024]);
    fatfs::format_volume(&mut cur, fatfs::FormatVolumeOptions::new().fat_type(t)).unwrap();
    Arc::new(MemDevice::new(cur.into_inner()))
}

fn data(n: usize, seed: u8) -> Vec<u8> {
    (0..n)
        .map(|i| ((i * 13 + seed as usize) % 251) as u8)
        .collect()
}

fn read_all(fs: &FatFs, path: &str) -> Vec<u8> {
    let size = fs.stat(path).unwrap().size as usize;
    let mut b = vec![0u8; size];
    assert_eq!(fs.read(path, 0, &mut b).unwrap(), size);
    b
}

const TYPES: [(usize, fatfs::FatType); 3] = [
    (1, fatfs::FatType::Fat12),
    (16, fatfs::FatType::Fat16),
    (64, fatfs::FatType::Fat32),
];

#[test]
fn create_write_read_roundtrip_all_fat_types() {
    for (mib, t) in TYPES {
        let dev = blank(mib, t);
        let fs = FatFs::open_rw(dev.clone()).unwrap();
        assert!(fs.is_writable());
        let a = data(70_000, 1);
        fs.create_file("/a.bin").unwrap();
        fs.write("/a.bin", 0, &a).unwrap();
        fs.write("/a.bin", 1000, b"PATCH").unwrap();
        let mut want = a.clone();
        want[1000..1005].copy_from_slice(b"PATCH");
        assert_eq!(read_all(&fs, "/a.bin"), want);
        // Write past the end leaves a zero-filled gap.
        fs.create_file("/gap.bin").unwrap();
        fs.write("/gap.bin", 5000, b"end").unwrap();
        let g = read_all(&fs, "/gap.bin");
        assert_eq!(g.len(), 5003);
        assert!(g[..5000].iter().all(|x| *x == 0));
        fs.sync().unwrap();
        // A fresh reader (new mount) sees the same bytes.
        let again = FatFs::open(dev).unwrap();
        assert_eq!(read_all(&again, "/a.bin"), want);
    }
}

#[test]
fn unicode_names_and_nested_dirs() {
    let fs = FatFs::open_rw(blank(16, fatfs::FatType::Fat16)).unwrap();
    fs.mkdir("/Cartella Con Spazi").unwrap();
    fs.mkdir("/Cartella Con Spazi/sotto").unwrap();
    fs.create_file("/Cartella Con Spazi/sotto/àèìòù €.txt")
        .unwrap();
    fs.write("/Cartella Con Spazi/sotto/àèìòù €.txt", 0, b"ciao")
        .unwrap();
    assert_eq!(
        read_all(&fs, "/cartella con spazi/SOTTO/ÀÈÌÒÙ €.TXT"),
        b"ciao"
    );
    let names: Vec<String> = fs
        .list("/Cartella Con Spazi/sotto")
        .unwrap()
        .into_iter()
        .map(|e| e.name)
        .collect();
    assert_eq!(names, vec!["àèìòù €.txt"]);
}

#[test]
fn conflicts_and_invalid_names() {
    let fs = FatFs::open_rw(blank(16, fatfs::FatType::Fat16)).unwrap();
    fs.create_file("/x.txt").unwrap();
    assert!(matches!(
        fs.create_file("/x.txt"),
        Err(CofferError::AlreadyExists(_))
    ));
    assert!(matches!(
        fs.create_file("/X.TXT"),
        Err(CofferError::AlreadyExists(_))
    ));
    assert!(matches!(
        fs.mkdir("/x.txt"),
        Err(CofferError::AlreadyExists(_))
    ));
    assert!(matches!(
        fs.create_file("/bad:name"),
        Err(CofferError::InvalidPath(_))
    ));
    assert!(matches!(
        fs.create_file("/nodir/f"),
        Err(CofferError::NotFound(_))
    ));
    assert!(matches!(
        fs.write("/missing", 0, b"x"),
        Err(CofferError::NotFound(_))
    ));
}

#[test]
fn remove_files_and_dirs() {
    let fs = FatFs::open_rw(blank(16, fatfs::FatType::Fat16)).unwrap();
    fs.mkdir("/d").unwrap();
    fs.create_file("/d/f").unwrap();
    assert!(matches!(fs.remove("/d"), Err(CofferError::NotEmpty(_))));
    fs.remove("/d/f").unwrap();
    fs.remove("/d").unwrap();
    assert!(fs.list("/").unwrap().is_empty());
    assert!(matches!(fs.remove("/d"), Err(CofferError::NotFound(_))));
}

#[test]
fn rename_and_move() {
    let fs = FatFs::open_rw(blank(16, fatfs::FatType::Fat16)).unwrap();
    fs.mkdir("/a").unwrap();
    fs.mkdir("/b").unwrap();
    fs.create_file("/a/f.txt").unwrap();
    fs.write("/a/f.txt", 0, b"payload").unwrap();
    fs.rename("/a/f.txt", "/a/g.txt").unwrap();
    fs.rename("/a/g.txt", "/b/h.txt").unwrap();
    assert_eq!(read_all(&fs, "/b/h.txt"), b"payload");
    assert!(fs.list("/a").unwrap().is_empty());
    fs.create_file("/b/taken").unwrap();
    assert!(matches!(
        fs.rename("/b/h.txt", "/b/taken"),
        Err(CofferError::AlreadyExists(_))
    ));
    fs.mkdir("/a/inner").unwrap();
    assert!(matches!(
        fs.rename("/a", "/a/inner/a2"),
        Err(CofferError::InvalidPath(_))
    ));
    fs.rename("/a", "/b/a-moved").unwrap();
    assert!(fs.stat("/b/a-moved/inner").unwrap().is_dir);
}

#[test]
fn set_len_truncates_and_extends() {
    let fs = FatFs::open_rw(blank(16, fatfs::FatType::Fat16)).unwrap();
    fs.create_file("/f").unwrap();
    fs.write("/f", 0, &data(10_000, 3)).unwrap();
    fs.set_len("/f", 100).unwrap();
    assert_eq!(read_all(&fs, "/f"), data(10_000, 3)[..100].to_vec());
    fs.set_len("/f", 300).unwrap();
    let r = read_all(&fs, "/f");
    assert_eq!(r.len(), 300);
    assert!(r[100..].iter().all(|x| *x == 0));
}

#[test]
fn no_space_is_reported_and_volume_stays_usable() {
    let dev = blank(1, fatfs::FatType::Fat12);
    let fs = FatFs::open_rw(dev.clone()).unwrap();
    fs.create_file("/big").unwrap();
    let r = fs.write("/big", 0, &data(2 * 1024 * 1024, 5));
    assert!(matches!(r, Err(CofferError::NoSpace)), "{r:?}");
    fs.remove("/big").unwrap();
    fs.create_file("/small").unwrap();
    fs.write("/small", 0, b"ok").unwrap();
    let again = FatFs::open(dev).unwrap();
    assert_eq!(read_all(&again, "/small"), b"ok");
}

#[test]
fn read_only_mount_rejects_writes() {
    let fs = FatFs::open(blank(16, fatfs::FatType::Fat16)).unwrap();
    assert!(!fs.is_writable());
    assert!(matches!(fs.create_file("/x"), Err(CofferError::ReadOnly)));
    assert!(matches!(fs.mkdir("/x"), Err(CofferError::ReadOnly)));
}

#[test]
fn modified_time_is_current() {
    coffer_fat::set_local_offset_minutes(0);
    let fs = FatFs::open_rw(blank(16, fatfs::FatType::Fat16)).unwrap();
    fs.create_file("/t").unwrap();
    fs.write("/t", 0, b"x").unwrap();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    let m = fs.stat("/t").unwrap().modified.unwrap();
    assert!((now - m).abs() < 5, "modified {m} vs now {now}");
}
