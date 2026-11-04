// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{
    encrypt_header, CancelToken, CipherSuite, CofferError, Credentials, Prf, VolumeHeader,
    XtsCascade,
};
use coffer_volume::{open_volume, BlockDevice, MemDevice, VolumeKind, DATA_START};
use std::sync::Arc;
use zeroize::Zeroizing;

const LEN: u64 = 1024 * 1024; // 1 MiB container
const HIDDEN_SIZE: u64 = 128 * 1024;

fn creds(pw: &str) -> Credentials {
    let mut c = Credentials::new(pw.as_bytes());
    c.pim = 1;
    c.prf_hint = Some(Prf::Sha512);
    c
}

fn master_key(seed: u8) -> Zeroizing<[u8; 256]> {
    let mut k = [0u8; 256];
    for (i, b) in k.iter_mut().enumerate() {
        *b = (i as u8).wrapping_mul(13) ^ seed;
    }
    Zeroizing::new(k)
}

fn header(seed: u8, ea_start: u64, size: u64, hidden: u64) -> VolumeHeader {
    VolumeHeader {
        version: 5,
        min_program_version: 0x011a,
        hidden_volume_size: hidden,
        volume_size: size,
        encrypted_area_start: ea_start,
        encrypted_area_size: size,
        flags: 0,
        sector_size: 512,
        master_key: master_key(seed),
        suite: CipherSuite::Aes,
        prf: Prf::Sha512,
    }
}

/// Plaintext pattern of the volume byte at offset `i` (seeded per volume).
fn pattern(seed: u8, i: u64) -> u8 {
    ((i.wrapping_mul(2654435761) >> 7) as u8) ^ seed
}

/// Encrypts `size` bytes of pattern data at host offset `start`. Data unit
/// number = host byte offset / 512 (format-notes.md, "Area dati").
fn write_data(img: &mut [u8], start: u64, size: u64, seed: u8) {
    let x = XtsCascade::new(CipherSuite::Aes, &master_key(seed)[..64]).unwrap();
    let mut plain: Vec<u8> = (0..size).map(|i| pattern(seed, i)).collect();
    x.encrypt_units(&mut plain, start / 512);
    img[start as usize..(start + size) as usize].copy_from_slice(&plain);
}

fn put(img: &mut [u8], off: u64, raw: &[u8; 512]) {
    img[off as usize..off as usize + 512].copy_from_slice(raw);
}

fn build(with_hidden: bool) -> Vec<u8> {
    let mut img = vec![0x5Au8; LEN as usize];
    let outer_size = LEN - 2 * DATA_START;
    let h = header(1, DATA_START, outer_size, 0);
    let c = creds("outer");
    put(&mut img, 0, &encrypt_header(&h, &c, &[1; 64]).unwrap());
    put(
        &mut img,
        LEN - DATA_START,
        &encrypt_header(&h, &c, &[2; 64]).unwrap(),
    );
    write_data(&mut img, DATA_START, outer_size, 1);
    if with_hidden {
        let start = LEN - DATA_START - HIDDEN_SIZE;
        let hh = header(2, start, HIDDEN_SIZE, HIDDEN_SIZE);
        let hc = creds("hidden");
        put(
            &mut img,
            65_536,
            &encrypt_header(&hh, &hc, &[3; 64]).unwrap(),
        );
        put(
            &mut img,
            LEN - 65_536,
            &encrypt_header(&hh, &hc, &[4; 64]).unwrap(),
        );
        write_data(&mut img, start, HIDDEN_SIZE, 2);
    }
    img
}

fn dev(img: Vec<u8>) -> Arc<dyn BlockDevice> {
    Arc::new(MemDevice::new(img))
}

#[test]
fn open_standard_reads_plain() {
    let v = open_volume(dev(build(false)), &creds("outer"), &CancelToken::new()).unwrap();
    assert_eq!(v.kind, VolumeKind::Standard);
    assert!(!v.used_backup_header);
    assert_eq!(v.len(), LEN - 2 * DATA_START);
    let mut buf = vec![0u8; 4096];
    v.read_at(0, &mut buf).unwrap();
    assert!(buf
        .iter()
        .enumerate()
        .all(|(i, b)| *b == pattern(1, i as u64)));
}

#[test]
fn open_hidden_with_hidden_password() {
    let v = open_volume(dev(build(true)), &creds("hidden"), &CancelToken::new()).unwrap();
    assert_eq!(v.kind, VolumeKind::Hidden);
    assert_eq!(v.len(), HIDDEN_SIZE);
    let mut buf = vec![0u8; 1024];
    v.read_at(HIDDEN_SIZE - 1024, &mut buf).unwrap();
    assert!(buf
        .iter()
        .enumerate()
        .all(|(i, b)| *b == pattern(2, HIDDEN_SIZE - 1024 + i as u64)));
}

#[test]
fn outer_password_still_opens_outer_when_hidden_exists() {
    let v = open_volume(dev(build(true)), &creds("outer"), &CancelToken::new()).unwrap();
    assert_eq!(v.kind, VolumeKind::Standard);
}

#[test]
fn backup_header_used_when_primary_zeroed() {
    let mut img = build(false);
    img[..512].fill(0);
    let v = open_volume(dev(img), &creds("outer"), &CancelToken::new()).unwrap();
    assert!(v.used_backup_header);
    let mut buf = [0u8; 16];
    v.read_at(0, &mut buf).unwrap();
    assert_eq!(buf[0], pattern(1, 0));
}

#[test]
fn wrong_password_is_wrong_credentials() {
    let r = open_volume(dev(build(true)), &creds("nope"), &CancelToken::new());
    assert!(matches!(r, Err(CofferError::WrongCredentials)));
}

#[test]
fn too_small_is_corrupted() {
    let r = open_volume(
        dev(vec![0u8; 200_000]),
        &creds("outer"),
        &CancelToken::new(),
    );
    assert!(matches!(r, Err(CofferError::Corrupted(_))));
}

#[test]
fn header_geometry_beyond_file_is_corrupted() {
    let mut img = vec![0u8; LEN as usize];
    let h = header(1, DATA_START, LEN * 4, 0);
    put(
        &mut img,
        0,
        &encrypt_header(&h, &creds("outer"), &[1; 64]).unwrap(),
    );
    let r = open_volume(dev(img), &creds("outer"), &CancelToken::new());
    assert!(matches!(r, Err(CofferError::Corrupted(_))));
}

#[test]
fn read_out_of_bounds_is_error() {
    let v = open_volume(dev(build(false)), &creds("outer"), &CancelToken::new()).unwrap();
    let mut buf = [0u8; 512];
    assert!(v.read_at(v.len() - 256, &mut buf).is_err());
    assert!(v.read_at(u64::MAX - 10, &mut buf).is_err());
}

#[test]
fn unaligned_read() {
    let v = open_volume(dev(build(false)), &creds("outer"), &CancelToken::new()).unwrap();
    let mut buf = vec![0u8; 1000];
    v.read_at(100, &mut buf).unwrap();
    assert!(buf
        .iter()
        .enumerate()
        .all(|(i, b)| *b == pattern(1, 100 + i as u64)));
}

#[test]
fn unaligned_write_then_read_back() {
    let v = open_volume(dev(build(false)), &creds("outer"), &CancelToken::new()).unwrap();
    let data: Vec<u8> = (0..777u32).map(|i| (i % 251) as u8).collect();
    v.write_at(300, &data).unwrap();
    let mut back = vec![0u8; 777];
    v.read_at(300, &mut back).unwrap();
    assert_eq!(back, data);
    let mut before = [0u8; 300];
    v.read_at(0, &mut before).unwrap();
    assert!(before
        .iter()
        .enumerate()
        .all(|(i, b)| *b == pattern(1, i as u64)));
}

#[test]
fn hidden_and_backup_still_tried_when_argon2_skipped_for_memory() {
    let mut c = creds("hidden");
    c.prf_hint = None; // auto-detect: Argon2id will be skipped for memory
    c.max_kdf_memory = 1024 * 1024;
    let v = open_volume(dev(build(true)), &c, &CancelToken::new()).unwrap();
    assert_eq!(v.kind, VolumeKind::Hidden);
    let mut img = build(false);
    img[..512].fill(0);
    let mut c = creds("outer");
    c.prf_hint = None;
    c.max_kdf_memory = 1024 * 1024;
    let v = open_volume(dev(img), &c, &CancelToken::new()).unwrap();
    assert!(v.used_backup_header);
}
