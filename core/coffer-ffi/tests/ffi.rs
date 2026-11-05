// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{encrypt_header, CipherSuite, Credentials, Prf, VolumeHeader, XtsCascade};
use coffer_ffi::{open_device, FfiCancel, FfiCredentials, FfiError, MAX_READ};
use coffer_volume::{BlockDevice, MemDevice, DATA_START};
use std::io::{Cursor, Write};
use std::sync::Arc;
use zeroize::Zeroizing;

const LEN: u64 = 4 * 1024 * 1024;

/// A VeraCrypt-layout container (AES, SHA-512, PIM 1) holding a FAT16 image
/// with /hello.txt and /dir/big.bin.
fn container() -> Arc<dyn BlockDevice> {
    let size = LEN - 2 * DATA_START;
    let mut cur = Cursor::new(vec![0u8; size as usize]);
    fatfs::format_volume(&mut cur, fatfs::FormatVolumeOptions::new()).unwrap();
    {
        let fs = fatfs::FileSystem::new(&mut cur, fatfs::FsOptions::new()).unwrap();
        let root = fs.root_dir();
        root.create_file("hello.txt")
            .unwrap()
            .write_all(b"hello ffi")
            .unwrap();
        let d = root.create_dir("dir").unwrap();
        d.create_file("big.bin").unwrap().write_all(&big()).unwrap();
    }
    let mut plain = cur.into_inner();
    let mut mk = [0u8; 256];
    mk.iter_mut()
        .enumerate()
        .for_each(|(i, b)| *b = i as u8 ^ 0x5A);
    XtsCascade::new(CipherSuite::Aes, &mk[..64])
        .unwrap()
        .encrypt_units(&mut plain, DATA_START / 512);
    let h = VolumeHeader {
        version: 5,
        min_program_version: 0x011a,
        hidden_volume_size: 0,
        volume_size: size,
        encrypted_area_start: DATA_START,
        encrypted_area_size: size,
        flags: 0,
        sector_size: 512,
        master_key: Zeroizing::new(mk),
        suite: CipherSuite::Aes,
        prf: Prf::Sha512,
    };
    let mut c = Credentials::new(b"ffi-pass");
    c.pim = 1;
    let mut img = vec![0u8; LEN as usize];
    img[..512].copy_from_slice(&encrypt_header(&h, &c, &[7; 64]).unwrap());
    img[DATA_START as usize..(DATA_START + size) as usize].copy_from_slice(&plain);
    Arc::new(MemDevice::new(img))
}

fn big() -> Vec<u8> {
    (0..300_000u32).map(|i| (i % 241) as u8).collect()
}

fn creds(pw: &str) -> FfiCredentials {
    FfiCredentials {
        password: pw.as_bytes().to_vec(),
        pim: 1,
        keyfiles: vec![],
        prf: Some("SHA-512".into()),
        max_kdf_memory: 1 << 30,
        try_backup_headers: false,
    }
}

#[test]
fn wrong_password_maps_to_wrong_credentials() {
    let r = open_device(container(), creds("nope"), FfiCancel::new());
    assert!(matches!(r, Err(FfiError::WrongCredentials)));
}

#[test]
fn list_and_read_roundtrip() {
    let v = open_device(container(), creds("ffi-pass"), FfiCancel::new()).unwrap();
    let info = v.info();
    assert_eq!(info.cipher, "AES");
    assert_eq!(info.prf, "SHA-512");
    assert!(!info.hidden);
    assert!(info.fs_kind.starts_with("FAT"));
    let mut names: Vec<String> = v
        .list("/".into())
        .unwrap()
        .into_iter()
        .map(|e| e.name)
        .collect();
    names.sort();
    assert_eq!(names, vec!["dir", "hello.txt"]);
    assert_eq!(v.read("/hello.txt".into(), 0, 100).unwrap(), b"hello ffi");
    let st = v.stat("/dir/big.bin".into()).unwrap();
    assert_eq!(st.size, 300_000);
    let mut all = Vec::new();
    loop {
        let chunk = v
            .read("/dir/big.bin".into(), all.len() as u64, 65_536)
            .unwrap();
        if chunk.is_empty() {
            break;
        }
        all.extend_from_slice(&chunk);
    }
    assert_eq!(all, big());
    assert!(v.stats().unwrap().total_bytes > 0);
}

#[test]
fn read_len_cap() {
    let v = open_device(container(), creds("ffi-pass"), FfiCancel::new()).unwrap();
    assert!(v.read("/hello.txt".into(), 0, MAX_READ + 1).is_err());
}

#[test]
fn errors_are_mapped() {
    let v = open_device(container(), creds("ffi-pass"), FfiCancel::new()).unwrap();
    assert!(matches!(
        v.stat("/missing".into()),
        Err(FfiError::NotFound { .. })
    ));
    assert!(matches!(
        v.list("/hello.txt".into()),
        Err(FfiError::NotADirectory { .. })
    ));
    assert!(matches!(
        v.read("/dir".into(), 0, 10),
        Err(FfiError::IsADirectory { .. })
    ));
    assert!(matches!(
        v.list("relative".into()),
        Err(FfiError::InvalidPath { .. })
    ));
}

#[test]
fn closed_vault_errors() {
    let v = open_device(container(), creds("ffi-pass"), FfiCancel::new()).unwrap();
    v.close();
    assert!(matches!(v.list("/".into()), Err(FfiError::Closed)));
    v.close(); // idempotent
}

#[test]
fn cancel_maps_to_cancelled() {
    let c = FfiCancel::new();
    c.cancel();
    let r = open_device(container(), creds("ffi-pass"), c);
    assert!(matches!(r, Err(FfiError::Cancelled)));
}
