// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Mounts the filesystem found inside an opened volume.

pub use coffer_vfs::{CofferError, DirEntry, Filesystem, FsKind, FsStats};
use coffer_volume::BlockDevice;
use std::sync::Arc;

/// Detects the filesystem of `volume` (a decrypted volume) and mounts it
/// read-only.
pub fn mount(volume: Arc<dyn BlockDevice>) -> Result<Box<dyn Filesystem>, CofferError> {
    let mut s0 = [0u8; 512];
    volume.read_at(0, &mut s0)?;
    match coffer_vfs::detect(&s0) {
        FsKind::Fat12 | FsKind::Fat16 | FsKind::Fat32 => {
            Ok(Box::new(coffer_fat::FatFs::open(volume)?))
        }
        FsKind::ExFat => Ok(Box::new(coffer_exfat::ExFatFs::open(volume)?)),
        FsKind::Ntfs => Err(CofferError::Unsupported("NTFS".into())),
        FsKind::Unknown => Err(CofferError::Corrupted(
            "no FAT or exFAT filesystem found in the volume".into(),
        )),
    }
}
