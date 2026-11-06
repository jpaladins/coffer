// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{CipherSuite, Prf};
use coffer_volume::{
    create_volume, open_volume_with, BlockDevice, CancelToken, CofferError, CreateParams,
    Credentials, MemDevice, OpenOptions, DATA_START, HIDDEN_HEADER, MIN_FAT,
};
use std::sync::Arc;

/// Deterministic test RNG (xorshift); production uses the OS RNG.
fn rng(seed: u64) -> impl FnMut(&mut [u8]) -> Result<(), CofferError> {
    let mut x = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1;
    move |buf: &mut [u8]| {
        for b in buf.iter_mut() {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            *b = (x >> 32) as u8;
        }
        Ok(())
    }
}

fn creds(prf: Prf, suite: CipherSuite) -> Credentials {
    let mut c = Credentials::new(b"create-test");
    c.pim = 1; // 16,000 PBKDF2 iterations: keeps the test fast
    c.prf_hint = Some(prf);
    c.suite_hint = Some(suite);
    c
}

fn params(size: u64, suite: CipherSuite, prf: Prf, quick: bool) -> CreateParams {
    CreateParams {
        size,
        suite,
        prf,
        quick,
    }
}

fn mem(size: u64) -> Arc<MemDevice> {
    Arc::new(MemDevice::new(vec![0u8; size as usize]))
}

fn create(dev: &Arc<MemDevice>, c: &Credentials, p: &CreateParams) -> Result<(), CofferError> {
    let d: Arc<dyn BlockDevice> = dev.clone();
    create_volume(d, c, p, &mut rng(7), &|_, _| {}, &CancelToken::new()).map(|_| ())
}

fn open(
    dev: &Arc<MemDevice>,
    c: &Credentials,
    backup: bool,
) -> Result<coffer_volume::OpenedVolume, CofferError> {
    let d: Arc<dyn BlockDevice> = dev.clone();
    open_volume_with(
        d,
        c,
        &CancelToken::new(),
        OpenOptions {
            try_backup_headers: backup,
        },
    )
}

#[test]
fn created_volume_opens_with_same_credentials() {
    let size = MIN_FAT;
    for (i, suite) in CipherSuite::ALL.iter().enumerate() {
        let prf = [
            Prf::Sha512,
            Prf::Whirlpool,
            Prf::Sha256,
            Prf::Blake2s256,
            Prf::Streebog,
        ][i % 5];
        let dev = mem(size);
        let c = creds(prf, *suite);
        create(&dev, &c, &params(size, *suite, prf, true)).unwrap();
        let v = open(&dev, &c, false).unwrap();
        assert_eq!(v.header.suite, *suite);
        assert_eq!(v.header.prf, prf);
        assert_eq!(v.header.version, 5);
        assert_eq!(v.header.min_program_version, 0x010B);
        assert_eq!(v.header.sector_size, 512);
        assert_eq!(v.header.encrypted_area_start, DATA_START);
        assert_eq!(v.len(), size - 2 * DATA_START);
        // Writing and reading back through the new volume works.
        v.write_at(4096, b"hello coffer").unwrap();
        let mut b = [0u8; 12];
        v.read_at(4096, &mut b).unwrap();
        assert_eq!(&b, b"hello coffer");
    }
}

#[test]
fn backup_header_opens_when_primary_is_destroyed() {
    let dev = mem(MIN_FAT);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    create(
        &dev,
        &c,
        &params(MIN_FAT, CipherSuite::Aes, Prf::Sha512, true),
    )
    .unwrap();
    dev.write_at(0, &[0u8; 512]).unwrap();
    assert!(matches!(
        open(&dev, &c, false),
        Err(CofferError::WrongCredentials)
    ));
    let v = open(&dev, &c, true).unwrap();
    assert!(v.used_backup_header);
}

#[test]
fn salts_of_header_and_backup_differ() {
    let dev = mem(MIN_FAT);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    create(
        &dev,
        &c,
        &params(MIN_FAT, CipherSuite::Aes, Prf::Sha512, true),
    )
    .unwrap();
    let (mut a, mut b) = ([0u8; 64], [0u8; 64]);
    dev.read_at(0, &mut a).unwrap();
    dev.read_at(MIN_FAT - DATA_START, &mut b).unwrap();
    assert_ne!(a, b);
}

#[test]
fn hidden_header_area_is_not_zeros() {
    let dev = mem(MIN_FAT);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    create(
        &dev,
        &c,
        &params(MIN_FAT, CipherSuite::Aes, Prf::Sha512, true),
    )
    .unwrap();
    for off in [
        HIDDEN_HEADER,
        MIN_FAT - 65_536,
        512,
        MIN_FAT - DATA_START + 512,
    ] {
        let mut b = vec![0u8; 4096];
        dev.read_at(off, &mut b).unwrap();
        let zeros = b.iter().filter(|x| **x == 0).count();
        assert!(
            zeros < 100,
            "area at {off} looks unfilled ({zeros} zero bytes)"
        );
    }
}

#[test]
fn full_format_data_reads_as_zeros_but_is_not_zero_on_disk() {
    let size = 3 * MIN_FAT;
    let dev = mem(size);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    let d: Arc<dyn BlockDevice> = dev.clone();
    let v = create_volume(
        d,
        &c,
        &params(size, CipherSuite::Aes, Prf::Sha512, false),
        &mut rng(3),
        &|done, total| {
            let _ = (done, total);
        },
        &CancelToken::new(),
    )
    .unwrap();
    let mut plain = vec![0xFFu8; v.len() as usize];
    v.read_at(0, &mut plain).unwrap();
    assert!(plain.iter().all(|x| *x == 0));
    let mut raw = vec![0u8; 4096];
    dev.read_at(DATA_START + 8192, &mut raw).unwrap();
    assert!(raw.iter().filter(|x| **x == 0).count() < 100);
}

#[test]
fn full_format_reports_progress_to_total() {
    let size = 3 * MIN_FAT;
    let dev = mem(size);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    let last = std::sync::Mutex::new((0u64, 0u64));
    let d: Arc<dyn BlockDevice> = dev.clone();
    create_volume(
        d,
        &c,
        &params(size, CipherSuite::Aes, Prf::Sha512, false),
        &mut rng(4),
        &|done, total| {
            *last.lock().unwrap() = (done, total);
        },
        &CancelToken::new(),
    )
    .unwrap();
    let (done, total) = *last.lock().unwrap();
    assert_eq!(done, total);
    assert_eq!(total, size - 2 * DATA_START);
}

#[test]
fn cancel_stops_full_format() {
    let size = 3 * MIN_FAT;
    let dev = mem(size);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    let cancel = CancelToken::new();
    let d: Arc<dyn BlockDevice> = dev.clone();
    let r = create_volume(
        d,
        &c,
        &params(size, CipherSuite::Aes, Prf::Sha512, false),
        &mut rng(5),
        &|done, _| {
            if done > 0 {
                cancel.cancel();
            }
        },
        &cancel,
    );
    assert!(matches!(r, Err(CofferError::Cancelled)));
}

#[test]
fn too_small_is_rejected() {
    let size = MIN_FAT - 512;
    let dev = mem(size);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    let r = create(&dev, &c, &params(size, CipherSuite::Aes, Prf::Sha512, true));
    assert!(matches!(r, Err(CofferError::InvalidSize(_))), "{r:?}");
}

#[test]
fn size_not_multiple_of_512_is_rejected() {
    let size = MIN_FAT + 100;
    let dev = mem(size);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    let r = create(&dev, &c, &params(size, CipherSuite::Aes, Prf::Sha512, true));
    assert!(matches!(r, Err(CofferError::InvalidSize(_))), "{r:?}");
}

#[test]
fn device_size_must_match() {
    let dev = mem(2 * MIN_FAT);
    let c = creds(Prf::Sha512, CipherSuite::Aes);
    let r = create(
        &dev,
        &c,
        &params(MIN_FAT, CipherSuite::Aes, Prf::Sha512, true),
    );
    assert!(matches!(r, Err(CofferError::InvalidSize(_))), "{r:?}");
}

/// Review P5 #1: a full disk while formatting is reported as NoSpace.
#[test]
fn storage_full_maps_to_no_space() {
    let e: CofferError = std::io::Error::from(std::io::ErrorKind::StorageFull).into();
    assert_eq!(e, CofferError::NoSpace);
}
