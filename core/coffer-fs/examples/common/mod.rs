// SPDX-License-Identifier: GPL-3.0-or-later
//! Shared by the `mutate` and `create` examples: the deterministic set of
//! changes and the manifest writer that Windows checks.
#![allow(dead_code)]
use coffer_fs::Filesystem;
use sha2::{Digest, Sha256};

pub fn pattern(n: usize, seed: u64) -> Vec<u8> {
    let mut x = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1;
    (0..n)
        .map(|_| {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            (x >> 24) as u8
        })
        .collect()
}

pub fn sha_hex(b: &[u8]) -> String {
    Sha256::digest(b)
        .iter()
        .map(|x| format!("{x:02x}"))
        .collect()
}

pub fn mutate(fs: &dyn Filesystem) -> Result<(), coffer_vfs::CofferError> {
    let free = fs.stats()?.free_bytes.unwrap_or(0);
    let big = (5usize << 20).min((free / 4) as usize);
    fs.mkdir("/coffer")?;
    fs.mkdir("/coffer/sub")?;
    fs.mkdir("/coffer/sub/deep")?;
    fs.create_file("/coffer/hello.txt")?;
    fs.write("/coffer/hello.txt", 0, "Ciao da Coffer!\n".as_bytes())?;
    fs.create_file("/coffer/àèìòù €.txt")?;
    fs.write("/coffer/àèìòù €.txt", 0, &pattern(333, 1))?;
    fs.create_file("/coffer/big.bin")?;
    let data = pattern(big, 2);
    for (i, chunk) in data.chunks(1 << 20).enumerate() {
        fs.write("/coffer/big.bin", (i << 20) as u64, chunk)?;
    }
    fs.write("/coffer/big.bin", 5, b"PATCH")?;
    fs.mkdir("/coffer/many")?;
    for i in 0..300 {
        let p = format!("/coffer/many/f{i:03}.txt");
        fs.create_file(&p)?;
        fs.write(&p, 0, format!("file number {i}\n").as_bytes())?;
    }
    fs.create_file(&format!("/coffer/{}.txt", "n".repeat(120)))?;
    fs.rename("/coffer/hello.txt", "/coffer/sub/saluti.txt")?;
    fs.rename("/coffer/sub/deep", "/coffer/deep-moved")?;
    fs.create_file("/coffer/deep-moved/inside.txt")?;
    fs.write("/coffer/deep-moved/inside.txt", 0, b"moved dir still works")?;
    fs.create_file("/coffer/temp.bin")?;
    fs.write("/coffer/temp.bin", 0, &pattern(100_000, 3))?;
    fs.remove("/coffer/temp.bin")?;
    // Changes to files that were written by Windows (populated corpus).
    if fs.stat("/one.bin").is_ok() {
        fs.remove("/one.bin")?;
    }
    if fs.stat("/vuota").is_ok() {
        fs.remove("/vuota")?;
    }
    if fs.stat("/sector.bin").is_ok() {
        fs.set_len("/sector.bin", 100)?;
    }
    if fs.stat("/odd.bin").is_ok() {
        fs.write("/odd.bin", 4000, &pattern(10_000, 4))?; // extend past the end
    }
    fs.sync()
}

pub fn walk(
    fs: &dyn Filesystem,
    dir: &str,
    out: &mut Vec<String>,
) -> Result<(), coffer_vfs::CofferError> {
    for e in fs.list(dir)? {
        let p = if dir == "/" {
            format!("/{}", e.name)
        } else {
            format!("{dir}/{}", e.name)
        };
        if p.starts_with("/System Volume Information") {
            continue;
        }
        if e.is_dir {
            out.push(format!(
                "{{\"path\":{:?},\"dir\":true,\"size\":0,\"sha256\":\"\"}}",
                p
            ));
            walk(fs, &p, out)?;
        } else {
            // Streamed: files may be larger than memory (> 4 GiB on exFAT).
            let mut h = Sha256::new();
            let mut buf = vec![0u8; 4 << 20];
            let mut off = 0u64;
            while off < e.size {
                let n = fs.read(&p, off, &mut buf)?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
                off += n as u64;
            }
            let digest: String = h.finalize().iter().map(|x| format!("{x:02x}")).collect();
            out.push(format!(
                "{{\"path\":{:?},\"dir\":false,\"size\":{},\"sha256\":\"{}\"}}",
                p, off, digest
            ));
        }
    }
    Ok(())
}
