// SPDX-License-Identifier: GPL-3.0-or-later
use thiserror::Error;

/// Errors surfaced by the Coffer core. Messages are technical; the app maps
/// each variant to user-facing copy.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum CofferError {
    #[error("wrong password, PIM or keyfiles")]
    WrongCredentials,
    #[error("volume is corrupted: {0}")]
    Corrupted(String),
    #[error("operation cancelled")]
    Cancelled,
    #[error("key derivation needs {required_bytes} bytes of memory")]
    KdfOutOfMemory { required_bytes: u64 },
    #[error("I/O error: {0}")]
    Io(String),
    #[error("unsupported: {0}")]
    Unsupported(String),
}

impl From<std::io::Error> for CofferError {
    fn from(e: std::io::Error) -> Self {
        CofferError::Io(e.to_string())
    }
}
