// SPDX-License-Identifier: GPL-3.0-or-later
//! Creation of a standard volume (format-notes.md, "Creazione").
use crate::decrypted::{OpenedVolume, VolumeKind};
use crate::device::BlockDevice;
use crate::layout::*;
use coffer_crypto::{
    encrypt_header, CancelToken, CipherSuite, CofferError, Credentials, Prf, VolumeHeader,
    MASTER_KEY_LEN,
};
use std::sync::Arc;
use zeroize::Zeroizing;

/// Smallest container Coffer creates with a FAT filesystem.
pub const MIN_FAT: u64 = 1 << 20;
/// Smallest container Coffer creates with an exFAT filesystem.
pub const MIN_EXFAT: u64 = 4 << 20;

/// Header fields VeraCrypt 1.26.29 writes for a standard volume [oracle].
const HEADER_VERSION: u16 = 5;
const MIN_PROGRAM_VERSION: u16 = 0x010B;
const SALT_LEN: usize = 64;
/// Plaintext zeros encrypted per round of a full format.
const FORMAT_CHUNK: u64 = 1 << 20;

pub struct CreateParams {
    /// Container size in bytes; the device must already have this length.
    pub size: u64,
    pub suite: CipherSuite,
    pub prf: Prf,
    /// Skip encrypting the data area (unused space keeps its old bytes).
    pub quick: bool,
}

/// Source of random bytes (OS RNG in production).
pub type Rng<'a> = dyn FnMut(&mut [u8]) -> Result<(), CofferError> + 'a;

/// Writes a new standard volume onto `dev` and returns it opened.
///
/// Layout: header at 0 and its backup at `size - 131072`, encrypted with
/// the same master key and different salts; the hidden-header areas and
/// the rest of every 64 KiB header area hold random bytes, so the
/// container looks the same whether or not it hides a volume. A full
/// format encrypts zeros over the whole data area (`progress(done,
/// total)` after each MiB, `cancel` checked between rounds).
pub fn create_volume(
    dev: Arc<dyn BlockDevice>,
    creds: &Credentials,
    params: &CreateParams,
    rng: &mut Rng<'_>,
    progress: &dyn Fn(u64, u64),
    cancel: &CancelToken,
) -> Result<OpenedVolume, CofferError> {
    let size = params.size;
    if size < MIN_FAT {
        return Err(CofferError::InvalidSize(format!(
            "{size} bytes; the minimum is {MIN_FAT}"
        )));
    }
    if !size.is_multiple_of(SECTOR) {
        return Err(CofferError::InvalidSize(format!(
            "{size} bytes is not a multiple of {SECTOR}"
        )));
    }
    if dev.len() != size {
        return Err(CofferError::InvalidSize(format!(
            "device is {} bytes, expected {size}",
            dev.len()
        )));
    }
    let mut master_key = Zeroizing::new([0u8; MASTER_KEY_LEN]);
    rng(master_key.as_mut())?;
    let data_size = size - 2 * DATA_START;
    let header = VolumeHeader {
        version: HEADER_VERSION,
        min_program_version: MIN_PROGRAM_VERSION,
        hidden_volume_size: 0,
        volume_size: data_size,
        encrypted_area_start: DATA_START,
        encrypted_area_size: data_size,
        flags: 0,
        sector_size: SECTOR as u32,
        master_key,
        suite: params.suite,
        prf: params.prf,
    };
    for (area, has_header) in [
        (STD_HEADER, true),
        (HIDDEN_HEADER, false),
        (backup_std_header(size), true),
        (backup_hidden_header(size), false),
    ] {
        if cancel.is_cancelled() {
            return Err(CofferError::Cancelled);
        }
        let mut block = vec![0u8; HEADER_AREA as usize];
        rng(&mut block)?;
        if has_header {
            let mut salt = [0u8; SALT_LEN];
            rng(&mut salt)?;
            let raw = encrypt_header(&header, creds, &salt)?;
            block[..raw.len()].copy_from_slice(&raw);
        }
        dev.write_at(area, &block)?;
    }
    let vol = OpenedVolume::new(VolumeKind::Standard, false, header, dev)?;
    if !params.quick {
        let zeros = vec![0u8; FORMAT_CHUNK as usize];
        let mut done = 0;
        progress(0, data_size);
        while done < data_size {
            if cancel.is_cancelled() {
                return Err(CofferError::Cancelled);
            }
            let n = FORMAT_CHUNK.min(data_size - done);
            vol.write_at(done, &zeros[..n as usize])?;
            done += n;
            progress(done, data_size);
        }
    }
    vol.flush()?;
    Ok(vol)
}
