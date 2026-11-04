// SPDX-License-Identifier: GPL-3.0-or-later
//! Header key derivation (see docs/security/format-notes.md).
use crate::{CancelToken, CofferError, Prf};
use digest::{common::BlockSizeUser, Digest};
use rayon::prelude::*;
use zeroize::Zeroizing;

pub const SALT_LEN: usize = 64;
pub const HEADER_KEY_LEN: usize = 192;
/// Default upper bound for Argon2id memory; the app lowers it on small devices.
pub const DEFAULT_MAX_KDF_MEMORY: u64 = 1024 * 1024 * 1024;
const CANCEL_CHECK_EVERY: u32 = 4096;

/// PBKDF2 iteration count for non-system volumes and file containers.
pub fn iterations(_prf: Prf, pim: u32) -> u32 {
    if pim == 0 {
        500_000
    } else {
        pim.saturating_mul(1000).saturating_add(15_000)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Argon2Params {
    pub m_cost_kib: u32,
    pub t_cost: u32,
}

/// Argon2id cost parameters derived from the PIM (PIM 0 behaves as PIM 12).
pub fn argon2_params(pim: u32) -> Argon2Params {
    let pim = if pim == 0 { 12 } else { pim };
    let m_mib = (64u64 + (u64::from(pim) - 1) * 32).min(1024);
    let t_cost = if pim <= 31 {
        3 + (pim - 1) / 3
    } else {
        13u32.saturating_add(pim - 31)
    };
    Argon2Params {
        m_cost_kib: (m_mib * 1024) as u32,
        t_cost,
    }
}

/// Derives the 192-byte header key, with the default Argon2id memory limit.
pub fn derive_header_key(
    prf: Prf,
    password: &[u8],
    salt: &[u8; SALT_LEN],
    pim: u32,
    cancel: &CancelToken,
) -> Result<Zeroizing<[u8; HEADER_KEY_LEN]>, CofferError> {
    derive_header_key_with_limit(prf, password, salt, pim, cancel, DEFAULT_MAX_KDF_MEMORY)
}

/// Like [`derive_header_key`], refusing Argon2id runs that need more than
/// `max_kdf_memory` bytes.
pub fn derive_header_key_with_limit(
    prf: Prf,
    password: &[u8],
    salt: &[u8; SALT_LEN],
    pim: u32,
    cancel: &CancelToken,
    max_kdf_memory: u64,
) -> Result<Zeroizing<[u8; HEADER_KEY_LEN]>, CofferError> {
    let mut out = Zeroizing::new([0u8; HEADER_KEY_LEN]);
    if prf == Prf::Argon2id {
        argon2id(password, salt, pim, cancel, max_kdf_memory, out.as_mut())?;
    } else {
        pbkdf2_raw(
            prf,
            password,
            salt,
            iterations(prf, pim),
            out.as_mut(),
            cancel,
        )?;
    }
    Ok(out)
}

fn argon2id(
    password: &[u8],
    salt: &[u8],
    pim: u32,
    cancel: &CancelToken,
    max_kdf_memory: u64,
    out: &mut [u8],
) -> Result<(), CofferError> {
    let p = argon2_params(pim);
    let required_bytes = u64::from(p.m_cost_kib) * 1024;
    if required_bytes > max_kdf_memory {
        return Err(CofferError::KdfOutOfMemory { required_bytes });
    }
    if cancel.is_cancelled() {
        return Err(CofferError::Cancelled);
    }
    let params = argon2::Params::new(p.m_cost_kib, p.t_cost, 1, Some(out.len()))
        .map_err(|e| CofferError::Unsupported(format!("argon2 params: {e}")))?;
    let a = argon2::Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params);
    a.hash_password_into(password, salt, out)
        .map_err(|e| CofferError::Unsupported(format!("argon2: {e}")))?;
    if cancel.is_cancelled() {
        return Err(CofferError::Cancelled);
    }
    Ok(())
}

/// PBKDF2-HMAC (RFC 8018) with cooperative cancellation. Output blocks are
/// computed in parallel. Exposed for test vectors.
#[doc(hidden)]
pub fn pbkdf2_raw(
    prf: Prf,
    password: &[u8],
    salt: &[u8],
    iterations: u32,
    out: &mut [u8],
    cancel: &CancelToken,
) -> Result<(), CofferError> {
    match prf {
        Prf::Sha512 => pbkdf2::<sha2::Sha512>(password, salt, iterations, out, cancel),
        Prf::Sha256 => pbkdf2::<sha2::Sha256>(password, salt, iterations, out, cancel),
        Prf::Whirlpool => pbkdf2::<whirlpool::Whirlpool>(password, salt, iterations, out, cancel),
        Prf::Blake2s256 => pbkdf2::<blake2::Blake2s256>(password, salt, iterations, out, cancel),
        Prf::Streebog => pbkdf2::<streebog::Streebog512>(password, salt, iterations, out, cancel),
        Prf::Argon2id => Err(CofferError::Unsupported(
            "Argon2id is not a PBKDF2 PRF".into(),
        )),
    }
}

/// Precomputed HMAC inner/outer states for one key.
struct HmacKey<D> {
    inner: D,
    outer: D,
}

impl<D: Digest + BlockSizeUser + Clone> HmacKey<D> {
    fn new(key: &[u8]) -> Self {
        let bs = <D as BlockSizeUser>::block_size();
        let mut k = Zeroizing::new(vec![0u8; bs]);
        if key.len() > bs {
            let h = D::digest(key);
            k[..h.len()].copy_from_slice(&h);
        } else {
            k[..key.len()].copy_from_slice(key);
        }
        let mut pad = Zeroizing::new(vec![0u8; bs]);
        let mut inner = D::new();
        pad.iter_mut()
            .zip(k.iter())
            .for_each(|(p, b)| *p = b ^ 0x36);
        inner.update(pad.as_slice());
        let mut outer = D::new();
        pad.iter_mut()
            .zip(k.iter())
            .for_each(|(p, b)| *p = b ^ 0x5c);
        outer.update(pad.as_slice());
        Self { inner, outer }
    }

    fn mac(&self, parts: &[&[u8]], out: &mut [u8]) {
        let mut i = self.inner.clone();
        for p in parts {
            i.update(p);
        }
        let ih = i.finalize();
        let mut o = self.outer.clone();
        o.update(&ih);
        out.copy_from_slice(&o.finalize());
    }
}

fn pbkdf2<D: Digest + BlockSizeUser + Clone + Sync>(
    password: &[u8],
    salt: &[u8],
    iterations: u32,
    out: &mut [u8],
    cancel: &CancelToken,
) -> Result<(), CofferError> {
    let key = HmacKey::<D>::new(password);
    let hlen = <D as Digest>::output_size();
    out.par_chunks_mut(hlen)
        .enumerate()
        .try_for_each(|(i, chunk)| {
            let block_index = (i as u32 + 1).to_be_bytes();
            let mut u = Zeroizing::new(vec![0u8; hlen]);
            key.mac(&[salt, &block_index], &mut u);
            let mut t = u.clone();
            for n in 1..iterations {
                if n % CANCEL_CHECK_EVERY == 0 && cancel.is_cancelled() {
                    return Err(CofferError::Cancelled);
                }
                let prev = u.clone();
                key.mac(&[&prev], &mut u);
                t.iter_mut().zip(u.iter()).for_each(|(a, b)| *a ^= b);
            }
            chunk.copy_from_slice(&t[..chunk.len()]);
            Ok(())
        })
}
