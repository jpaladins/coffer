// SPDX-License-Identifier: GPL-3.0-or-later
use crate::decrypted::{OpenedVolume, VolumeKind};
use crate::device::BlockDevice;
use crate::layout::*;
use coffer_crypto::{
    decrypt_header, CancelToken, CofferError, Credentials, VolumeHeader, HEADER_LEN,
};
use std::sync::Arc;

#[derive(Clone, Copy, Debug)]
pub struct OpenOptions {
    /// Also try the embedded backup headers when the primary ones do not
    /// match (doubles the time needed to reject a wrong password).
    pub try_backup_headers: bool,
}

impl Default for OpenOptions {
    fn default() -> Self {
        Self {
            try_backup_headers: true,
        }
    }
}

pub fn open_volume(
    dev: Arc<dyn BlockDevice>,
    creds: &Credentials,
    cancel: &CancelToken,
) -> Result<OpenedVolume, CofferError> {
    open_volume_with(dev, creds, cancel, OpenOptions::default())
}

/// Tries, in order: standard header, hidden header, then (optionally) the
/// backup standard and backup hidden headers.
pub fn open_volume_with(
    dev: Arc<dyn BlockDevice>,
    creds: &Credentials,
    cancel: &CancelToken,
    opts: OpenOptions,
) -> Result<OpenedVolume, CofferError> {
    let len = dev.len();
    if len < MIN_CONTAINER {
        return Err(CofferError::Corrupted(format!(
            "container is {len} bytes, minimum is {MIN_CONTAINER}"
        )));
    }
    let mut candidates = vec![
        (STD_HEADER, VolumeKind::Standard, false),
        (HIDDEN_HEADER, VolumeKind::Hidden, false),
    ];
    if opts.try_backup_headers {
        candidates.push((backup_std_header(len), VolumeKind::Standard, true));
        candidates.push((backup_hidden_header(len), VolumeKind::Hidden, true));
    }
    for (off, kind, backup) in candidates {
        let mut raw = [0u8; HEADER_LEN];
        dev.read_at(off, &mut raw)?;
        match decrypt_header(&raw, creds, cancel) {
            Ok(h) => {
                validate(&h, len)?;
                return OpenedVolume::new(kind, backup, h, dev);
            }
            Err(CofferError::WrongCredentials) => continue,
            Err(e) => return Err(e),
        }
    }
    Err(CofferError::WrongCredentials)
}

fn validate(h: &VolumeHeader, len: u64) -> Result<(), CofferError> {
    if h.sector_size != 512 {
        return Err(CofferError::Unsupported(format!(
            "sector size {} (only 512 is supported)",
            h.sector_size
        )));
    }
    let end = h
        .encrypted_area_start
        .checked_add(h.volume_size)
        .ok_or_else(|| CofferError::Corrupted("volume geometry overflows".into()))?;
    if h.volume_size == 0
        || !h.encrypted_area_start.is_multiple_of(SECTOR)
        || !h.volume_size.is_multiple_of(SECTOR)
        || h.encrypted_area_start < HEADER_AREA
        || end > len
    {
        return Err(CofferError::Corrupted(format!(
            "header geometry (start {}, size {}) does not fit a {len}-byte container",
            h.encrypted_area_start, h.volume_size
        )));
    }
    Ok(())
}
