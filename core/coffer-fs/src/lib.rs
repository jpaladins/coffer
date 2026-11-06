// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Mounts the filesystem found inside an opened volume.

pub use coffer_vfs::{CofferError, DirEntry, Filesystem, FsKind, FsStats};
use coffer_volume::BlockDevice;
use std::sync::Arc;

/// Filesystem to create in a new volume.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FsChoice {
    Fat,
    ExFat,
}

/// Formats a new (decrypted) volume. FAT needs at least `MIN_FAT` bytes of
/// container, exFAT `MIN_EXFAT` (checked by the caller against the
/// container size; this checks the volume itself can hold the filesystem).
pub fn format(
    volume: Arc<dyn BlockDevice>,
    fs: FsChoice,
    label: &str,
    serial: u32,
) -> Result<(), CofferError> {
    match fs {
        FsChoice::Fat => coffer_fat::format(volume, label, serial),
        FsChoice::ExFat => coffer_exfat::format(volume.as_ref(), label, serial),
    }
}

/// Detects the filesystem of `volume` (a decrypted volume) and mounts it
/// read-only.
pub fn mount(volume: Arc<dyn BlockDevice>) -> Result<Box<dyn Filesystem>, CofferError> {
    mount_with(volume, false)
}

/// Mounts for reading and writing (the underlying device must be writable).
pub fn mount_rw(volume: Arc<dyn BlockDevice>) -> Result<Box<dyn Filesystem>, CofferError> {
    mount_with(volume, true)
}

fn mount_with(
    volume: Arc<dyn BlockDevice>,
    writable: bool,
) -> Result<Box<dyn Filesystem>, CofferError> {
    let mut s0 = [0u8; 512];
    volume.read_at(0, &mut s0)?;
    match coffer_vfs::detect(&s0) {
        FsKind::Fat12 | FsKind::Fat16 | FsKind::Fat32 if writable => {
            Ok(Box::new(coffer_fat::FatFs::open_rw(volume)?))
        }
        FsKind::Fat12 | FsKind::Fat16 | FsKind::Fat32 => {
            Ok(Box::new(coffer_fat::FatFs::open(volume)?))
        }
        FsKind::ExFat if writable => Ok(Box::new(coffer_exfat::ExFatFs::open_rw(volume)?)),
        FsKind::ExFat => Ok(Box::new(coffer_exfat::ExFatFs::open(volume)?)),
        FsKind::Ntfs => Err(CofferError::Unsupported("NTFS".into())),
        FsKind::Unknown => Err(CofferError::Corrupted(
            "no FAT or exFAT filesystem found in the volume".into(),
        )),
    }
}
