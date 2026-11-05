// SPDX-License-Identifier: GPL-3.0-or-later
//! Applies a fixed set of changes to a container (in place: pass a copy) and
//! writes the resulting tree, as Coffer reads it back, to a JSON manifest.
//! Windows then checks the same tree and runs chkdsk (corpus/verify-writes.ps1).
//!
//! Usage: mutate <container> <password> <manifest.json> [<hidden password to protect>]
use coffer_crypto::Prf;
use coffer_fs::{mount, mount_rw, Filesystem};
use coffer_volume::{open_volume, BlockDevice, CancelToken, Credentials, FileDevice};
use sha2::{Digest, Sha256};
use std::process::ExitCode;
use std::sync::Arc;

fn pattern(n: usize, seed: u64) -> Vec<u8> {
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

fn creds(pw: &str) -> Credentials {
    let mut c = Credentials::new(pw.as_bytes());
    c.prf_hint = Some(Prf::Sha512);
    c
}

fn sha_hex(b: &[u8]) -> String {
    Sha256::digest(b).iter().map(|x| format!("{x:02x}")).collect()
}

fn hidden_digest(dev: &Arc<dyn BlockDevice>, pw: &str) -> Result<String, String> {
    let v = open_volume(dev.clone(), &creds(pw), &CancelToken::new()).map_err(|e| e.to_string())?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    let mut off = 0;
    while off < v.len() {
        let n = buf.len().min((v.len() - off) as usize);
        v.read_at(off, &mut buf[..n]).map_err(|e| e.to_string())?;
        h.update(&buf[..n]);
        off += n as u64;
    }
    Ok(h.finalize().iter().map(|x| format!("{x:02x}")).collect())
}

fn mutate(fs: &dyn Filesystem) -> Result<(), coffer_vfs::CofferError> {
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

fn walk(fs: &dyn Filesystem, dir: &str, out: &mut Vec<String>) -> Result<(), coffer_vfs::CofferError> {
    for e in fs.list(dir)? {
        let p = if dir == "/" { format!("/{}", e.name) } else { format!("{dir}/{}", e.name) };
        if p.starts_with("/System Volume Information") {
            continue;
        }
        if e.is_dir {
            out.push(format!("{{\"path\":{:?},\"dir\":true,\"size\":0,\"sha256\":\"\"}}", p));
            walk(fs, &p, out)?;
        } else {
            let mut b = vec![0u8; e.size as usize];
            let n = fs.read(&p, 0, &mut b)?;
            b.truncate(n);
            out.push(format!("{{\"path\":{:?},\"dir\":false,\"size\":{},\"sha256\":\"{}\"}}", p, n, sha_hex(&b)));
        }
    }
    Ok(())
}

fn run(args: &[String]) -> Result<(), String> {
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&args[1], true).map_err(e)?);
    let hidden_pw = args.get(4);
    let before = match hidden_pw {
        Some(pw) => Some(hidden_digest(&dev, pw)?),
        None => None,
    };
    let mut vol = open_volume(dev.clone(), &creds(&args[2]), &CancelToken::new()).map_err(e)?;
    if let Some(pw) = hidden_pw {
        vol.protect_hidden_volume(&creds(pw), &CancelToken::new()).map_err(e)?;
    }
    let vol = Arc::new(vol);
    {
        let fs = mount_rw(vol.clone()).map_err(e)?;
        println!("{} before: {} KiB free", fs.kind().name(), fs.stats().map_err(e)?.free_bytes.unwrap_or(0) / 1024);
        mutate(fs.as_ref()).map_err(e)?;
    }
    let fs = mount(vol).map_err(e)?;
    let mut entries = Vec::new();
    walk(fs.as_ref(), "/", &mut entries).map_err(e)?;
    std::fs::write(&args[3], format!("[\n{}\n]\n", entries.join(",\n"))).map_err(|x| x.to_string())?;
    println!("{} entries written to {}", entries.len(), args[3]);
    if let (Some(pw), Some(b)) = (hidden_pw, before) {
        let after = hidden_digest(&dev, pw)?;
        if after != b {
            return Err("HIDDEN VOLUME CHANGED".into());
        }
        println!("hidden volume intact ({})", &after[..16]);
    }
    Ok(())
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: mutate <container> <password> <manifest.json> [hidden-password]");
        return ExitCode::from(2);
    }
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(1)
        }
    }
}
