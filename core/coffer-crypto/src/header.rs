// SPDX-License-Identifier: GPL-3.0-or-later
//! Volume header (512 bytes): 64-byte salt + 448 encrypted bytes.
//! Field offsets from docs/security/format-notes.md ("Header"), big-endian.
use crate::kdf::{derive_header_key_with_limit, DEFAULT_MAX_KDF_MEMORY, SALT_LEN};
use crate::{apply_keyfiles, CancelToken, CipherSuite, CofferError, Prf, XtsCascade};
use rayon::prelude::*;
use zeroize::Zeroizing;

pub const HEADER_LEN: usize = 512;
const ENC_LEN: usize = HEADER_LEN - SALT_LEN; // 448
pub const MASTER_KEY_LEN: usize = 256;

// Offsets inside the 448-byte decrypted area (= header offset - 64).
const OFF_MAGIC: usize = 0;
const OFF_VERSION: usize = 4;
const OFF_MIN_VERSION: usize = 6;
const OFF_CRC_KEYS: usize = 8;
const OFF_HIDDEN_SIZE: usize = 28;
const OFF_VOLUME_SIZE: usize = 36;
const OFF_EA_START: usize = 44;
const OFF_EA_SIZE: usize = 52;
const OFF_FLAGS: usize = 60;
const OFF_SECTOR_SIZE: usize = 64;
const OFF_CRC_HEADER: usize = 188;
const OFF_KEYS: usize = 192;
const MAGIC: &[u8; 4] = b"VERA";

/// What the user typed or selected to open a volume.
pub struct Credentials {
    pub password: Zeroizing<Vec<u8>>,
    pub pim: u32,
    pub keyfiles: Vec<Zeroizing<Vec<u8>>>,
    pub prf_hint: Option<Prf>,
    pub suite_hint: Option<CipherSuite>,
    /// Argon2id runs needing more memory than this are refused.
    pub max_kdf_memory: u64,
}

impl Credentials {
    pub fn new(password: &[u8]) -> Self {
        Self {
            password: Zeroizing::new(password.to_vec()),
            pim: 0,
            keyfiles: Vec::new(),
            prf_hint: None,
            suite_hint: None,
            max_kdf_memory: DEFAULT_MAX_KDF_MEMORY,
        }
    }

    fn effective_password(&self) -> Zeroizing<Vec<u8>> {
        let kfs: Zeroizing<Vec<Vec<u8>>> =
            Zeroizing::new(self.keyfiles.iter().map(|k| k.to_vec()).collect());
        apply_keyfiles(&self.password, &kfs)
    }
}

/// Decrypted header fields.
pub struct VolumeHeader {
    pub version: u16,
    pub min_program_version: u16,
    pub hidden_volume_size: u64,
    pub volume_size: u64,
    pub encrypted_area_start: u64,
    pub encrypted_area_size: u64,
    pub flags: u32,
    pub sector_size: u32,
    pub master_key: Zeroizing<[u8; MASTER_KEY_LEN]>,
    pub suite: CipherSuite,
    pub prf: Prf,
}

impl std::fmt::Debug for VolumeHeader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VolumeHeader")
            .field("version", &self.version)
            .field("min_program_version", &self.min_program_version)
            .field("hidden_volume_size", &self.hidden_volume_size)
            .field("volume_size", &self.volume_size)
            .field("encrypted_area_start", &self.encrypted_area_start)
            .field("encrypted_area_size", &self.encrypted_area_size)
            .field("flags", &self.flags)
            .field("sector_size", &self.sector_size)
            .field("suite", &self.suite)
            .field("prf", &self.prf)
            .finish_non_exhaustive()
    }
}

fn be16(b: &[u8], o: usize) -> u16 {
    u16::from_be_bytes([b[o], b[o + 1]])
}
fn be32(b: &[u8], o: usize) -> u32 {
    u32::from_be_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}
fn be64(b: &[u8], o: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[o..o + 8]);
    u64::from_be_bytes(a)
}

/// Decrypts with one suite and validates magic and both CRCs.
fn try_suite(
    raw: &[u8; HEADER_LEN],
    key: &[u8],
    suite: CipherSuite,
    prf: Prf,
) -> Option<VolumeHeader> {
    let x = XtsCascade::new(suite, &key[..suite.key_len()]).ok()?;
    let mut p = Zeroizing::new([0u8; ENC_LEN]);
    p.copy_from_slice(&raw[SALT_LEN..]);
    x.decrypt_units(p.as_mut(), 0);
    if &p[OFF_MAGIC..OFF_MAGIC + 4] != MAGIC {
        return None;
    }
    if crc32fast::hash(&p[OFF_KEYS..]) != be32(p.as_ref(), OFF_CRC_KEYS) {
        return None;
    }
    if crc32fast::hash(&p[..OFF_CRC_HEADER]) != be32(p.as_ref(), OFF_CRC_HEADER) {
        return None;
    }
    let mut mk = Zeroizing::new([0u8; MASTER_KEY_LEN]);
    mk.copy_from_slice(&p[OFF_KEYS..]);
    Some(VolumeHeader {
        version: be16(p.as_ref(), OFF_VERSION),
        min_program_version: be16(p.as_ref(), OFF_MIN_VERSION),
        hidden_volume_size: be64(p.as_ref(), OFF_HIDDEN_SIZE),
        volume_size: be64(p.as_ref(), OFF_VOLUME_SIZE),
        encrypted_area_start: be64(p.as_ref(), OFF_EA_START),
        encrypted_area_size: be64(p.as_ref(), OFF_EA_SIZE),
        flags: be32(p.as_ref(), OFF_FLAGS),
        sector_size: be32(p.as_ref(), OFF_SECTOR_SIZE),
        master_key: mk,
        suite,
        prf,
    })
}

enum Outcome {
    Found(Box<VolumeHeader>),
    NoMatch,
    Cancelled,
    OutOfMemory(u64),
    Failed(CofferError),
}

/// Tries every candidate KDF (in parallel) and cipher suite. The first match
/// cancels the remaining derivations.
pub fn decrypt_header(
    raw: &[u8; HEADER_LEN],
    creds: &Credentials,
    cancel: &CancelToken,
) -> Result<VolumeHeader, CofferError> {
    let password = creds.effective_password();
    let mut salt = [0u8; SALT_LEN];
    salt.copy_from_slice(&raw[..SALT_LEN]);
    let prfs: Vec<Prf> = creds
        .prf_hint
        .map_or_else(|| Prf::ALL.to_vec(), |p| vec![p]);
    let suites: Vec<CipherSuite> = creds
        .suite_hint
        .map_or_else(|| CipherSuite::ALL.to_vec(), |s| vec![s]);
    let found = cancel.child();

    let attempt = |prf: Prf| -> Outcome {
        if found.is_cancelled() {
            return Outcome::Cancelled;
        }
        let key = match derive_header_key_with_limit(
            prf,
            &password,
            &salt,
            creds.pim,
            &found,
            creds.max_kdf_memory,
        ) {
            Ok(k) => k,
            Err(CofferError::Cancelled) => return Outcome::Cancelled,
            Err(CofferError::KdfOutOfMemory { required_bytes }) => {
                return Outcome::OutOfMemory(required_bytes)
            }
            Err(e) => return Outcome::Failed(e),
        };
        for &suite in &suites {
            if let Some(h) = try_suite(raw, key.as_ref(), suite, prf) {
                found.cancel();
                return Outcome::Found(Box::new(h));
            }
        }
        Outcome::NoMatch
    };

    // Phase 1: PBKDF2 PRFs in parallel. Phase 2: Argon2id, which is
    // memory-hard and can take minutes with a large PIM, only if needed.
    let (memory_hard, pbkdf2): (Vec<Prf>, Vec<Prf>) =
        prfs.into_iter().partition(|p| *p == Prf::Argon2id);
    let mut outcomes: Vec<Outcome> = pbkdf2.par_iter().map(|&p| attempt(p)).collect();
    if !outcomes.iter().any(|o| matches!(o, Outcome::Found(_))) {
        outcomes.extend(memory_hard.into_iter().map(attempt));
    }

    let mut oom = None;
    let mut failure = None;
    for o in outcomes {
        match o {
            Outcome::Found(h) => return Ok(*h),
            Outcome::OutOfMemory(n) => oom = Some(n),
            Outcome::Failed(e) => failure = Some(e),
            Outcome::NoMatch | Outcome::Cancelled => {}
        }
    }
    if cancel.is_cancelled() {
        return Err(CofferError::Cancelled);
    }
    if let Some(e) = failure {
        return Err(e);
    }
    if let Some(required_bytes) = oom {
        // Could be an Argon2id volume we could not try on this device.
        return Err(CofferError::KdfOutOfMemory { required_bytes });
    }
    Err(CofferError::WrongCredentials)
}

/// Serializes and encrypts a header with `h.prf`/`h.suite` and `creds`.
pub fn encrypt_header(
    h: &VolumeHeader,
    creds: &Credentials,
    salt: &[u8; SALT_LEN],
) -> Result<[u8; HEADER_LEN], CofferError> {
    let mut p = Zeroizing::new([0u8; ENC_LEN]);
    p[OFF_MAGIC..OFF_MAGIC + 4].copy_from_slice(MAGIC);
    p[OFF_VERSION..OFF_VERSION + 2].copy_from_slice(&h.version.to_be_bytes());
    p[OFF_MIN_VERSION..OFF_MIN_VERSION + 2].copy_from_slice(&h.min_program_version.to_be_bytes());
    p[OFF_HIDDEN_SIZE..OFF_HIDDEN_SIZE + 8].copy_from_slice(&h.hidden_volume_size.to_be_bytes());
    p[OFF_VOLUME_SIZE..OFF_VOLUME_SIZE + 8].copy_from_slice(&h.volume_size.to_be_bytes());
    p[OFF_EA_START..OFF_EA_START + 8].copy_from_slice(&h.encrypted_area_start.to_be_bytes());
    p[OFF_EA_SIZE..OFF_EA_SIZE + 8].copy_from_slice(&h.encrypted_area_size.to_be_bytes());
    p[OFF_FLAGS..OFF_FLAGS + 4].copy_from_slice(&h.flags.to_be_bytes());
    p[OFF_SECTOR_SIZE..OFF_SECTOR_SIZE + 4].copy_from_slice(&h.sector_size.to_be_bytes());
    p[OFF_KEYS..].copy_from_slice(h.master_key.as_ref());
    let crc_keys = crc32fast::hash(&p[OFF_KEYS..]);
    p[OFF_CRC_KEYS..OFF_CRC_KEYS + 4].copy_from_slice(&crc_keys.to_be_bytes());
    let crc_header = crc32fast::hash(&p[..OFF_CRC_HEADER]);
    p[OFF_CRC_HEADER..OFF_CRC_HEADER + 4].copy_from_slice(&crc_header.to_be_bytes());

    let password = creds.effective_password();
    let key = derive_header_key_with_limit(
        h.prf,
        &password,
        salt,
        creds.pim,
        &CancelToken::new(),
        creds.max_kdf_memory,
    )?;
    let x = XtsCascade::new(h.suite, &key[..h.suite.key_len()])?;
    x.encrypt_units(p.as_mut(), 0);
    let mut raw = [0u8; HEADER_LEN];
    raw[..SALT_LEN].copy_from_slice(salt);
    raw[SALT_LEN..].copy_from_slice(p.as_ref());
    Ok(raw)
}
