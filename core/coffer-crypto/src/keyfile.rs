// SPDX-License-Identifier: GPL-3.0-or-later
//! Keyfile pool (see docs/security/format-notes.md, "Keyfile").
use zeroize::Zeroizing;

pub const KEYFILE_POOL_LEN: usize = 64;
pub const KEYFILE_MAX_READ: usize = 1_048_576;

/// How the 4-byte CRC-32 state is added to the pool. Kept configurable so the
/// corpus oracle can pin the variant (see Task 7 ruling).
#[doc(hidden)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KeyfileVariant {
    pub pool_len: usize,
    pub big_endian: bool,
    pub finalized: bool,
}

pub const KEYFILE_VARIANT: KeyfileVariant = KeyfileVariant {
    pool_len: KEYFILE_POOL_LEN,
    big_endian: true,
    finalized: true,
};

/// Combines keyfiles with the password. Without keyfiles the password is
/// returned unchanged.
pub fn apply_keyfiles(password: &[u8], keyfiles: &[Vec<u8>]) -> Zeroizing<Vec<u8>> {
    apply_keyfiles_variant(password, keyfiles, KEYFILE_VARIANT)
}

#[doc(hidden)]
pub fn apply_keyfiles_variant(
    password: &[u8],
    keyfiles: &[Vec<u8>],
    v: KeyfileVariant,
) -> Zeroizing<Vec<u8>> {
    let mut out = Zeroizing::new(password.to_vec());
    if keyfiles.is_empty() {
        return out;
    }
    let mut pool = Zeroizing::new(vec![0u8; v.pool_len]);
    for kf in keyfiles {
        let mut cursor = 0usize;
        let mut reg: u32 = 0xFFFF_FFFF;
        for &b in kf.iter().take(KEYFILE_MAX_READ) {
            reg = CRC_TABLE[((reg ^ u32::from(b)) & 0xFF) as usize] ^ (reg >> 8);
            let state = if v.finalized { !reg } else { reg };
            let bytes = if v.big_endian {
                state.to_be_bytes()
            } else {
                state.to_le_bytes()
            };
            for t in bytes {
                pool[cursor] = pool[cursor].wrapping_add(t);
                cursor = (cursor + 1) % v.pool_len;
            }
        }
    }
    if out.len() < v.pool_len {
        out.resize(v.pool_len, 0);
    }
    out.iter_mut().zip(pool.iter()).for_each(|(b, g)| *b ^= g);
    out
}

/// CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320) lookup table.
const CRC_TABLE: [u32; 256] = {
    let mut t = [0u32; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = i as u32;
        let mut k = 0;
        while k < 8 {
            c = if c & 1 != 0 {
                0xEDB8_8320 ^ (c >> 1)
            } else {
                c >> 1
            };
            k += 1;
        }
        t[i] = c;
        i += 1;
    }
    t
};
