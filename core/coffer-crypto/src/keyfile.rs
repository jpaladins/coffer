// SPDX-License-Identifier: GPL-3.0-or-later
//! Keyfile pool (see docs/security/format-notes.md, "Keyfile"). Details not
//! fixed by the documentation were pinned against the VeraCrypt corpus.
use zeroize::Zeroizing;

/// Pool size for passwords up to 64 bytes (legacy maximum length).
pub const KEYFILE_POOL_LEN: usize = 64;
/// Pool size for passwords longer than 64 bytes (maximum 128).
pub const KEYFILE_POOL_LEN_LONG: usize = 128;
pub const KEYFILE_MAX_READ: usize = 1_048_576;

/// Combines keyfiles with the password. Without keyfiles the password is
/// returned unchanged.
///
/// For each keyfile (first 1 MiB), a CRC-32 register (reset per keyfile, not
/// finalized) is updated byte by byte; after each byte its 4 bytes
/// (big-endian) are added modulo 256 into the pool at a cursor that restarts
/// at 0 for each keyfile and wraps at the end of the pool. The password,
/// zero-padded to the pool size, is then combined with the pool by bytewise
/// addition modulo 256.
pub fn apply_keyfiles(password: &[u8], keyfiles: &[Vec<u8>]) -> Zeroizing<Vec<u8>> {
    let mut out = Zeroizing::new(password.to_vec());
    if keyfiles.is_empty() {
        return out;
    }
    let pool_len = if password.len() > KEYFILE_POOL_LEN {
        KEYFILE_POOL_LEN_LONG
    } else {
        KEYFILE_POOL_LEN
    };
    let mut pool = Zeroizing::new(vec![0u8; pool_len]);
    for kf in keyfiles {
        let mut cursor = 0usize;
        let mut reg: u32 = 0xFFFF_FFFF;
        for &b in kf.iter().take(KEYFILE_MAX_READ) {
            reg = CRC_TABLE[((reg ^ u32::from(b)) & 0xFF) as usize] ^ (reg >> 8);
            for t in reg.to_be_bytes() {
                pool[cursor] = pool[cursor].wrapping_add(t);
                cursor = (cursor + 1) % pool_len;
            }
        }
    }
    if out.len() < pool_len {
        out.resize(pool_len, 0);
    }
    out.iter_mut()
        .zip(pool.iter())
        .for_each(|(b, g)| *b = b.wrapping_add(*g));
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
