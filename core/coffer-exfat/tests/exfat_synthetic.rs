// SPDX-License-Identifier: GPL-3.0-or-later
mod builder;
use builder::{Img, Layout, BPS, ROOT};
use coffer_exfat::{chain_for_len, parse_boot, ExFatFs};
use coffer_vfs::{CofferError, Filesystem, FsKind};
use coffer_volume::{BlockDevice, MemDevice};
use std::sync::Arc;

fn payload(n: usize, seed: u8) -> Vec<u8> {
    (0..n)
        .map(|i| ((i * 31 + seed as usize) % 253) as u8)
        .collect()
}

fn dev(img: Img) -> Arc<dyn BlockDevice> {
    Arc::new(MemDevice::new(img.bytes))
}

// ---- Task 4: boot region and cluster chains ----

#[test]
fn boot_fields_parsed() {
    let img = Img::new(512, 3);
    let b = parse_boot(&img.bytes[..12 * BPS]).unwrap();
    assert_eq!(b.bytes_per_sector, 512);
    assert_eq!(b.sectors_per_cluster, 8);
    assert_eq!(b.cluster_count, 512);
    assert_eq!(b.fat_offset, 24);
    assert_eq!(b.root_cluster, 4);
    assert_eq!(b.cluster_heap_offset as usize, img.heap_offset);
}

#[test]
fn boot_checksum_verified() {
    let mut img = Img::new(512, 3);
    img.bytes[200] ^= 1; // inside sectors 0-10, not re-sealed
    assert!(matches!(
        parse_boot(&img.bytes[..12 * BPS]),
        Err(CofferError::Corrupted(_))
    ));
    // Bytes excluded from the checksum (VolumeFlags, PercentInUse) may change.
    let mut img = Img::new(512, 3);
    img.bytes[106] = 2;
    img.bytes[112] = 50;
    assert!(parse_boot(&img.bytes[..12 * BPS]).is_ok());
}

#[test]
fn boot_rejects_bad_shifts_and_short_region() {
    let mut img = Img::new(512, 3);
    img.bytes[109] = 30; // cluster > 32 MiB
    img.seal_boot();
    assert!(parse_boot(&img.bytes[..12 * BPS]).is_err());
    assert!(parse_boot(&[0u8; 100]).is_err());
}

#[test]
fn chain_follows_fat_and_contiguous() {
    let mut img = Img::new(512, 0); // 512-byte clusters
    let frag = img.alloc(Layout::Fragmented, 4);
    let cont = img.alloc(Layout::Contiguous, 3);
    let b = parse_boot(&img.bytes[..12 * BPS]).unwrap();
    let d = dev(img);
    assert_eq!(
        chain_for_len(d.as_ref(), &b, frag[0], false, 4 * 512).unwrap(),
        frag
    );
    assert_eq!(
        chain_for_len(d.as_ref(), &b, cont[0], true, 3 * 512).unwrap(),
        cont
    );
    assert_eq!(
        chain_for_len(d.as_ref(), &b, cont[0], true, 1).unwrap(),
        vec![cont[0]]
    );
    assert!(chain_for_len(d.as_ref(), &b, 0, false, 0)
        .unwrap()
        .is_empty());
}

#[test]
fn chain_cycle_and_range_errors() {
    let mut img = Img::new(512, 0);
    let c = img.alloc(Layout::FatChain, 3);
    img.set_fat(c[1], c[0]); // cycle
    let b = parse_boot(&img.bytes[..12 * BPS]).unwrap();
    let d = dev(img);
    let r = chain_for_len(d.as_ref(), &b, c[0], false, 3 * 512);
    assert!(matches!(r, Err(CofferError::Corrupted(_))), "cycle: {r:?}");
    assert!(chain_for_len(d.as_ref(), &b, 1, false, 512).is_err());
    assert!(chain_for_len(d.as_ref(), &b, 600, false, 512).is_err());
    assert!(chain_for_len(d.as_ref(), &b, 510, true, 10 * 512).is_err()); // runs off the heap
                                                                          // Chain ends (0xFFFFFFFF) before the declared length.
    assert!(chain_for_len(d.as_ref(), &b, c[2], false, 2 * 512).is_err());
}

// ---- Task 5: directories, files, Filesystem ----

fn sample() -> (Img, Vec<u8>, Vec<u8>) {
    let mut img = Img::new(1024, 1); // 1 KiB clusters
    let a = payload(5000, 1);
    let f = payload(9000, 2);
    img.add_file(ROOT, "hello.txt", b"hello exfat", Layout::Contiguous);
    img.add_file(ROOT, "àèìòù €.txt", b"unicode", Layout::FatChain);
    img.add_file(ROOT, "empty.txt", b"", Layout::Contiguous);
    let d = img.add_dir(ROOT, "Cartella Con Spazi", 1);
    let s = img.add_dir(d, "sotto cartella", 1);
    img.add_file(s, "deep.bin", &a, Layout::Contiguous);
    img.add_file(d, "frag.bin", &f, Layout::Fragmented);
    img.add_file(ROOT, &"n".repeat(40), b"long", Layout::FatChain);
    (img, a, f)
}

#[test]
fn list_root_and_nested() {
    let (img, _, _) = sample();
    let fs = ExFatFs::open(dev(img)).unwrap();
    assert_eq!(fs.kind(), FsKind::ExFat);
    let mut names: Vec<String> = fs.list("/").unwrap().into_iter().map(|e| e.name).collect();
    names.sort();
    let mut want = vec![
        "Cartella Con Spazi".to_string(),
        "empty.txt".into(),
        "hello.txt".into(),
        "n".repeat(40),
        "àèìòù €.txt".into(),
    ];
    want.sort();
    assert_eq!(names, want);
    let sub = fs.list("/Cartella Con Spazi").unwrap();
    assert_eq!(sub.len(), 2);
    assert!(
        fs.stat("/cartella con spazi/SOTTO CARTELLA")
            .unwrap()
            .is_dir
    );
}

#[test]
fn read_contiguous_fatchain_fragmented() {
    let (img, a, f) = sample();
    let fs = ExFatFs::open(dev(img)).unwrap();
    let mut b = vec![0u8; 100];
    let n = fs.read("/hello.txt", 0, &mut b).unwrap();
    assert_eq!(&b[..n], b"hello exfat");
    let mut all = vec![0u8; 5000];
    assert_eq!(
        fs.read("/Cartella Con Spazi/sotto cartella/deep.bin", 0, &mut all)
            .unwrap(),
        5000
    );
    assert_eq!(all, a);
    let mut all = vec![0u8; 9000];
    assert_eq!(
        fs.read("/Cartella Con Spazi/frag.bin", 0, &mut all)
            .unwrap(),
        9000
    );
    assert_eq!(all, f);
    for off in [0usize, 1023, 1024, 4097, 8999] {
        let mut b = vec![0u8; 1500];
        let n = fs
            .read("/Cartella Con Spazi/frag.bin", off as u64, &mut b)
            .unwrap();
        assert_eq!(n, 1500.min(9000 - off));
        assert_eq!(&b[..n], &f[off..off + n]);
    }
    assert_eq!(
        fs.read("/Cartella Con Spazi/frag.bin", 9000, &mut b)
            .unwrap(),
        0
    );
    assert_eq!(fs.read("/empty.txt", 0, &mut b).unwrap(), 0);
}

#[test]
fn long_name_and_unicode() {
    let (img, _, _) = sample();
    let fs = ExFatFs::open(dev(img)).unwrap();
    assert_eq!(fs.stat(&format!("/{}", "N".repeat(40))).unwrap().size, 4);
    assert_eq!(fs.stat("/ÀÈÌÒÙ €.TXT").unwrap().size, 7);
}

#[test]
fn valid_data_length_zero_fill() {
    let mut img = Img::new(1024, 1);
    let data = payload(8192, 3);
    img.add_file_valid(ROOT, "vdl.bin", &data, 1000, Layout::Contiguous);
    // Garbage after ValidDataLength on disk must not be returned.
    let c = img.cluster_offset(img.next_free - 1);
    img.bytes[c..c + 16].fill(0xEE);
    let fs = ExFatFs::open(dev(img)).unwrap();
    let mut b = vec![0xAAu8; 8192];
    assert_eq!(fs.read("/vdl.bin", 0, &mut b).unwrap(), 8192);
    assert_eq!(&b[..1000], &data[..1000]);
    assert!(b[1000..].iter().all(|x| *x == 0));
}

#[test]
fn modified_time_converted() {
    let (img, _, _) = sample();
    let fs = ExFatFs::open(dev(img)).unwrap();
    // Stored 13:34:56 local with UTC+1h => 12:34:56 UTC.
    assert_eq!(fs.stat("/hello.txt").unwrap().modified, Some(1_793_882_096));
}

#[test]
fn bad_set_checksum_is_corrupted() {
    let mut img = Img::new(1024, 1);
    img.add_file(ROOT, "x.txt", b"x", Layout::Contiguous);
    let o = img.cluster_offset(4) + 2 * 32 + 2; // first file entry's checksum
    img.bytes[o] ^= 0xFF;
    let fs = ExFatFs::open(dev(img)).unwrap();
    assert!(matches!(fs.list("/"), Err(CofferError::Corrupted(_))));
}

#[test]
fn directory_cycle_is_bounded() {
    let mut img = Img::new(1024, 1);
    // "loop" is a directory whose data is the root directory itself.
    let set = Img::entry_set("loop", 0x10, 4, 1024, 1024, false, 0, 0);
    img.push_set(ROOT, &set);
    let fs = ExFatFs::open(dev(img)).unwrap();
    assert!(fs.list("/loop/loop/loop").is_ok());
    let deep = "/loop".repeat(300);
    assert!(fs.stat(&deep).is_err());
}

#[test]
fn errors_for_missing_and_wrong_kind() {
    let (img, _, _) = sample();
    let fs = ExFatFs::open(dev(img)).unwrap();
    let mut b = [0u8; 4];
    assert!(matches!(fs.stat("/nope"), Err(CofferError::NotFound(_))));
    assert!(matches!(
        fs.list("/hello.txt"),
        Err(CofferError::NotADirectory(_))
    ));
    assert!(matches!(
        fs.read("/Cartella Con Spazi", 0, &mut b),
        Err(CofferError::IsADirectory(_))
    ));
}

#[test]
fn stats_from_bitmap() {
    let (img, _, _) = sample();
    let used = img.next_free - 2;
    let total = img.cluster_count as u64 * img.cluster_size as u64;
    let cs = img.cluster_size as u64;
    // Mark used clusters in the allocation bitmap (cluster 2).
    let mut img = img;
    let bo = img.cluster_offset(2);
    for i in 0..used as usize {
        img.bytes[bo + i / 8] |= 1 << (i % 8);
    }
    let s = ExFatFs::open(dev(img)).unwrap().stats().unwrap();
    assert_eq!(s.total_bytes, total);
    assert_eq!(s.free_bytes, Some(total - u64::from(used) * cs));
}

#[test]
fn hostile_boot_bytes_do_not_panic() {
    let mut img = Img::new(512, 3);
    for b in img.bytes[120..11 * BPS].iter_mut() {
        *b = 0xFF;
    }
    let _ = parse_boot(&img.bytes[..12 * BPS]);
}
