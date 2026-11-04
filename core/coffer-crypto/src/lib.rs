// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Clean-room implementation of VeraCrypt volume header cryptography.
//! Sources: docs/security/format-notes.md and legal/CLEAN_ROOM.md.

mod cancel;
mod cipher;
mod error;
mod header;
mod kdf;
mod keyfile;
mod prf;
mod xts;

pub use cancel::CancelToken;
pub use cipher::{Algo, CipherSuite, XtsCascade};
pub use error::CofferError;
pub use header::{
    decrypt_header, encrypt_header, Credentials, VolumeHeader, HEADER_LEN, MASTER_KEY_LEN,
};
pub use kdf::{
    argon2_params, derive_header_key, derive_header_key_with_limit, iterations, pbkdf2_raw,
    Argon2Params, DEFAULT_MAX_KDF_MEMORY, HEADER_KEY_LEN, SALT_LEN,
};
pub use keyfile::{apply_keyfiles, KEYFILE_MAX_READ, KEYFILE_POOL_LEN, KEYFILE_POOL_LEN_LONG};
pub use prf::Prf;
pub use xts::{Xts, DATA_UNIT};
