// SPDX-License-Identifier: GPL-3.0-or-later
//! Creates an exFAT container holding a copy of a host folder (store
//! screenshots, demos).
//!
//! Usage: demo <out.hc> <size-bytes> <password> <source-folder>
use coffer_crypto::{CipherSuite, Prf};
use coffer_fs::{format, mount_rw, Filesystem, FsChoice};
use coffer_volume::{create_volume, BlockDevice, CancelToken, CreateParams, Credentials, FileDevice};
use std::path::Path;
use std::sync::Arc;

fn os_rng(buf: &mut [u8]) -> Result<(), coffer_vfs::CofferError> {
    getrandom::fill(buf).map_err(|e| coffer_vfs::CofferError::Io(e.to_string()))
}

fn copy_dir(fs: &dyn Filesystem, src: &Path, dst: &str) -> Result<(), String> {
    let mut entries: Vec<_> = std::fs::read_dir(src).map_err(|e| e.to_string())?.flatten().collect();
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        let name = e.file_name().to_string_lossy().to_string();
        let path = if dst == "/" { format!("/{name}") } else { format!("{dst}/{name}") };
        if e.path().is_dir() {
            fs.mkdir(&path).map_err(|x| x.to_string())?;
            copy_dir(fs, &e.path(), &path)?;
        } else {
            let data = std::fs::read(e.path()).map_err(|x| x.to_string())?;
            fs.create_file(&path).map_err(|x| x.to_string())?;
            for (i, chunk) in data.chunks(1 << 20).enumerate() {
                fs.write(&path, (i << 20) as u64, chunk).map_err(|x| x.to_string())?;
            }
        }
    }
    Ok(())
}

fn main() -> Result<(), String> {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 5 {
        return Err("usage: demo <out.hc> <size-bytes> <password> <source-folder>".into());
    }
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let size: u64 = a[2].parse().map_err(|_| "bad size")?;
    std::fs::File::create(&a[1]).and_then(|f| f.set_len(size)).map_err(|x| x.to_string())?;
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&a[1], true).map_err(e)?);
    let params = CreateParams { size, suite: CipherSuite::Aes, prf: Prf::Sha512, quick: false };
    let vol = Arc::new(
        create_volume(dev, &Credentials::new(a[3].as_bytes()), &params, &mut os_rng, &|_, _| {}, &CancelToken::new())
            .map_err(e)?,
    );
    format(vol.clone(), FsChoice::ExFat, "Coffer", 0x2026_1106).map_err(e)?;
    let fs = mount_rw(vol).map_err(e)?;
    copy_dir(fs.as_ref(), Path::new(&a[4]), "/")?;
    fs.sync().map_err(e)?;
    println!("demo container written to {}", a[1]);
    Ok(())
}
