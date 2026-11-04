// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{
    decrypt_header, derive_header_key, encrypt_header, CancelToken, CipherSuite, CofferError,
    Credentials, Prf, VolumeHeader, XtsCascade,
};
use std::time::{Duration, Instant};
use zeroize::Zeroizing;

fn creds(pw: &str, pim: u32) -> Credentials {
    let mut c = Credentials::new(pw.as_bytes());
    c.pim = pim;
    c
}

fn sample_header(suite: CipherSuite, prf: Prf) -> VolumeHeader {
    let mut mk = [0u8; 256];
    for (i, b) in mk.iter_mut().enumerate() {
        *b = (i as u8).wrapping_mul(31).wrapping_add(7);
    }
    VolumeHeader {
        version: 5,
        min_program_version: 0x011a,
        hidden_volume_size: 0,
        volume_size: 10 * 1024 * 1024 - 262_144,
        encrypted_area_start: 131_072,
        encrypted_area_size: 10 * 1024 * 1024 - 262_144,
        flags: 0,
        sector_size: 512,
        master_key: Zeroizing::new(mk),
        suite,
        prf,
    }
}

fn same(a: &VolumeHeader, b: &VolumeHeader) {
    assert_eq!(a.version, b.version);
    assert_eq!(a.min_program_version, b.min_program_version);
    assert_eq!(a.hidden_volume_size, b.hidden_volume_size);
    assert_eq!(a.volume_size, b.volume_size);
    assert_eq!(a.encrypted_area_start, b.encrypted_area_start);
    assert_eq!(a.encrypted_area_size, b.encrypted_area_size);
    assert_eq!(a.flags, b.flags);
    assert_eq!(a.sector_size, b.sector_size);
    assert_eq!(a.suite, b.suite);
    assert_eq!(a.prf, b.prf);
    let n = a.suite.key_len();
    assert_eq!(&a.master_key[..n], &b.master_key[..n]);
}

#[test]
fn roundtrip_every_suite_sha512() {
    let c = creds("coffer", 1);
    for suite in CipherSuite::ALL {
        let h = sample_header(suite, Prf::Sha512);
        let raw = encrypt_header(&h, &c, &[9u8; 64]).unwrap();
        let back = decrypt_header(&raw, &c, &CancelToken::new()).unwrap();
        same(&h, &back);
    }
}

#[test]
fn roundtrip_every_prf_autodetected() {
    let c = creds("coffer", 1);
    for prf in Prf::ALL {
        let h = sample_header(CipherSuite::SerpentAes, prf);
        let raw = encrypt_header(&h, &c, &[3u8; 64]).unwrap();
        let back = decrypt_header(&raw, &c, &CancelToken::new()).unwrap();
        same(&h, &back);
    }
}

#[test]
fn salt_is_stored_in_clear() {
    let c = creds("coffer", 1);
    let raw = encrypt_header(
        &sample_header(CipherSuite::Aes, Prf::Sha512),
        &c,
        &[0xAB; 64],
    )
    .unwrap();
    assert_eq!(&raw[..64], &[0xAB; 64]);
    assert_ne!(&raw[64..68], b"VERA");
}

#[test]
fn wrong_password_is_wrong_credentials() {
    let raw = encrypt_header(
        &sample_header(CipherSuite::Aes, Prf::Sha512),
        &creds("coffer", 1),
        &[1u8; 64],
    )
    .unwrap();
    let r = decrypt_header(&raw, &creds("not-it", 1), &CancelToken::new());
    assert!(matches!(r, Err(CofferError::WrongCredentials)));
}

#[test]
fn wrong_pim_is_wrong_credentials() {
    let raw = encrypt_header(
        &sample_header(CipherSuite::Aes, Prf::Sha512),
        &creds("coffer", 1),
        &[1u8; 64],
    )
    .unwrap();
    let r = decrypt_header(&raw, &creds("coffer", 2), &CancelToken::new());
    assert!(matches!(r, Err(CofferError::WrongCredentials)));
}

/// Builds a header whose magic is right but whose key-area CRC is wrong.
#[test]
fn crc_tamper_rejected() {
    let c = creds("coffer", 1);
    let salt = [5u8; 64];
    let mut plain = [0u8; 448];
    plain[..4].copy_from_slice(b"VERA");
    plain[4..6].copy_from_slice(&5u16.to_be_bytes());
    // CRC of key area (offset 72 => plain[8..12]) deliberately wrong; header CRC right.
    plain[8..12].copy_from_slice(&0xDEADBEEFu32.to_be_bytes());
    let crc = crc32fast::hash(&plain[..188]);
    plain[188..192].copy_from_slice(&crc.to_be_bytes());
    let key = derive_header_key(Prf::Sha512, b"coffer", &salt, 1, &CancelToken::new()).unwrap();
    let x = XtsCascade::new(CipherSuite::Aes, &key[..64]).unwrap();
    x.encrypt_units(&mut plain, 0);
    let mut raw = [0u8; 512];
    raw[..64].copy_from_slice(&salt);
    raw[64..].copy_from_slice(&plain);
    let r = decrypt_header(&raw, &c, &CancelToken::new());
    assert!(matches!(r, Err(CofferError::WrongCredentials)));
}

#[test]
fn hints_restrict_search() {
    let raw = encrypt_header(
        &sample_header(CipherSuite::Aes, Prf::Sha512),
        &creds("coffer", 1),
        &[2u8; 64],
    )
    .unwrap();
    let mut c = creds("coffer", 1);
    c.prf_hint = Some(Prf::Sha256);
    assert!(matches!(
        decrypt_header(&raw, &c, &CancelToken::new()),
        Err(CofferError::WrongCredentials)
    ));
    c.prf_hint = Some(Prf::Sha512);
    c.suite_hint = Some(CipherSuite::Aes);
    assert!(decrypt_header(&raw, &c, &CancelToken::new()).is_ok());
}

#[test]
fn keyfiles_are_applied() {
    let mut with_kf = creds("coffer", 1);
    with_kf.keyfiles.push(Zeroizing::new(vec![1, 2, 3, 4, 5]));
    let raw = encrypt_header(
        &sample_header(CipherSuite::Aes, Prf::Sha512),
        &with_kf,
        &[4u8; 64],
    )
    .unwrap();
    assert!(decrypt_header(&raw, &with_kf, &CancelToken::new()).is_ok());
    assert!(matches!(
        decrypt_header(&raw, &creds("coffer", 1), &CancelToken::new()),
        Err(CofferError::WrongCredentials)
    ));
}

#[test]
fn argon2_skipped_for_memory_reports_out_of_memory() {
    let raw = [0u8; 512];
    let mut c = creds("x", 0);
    c.max_kdf_memory = 1024 * 1024;
    c.prf_hint = Some(Prf::Argon2id);
    assert!(matches!(
        decrypt_header(&raw, &c, &CancelToken::new()),
        Err(CofferError::KdfOutOfMemory { .. })
    ));
}

#[test]
fn cancel_during_search() {
    let raw = [0u8; 512];
    let cancel = CancelToken::new();
    let c2 = cancel.clone();
    let t = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        c2.cancel();
    });
    let start = Instant::now();
    let mut c = creds("x", 0);
    c.prf_hint = Some(Prf::Sha512);
    let r = decrypt_header(&raw, &c, &cancel);
    t.join().unwrap();
    assert!(matches!(r, Err(CofferError::Cancelled)));
    assert!(start.elapsed() < Duration::from_secs(1));
}
