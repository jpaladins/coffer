// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_fat::FatFs;
use coffer_vfs::{CofferError, Filesystem, FsKind};
use coffer_volume::{BlockDevice, MemDevice};
use std::io::{Cursor, Write};
use std::sync::Arc;

fn payload(n: usize) -> Vec<u8> {
    (0..n).map(|i| (i * 7 % 251) as u8).collect()
}

/// Formats an image of `mib` MiB with fatfs and writes a small tree.
fn image(mib: usize, fat_type: fatfs::FatType) -> Arc<dyn BlockDevice> {
    let mut cur = Cursor::new(vec![0u8; mib * 1024 * 1024]);
    fatfs::format_volume(
        &mut cur,
        fatfs::FormatVolumeOptions::new().fat_type(fat_type),
    )
    .unwrap();
    {
        let fs = fatfs::FileSystem::new(&mut cur, fatfs::FsOptions::new()).unwrap();
        let root = fs.root_dir();
        root.create_file("hello.txt")
            .unwrap()
            .write_all(b"hello coffer")
            .unwrap();
        root.create_file("àèìòù €.txt")
            .unwrap()
            .write_all(b"unicode")
            .unwrap();
        let d = root.create_dir("Cartella Con Spazi").unwrap();
        d.create_dir("sub").unwrap();
        d.create_file("big.bin")
            .unwrap()
            .write_all(&payload(70_000))
            .unwrap();
        root.create_file("UPPER.TXT")
            .unwrap()
            .write_all(b"up")
            .unwrap();
    }
    Arc::new(MemDevice::new(cur.into_inner()))
}

#[test]
fn kind_matches() {
    assert_eq!(
        FatFs::open(image(1, fatfs::FatType::Fat12)).unwrap().kind(),
        FsKind::Fat12
    );
    assert_eq!(
        FatFs::open(image(16, fatfs::FatType::Fat16))
            .unwrap()
            .kind(),
        FsKind::Fat16
    );
    assert_eq!(
        FatFs::open(image(64, fatfs::FatType::Fat32))
            .unwrap()
            .kind(),
        FsKind::Fat32
    );
}

#[test]
fn list_root_and_subdir() {
    for (mib, t) in [
        (1, fatfs::FatType::Fat12),
        (16, fatfs::FatType::Fat16),
        (64, fatfs::FatType::Fat32),
    ] {
        let fs = FatFs::open(image(mib, t)).unwrap();
        let mut names: Vec<String> = fs.list("/").unwrap().into_iter().map(|e| e.name).collect();
        names.sort();
        assert_eq!(
            names,
            vec![
                "Cartella Con Spazi",
                "UPPER.TXT",
                "hello.txt",
                "àèìòù €.txt"
            ]
        );
        let sub = fs.list("/Cartella Con Spazi").unwrap();
        let mut sn: Vec<(String, bool, u64)> = sub
            .into_iter()
            .map(|e| (e.name, e.is_dir, e.size))
            .collect();
        sn.sort();
        assert_eq!(
            sn,
            vec![("big.bin".into(), false, 70_000), ("sub".into(), true, 0)]
        );
    }
}

#[test]
fn stat_file_and_dir() {
    let fs = FatFs::open(image(16, fatfs::FatType::Fat16)).unwrap();
    let f = fs.stat("/hello.txt").unwrap();
    assert!(!f.is_dir);
    assert_eq!(f.size, 12);
    // fatfs without a clock writes zero dates; real timestamps are checked
    // on the Windows-written corpus.
    assert!(fs.stat("/Cartella Con Spazi/sub").unwrap().is_dir);
    assert!(fs.stat("/").unwrap().is_dir);
}

#[test]
fn read_whole_and_ranges() {
    let fs = FatFs::open(image(16, fatfs::FatType::Fat16)).unwrap();
    let want = payload(70_000);
    let p = "/Cartella Con Spazi/big.bin";
    let mut all = vec![0u8; 70_000];
    assert_eq!(fs.read(p, 0, &mut all).unwrap(), 70_000);
    assert_eq!(all, want);
    for off in [0usize, 511, 4095, 69_999] {
        let mut b = vec![0u8; 1000];
        let n = fs.read(p, off as u64, &mut b).unwrap();
        assert_eq!(n, 1000.min(70_000 - off));
        assert_eq!(&b[..n], &want[off..off + n]);
    }
    let mut b = [0u8; 10];
    assert_eq!(fs.read(p, 70_000, &mut b).unwrap(), 0);
    assert_eq!(fs.read(p, 1 << 40, &mut b).unwrap(), 0);
}

#[test]
fn errors_for_missing_and_wrong_kind() {
    let fs = FatFs::open(image(16, fatfs::FatType::Fat16)).unwrap();
    assert!(matches!(
        fs.stat("/nope.txt"),
        Err(CofferError::NotFound(_))
    ));
    assert!(matches!(
        fs.list("/hello.txt"),
        Err(CofferError::NotADirectory(_))
    ));
    let mut b = [0u8; 4];
    assert!(matches!(
        fs.read("/Cartella Con Spazi", 0, &mut b),
        Err(CofferError::IsADirectory(_))
    ));
    assert!(matches!(
        fs.list("/hello.txt/x"),
        Err(CofferError::NotADirectory(_))
    ));
}

#[test]
fn case_insensitive_lookup() {
    let fs = FatFs::open(image(16, fatfs::FatType::Fat16)).unwrap();
    assert_eq!(fs.stat("/HELLO.TXT").unwrap().size, 12);
    assert_eq!(fs.stat("/upper.txt").unwrap().size, 2);
    assert_eq!(fs.stat("/ÀÈÌÒÙ €.TXT").unwrap().size, 7);
    assert!(fs.stat("/cartella con spazi/SUB").unwrap().is_dir);
}

#[test]
fn corrupted_boot_sector_is_corrupted() {
    let dev = image(16, fatfs::FatType::Fat16);
    dev.write_at(0, &[0u8; 512]).unwrap();
    assert!(matches!(FatFs::open(dev), Err(CofferError::Corrupted(_))));
}

#[test]
fn stats_report_sizes() {
    let s = FatFs::open(image(16, fatfs::FatType::Fat16))
        .unwrap()
        .stats()
        .unwrap();
    assert!(s.total_bytes > 15 * 1024 * 1024 && s.total_bytes <= 16 * 1024 * 1024);
    assert!(s.free_bytes.unwrap() < s.total_bytes);
    assert!(s.cluster_size >= 512);
}

/// FAT32 root directory whose cluster chain points to itself, with no
/// end-of-directory marker: listing must fail fast instead of looping.
#[test]
fn cyclic_root_dir_chain_is_corrupted() {
    let mut cur = Cursor::new(vec![0u8; 64 * 1024 * 1024]);
    fatfs::format_volume(
        &mut cur,
        fatfs::FormatVolumeOptions::new().fat_type(fatfs::FatType::Fat32),
    )
    .unwrap();
    let mut img = cur.into_inner();
    let le16 = |o: usize| u16::from_le_bytes([img[o], img[o + 1]]) as usize;
    let le32 = |o: usize| u32::from_le_bytes([img[o], img[o + 1], img[o + 2], img[o + 3]]) as usize;
    let (bps, spc, rsvd, nfats, fatsz, rc) = (
        le16(11),
        img[13] as usize,
        le16(14),
        img[16] as usize,
        le32(36),
        le32(44),
    );
    for f in 0..nfats {
        let off = (rsvd + f * fatsz) * bps + rc * 4;
        img[off..off + 4].copy_from_slice(&(rc as u32).to_le_bytes());
    }
    let start = (rsvd + nfats * fatsz) * bps + (rc - 2) * spc * bps;
    for slot in (start..start + spc * bps).step_by(32) {
        if img[slot] == 0 {
            img[slot] = 0xE5; // deleted entry: iteration continues
            img[slot + 11] = 0x20;
        }
    }
    let fs = FatFs::open(Arc::new(MemDevice::new(img))).unwrap();
    let t = std::time::Instant::now();
    assert!(matches!(fs.list("/"), Err(CofferError::Corrupted(_))));
    assert!(t.elapsed() < std::time::Duration::from_secs(10));
}

struct Counting {
    inner: MemDevice,
    calls: std::sync::atomic::AtomicU64,
}
impl BlockDevice for Counting {
    fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<(), CofferError> {
        self.calls
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.inner.read_at(off, buf)
    }
    fn write_at(&self, off: u64, buf: &[u8]) -> Result<(), CofferError> {
        self.inner.write_at(off, buf)
    }
    fn len(&self) -> u64 {
        self.inner.len()
    }
    fn flush(&self) -> Result<(), CofferError> {
        Ok(())
    }
}

/// Sequential reads must not re-walk the whole FAT chain from the device on
/// every call (quadratic in file size).
#[test]
fn sequential_reads_are_not_quadratic() {
    let mut cur = Cursor::new(vec![0u8; 64 * 1024 * 1024]);
    fatfs::format_volume(
        &mut cur,
        fatfs::FormatVolumeOptions::new()
            .fat_type(fatfs::FatType::Fat32)
            .bytes_per_cluster(512),
    )
    .unwrap();
    let data = payload(2 * 1024 * 1024); // 4096 clusters
    {
        let fs = fatfs::FileSystem::new(&mut cur, fatfs::FsOptions::new()).unwrap();
        fs.root_dir()
            .create_file("big.bin")
            .unwrap()
            .write_all(&data)
            .unwrap();
    }
    let c = Arc::new(Counting {
        inner: MemDevice::new(cur.into_inner()),
        calls: Default::default(),
    });
    let fs = FatFs::open(c.clone()).unwrap();
    let mut buf = vec![0u8; 64 * 1024];
    let mut off = 0u64;
    while off < data.len() as u64 {
        let n = fs.read("/big.bin", off, &mut buf).unwrap();
        assert_eq!(&buf[..n], &data[off as usize..off as usize + n]);
        off += n as u64;
    }
    let calls = c.calls.load(std::sync::atomic::Ordering::Relaxed);
    assert!(calls < 20_000, "{calls} device reads for a 2 MiB file");
}

/// A directory entry claiming more bytes than its cluster chain holds must
/// be reported, not silently truncated.
#[test]
fn chain_shorter_than_size_is_corrupted() {
    let mut cur = Cursor::new(vec![0u8; 16 * 1024 * 1024]);
    fatfs::format_volume(
        &mut cur,
        fatfs::FormatVolumeOptions::new().fat_type(fatfs::FatType::Fat16),
    )
    .unwrap();
    {
        let fs = fatfs::FileSystem::new(&mut cur, fatfs::FsOptions::new()).unwrap();
        fs.root_dir()
            .create_file("SHORT.BIN")
            .unwrap()
            .write_all(&payload(100))
            .unwrap();
    }
    let mut img = cur.into_inner();
    // Find the 8.3 entry "SHORT   BIN" and inflate its size field (offset 28).
    let pos = img.windows(11).position(|w| w == b"SHORT   BIN").unwrap();
    img[pos + 28..pos + 32].copy_from_slice(&1_000_000u32.to_le_bytes());
    let fs = FatFs::open(Arc::new(MemDevice::new(img))).unwrap();
    let mut b = vec![0u8; 4096];
    assert!(matches!(
        fs.read("/SHORT.BIN", 500_000, &mut b),
        Err(CofferError::Corrupted(_))
    ));
    assert!(matches!(
        fs.read("/SHORT.BIN", 0, &mut vec![0u8; 200_000]),
        Err(CofferError::Corrupted(_))
    ));
}
