// SPDX-License-Identifier: GPL-3.0-or-later
//! Known-answer tests that run on the user's device, so a miscompiled or
//! broken native build is caught before it touches a container.
//!
//! Block ciphers and hashes use the vectors published with each algorithm.
//! PBKDF2 uses Python's hashlib as the reference. XTS cascades, Serpent,
//! Streebog and Argon2id use values from the release build verified against
//! VeraCrypt (the corpus tests): on a phone they must match bit for bit.

use crate::{derive_header_key, pbkdf2_raw, CancelToken, CipherSuite, Prf, XtsCascade, DATA_UNIT, SALT_LEN};
use cipher::{Block, BlockCipherEncrypt, KeyInit};
use digest::Digest;

/// One named check and whether it matched its expected value.
pub struct Check {
    pub name: String,
    pub ok: bool,
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len() / 2)
        .filter_map(|i| s.get(2 * i..2 * i + 2).and_then(|b| u8::from_str_radix(b, 16).ok()))
        .collect()
}

pub(crate) fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn block<C: BlockCipherEncrypt + KeyInit>(key: &str, plain: &str) -> String {
    let Ok(c) = C::new_from_slice(&unhex(key)) else { return String::new() };
    let mut b = Block::<C>::default();
    b.copy_from_slice(&unhex(plain));
    c.encrypt_block(&mut b);
    hex(&b)
}

fn digest<D: Digest>(msg: &[u8]) -> String {
    hex(&D::digest(msg))
}

/// The fixed 512-byte data unit every XTS check encrypts.
fn xts_plain() -> Vec<u8> {
    (0..DATA_UNIT).map(|i| (i * 7 + 3) as u8).collect()
}

/// SHA-256 of the unit encrypted with `suite`, key bytes 0, 1, 2, …, data unit 5.
pub(crate) fn xts_fingerprint(suite: CipherSuite) -> String {
    let key: Vec<u8> = (0..suite.key_len()).map(|i| i as u8).collect();
    let Ok(x) = XtsCascade::new(suite, &key) else { return String::new() };
    let mut data = xts_plain();
    x.encrypt_units(&mut data, 5);
    let enc = digest::<sha2::Sha256>(&data);
    x.decrypt_units(&mut data, 5);
    // A cipher that does not decrypt back to the plaintext fails too.
    if data != xts_plain() {
        return String::new();
    }
    enc
}

fn pbkdf2_hex(prf: Prf) -> String {
    let salt: Vec<u8> = (0u8..64).collect();
    let mut out = [0u8; 192];
    match pbkdf2_raw(prf, b"password", &salt, 1000, &mut out, &CancelToken::new()) {
        Ok(()) => hex(&out),
        Err(_) => String::new(),
    }
}

/// Argon2id as VeraCrypt uses it (PIM 1: 64 MiB, 3 passes), on a fixed salt.
pub(crate) fn argon2_hex() -> String {
    let salt = [0x5au8; SALT_LEN];
    match derive_header_key(Prf::Argon2id, b"password", &salt, 1, &CancelToken::new()) {
        Ok(k) => hex(&k[..32]),
        Err(_) => String::new(),
    }
}

const PY_SHA512: &str = "b1ea1939de322a9e2a7a99c77919ce22aa31a447afcbde93cfd9a8b982122b6a9d28a79437a74e1bc3970b88255a68493579517a4eaa189ab41e9958b45e9d71d661f0f7ebf61d78395b5b6d83b7813382662e725e2d91498e06e0e0ca962cd52d7a69d53584a4a9d8482c216821a8cc223b9ac3370887b6d9776e03219b9784c9e504d48dab0e2faf349cafa45bdeeb5e6d49b4628faff70d0497a64d596adcee8bad8f7ae9b5d58e2712af5e24299298e77ec85f4c357e6a5af82d56f2dde7";
const PY_SHA256: &str = "5f13e6f0ef9102086dea98d9375105b7208b082e6114e628393112b6a37aa4f869e29e57c44cb2598132b5afbbfd2bace028355ff3795ba5177187881536559a43f95f8217c2e1de5c57d53ba2728e3704e6955bb95caab8a16d74bddd76508ff8ee6594e4fe4ba5b6f256bb53b072b33e856acd628c3d8f988102406cf002ea6d47be6b8d0bf80038c3230412b6da9176ddac41727c6e6ea0e9ac1cbdcab1529f9e151314010ed15f311924782c978b7f6739dc68a2223fd3198206d8269b7a";
const PY_BLAKE2S: &str = "b55abe899157975d6fa819bef8e96ecd69b117c3a984486702d54fab054c264f26a3d6dccb0da4685ad552e24d78d433cf1dc902be0a95cb049209bc5776fead162ef3e386af9a83b867e87a9aed361e8a114646c1fbe3f5db59029bdf0b2198a4a44a44a95165cfaeb5781d167310a37477bf4a86ac8a84cc904b36c37ccacb89148e54845cce54a71db642bf3745c2af70691be9fec1de5c0fc69a7f591791b75c40600409c120fb6c48ae7d2e6dd79e54966382bb2bd4bd2d6700049a3fbd";

/// Reference values from the verified build (see the module comment).
pub(crate) const GOLDEN_SERPENT: &str = "49672ba898d98df95019180445491089";
pub(crate) const GOLDEN_STREEBOG: &str = "28156e28317da7c98f4fe2bed6b542d0dab85bb224445fcedaf75d46e26d7eb8d5997f3e0915dd6b7f0aab08d9c8beb0d8c64bae2ab8b3c8c6bc53b3bf0db728";
pub(crate) const GOLDEN_PBKDF2_WHIRLPOOL: &str = "09526e0537be528fadb974edf19801f67962aa94e6e21d0f566f24b438fed18c1e2f25fa5604e6733d441a422cd311da4f0c87e513624b28a3a58925a076e1400e4cb9f1e34bd36a47bce0f04565e3b078d27772034e71b3d2049a5044f0ccc318054fd1d175257ff07f748a9a9fe319e74322d216a8f90e0ca61918523786e9744ae0a752c3c6f443080304267fe8e84e6120449b148b9d9f11e2fcc76f96997578101f426b9a3d7ce25d8dd36a1373ff9134b8ff41dbb9903d30f2097abefc";
pub(crate) const GOLDEN_PBKDF2_STREEBOG: &str = "7c4b2fa5c3c2a78c0560626dd70ab0fdd6d55eef05c31a85fdcf09dbf7a2804ac6064b613091c3179ca40d3497b8f650aa17a201495306450b5b9d32ad0974df64a8abaf5b126a165c4b00629b04857342da63e3c5196c80f03f0966e86569ff78153eb502d1afb39f27c3850d54ffefb5b621be1b5dc349c80f266aa83953462bfe03b3d947d7994edf0e6e9ddb5434a2c9f663b02630eb8fb8e3a6a45cf13ad8be8bdc0462df9e2701c1c9a45b72e53650ffd424424cdcc5d471538749ee26";
pub(crate) const GOLDEN_ARGON2: &str = "6d08c35ebf77bf71a4b272c8d8c1726562d816b2310f9126d36421788e421f7d";
/// One per entry of [`CipherSuite::ALL`], in the same order.
pub(crate) const GOLDEN_XTS: [&str; 15] = [
    "9d528a9c19737a5bd2b135c40dadffd76e43ba52db80f5cb4cb58b184e5fbfb4",
    "8b644b1a8158a788ad0ff763e62d6fb5004921a8605dcce757326372c5016c03",
    "da00a665e2a52e6010b69da73ac4b6d9971afc5605c4b3a989686b8dfa1f1196",
    "b68df4b1119bbca9b6c6c26b16c00fc8b9dcf5628f9eccec0be4447bffe82380",
    "989dd86610bb90ae070a8e16d20c6e6db3d5a47b106894ce5b0d061920d7342a",
    "edab574164337bbb6094dbcce9c4bfc890396ead67c384ef6e12c2080d75384a",
    "48c43ffb77648efe75256e95ae894b0a44e5e3d37cb8a8fa693a950cfd307059",
    "099cac019c50683023b47f44c2824201537e132df704666bf253281233314be1",
    "b50f088c309b49aff9809f713d11327c3eeedfeb20f5f3e6dce8771c048e7903",
    "7d43a0ec4691f39e80ac5a564c608ee7e9315e591d34ac0db4eb3e193fabaaa5",
    "5bc35952ab9cf80e8b841ef99578efd3a8b1f5d09a5b3fff4a91758834a738aa",
    "78f606c411a65ffa51ede02101375bafb3943a05242c6737674e61439158cb53",
    "d6f0d5e16a7c8fcfc7c8398cdd3ccdc8c9c9a538e252584db30404ca369e82bb",
    "d853791c3df4621bf12cead08ce68dd51b2e26b4b06ddd66b75e812b351648f6",
    "34c9fbcaa8d43cd8b64b52dfcc5f1d2ee6a452b62aa23e72b9f5e412023f7f45",
];

/// Runs every check. Argon2id takes about a second on a phone.
pub fn run() -> Vec<Check> {
    let mut out = Vec::new();
    let mut add = |name: &str, actual: String, expected: &str| {
        out.push(Check { name: name.to_owned(), ok: !expected.is_empty() && actual == expected });
    };
    // FIPS-197 C.3.
    add("AES-256", block::<aes::Aes256>("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "00112233445566778899aabbccddeeff"), "8ea2b7ca516745bfeafc49904b496089");
    // RFC 3713 appendix A.
    add("Camellia-256", block::<camellia::Camellia256>("0123456789abcdeffedcba987654321000112233445566778899aabbccddeeff", "0123456789abcdeffedcba9876543210"), "9acc237dff16d76c20ef7c919e3a7509");
    // GOST R 34.12-2015 appendix A.1.
    add("Kuznyechik", block::<kuznyechik::Kuznyechik>("8899aabbccddeeff0011223344556677fedcba98765432100123456789abcdef", "1122334455667700ffeeddccbbaa9988"), "7f679d90bebc24305a468d42b9d4edcd");
    // Twofish paper, 256-bit key, all-zero key and block.
    add("Twofish-256", block::<twofish::Twofish>(&"00".repeat(32), &"00".repeat(16)), "57ff739d4dc92c1bd7fc01700cc8216f");
    add("Serpent-256", block::<serpent::Serpent>(&"00".repeat(32), &"00".repeat(16)), GOLDEN_SERPENT);
    // FIPS 180 / ISO 10118-3 / RFC 7693 "abc" digests.
    add("SHA-256", digest::<sha2::Sha256>(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    add("SHA-512", digest::<sha2::Sha512>(b"abc"), "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
    add("Whirlpool", digest::<whirlpool::Whirlpool>(b"abc"), "4e2448a4c6f486bb16b6562c73b4020bf3043e3a731bce721ae1b303d97e6d4c7181eebdb6c57e277d0e34957114cbd6c797fc9d95d8b582d225292076d4eef5");
    add("BLAKE2s-256", digest::<blake2::Blake2s256>(b"abc"), "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982");
    add("Streebog-512", digest::<streebog::Streebog512>(b"abc"), GOLDEN_STREEBOG);
    add("PBKDF2-SHA-512", pbkdf2_hex(Prf::Sha512), PY_SHA512);
    add("PBKDF2-SHA-256", pbkdf2_hex(Prf::Sha256), PY_SHA256);
    add("PBKDF2-BLAKE2s", pbkdf2_hex(Prf::Blake2s256), PY_BLAKE2S);
    add("PBKDF2-Whirlpool", pbkdf2_hex(Prf::Whirlpool), GOLDEN_PBKDF2_WHIRLPOOL);
    add("PBKDF2-Streebog", pbkdf2_hex(Prf::Streebog), GOLDEN_PBKDF2_STREEBOG);
    add("Argon2id", argon2_hex(), GOLDEN_ARGON2);
    for (suite, expected) in CipherSuite::ALL.iter().zip(GOLDEN_XTS) {
        add(&format!("XTS {}", suite.name()), xts_fingerprint(*suite), expected);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Prints the values to freeze; run once on the verified build.
    #[test]
    fn print_golden_values() {
        println!("SERPENT {}", block::<serpent::Serpent>(&"00".repeat(32), &"00".repeat(16)));
        println!("STREEBOG {}", digest::<streebog::Streebog512>(b"abc"));
        println!("PBKDF2_WHIRLPOOL {}", pbkdf2_hex(Prf::Whirlpool));
        println!("PBKDF2_STREEBOG {}", pbkdf2_hex(Prf::Streebog));
        println!("ARGON2 {}", argon2_hex());
        for s in CipherSuite::ALL {
            println!("XTS {} {}", s.name(), xts_fingerprint(s));
        }
    }

    #[test]
    fn every_check_passes() {
        let failed: Vec<String> = run().into_iter().filter(|c| !c.ok).map(|c| c.name).collect();
        assert!(failed.is_empty(), "failed: {failed:?}");
    }
}
