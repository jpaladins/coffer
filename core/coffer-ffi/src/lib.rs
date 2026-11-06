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
    #[error("write blocked to protect the hidden volume")]
    HiddenVolumeProtected,
    #[error("not enough free space")]
    NoSpace,
    #[error("already exists: {path}")]
    AlreadyExists { path: String },
    #[error("directory not empty: {path}")]
    NotEmpty { path: String },
    #[error("read-only")]
    ReadOnly,
    #[error("invalid container size: {detail}")]
    InvalidSize { detail: String },
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
            CofferError::HiddenVolumeProtected => FfiError::HiddenVolumeProtected,
            CofferError::NoSpace => FfiError::NoSpace,
            CofferError::AlreadyExists(path) => FfiError::AlreadyExists { path },
            CofferError::NotEmpty(path) => FfiError::NotEmpty { path },
            CofferError::ReadOnly => FfiError::ReadOnly,
            CofferError::InvalidSize(detail) => FfiError::InvalidSize { detail },
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
    pub writable: bool,
    pub hidden_protected: bool,
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

    pub fn create_file(&self, path: String) -> Result<(), FfiError> {
        Ok(self.fs()?.create_file(&path)?)
    }

    pub fn mkdir(&self, path: String) -> Result<(), FfiError> {
        Ok(self.fs()?.mkdir(&path)?)
    }

    /// Writes at `offset`, growing the file as needed.
    pub fn write(&self, path: String, offset: u64, data: Vec<u8>) -> Result<(), FfiError> {
        if data.len() > MAX_READ as usize {
            return Err(FfiError::InvalidPath {
                path: format!("{path}: write of {} bytes exceeds {MAX_READ}", data.len()),
            });
        }
        Ok(self.fs()?.write(&path, offset, &data)?)
    }

    pub fn set_len(&self, path: String, len: u64) -> Result<(), FfiError> {
        Ok(self.fs()?.set_len(&path, len)?)
    }

    pub fn remove(&self, path: String) -> Result<(), FfiError> {
        Ok(self.fs()?.remove(&path)?)
    }

    pub fn rename(&self, from: String, to: String) -> Result<(), FfiError> {
        Ok(self.fs()?.rename(&from, &to)?)
    }

    /// Flushes and marks the volume clean (exFAT VolumeDirty).
    pub fn sync(&self) -> Result<(), FfiError> {
        Ok(self.fs()?.sync()?)
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
        if let Some(fs) = self.fs.lock().unwrap_or_else(|p| p.into_inner()).take() {
            // Best effort: a clean unmount clears VolumeDirty.
            let _ = fs.sync();
        }
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
/// is wiped. With `writable`, the filesystem is mounted read-write; with
/// `hidden_secret`, the hidden volume inside the outer one is protected
/// from writes (it is wiped too).
pub fn open_device_secret(
    dev: Arc<dyn BlockDevice>,
    secret: &mut [u8],
    options: FfiOpenOptions,
    writable: bool,
    hidden_secret: Option<&mut [u8]>,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let hidden = match hidden_secret {
        Some(h) => Some(take_secrets(h)?),
        None => None,
    };
    let secrets = take_secrets(secret)?;
    let max_kdf_memory = options.max_kdf_memory;
    let (creds, opts) = to_credentials(secrets, options)?;
    if cancel.token.is_cancelled() {
        return Err(FfiError::Cancelled);
    }
    let mut vol = open_volume_with(dev, &creds, &cancel.token, opts)?;
    if let Some(h) = hidden {
        let (mut hcreds, _) = to_credentials(
            h,
            FfiOpenOptions {
                pim: 0,
                prf: None,
                max_kdf_memory,
                try_backup_headers: true,
            },
        )?;
        // The hidden volume usually shares the outer KDF: try that first,
        // then every KDF (it may differ from the outer one).
        hcreds.prf_hint = Some(vol.header.prf);
        match vol.protect_hidden_volume(&hcreds, &cancel.token) {
            Err(CofferError::WrongCredentials) => {
                hcreds.prf_hint = None;
                vol.protect_hidden_volume(&hcreds, &cancel.token)?;
            }
            r => r?,
        }
    }
    let hidden_protected = vol.is_hidden_protected();
    vault_from(Arc::new(vol), writable, hidden_protected)
}

fn vault_from(
    vol: Arc<coffer_volume::OpenedVolume>,
    writable: bool,
    hidden_protected: bool,
) -> Result<Arc<FfiVault>, FfiError> {
    let info_base = (
        vol.header.suite.name().to_string(),
        vol.header.prf.name().to_string(),
        vol.kind == VolumeKind::Hidden,
        vol.used_backup_header,
        vol.len(),
    );
    let fs: Arc<dyn Filesystem> = Arc::from(if writable {
        coffer_fs::mount_rw(vol)?
    } else {
        coffer_fs::mount(vol)?
    });
    Ok(Arc::new(FfiVault {
        info: FfiVolumeInfo {
            cipher: info_base.0,
            prf: info_base.1,
            hidden: info_base.2,
            used_backup_header: info_base.3,
            fs_kind: fs.kind().name().to_string(),
            size: info_base.4,
            writable: fs.is_writable(),
            hidden_protected,
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
///
/// `hidden_ptr`/`hidden_len` (0/0 for none) carry the hidden volume's secrets
/// in the same format, to protect it while writing to the outer volume.
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn open_fd_secret(
    fd: i32,
    writable: bool,
    secret_ptr: u64,
    secret_len: u64,
    hidden_ptr: u64,
    hidden_len: u64,
    options: FfiOpenOptions,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let file = file_from_fd(fd)?;
    let secret = caller_memory(secret_ptr, secret_len)?;
    let hidden = if hidden_ptr == 0 {
        None
    } else {
        Some(caller_memory(hidden_ptr, hidden_len)?)
    };
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::from_file(file, writable)?);
    open_device_secret(dev, secret, options, writable, hidden, cancel)
}

/// Progress of a long operation (volume creation), called from the
/// worker thread.
#[uniffi::export(with_foreign)]
pub trait FfiProgress: Send + Sync {
    fn on_progress(&self, done: u64, total: u64);
}

/// Options of a new volume. `cipher`/`prf` are names from [`list_ciphers`]
/// and [`list_prfs`]; `fs` is "exFAT" or "FAT". `extra_entropy` (e.g. touch
/// samples) is mixed into the OS random bytes, never replacing them.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiCreateOptions {
    pub size: u64,
    pub cipher: String,
    pub prf: String,
    pub pim: u32,
    pub fs: String,
    pub quick: bool,
    pub label: String,
    pub extra_entropy: Vec<u8>,
}

#[uniffi::export]
pub fn list_ciphers() -> Vec<String> {
    coffer_crypto::CipherSuite::ALL
        .iter()
        .map(|c| c.name().to_string())
        .collect()
}

#[uniffi::export]
pub fn list_prfs() -> Vec<String> {
    Prf::ALL.iter().map(|p| p.name().to_string()).collect()
}

/// OS randomness XORed with a SHA-512 keystream keyed by `extra`: the
/// result is at least as unpredictable as the OS bytes alone.
struct MixedRng {
    key: Zeroizing<Vec<u8>>,
    counter: u64,
}

impl MixedRng {
    fn new(extra: &[u8]) -> Result<Self, CofferError> {
        let mut salt = Zeroizing::new([0u8; 64]);
        os_random(salt.as_mut())?;
        let mut key = Zeroizing::new(salt.to_vec());
        key.extend_from_slice(extra);
        Ok(Self { key, counter: 0 })
    }

    fn fill(&mut self, buf: &mut [u8]) -> Result<(), CofferError> {
        use sha2::{Digest, Sha512};
        os_random(buf)?;
        for chunk in buf.chunks_mut(64) {
            let mut h = Sha512::new();
            h.update(&self.key[..]);
            h.update(self.counter.to_le_bytes());
            self.counter += 1;
            let block = h.finalize();
            for (b, k) in chunk.iter_mut().zip(block.iter()) {
                *b ^= k;
            }
        }
        Ok(())
    }
}

fn os_random(buf: &mut [u8]) -> Result<(), CofferError> {
    getrandom::fill(buf).map_err(|e| CofferError::Io(format!("system RNG: {e}")))
}

/// Creates a volume on `dev` (already `options.size` bytes long), formats
/// it and returns it opened read-write. `secret` is wiped.
pub fn create_device_secret(
    dev: Arc<dyn BlockDevice>,
    secret: &mut [u8],
    options: FfiCreateOptions,
    progress: Arc<dyn FfiProgress>,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let secrets = take_secrets(secret)?;
    let suite = coffer_crypto::CipherSuite::from_name(&options.cipher).ok_or_else(|| {
        FfiError::Unsupported {
            detail: format!("unknown cipher {}", options.cipher),
        }
    })?;
    let prf = Prf::from_name(&options.prf).ok_or_else(|| FfiError::Unsupported {
        detail: format!("unknown KDF {}", options.prf),
    })?;
    let (fs, min) = match options.fs.as_str() {
        "exFAT" => (coffer_fs::FsChoice::ExFat, coffer_volume::MIN_EXFAT),
        "FAT" => (coffer_fs::FsChoice::Fat, coffer_volume::MIN_FAT),
        other => {
            return Err(FfiError::Unsupported {
                detail: format!("filesystem {other}"),
            })
        }
    };
    if options.size < min {
        return Err(FfiError::InvalidSize {
            detail: format!(
                "{} bytes; {} needs at least {min}",
                options.size, options.fs
            ),
        });
    }
    let mut creds = Credentials::new(&secrets.password);
    creds.pim = options.pim;
    creds.keyfiles = secrets.keyfiles;
    let params = coffer_volume::CreateParams {
        size: options.size,
        suite,
        prf,
        quick: options.quick,
    };
    let mut rng = MixedRng::new(&options.extra_entropy)?;
    let mut fill = |b: &mut [u8]| rng.fill(b);
    let report = |done: u64, total: u64| progress.on_progress(done, total);
    let vol =
        coffer_volume::create_volume(dev, &creds, &params, &mut fill, &report, &cancel.token)?;
    if cancel.token.is_cancelled() {
        return Err(FfiError::Cancelled);
    }
    let mut serial = [0u8; 4];
    rng.fill(&mut serial)?;
    let vol = Arc::new(vol);
    coffer_fs::format(vol.clone(), fs, &options.label, u32::from_le_bytes(serial))?;
    if options.quick {
        progress.on_progress(1, 1);
    }
    vault_from(vol, true, false)
}

/// Creates a volume in the file behind `fd` (Android SAF, opened "rw"):
/// sets its length to `options.size`, then as [`create_device_secret`].
/// Takes ownership of `fd`. Secrets as in [`open_fd_secret`].
#[uniffi::export]
pub fn create_fd_secret(
    fd: i32,
    secret_ptr: u64,
    secret_len: u64,
    options: FfiCreateOptions,
    progress: Arc<dyn FfiProgress>,
    cancel: Arc<FfiCancel>,
) -> Result<Arc<FfiVault>, FfiError> {
    let file = file_from_fd(fd)?;
    let secret = caller_memory(secret_ptr, secret_len)?;
    file.set_len(options.size).map_err(|e| {
        if e.kind() == std::io::ErrorKind::StorageFull {
            FfiError::NoSpace
        } else {
            FfiError::Io {
                detail: e.to_string(),
            }
        }
    })?;
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::from_file(file, true)?);
    create_device_secret(dev, secret, options, progress, cancel)
}

fn caller_memory<'a>(ptr: u64, len: u64) -> Result<&'a mut [u8], FfiError> {
    if ptr == 0 || len > MAX_SECRETS {
        return Err(FfiError::InvalidPath {
            path: "invalid secrets buffer".into(),
        });
    }
    // SAFETY: the caller guarantees `len` writable bytes at `ptr` for the
    // duration of the call (Kotlin JNA Memory), not aliased elsewhere.
    Ok(unsafe { std::slice::from_raw_parts_mut(ptr as *mut u8, len as usize) })
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

/// Local time offset (minutes east of UTC) used for new FAT timestamps; FAT
/// stores local time. exFAT stores UTC with an explicit offset instead.
#[uniffi::export]
pub fn set_local_offset_minutes(minutes: i32) {
    coffer_fat::set_local_offset_minutes(minutes);
}
