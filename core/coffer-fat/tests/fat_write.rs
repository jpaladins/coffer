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

/// Found by Windows chkdsk: "." and ".." must be the first two entries of a
/// directory, with no long-name (LFN) entries before them.
#[test]
fn dot_entries_have_no_long_name() {
    let dev = blank(16, fatfs::FatType::Fat16);
    let fs = FatFs::open_rw(dev.clone()).unwrap();
    fs.mkdir("/coffer").unwrap();
    fs.mkdir("/coffer/sub").unwrap();
    let mut img = vec![0u8; dev.len() as usize];
    dev.read_at(0, &mut img).unwrap();
    let mut dots = 0;
    for i in (32..img.len()).step_by(32) {
        let e = &img[i..i + 32];
        let is_dot = e[11] == 0x10 && (&e[..11] == b".          " || &e[..11] == b"..         ");
        if is_dot {
            dots += 1;
            assert_ne!(
                img[i - 32 + 11],
                0x0F,
                "LFN entry before a dot entry at {i}"
            );
        }
    }
    assert_eq!(dots, 4);
}

/// Found by chkdsk ("errors in . and/or .."): ".." must point at the parent
/// directory's first cluster, and be 0 when the parent is the root (also on
/// FAT32), including after a directory is moved.
#[test]
fn dotdot_points_to_parent_after_create_and_move() {
    let dev = blank(64, fatfs::FatType::Fat32);
    let fs = FatFs::open_rw(dev.clone()).unwrap();
    fs.mkdir("/aaa").unwrap();
    fs.mkdir("/bbb").unwrap();
    fs.mkdir("/aaa/xxx").unwrap();
    fs.rename("/aaa/xxx", "/bbb/xxx").unwrap();
    let mut img = vec![0u8; dev.len() as usize];
    dev.read_at(0, &mut img).unwrap();
    let cluster = |e: &[u8]| {
        u32::from(u16::from_le_bytes([e[26], e[27]]))
            | u32::from(u16::from_le_bytes([e[20], e[21]])) << 16
    };
    let entries: Vec<&[u8]> = img.chunks(32).collect();
    let find = |name: &[u8; 11]| {
        entries
            .iter()
            .position(|e| &e[..11] == name && e[11] & 0x10 != 0)
            .unwrap()
    };
    let bbb = cluster(entries[find(b"BBB        ")]);
    // Each directory's "." is followed by its "..": check all of them.
    let mut checked = 0;
    for (i, e) in entries.iter().enumerate() {
        if &e[..11] == b".          " && e[11] & 0x10 != 0 {
            let dir = cluster(e);
            let dotdot = cluster(entries[i + 1]);
            if dir == cluster(entries[find(b"AAA        ")]) || dir == bbb {
                assert_eq!(dotdot, 0, "child of root must have .. = 0");
                checked += 1;
            } else {
                assert_eq!(dotdot, bbb, "moved dir must point at its new parent");
                checked += 1;
            }
        }
    }
    assert_eq!(checked, 3);
}

/// Device that refuses writes in `[from, len)`, like a protected hidden volume.
struct Guarded {
    inner: MemDevice,
    from: std::sync::atomic::AtomicU64,
}

impl BlockDevice for Guarded {
    fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<(), CofferError> {
        self.inner.read_at(off, buf)
    }
    fn write_at(&self, off: u64, buf: &[u8]) -> Result<(), CofferError> {
        if off + buf.len() as u64 > self.from.load(std::sync::atomic::Ordering::SeqCst) {
            return Err(CofferError::HiddenVolumeProtected);
        }
        self.inner.write_at(off, buf)
    }
    fn len(&self) -> u64 {
        self.inner.len()
    }
    fn flush(&self) -> Result<(), CofferError> {
        Ok(())
    }
}

fn le16(b: &[u8], o: usize) -> u64 {
    u64::from(u16::from_le_bytes([b[o], b[o + 1]]))
}

/// Review I1: growing a directory into a protected area must not link a
/// cluster that still holds foreign (random) bytes.
#[test]
fn failed_directory_growth_leaves_no_garbage_entries() {
    // Free clusters hold non-zero bytes, as decrypted hidden data would.
    let mut cur = Cursor::new(vec![0xA7u8; 16 * 1024 * 1024]);
    fatfs::format_volume(
        &mut cur,
        fatfs::FormatVolumeOptions::new().fat_type(fatfs::FatType::Fat16),
    )
    .unwrap();
    let img = cur.into_inner();
    let (bps, spc) = (le16(&img, 11), u64::from(img[13]));
    let fat_start = le16(&img, 14) * bps;
    let data_start = fat_start + u64::from(img[16]) * le16(&img, 22) * bps + le16(&img, 17) * 32;
    let cluster = bps * spc;
    let dev = Arc::new(Guarded {
        inner: MemDevice::new(img),
        from: u64::MAX.into(),
    });
    let fat = |c: u64| {
        let mut b = [0u8; 2];
        dev.inner.read_at(fat_start + c * 2, &mut b).unwrap();
        u64::from(u16::from_le_bytes(b))
    };
    let fs = FatFs::open_rw(dev.clone()).unwrap();
    fs.mkdir("/D").unwrap(); // cluster 2
                             // Last cluster of /D's chain; protect everything after it.
    let mut last = 2;
    while fat(last) < 0xFFF8 {
        last = fat(last);
    }
    dev.from.store(
        data_start + (last - 1) * cluster,
        std::sync::atomic::Ordering::SeqCst,
    );
    let mut created = 0;
    let err = loop {
        match fs.create_file(&format!("/D/F{created:05}.TXT")) {
            Ok(()) => created += 1,
            Err(e) => break e,
        }
        assert!(created < 1000);
    };
    assert!(matches!(err, CofferError::HiddenVolumeProtected), "{err:?}");
    assert!(
        fat(last) >= 0xFFF8,
        "chain end {last} linked to {:#x}",
        fat(last)
    );
    assert_eq!(fs.list("/D").unwrap().len(), created);
}

/// Review C1: writers on several threads must not cross-link clusters.
#[test]
fn concurrent_writers_keep_the_volume_consistent() {
    let dev = blank(16, fatfs::FatType::Fat16);
    let fs = Arc::new(FatFs::open_rw(dev.clone()).unwrap());
    let threads: Vec<_> = (0..4u8)
        .map(|t| {
            let fs = fs.clone();
            std::thread::spawn(move || {
                fs.mkdir(&format!("/t{t}")).unwrap();
                for i in 0..25u8 {
                    let p = format!("/t{t}/f{i}.bin");
                    fs.create_file(&p).unwrap();
                    fs.write(&p, 0, &data(9000 + usize::from(i) * 100, t ^ i))
                        .unwrap();
                }
            })
        })
        .collect();
    for th in threads {
        th.join().unwrap();
    }
    let again = FatFs::open(dev).unwrap();
    for t in 0..4u8 {
        for i in 0..25u8 {
            let p = format!("/t{t}/f{i}.bin");
            assert_eq!(
                read_all(&again, &p),
                data(9000 + usize::from(i) * 100, t ^ i),
                "{p}"
            );
        }
    }
}

/// Review M2: changing only the case of a name works on FAT too.
#[test]
fn case_only_rename() {
    let fs = FatFs::open_rw(blank(1, fatfs::FatType::Fat12)).unwrap();
    fs.create_file("/note.txt").unwrap();
    fs.write("/note.txt", 0, b"hi").unwrap();
    fs.rename("/note.txt", "/Note.TXT").unwrap();
    let names: Vec<String> = fs.list("/").unwrap().into_iter().map(|e| e.name).collect();
    assert_eq!(names, vec!["Note.TXT".to_string()]);
    assert_eq!(read_all(&fs, "/Note.TXT"), b"hi");
    fs.rename("/Note.TXT", "/Note.TXT").unwrap();
}
