// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Common filesystem interface for the volumes Coffer opens.

mod detect;
mod path;
mod types;

pub use coffer_crypto::CofferError;
pub use detect::detect;
pub use path::{names_equal, split_parent, split_path, validate_name};
pub use types::{DirEntry, Filesystem, FsKind, FsStats};
