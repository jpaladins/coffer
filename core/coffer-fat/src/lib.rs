// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Read-only FAT12/16/32 for Coffer volumes, built on the fatfs crate.

mod clock;
mod fs;
mod io;

pub use clock::set_local_offset_minutes;
pub use fs::FatFs;
