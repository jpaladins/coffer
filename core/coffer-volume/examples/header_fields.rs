// SPDX-License-Identifier: GPL-3.0-or-later
//! Oracle aid: prints the decrypted header fields of a VeraCrypt container
//! (primary and backup) so Coffer copies the values VeraCrypt writes.
//! Usage: header_fields <container> <password>
use coffer_volume::{
    open_volume_with, BlockDevice, CancelToken, Credentials, FileDevice, OpenOptions,
};
use std::sync::Arc;

fn main() -> Result<(), String> {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 3 {
        return Err("usage: header_fields <container> <password>".into());
    }
    let dev: Arc<dyn BlockDevice> =
        Arc::new(FileDevice::open(&a[1], false).map_err(|e| e.to_string())?);
    let v = open_volume_with(
        dev,
        &Credentials::new(a[2].as_bytes()),
        &CancelToken::new(),
        OpenOptions::default(),
    )
    .map_err(|e| e.to_string())?;
    println!("{:?}", v.header);
    Ok(())
}
