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

#[path = "common.rs"]
mod common;
use common::{mutate, walk};

fn creds(pw: &str) -> Credentials {
    let mut c = Credentials::new(pw.as_bytes());
    c.prf_hint = Some(Prf::Sha512);
    c
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
        vol.protect_hidden_volume(&creds(pw), &CancelToken::new())
            .map_err(e)?;
    }
    let vol = Arc::new(vol);
    {
        let fs = mount_rw(vol.clone()).map_err(e)?;
        println!(
            "{} before: {} KiB free",
            fs.kind().name(),
            fs.stats().map_err(e)?.free_bytes.unwrap_or(0) / 1024
        );
        mutate(fs.as_ref()).map_err(e)?;
    }
    let fs = mount(vol).map_err(e)?;
    let mut entries = Vec::new();
    walk(fs.as_ref(), "/", &mut entries).map_err(e)?;
    std::fs::write(&args[3], format!("[\n{}\n]\n", entries.join(",\n")))
        .map_err(|x| x.to_string())?;
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
