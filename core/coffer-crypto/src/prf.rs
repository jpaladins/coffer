// SPDX-License-Identifier: GPL-3.0-or-later

/// Header key derivation function (PBKDF2-HMAC with a hash, or Argon2id).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Prf {
    Sha512,
    Sha256,
    Whirlpool,
    Blake2s256,
    Streebog,
    Argon2id,
}

impl Prf {
    /// Search order when auto-detecting: most common first, memory-hard last.
    pub const ALL: [Prf; 6] = [
        Prf::Sha512,
        Prf::Sha256,
        Prf::Blake2s256,
        Prf::Whirlpool,
        Prf::Streebog,
        Prf::Argon2id,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Prf::Sha512 => "SHA-512",
            Prf::Sha256 => "SHA-256",
            Prf::Whirlpool => "Whirlpool",
            Prf::Blake2s256 => "BLAKE2s-256",
            Prf::Streebog => "Streebog",
            Prf::Argon2id => "Argon2id",
        }
    }

    pub fn from_name(name: &str) -> Option<Prf> {
        Prf::ALL
            .into_iter()
            .find(|p| p.name().eq_ignore_ascii_case(name))
    }
}
