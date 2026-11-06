// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{encrypt_header, CipherSuite, Credentials, Prf, VolumeHeader, XtsCascade};
use coffer_ffi::{open_device_secret, FfiCancel, FfiError, MAX_READ};
use coffer_volume::{BlockDevice, MemDevice, DATA_START};
use std::io::{Cursor, Write};
use std::sync::{Arc, Mutex};
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

fn open(
    pw: &str,
    cancel: std::sync::Arc<FfiCancel>,
) -> Result<std::sync::Arc<coffer_ffi::FfiVault>, FfiError> {
    open_device_secret(
        container(),
        &mut blob(pw.as_bytes(), &[]),
        opts(),
        false,
        None,
        cancel,
    )
}

#[test]
fn wrong_password_maps_to_wrong_credentials() {
    let r = open("nope", FfiCancel::new());
    assert!(matches!(r, Err(FfiError::WrongCredentials)));
}

#[test]
fn list_and_read_roundtrip() {
    let v = open("ffi-pass", FfiCancel::new()).unwrap();
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
    let v = open("ffi-pass", FfiCancel::new()).unwrap();
    assert!(v.read("/hello.txt".into(), 0, MAX_READ + 1).is_err());
}

#[test]
fn errors_are_mapped() {
    let v = open("ffi-pass", FfiCancel::new()).unwrap();
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
    let v = open("ffi-pass", FfiCancel::new()).unwrap();
    v.lock();
    assert!(matches!(v.list("/".into()), Err(FfiError::Closed)));
    v.lock(); // idempotent
}

#[test]
fn cancel_maps_to_cancelled() {
    let c = FfiCancel::new();
    c.cancel();
    let r = open("ffi-pass", c);
    assert!(matches!(r, Err(FfiError::Cancelled)));
}

// ---- secrets passed through caller-owned memory (review P3 #3) ----

fn blob(pw: &[u8], keyfiles: &[&[u8]]) -> Vec<u8> {
    let mut b = (pw.len() as u32).to_le_bytes().to_vec();
    b.extend_from_slice(pw);
    b.extend_from_slice(&(keyfiles.len() as u32).to_le_bytes());
    for k in keyfiles {
        b.extend_from_slice(&(k.len() as u32).to_le_bytes());
        b.extend_from_slice(k);
    }
    b
}

#[test]
fn secret_blob_is_parsed_and_source_wiped() {
    let mut b = blob(b"ffi-pass", &[b"kf1", b""]);
    let s = coffer_ffi::take_secrets(&mut b).unwrap();
    assert_eq!(s.password.as_slice(), b"ffi-pass");
    assert_eq!(s.keyfiles.len(), 2);
    assert_eq!(s.keyfiles[0].as_slice(), b"kf1");
    assert!(b.iter().all(|x| *x == 0), "source not wiped");
}

#[test]
fn malformed_secret_blob_is_rejected_and_wiped() {
    for mut b in [vec![9, 0, 0, 0, 1, 2], vec![1, 0], {
        let mut v = blob(b"pw", &[]);
        v[6] = 5; // keyfile count 5 but no keyfiles
        v
    }] {
        assert!(coffer_ffi::take_secrets(&mut b).is_err());
        assert!(b.iter().all(|x| *x == 0));
    }
}

#[test]
fn open_with_secrets_roundtrip() {
    let mut b = blob(b"ffi-pass", &[]);
    let v =
        coffer_ffi::open_device_secret(container(), &mut b, opts(), false, None, FfiCancel::new())
            .unwrap();
    assert_eq!(v.read("/hello.txt".into(), 0, 100).unwrap(), b"hello ffi");
}

fn opts() -> coffer_ffi::FfiOpenOptions {
    coffer_ffi::FfiOpenOptions {
        pim: 1,
        prf: Some("SHA-512".into()),
        max_kdf_memory: 1 << 30,
        try_backup_headers: false,
    }
}

// ---- P4: writing ----

#[test]
fn write_operations_roundtrip() {
    let dev = container();
    let v = open_device_secret(
        dev.clone(),
        &mut blob(b"ffi-pass", &[]),
        opts(),
        true,
        None,
        FfiCancel::new(),
    )
    .unwrap();
    assert!(v.info().writable);
    v.mkdir("/new".into()).unwrap();
    v.create_file("/new/a.txt".into()).unwrap();
    v.write("/new/a.txt".into(), 0, b"hello write".to_vec())
        .unwrap();
    v.rename("/new/a.txt".into(), "/new/b.txt".into()).unwrap();
    v.create_file("/gone".into()).unwrap();
    v.remove("/gone".into()).unwrap();
    assert!(matches!(
        v.create_file("/new/b.txt".into()),
        Err(FfiError::AlreadyExists { .. })
    ));
    v.lock(); // syncs
    let r = open_device_secret(
        dev,
        &mut blob(b"ffi-pass", &[]),
        opts(),
        false,
        None,
        FfiCancel::new(),
    )
    .unwrap();
    assert_eq!(r.read("/new/b.txt".into(), 0, 100).unwrap(), b"hello write");
    assert!(matches!(
        r.stat("/gone".into()),
        Err(FfiError::NotFound { .. })
    ));
}

#[test]
fn read_only_vault_rejects_writes() {
    let v = open("ffi-pass", FfiCancel::new()).unwrap();
    assert!(!v.info().writable);
    assert!(matches!(v.mkdir("/x".into()), Err(FfiError::ReadOnly)));
}

#[test]
fn hidden_protection_needs_a_hidden_volume() {
    let mut hidden = blob(b"not-there", &[]);
    let r = open_device_secret(
        container(),
        &mut blob(b"ffi-pass", &[]),
        opts(),
        true,
        Some(&mut hidden),
        FfiCancel::new(),
    );
    assert!(matches!(r, Err(FfiError::WrongCredentials)));
    assert!(hidden.iter().all(|b| *b == 0), "hidden secrets wiped");
}

// ---- P5: creating ----

struct Progress(Mutex<Vec<(u64, u64)>>, Option<Arc<FfiCancel>>);

impl coffer_ffi::FfiProgress for Progress {
    fn on_progress(&self, done: u64, total: u64) {
        self.0.lock().unwrap().push((done, total));
        if done > 0 {
            if let Some(c) = &self.1 {
                c.cancel();
            }
        }
    }
}

fn create_opts(fs: &str, quick: bool, size: u64) -> coffer_ffi::FfiCreateOptions {
    coffer_ffi::FfiCreateOptions {
        size,
        cipher: "AES".into(),
        prf: "SHA-512".into(),
        pim: 1,
        fs: fs.into(),
        quick,
        label: "Coffer".into(),
        extra_entropy: vec![1, 2, 3, 4],
    }
}

fn empty_device(size: u64) -> Arc<dyn BlockDevice> {
    Arc::new(coffer_volume::MemDevice::new(vec![0u8; size as usize]))
}

#[test]
fn create_then_write_and_reopen() {
    for fs in ["exFAT", "FAT"] {
        let dev = empty_device(4 << 20);
        let progress = Arc::new(Progress(Mutex::new(Vec::new()), None));
        let mut b = blob(b"new-pass", &[]);
        let v = coffer_ffi::create_device_secret(
            dev.clone(),
            &mut b,
            create_opts(fs, false, 4 << 20),
            progress.clone(),
            FfiCancel::new(),
        )
        .unwrap();
        assert!(b.iter().all(|x| *x == 0), "secrets not wiped");
        assert!(v.info().writable);
        assert_eq!(v.info().cipher, "AES");
        v.create_file("/a.txt".into()).unwrap();
        v.write("/a.txt".into(), 0, b"hi".to_vec()).unwrap();
        v.lock();
        let mut b = blob(b"new-pass", &[]);
        let again =
            coffer_ffi::open_device_secret(dev, &mut b, opts(), false, None, FfiCancel::new())
                .unwrap();
        assert_eq!(again.read("/a.txt".into(), 0, 10).unwrap(), b"hi");
        let p = progress.0.lock().unwrap();
        assert_eq!(
            p.last().map(|x| x.0 == x.1),
            Some(true),
            "{fs}: progress did not reach total"
        );
    }
}

#[test]
fn create_can_be_cancelled() {
    let dev = empty_device(8 << 20);
    let cancel = FfiCancel::new();
    let progress = Arc::new(Progress(Mutex::new(Vec::new()), Some(cancel.clone())));
    let mut b = blob(b"new-pass", &[]);
    let r = coffer_ffi::create_device_secret(
        dev,
        &mut b,
        create_opts("exFAT", false, 8 << 20),
        progress,
        cancel,
    );
    assert!(matches!(r, Err(FfiError::Cancelled)));
}

#[test]
fn create_rejects_bad_options() {
    let mut b = blob(b"p", &[]);
    let mut o = create_opts("exFAT", true, 4 << 20);
    o.cipher = "Rot13".into();
    let p = Arc::new(Progress(Mutex::new(Vec::new()), None));
    assert!(matches!(
        coffer_ffi::create_device_secret(
            empty_device(4 << 20),
            &mut b,
            o,
            p.clone(),
            FfiCancel::new()
        ),
        Err(FfiError::Unsupported { .. })
    ));
    let mut b = blob(b"p", &[]);
    // exFAT needs at least 4 MiB of container.
    assert!(matches!(
        coffer_ffi::create_device_secret(
            empty_device(2 << 20),
            &mut b,
            create_opts("exFAT", true, 2 << 20),
            p,
            FfiCancel::new()
        ),
        Err(FfiError::InvalidSize { .. })
    ));
}

#[test]
fn lists_ciphers_and_prfs() {
    assert_eq!(coffer_ffi::list_ciphers().len(), 15);
    assert_eq!(coffer_ffi::list_ciphers()[0], "AES");
    assert!(coffer_ffi::list_prfs().contains(&"Argon2id".to_string()));
}

#[test]
fn unclean_volume_is_flagged_and_can_drop_to_read_only() {
    let dev = empty_device(4 << 20);
    let p = Arc::new(Progress(Mutex::new(Vec::new()), None));
    let mut b = blob(b"new-pass", &[]);
    let v = coffer_ffi::create_device_secret(
        dev.clone(),
        &mut b,
        create_opts("exFAT", true, 4 << 20),
        p,
        FfiCancel::new(),
    )
    .unwrap();
    assert!(!v.info().was_dirty);
    v.create_file("/x".into()).unwrap();
    drop(v); // no lock(): like the app being killed mid-session
    let mut b = blob(b"new-pass", &[]);
    let v =
        coffer_ffi::open_device_secret(dev, &mut b, opts(), true, None, FfiCancel::new()).unwrap();
    assert!(v.info().was_dirty);
    assert!(v.info().writable);
    v.make_read_only().unwrap();
    assert!(!v.info().writable);
    assert!(matches!(
        v.create_file("/y".into()),
        Err(FfiError::ReadOnly)
    ));
    assert_eq!(v.list("/".into()).unwrap().len(), 1);
}
