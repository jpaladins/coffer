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

/// Non-secret unlock options. Password and keyfiles never travel in a
/// UniFFI record: lifting copies them into buffers that are freed unwiped.
#[derive(uniffi::Record)]
pub struct FfiOpenOptions {
    pub pim: u32,
    /// KDF name as in `Prf::name()` ("SHA-512", "Argon2id"…); None = detect.
    pub prf: Option<String>,
    pub max_kdf_memory: u64,
    pub try_backup_headers: bool,
}

/// Password and keyfiles copied out of caller-owned memory.
pub struct Secrets {
    pub password: Zeroizing<Vec<u8>>,
    pub keyfiles: Vec<Zeroizing<Vec<u8>>>,
}

/// Largest secrets blob accepted (password + keyfile prefixes).
const MAX_SECRETS: u64 = 64 * 1024 * 1024;

struct Cursor<'a> {
    src: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], FfiError> {
        let end = self.pos.checked_add(n).ok_or_else(malformed)?;
        let out = self.src.get(self.pos..end).ok_or_else(malformed)?;
        self.pos = end;
        Ok(out)
    }

    fn u32(&mut self) -> Result<usize, FfiError> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as usize)
    }
}

fn malformed() -> FfiError {
    FfiError::InvalidPath {
        path: "malformed secrets".into(),
    }
}

fn parse_secrets(src: &[u8]) -> Result<Secrets, FfiError> {
    let mut c = Cursor { src, pos: 0 };
    let pw_len = c.u32()?;
    let password = Zeroizing::new(c.take(pw_len)?.to_vec());
    let n = c.u32()?;
    let mut keyfiles = Vec::new();
    for _ in 0..n {
        let len = c.u32()?;
        keyfiles.push(Zeroizing::new(c.take(len)?.to_vec()));
    }
    Ok(Secrets { password, keyfiles })
}

/// Parses `[u32 len][password][u32 count]([u32 len][keyfile])*` (little
/// endian) and wipes `src`, whether or not parsing succeeds.
pub fn take_secrets(src: &mut [u8]) -> Result<Secrets, FfiError> {
    use zeroize::Zeroize;
    let r = parse_secrets(src);
    src.zeroize();
    r
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

    /// Locks the vault: drops the filesystem and the volume (keys are zeroized
    /// on drop). Named `lock` because Kotlin reserves `close` (AutoCloseable).
    /// Idempotent.
    pub fn lock(&self) {
        self.fs.lock().unwrap_or_else(|p| p.into_inner()).take();
    }
}

fn to_credentials(
    secrets: Secrets,
    o: FfiOpenOptions,
) -> Result<(Credentials, OpenOptions), FfiError> {
    let mut creds = Credentials::new(&secrets.password);
    creds.pim = o.pim;
    creds.keyfiles = secrets.keyfiles;
    creds.max_kdf_memory = o.max_kdf_memory;
    creds.prf_hint = match o.prf {
        None => None,
        Some(name) => Some(Prf::from_name(&name).ok_or(FfiError::Unsupported {
            detail: format!("unknown KDF {name}"),
        })?),
    };
    let opts = OpenOptions {
        try_backup_headers: o.try_backup_headers,
    };
    Ok((creds, opts))
}

/// Opens a container from any block device and mounts its filesystem.
/// Opens a container from any block device; `secret` (see [`take_secrets`])
/// is wiped.
pub fn open_device_secret(
    dev: Arc<dyn BlockDevice>,
    secret: &mut [u8],
    options: FfiOpenOptions,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let secrets = take_secrets(secret)?;
    let (creds, opts) = to_credentials(secrets, options)?;
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
/// `fd`: it is closed when the vault is dropped.
///
/// `secret_ptr`/`secret_len` point to caller-owned native memory (JNA
/// `Memory`) holding the secrets blob described in [`take_secrets`]; it is
/// read in place and wiped, so no copy of the password is left behind by
/// UniFFI's buffer handling. The caller must keep it alive for the call.
#[uniffi::export]
pub fn open_fd_secret(
    fd: i32,
    writable: bool,
    secret_ptr: u64,
    secret_len: u64,
    options: FfiOpenOptions,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let file = file_from_fd(fd)?;
    if secret_ptr == 0 || secret_len > MAX_SECRETS {
        return Err(FfiError::InvalidPath {
            path: "invalid secrets buffer".into(),
        });
    }
    // SAFETY: the caller guarantees `secret_len` writable bytes at
    // `secret_ptr` for the duration of this call (Kotlin JNA Memory).
    let secret =
        unsafe { std::slice::from_raw_parts_mut(secret_ptr as *mut u8, secret_len as usize) };
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::from_file(file, writable)?);
    open_device_secret(dev, secret, options, cancel)
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
