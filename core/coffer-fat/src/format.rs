// SPDX-License-Identifier: GPL-3.0-or-later
//! FAT formatting through fatfs; the FAT type follows the volume size, as
//! when VeraCrypt formats a volume as "FAT".
use crate::fs::map_io;
use crate::io::{SectorCache, VolumeIo};
use coffer_vfs::CofferError;
use coffer_volume::BlockDevice;
use std::sync::{Arc, Mutex};

/// 8.3 volume label: upper-case ASCII, other characters become '_',
/// padded with spaces; empty becomes "NO NAME".
fn label_bytes(label: &str) -> [u8; 11] {
    let mut out = [b' '; 11];
    let src = if label.trim().is_empty() {
        "NO NAME"
    } else {
        label
    };
    for (o, c) in out.iter_mut().zip(src.chars()) {
        *o = match c.to_ascii_uppercase() {
            c @ ('A'..='Z' | '0'..='9' | ' ' | '-' | '_') => c as u8,
            _ => b'_',
        };
    }
    out
}

/// Formats `dev` (a decrypted volume) as FAT12/16/32.
pub fn format(dev: Arc<dyn BlockDevice>, label: &str, serial: u32) -> Result<(), CofferError> {
    let cache = Arc::new(Mutex::new(SectorCache::new(64)));
    let budget = dev.len().saturating_mul(4);
    let io = VolumeIo::new(dev.clone(), budget, cache, true);
    let options = fatfs::FormatVolumeOptions::new()
        .volume_label(label_bytes(label))
        .volume_id(serial);
    fatfs::format_volume(io, options).map_err(map_io)?;
    dev.flush()
}
