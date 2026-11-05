// SPDX-License-Identifier: GPL-3.0-or-later
//! Opens a container, mounts its filesystem and reads one file in 1 MiB
//! chunks, printing the throughput.
//! Usage: bench_read <container> <password> <path inside the volume>
use coffer_crypto::Prf;
use coffer_volume::{open_volume, CancelToken, Credentials, FileDevice};
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Instant;

fn run(args: &[String]) -> Result<String, String> {
    let mut creds = Credentials::new(args[2].as_bytes());
    creds.prf_hint = Some(Prf::Sha512);
    let dev = Arc::new(FileDevice::open(&args[1], false).map_err(|e| e.to_string())?);
    let vol = Arc::new(open_volume(dev, &creds, &CancelToken::new()).map_err(|e| e.to_string())?);
    let fs = coffer_fs::mount(vol).map_err(|e| e.to_string())?;
    let size = fs.stat(&args[3]).map_err(|e| e.to_string())?.size;
    let mut buf = vec![0u8; 1024 * 1024];
    let start = Instant::now();
    let mut off = 0u64;
    let mut sum = 0u64;
    while off < size {
        let n = fs
            .read(&args[3], off, &mut buf)
            .map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        sum = buf[..n]
            .iter()
            .fold(sum, |a, b| a.wrapping_add(u64::from(*b)));
        off += n as u64;
    }
    let secs = start.elapsed().as_secs_f64();
    Ok(format!(
        "{}: read {off} bytes in {:.0} ms = {:.1} MB/s (checksum {sum})",
        fs.kind().name(),
        secs * 1000.0,
        off as f64 / secs / 1e6
    ))
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: bench_read <container> <password> <path>");
        return ExitCode::from(2);
    }
    match run(&args) {
        Ok(s) => {
            println!("{s}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(1)
        }
    }
}
