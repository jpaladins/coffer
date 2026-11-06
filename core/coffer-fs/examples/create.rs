// SPDX-License-Identifier: GPL-3.0-or-later
//! Creates a new container the way the app does (create_volume + format),
//! applies the deterministic changes of `mutate` and writes the manifest
//! Windows checks (corpus/verify-created.ps1).
//!
//! Usage: create <out.hc> <size-bytes> <cipher> <prf> <pim> <fat|exfat> <password> <manifest.json> [quick] [big]
//! `big` also writes a 4.1 GiB file (exFAT only).
use coffer_crypto::{CipherSuite, Prf};
use coffer_fs::{format, mount, mount_rw, FsChoice};
use coffer_volume::{create_volume, BlockDevice, CancelToken, CreateParams, Credentials, FileDevice};
use std::process::ExitCode;
use std::sync::Arc;

#[path = "common/mod.rs"]
mod common;
use common::{mutate, pattern, walk};

fn os_rng(buf: &mut [u8]) -> Result<(), coffer_vfs::CofferError> {
    getrandom::fill(buf).map_err(|e| coffer_vfs::CofferError::Io(e.to_string()))
}

fn run(a: &[String]) -> Result<(), String> {
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let size: u64 = a[2].parse().map_err(|_| "bad size")?;
    let suite = CipherSuite::from_name(&a[3]).ok_or("unknown cipher")?;
    let prf = Prf::from_name(&a[4]).ok_or("unknown prf")?;
    let pim: u32 = a[5].parse().map_err(|_| "bad pim")?;
    let fs_choice = match a[6].as_str() {
        "fat" => FsChoice::Fat,
        "exfat" => FsChoice::ExFat,
        _ => return Err("fs must be fat or exfat".into()),
    };
    let quick = a.iter().any(|x| x == "quick");
    let big = a.iter().any(|x| x == "big");
    std::fs::File::create(&a[1])
        .and_then(|f| f.set_len(size))
        .map_err(|x| x.to_string())?;
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&a[1], true).map_err(e)?);
    let mut creds = Credentials::new(a[7].as_bytes());
    creds.pim = pim;
    let params = CreateParams { size, suite, prf, quick };
    let mut serial = [0u8; 4];
    os_rng(&mut serial).map_err(e)?;
    let vol = Arc::new(
        create_volume(dev, &creds, &params, &mut os_rng, &|_, _| {}, &CancelToken::new()).map_err(e)?,
    );
    format(vol.clone(), fs_choice, "Coffer", u32::from_le_bytes(serial)).map_err(e)?;
    {
        let fs = mount_rw(vol.clone()).map_err(e)?;
        println!("{} created, {} KiB free", fs.kind().name(), fs.stats().map_err(e)?.free_bytes.unwrap_or(0) / 1024);
        mutate(fs.as_ref()).map_err(e)?;
        if big {
            let total: u64 = (4 << 30) + (100 << 20);
            fs.create_file("/huge.bin").map_err(e)?;
            let chunk = pattern(4 << 20, 9);
            let mut off = 0;
            while off < total {
                let n = (total - off).min(chunk.len() as u64) as usize;
                fs.write("/huge.bin", off, &chunk[..n]).map_err(e)?;
                off += n as u64;
            }
            fs.sync().map_err(e)?;
        }
    }
    let fs = mount(vol).map_err(e)?;
    let mut entries = Vec::new();
    walk(fs.as_ref(), "/", &mut entries).map_err(e)?;
    std::fs::write(&a[8], format!("[\n{}\n]\n", entries.join(",\n"))).map_err(|x| x.to_string())?;
    println!("{} entries written to {}", entries.len(), a[8]);
    Ok(())
}

fn main() -> ExitCode {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 9 {
        eprintln!("usage: create <out.hc> <size> <cipher> <prf> <pim> <fat|exfat> <password> <manifest.json> [quick] [big]");
        return ExitCode::from(2);
    }
    match run(&a) {
        Ok(()) => ExitCode::SUCCESS,
        Err(x) => {
            eprintln!("error: {x}");
            ExitCode::from(1)
        }
    }
}
