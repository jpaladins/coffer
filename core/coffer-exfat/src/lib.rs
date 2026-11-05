// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Read-only exFAT, written from the Microsoft exFAT specification.

mod boot;
mod dir;
mod fat;
mod fs;
mod time;

pub use boot::{parse_boot, BootInfo};
pub use dir::{parse_dir, ExEntry};
pub use fat::{chain_for_len, chain_until_end};
pub use fs::ExFatFs;
