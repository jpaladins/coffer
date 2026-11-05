// SPDX-License-Identifier: GPL-3.0-or-later
//! Prints a container's decrypted-volume SHA-256 and its file tree with
//! per-file SHA-256, read-only. Used to check containers written on a device.
//!
//! Usage: inspect <container> <password>
use coffer_crypto::Prf;
use coffer_fs::{mount, Filesystem};
use coffer_volume::{open_volume, BlockDevice, CancelToken, Credentials, FileDevice};
use sha2::{Digest, Sha256};
use std::sync::Arc;

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn walk(fs: &dyn Filesystem, dir: &str) -> Result<(), coffer_vfs::CofferError> {
    for e in fs.list(dir)? {
        let p = if dir == "/" {
            format!("/{}", e.name)
        } else {
            format!("{dir}/{}", e.name)
        };
        if e.is_dir {
            println!("{p}/");
            walk(fs, &p)?;
        } else {
            let mut b = vec![0u8; e.size as usize];
            let n = fs.read(&p, 0, &mut b)?;
            println!("{p}  {n}  {}", hex(&Sha256::digest(&b[..n])));
        }
    }
    Ok(())
}

fn main() -> Result<(), String> {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 3 {
        return Err("usage: inspect <container> <password>".into());
    }
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&a[1], false).map_err(e)?);
    let mut c = Credentials::new(a[2].as_bytes());
    c.prf_hint = Some(Prf::Sha512);
    let v = open_volume(dev, &c, &CancelToken::new()).map_err(e)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    let mut off = 0;
    while off < v.len() {
        let n = buf.len().min((v.len() - off) as usize);
        v.read_at(off, &mut buf[..n]).map_err(e)?;
        h.update(&buf[..n]);
        off += n as u64;
    }
    println!("volume sha256 {}", hex(&h.finalize()));
    let fs = mount(Arc::new(v)).map_err(e)?;
    println!("fs {}", fs.kind().name());
    walk(fs.as_ref(), "/").map_err(e)
}
