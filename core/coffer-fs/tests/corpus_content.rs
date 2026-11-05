// SPDX-License-Identifier: GPL-3.0-or-later
//! Verifies every file written by Windows into the populated corpus
//! containers (corpus/populate.ps1) byte for byte.
use coffer_fs::{mount, Filesystem, FsKind};
use coffer_volume::{open_volume, CancelToken, Credentials, FileDevice};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;

fn walk(fs: &dyn Filesystem, dir: &str, depth: usize, out: &mut BTreeMap<String, (bool, u64)>) {
    assert!(depth < 64, "directory tree too deep");
    for e in fs.list(dir).unwrap() {
        let p = if dir == "/" {
            format!("/{}", e.name)
        } else {
            format!("{dir}/{}", e.name)
        };
        out.insert(p.clone(), (e.is_dir, e.size));
        if e.is_dir {
            walk(fs, &p, depth + 1, out);
        }
    }
}

fn sha256_of(fs: &dyn Filesystem, path: &str, size: u64) -> String {
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    let mut off = 0u64;
    loop {
        let n = fs.read(path, off, &mut buf).unwrap();
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
        off += n as u64;
    }
    assert_eq!(off, size, "{path}: read {off} bytes, expected {size}");
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
#[ignore]
fn corpus_content_matches() {
    let dir = PathBuf::from(std::env::var("COFFER_CORPUS").expect("COFFER_CORPUS non impostata"));
    let manifest: Value = serde_json::from_str(
        std::fs::read_to_string(dir.join("manifest.json"))
            .unwrap()
            .trim_start_matches('\u{feff}'),
    )
    .unwrap();
    let mut checked = 0;
    for entry in std::fs::read_dir(&dir).unwrap() {
        let name = entry.unwrap().file_name().into_string().unwrap();
        let Some(stem) = name
            .strip_prefix("content-")
            .and_then(|s| s.strip_suffix(".json"))
        else {
            continue;
        };
        let hc = format!("{stem}.hc");
        let m = manifest
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["file"] == hc.as_str())
            .unwrap_or_else(|| panic!("{hc} not in manifest"));
        let mut creds = Credentials::new(m["password"].as_str().unwrap().as_bytes());
        creds.pim = m["pim"].as_u64().unwrap() as u32;
        let dev = Arc::new(FileDevice::open(dir.join(&hc), false).unwrap());
        let vol = Arc::new(open_volume(dev, &creds, &CancelToken::new()).unwrap());
        let fs = mount(vol).unwrap();
        let want_kind = if stem.ends_with("fat12") {
            FsKind::Fat12
        } else if stem.ends_with("fat32") {
            FsKind::Fat32
        } else if stem.contains("exfat") {
            FsKind::ExFat
        } else {
            FsKind::Fat16
        };
        assert_eq!(fs.kind(), want_kind, "{hc}");

        let content: Value = serde_json::from_str(
            std::fs::read_to_string(dir.join(&name))
                .unwrap()
                .trim_start_matches('\u{feff}'),
        )
        .unwrap();
        let mut found = BTreeMap::new();
        walk(fs.as_ref(), "/", 0, &mut found);
        let expected: BTreeMap<String, (bool, u64, String)> = content
            .as_array()
            .unwrap()
            .iter()
            .map(|e| {
                (
                    e["path"].as_str().unwrap().to_string(),
                    (
                        e["dir"].as_bool().unwrap(),
                        e["size"].as_u64().unwrap(),
                        e["sha256"].as_str().unwrap().to_string(),
                    ),
                )
            })
            .collect();
        // Windows adds this folder on mount; populate.ps1 does not record it.
        found.retain(|p, _| !p.starts_with("/System Volume Information"));
        let missing: Vec<&String> = expected
            .keys()
            .filter(|p| !found.contains_key(*p))
            .collect();
        let extra: Vec<&String> = found
            .keys()
            .filter(|p| !expected.contains_key(*p))
            .collect();
        assert!(
            missing.is_empty() && extra.is_empty(),
            "{hc}: missing {missing:?}, extra {extra:?}"
        );
        for (path, (is_dir, size, sha)) in &expected {
            let (fd, fsz) = found[path];
            assert_eq!(fd, *is_dir, "{hc}{path}: is_dir");
            if !is_dir {
                assert_eq!(fsz, *size, "{hc}{path}: size");
                assert_eq!(
                    &sha256_of(fs.as_ref(), path, *size),
                    sha,
                    "{hc}{path}: sha256"
                );
                let st = fs.stat(path).unwrap();
                assert!(
                    st.modified.is_some_and(|t| t > 1_700_000_000),
                    "{hc}{path}: modified {:?}",
                    st.modified
                );
            }
        }
        // Random-offset reads of the fragmented file agree with a full read.
        let frag = "/frag.bin";
        let size = fs.stat(frag).unwrap().size as usize;
        let mut whole = vec![0u8; size];
        assert_eq!(fs.read(frag, 0, &mut whole).unwrap(), size);
        let mut x: u64 = 0x2545_F491_4F6C_DD1D;
        for _ in 0..200 {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            let off = (x % size as u64) as usize;
            let len = 1 + ((x >> 32) % 9000) as usize;
            let mut b = vec![0u8; len];
            let n = fs.read(frag, off as u64, &mut b).unwrap();
            assert_eq!(n, len.min(size - off));
            assert_eq!(
                &b[..n],
                &whole[off..off + n],
                "{hc}: frag.bin at {off}+{len}"
            );
        }
        let st = fs.stats().unwrap();
        println!(
            "OK {hc:<32} {:<6} {} entries, {} KiB free",
            fs.kind().name(),
            expected.len(),
            st.free_bytes.unwrap_or(0) / 1024
        );
        checked += 1;
    }
    assert!(checked >= 4, "only {checked} populated containers found");
}
