// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! VeraCrypt-compatible container layout and decrypted block device.

mod decrypted;
mod device;
mod layout;
mod open;

pub use coffer_crypto::{CancelToken, CofferError, Credentials};
pub use decrypted::{OpenedVolume, VolumeKind};
pub use device::{BlockDevice, FileDevice, MemDevice};
pub use layout::*;
pub use open::{open_volume, open_volume_with, OpenOptions};
