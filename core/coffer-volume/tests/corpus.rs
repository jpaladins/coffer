// SPDX-License-Identifier: GPL-3.0-or-later
//! Integration tests against real containers created by VeraCrypt (corpus/).
//! Run: COFFER_CORPUS=<dir with manifest.json> cargo test --release --test corpus -- --ignored
use coffer_crypto::{CipherSuite, Prf};
use coffer_volume::{
    open_volume, BlockDevice, CancelToken, CofferError, Credentials, FileDevice, VolumeKind,
};
use serde_json::Value;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;
use zeroize::Zeroizing;

struct Entry {
    file: String,
    password: String,
    pim: u32,
    keyfiles: Vec<String>,
    cipher: String,
    prf: String,
    fs: String,
    kind: String,
}

fn corpus() -> (PathBuf, Vec<Entry>) {
    let dir = PathBuf::from(
        std::env::var("COFFER_CORPUS").unwrap_or_else(|_| panic!("COFFER_CORPUS non impostata")),
    );
    let text = std::fs::read_to_string(dir.join("manifest.json")).unwrap();
    let v: Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).unwrap();
    let entries = v
        .as_array()
        .unwrap()
        .iter()
        .map(|e| Entry {
            file: e["file"].as_str().unwrap().into(),
            password: e["password"].as_str().unwrap().into(),
            pim: e["pim"].as_u64().unwrap() as u32,
            keyfiles: e["keyfiles"]
                .as_array()
                .unwrap()
                .iter()
                .map(|k| k.as_str().unwrap().into())
                .collect(),
            cipher: e["cipher"].as_str().unwrap().into(),
            prf: e["prf"].as_str().unwrap().into(),
            fs: e["fs"].as_str().unwrap().into(),
            kind: e["kind"].as_str().unwrap().into(),
        })
        .collect();
    (dir, entries)
}

fn creds(dir: &std::path::Path, e: &Entry, password: &str) -> Credentials {
    let mut c = Credentials::new(password.as_bytes());
    c.pim = e.pim;
    for k in &e.keyfiles {
        c.keyfiles
            .push(Zeroizing::new(std::fs::read(dir.join(k)).unwrap()));
    }
    c
}

/// Returns the detected filesystem label of the boot sector, or an error.
fn check_boot_sector(sector: &[u8], fs: &str) -> Result<&'static str, String> {
    if sector[510] != 0x55 || sector[511] != 0xAA {
        return Err(format!(
            "no 0x55AA boot signature (got {:02x}{:02x})",
            sector[510], sector[511]
        ));
    }
    match fs {
        "FAT" if &sector[82..90] == b"FAT32   " => Ok("FAT32"),
        "FAT" if &sector[54..62] == b"FAT16   " => Ok("FAT16"),
        "FAT" if &sector[54..62] == b"FAT12   " => Ok("FAT12"),
        "exFAT" if &sector[3..11] == b"EXFAT   " => Ok("exFAT"),
        _ => Err(format!("filesystem signature for {fs} not found")),
    }
}

#[test]
#[ignore]
fn corpus_all_entries_open() {
    let (dir, entries) = corpus();
    assert!(!entries.is_empty());
    let mut failures = Vec::new();
    for e in &entries {
        let start = Instant::now();
        let dev = Arc::new(FileDevice::open(dir.join(&e.file), false).unwrap());
        let res = open_volume(dev, &creds(&dir, e, &e.password), &CancelToken::new())
            .map_err(|err| format!("open: {err}"))
            .and_then(|v| {
                if Some(v.header.suite) != CipherSuite::from_name(&e.cipher) {
                    return Err(format!("cipher {:?}", v.header.suite));
                }
                if Some(v.header.prf) != Prf::from_name(&e.prf) {
                    return Err(format!("prf {:?}", v.header.prf));
                }
                let want = if e.kind == "hidden" {
                    VolumeKind::Hidden
                } else {
                    VolumeKind::Standard
                };
                if v.kind != want {
                    return Err(format!("kind {:?}", v.kind));
                }
                let mut s = [0u8; 512];
                v.read_at(0, &mut s).map_err(|err| format!("read: {err}"))?;
                check_boot_sector(&s, &e.fs)
            });
        let ms = start.elapsed().as_millis();
        match res {
            Ok(fs) => println!("OK   {:<48} {:<8} {ms:>6} ms", e.file, fs),
            Err(msg) => {
                println!("FAIL {:<48} {msg} ({ms} ms)", e.file);
                failures.push(format!("{}: {msg}", e.file));
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} failures:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[test]
#[ignore]
fn corpus_wrong_password_rejected() {
    let (dir, entries) = corpus();
    for e in entries.iter().filter(|e| e.keyfiles.is_empty()).take(3) {
        let dev = Arc::new(FileDevice::open(dir.join(&e.file), false).unwrap());
        let r = open_volume(dev, &creds(&dir, e, "wrong"), &CancelToken::new());
        assert!(
            matches!(r, Err(CofferError::WrongCredentials)),
            "{}: {:?}",
            e.file,
            r.err()
        );
    }
}
