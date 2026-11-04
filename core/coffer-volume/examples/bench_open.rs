// SPDX-License-Identifier: GPL-3.0-or-later
//! Opens a container and prints the time taken.
//! Usage: bench_open <container> <password> [<prf name, e.g. SHA-512>]
use coffer_crypto::Prf;
use coffer_volume::{open_volume, CancelToken, Credentials, FileDevice};
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Instant;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: bench_open <container> <password> [prf]");
        return ExitCode::from(2);
    }
    let mut creds = Credentials::new(args[2].as_bytes());
    if let Some(name) = args.get(3) {
        match Prf::from_name(name) {
            Some(p) => creds.prf_hint = Some(p),
            None => {
                eprintln!("unknown prf {name}");
                return ExitCode::from(2);
            }
        }
    }
    let dev = match FileDevice::open(&args[1], false) {
        Ok(d) => Arc::new(d),
        Err(e) => {
            eprintln!("cannot open file: {e}");
            return ExitCode::from(1);
        }
    };
    let cpus = std::thread::available_parallelism().map_or(1, |n| n.get());
    let start = Instant::now();
    match open_volume(dev, &creds, &CancelToken::new()) {
        Ok(v) => {
            println!(
                "opened in {} ms: {} / {} / {:?}, {} bytes, cpus={cpus}",
                start.elapsed().as_millis(),
                v.header.suite.name(),
                v.header.prf.name(),
                v.kind,
                v.header.volume_size
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            println!("failed after {} ms: {e}", start.elapsed().as_millis());
            ExitCode::from(1)
        }
    }
}
