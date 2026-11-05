// SPDX-License-Identifier: GPL-3.0-or-later
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! UniFFI surface of the Coffer core, consumed by the Android app.

use coffer_crypto::{CancelToken, CofferError, Credentials, Prf};
use coffer_fs::Filesystem;
use coffer_volume::{open_volume_with, BlockDevice, FileDevice, OpenOptions, VolumeKind};
use std::sync::{Arc, Mutex};
use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

/// Largest single `read` (bounds the JNI buffer a caller can request).
pub const MAX_READ: u32 = 4 * 1024 * 1024;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("wrong password, PIM or keyfiles")]
    WrongCredentials,
    #[error("cancelled")]
    Cancelled,
    #[error("key derivation needs {required_bytes} bytes of memory")]
    OutOfMemory { required_bytes: u64 },
    #[error("corrupted: {detail}")]
    Corrupted { detail: String },
    #[error("unsupported: {detail}")]
    Unsupported { detail: String },
    #[error("not found: {path}")]
    NotFound { path: String },
    #[error("not a directory: {path}")]
    NotADirectory { path: String },
    #[error("is a directory: {path}")]
    IsADirectory { path: String },
    #[error("invalid path: {path}")]
    InvalidPath { path: String },
    #[error("I/O error: {detail}")]
    Io { detail: String },
    #[error("vault is closed")]
    Closed,
}

impl From<CofferError> for FfiError {
    fn from(e: CofferError) -> Self {
        match e {
            CofferError::WrongCredentials => FfiError::WrongCredentials,
            CofferError::Cancelled => FfiError::Cancelled,
            CofferError::KdfOutOfMemory { required_bytes } => {
                FfiError::OutOfMemory { required_bytes }
            }
            CofferError::Corrupted(detail) => FfiError::Corrupted { detail },
            CofferError::Unsupported(detail) => FfiError::Unsupported { detail },
            CofferError::NotFound(path) => FfiError::NotFound { path },
            CofferError::NotADirectory(path) => FfiError::NotADirectory { path },
            CofferError::IsADirectory(path) => FfiError::IsADirectory { path },
            CofferError::InvalidPath(path) => FfiError::InvalidPath { path },
            CofferError::Io(detail) => FfiError::Io { detail },
        }
    }
}

#[derive(uniffi::Object, Default)]
pub struct FfiCancel {
    token: CancelToken,
}

#[uniffi::export]
impl FfiCancel {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn cancel(&self) {
        self.token.cancel();
    }
}

#[derive(uniffi::Record)]
pub struct FfiCredentials {
    pub password: Vec<u8>,
    pub pim: u32,
    pub keyfiles: Vec<Vec<u8>>,
    /// KDF name as in `Prf::name()` ("SHA-512", "Argon2id"…); None = detect.
    pub prf: Option<String>,
    pub max_kdf_memory: u64,
    pub try_backup_headers: bool,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct FfiEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub modified: Option<i64>,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiStats {
    pub total_bytes: u64,
    pub free_bytes: Option<u64>,
    pub fs_kind: String,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiVolumeInfo {
    pub cipher: String,
    pub prf: String,
    pub hidden: bool,
    pub used_backup_header: bool,
    pub fs_kind: String,
    pub size: u64,
}

impl From<coffer_vfs::DirEntry> for FfiEntry {
    fn from(e: coffer_vfs::DirEntry) -> Self {
        Self {
            name: e.name,
            is_dir: e.is_dir,
            size: e.size,
            modified: e.modified,
        }
    }
}

/// An unlocked volume with its mounted filesystem.
#[derive(uniffi::Object)]
pub struct FfiVault {
    info: FfiVolumeInfo,
    fs: Mutex<Option<Arc<dyn Filesystem>>>,
}

impl FfiVault {
    fn fs(&self) -> Result<Arc<dyn Filesystem>, FfiError> {
        self.fs
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone()
            .ok_or(FfiError::Closed)
    }
}

#[uniffi::export]
impl FfiVault {
    pub fn info(&self) -> FfiVolumeInfo {
        self.info.clone()
    }

    pub fn list(&self, path: String) -> Result<Vec<FfiEntry>, FfiError> {
        Ok(self
            .fs()?
            .list(&path)?
            .into_iter()
            .map(FfiEntry::from)
            .collect())
    }

    pub fn stat(&self, path: String) -> Result<FfiEntry, FfiError> {
        Ok(self.fs()?.stat(&path)?.into())
    }

    /// Reads up to `len` bytes at `offset`; an empty result means end of file.
    pub fn read(&self, path: String, offset: u64, len: u32) -> Result<Vec<u8>, FfiError> {
        if len > MAX_READ {
            return Err(FfiError::InvalidPath {
                path: format!("{path}: read of {len} bytes exceeds {MAX_READ}"),
            });
        }
        let mut buf = vec![0u8; len as usize];
        let n = self.fs()?.read(&path, offset, &mut buf)?;
        buf.truncate(n);
        Ok(buf)
    }

    pub fn stats(&self) -> Result<FfiStats, FfiError> {
        let fs = self.fs()?;
        let s = fs.stats()?;
        Ok(FfiStats {
            total_bytes: s.total_bytes,
            free_bytes: s.free_bytes,
            fs_kind: fs.kind().name().to_string(),
        })
    }

    /// Drops the filesystem and the volume (keys are zeroized on drop).
    /// Idempotent.
    pub fn close(&self) {
        self.fs.lock().unwrap_or_else(|p| p.into_inner()).take();
    }
}

fn to_credentials(c: FfiCredentials) -> Result<(Credentials, OpenOptions), FfiError> {
    let password = Zeroizing::new(c.password);
    let mut creds = Credentials::new(&password);
    creds.pim = c.pim;
    creds.keyfiles = c.keyfiles.into_iter().map(Zeroizing::new).collect();
    creds.max_kdf_memory = c.max_kdf_memory;
    creds.prf_hint = match c.prf {
        None => None,
        Some(name) => Some(Prf::from_name(&name).ok_or(FfiError::Unsupported {
            detail: format!("unknown KDF {name}"),
        })?),
    };
    let opts = OpenOptions {
        try_backup_headers: c.try_backup_headers,
    };
    Ok((creds, opts))
}

/// Opens a container from any block device and mounts its filesystem.
pub fn open_device(
    dev: Arc<dyn BlockDevice>,
    creds: FfiCredentials,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let (creds, opts) = to_credentials(creds)?;
    if cancel.token.is_cancelled() {
        return Err(FfiError::Cancelled);
    }
    let vol = Arc::new(open_volume_with(dev, &creds, &cancel.token, opts)?);
    let info_base = (
        vol.header.suite.name().to_string(),
        vol.header.prf.name().to_string(),
        vol.kind == VolumeKind::Hidden,
        vol.used_backup_header,
        vol.len(),
    );
    let fs: Arc<dyn Filesystem> = Arc::from(coffer_fs::mount(vol)?);
    Ok(Arc::new(FfiVault {
        info: FfiVolumeInfo {
            cipher: info_base.0,
            prf: info_base.1,
            hidden: info_base.2,
            used_backup_header: info_base.3,
            fs_kind: fs.kind().name().to_string(),
            size: info_base.4,
        },
        fs: Mutex::new(Some(fs)),
    }))
}

/// Opens a container from a file descriptor obtained from Android's
/// ContentResolver (`ParcelFileDescriptor.detachFd()`). Takes ownership of
/// `fd`: it is closed when the vault is closed or dropped.
#[uniffi::export]
pub fn open_fd(
    fd: i32,
    writable: bool,
    creds: FfiCredentials,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let file = file_from_fd(fd)?;
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::from_file(file, writable)?);
    open_device(dev, creds, cancel)
}

/// Opens a container by path (tests and tools; the app uses `open_fd`).
#[uniffi::export]
pub fn open_path(
    path: String,
    creds: FfiCredentials,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(path, false)?);
    open_device(dev, creds, cancel)
}

#[cfg(unix)]
fn file_from_fd(fd: i32) -> Result<std::fs::File, FfiError> {
    use std::os::unix::io::FromRawFd;
    if fd < 0 {
        return Err(FfiError::Io {
            detail: "invalid file descriptor".into(),
        });
    }
    // SAFETY: the caller transfers ownership of an open descriptor
    // (detachFd), so no one else will close it.
    Ok(unsafe { std::fs::File::from_raw_fd(fd) })
}

#[cfg(not(unix))]
fn file_from_fd(_fd: i32) -> Result<std::fs::File, FfiError> {
    Err(FfiError::Unsupported {
        detail: "file descriptors are only supported on Android/Unix".into(),
    })
}
