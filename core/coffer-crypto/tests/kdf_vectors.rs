// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{
    apply_keyfiles, argon2_params, derive_header_key, iterations, pbkdf2_raw, CancelToken,
    CofferError, Prf,
};
use std::time::{Duration, Instant};

// Expected values: python hashlib.pbkdf2_hmac(<h>, b'password', bytes(range(64)), 1000, 192)
const PY_SHA512: &str = "b1ea1939de322a9e2a7a99c77919ce22aa31a447afcbde93cfd9a8b982122b6a9d28a79437a74e1bc3970b88255a68493579517a4eaa189ab41e9958b45e9d71d661f0f7ebf61d78395b5b6d83b7813382662e725e2d91498e06e0e0ca962cd52d7a69d53584a4a9d8482c216821a8cc223b9ac3370887b6d9776e03219b9784c9e504d48dab0e2faf349cafa45bdeeb5e6d49b4628faff70d0497a64d596adcee8bad8f7ae9b5d58e2712af5e24299298e77ec85f4c357e6a5af82d56f2dde7";
const PY_SHA256: &str = "5f13e6f0ef9102086dea98d9375105b7208b082e6114e628393112b6a37aa4f869e29e57c44cb2598132b5afbbfd2bace028355ff3795ba5177187881536559a43f95f8217c2e1de5c57d53ba2728e3704e6955bb95caab8a16d74bddd76508ff8ee6594e4fe4ba5b6f256bb53b072b33e856acd628c3d8f988102406cf002ea6d47be6b8d0bf80038c3230412b6da9176ddac41727c6e6ea0e9ac1cbdcab1529f9e151314010ed15f311924782c978b7f6739dc68a2223fd3198206d8269b7a";
const PY_BLAKE2S: &str = "b55abe899157975d6fa819bef8e96ecd69b117c3a984486702d54fab054c264f26a3d6dccb0da4685ad552e24d78d433cf1dc902be0a95cb049209bc5776fead162ef3e386af9a83b867e87a9aed361e8a114646c1fbe3f5db59029bdf0b2198a4a44a44a95165cfaeb5781d167310a37477bf4a86ac8a84cc904b36c37ccacb89148e54845cce54a71db642bf3745c2af70691be9fec1de5c0fc69a7f591791b75c40600409c120fb6c48ae7d2e6dd79e54966382bb2bd4bd2d6700049a3fbd";

fn salt() -> Vec<u8> {
    (0u8..64).collect()
}

fn pbkdf2_hex(prf: Prf) -> String {
    let mut out = [0u8; 192];
    pbkdf2_raw(
        prf,
        b"password",
        &salt(),
        1000,
        &mut out,
        &CancelToken::new(),
    )
    .unwrap();
    hex::encode(out)
}

#[test]
fn iterations_default() {
    for prf in Prf::ALL {
        if prf != Prf::Argon2id {
            assert_eq!(iterations(prf, 0), 500_000);
        }
    }
}

#[test]
fn iterations_pim() {
    assert_eq!(iterations(Prf::Sha512, 1), 16_000);
    assert_eq!(iterations(Prf::Sha512, 485), 500_000);
    assert_eq!(iterations(Prf::Streebog, 100), 115_000);
}

#[test]
fn argon2_params_from_pim() {
    let p = |pim| {
        let a = argon2_params(pim);
        (a.m_cost_kib, a.t_cost)
    };
    assert_eq!(p(0), (416 * 1024, 6));
    assert_eq!(p(12), (416 * 1024, 6));
    assert_eq!(p(1), (64 * 1024, 3));
    assert_eq!(p(31), (1024 * 1024, 13));
    assert_eq!(p(32), (1024 * 1024, 14));
    assert_eq!(p(100), (1024 * 1024, 82));
}

#[test]
fn pbkdf2_sha512_matches_python() {
    assert_eq!(pbkdf2_hex(Prf::Sha512), PY_SHA512);
}

#[test]
fn pbkdf2_sha256_matches_python() {
    assert_eq!(pbkdf2_hex(Prf::Sha256), PY_SHA256);
}

#[test]
fn pbkdf2_blake2s_matches_python() {
    assert_eq!(pbkdf2_hex(Prf::Blake2s256), PY_BLAKE2S);
}

#[test]
fn pbkdf2_whirlpool_and_streebog_are_distinct_and_deterministic() {
    let w = pbkdf2_hex(Prf::Whirlpool);
    let s = pbkdf2_hex(Prf::Streebog);
    assert_ne!(w, s);
    assert_eq!(w, pbkdf2_hex(Prf::Whirlpool));
    assert_eq!(s, pbkdf2_hex(Prf::Streebog));
}

#[test]
fn pbkdf2_raw_rejects_argon2() {
    let mut out = [0u8; 192];
    let r = pbkdf2_raw(
        Prf::Argon2id,
        b"p",
        &salt(),
        1,
        &mut out,
        &CancelToken::new(),
    );
    assert!(matches!(r, Err(CofferError::Unsupported(_))));
}

#[test]
fn cancel_stops_kdf() {
    let cancel = CancelToken::new();
    let c2 = cancel.clone();
    let t = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        c2.cancel();
    });
    let start = Instant::now();
    let salt: [u8; 64] = [7; 64];
    // PIM 100000 => 100 015 000 iterations: would take minutes without cancellation.
    let r = derive_header_key(Prf::Sha512, b"pw", &salt, 100_000, &cancel);
    t.join().unwrap();
    assert!(matches!(r, Err(CofferError::Cancelled)));
    assert!(start.elapsed() < Duration::from_secs(1));
}

#[test]
fn argon2_out_of_memory_is_reported() {
    let salt: [u8; 64] = [1; 64];
    let r = coffer_crypto::derive_header_key_with_limit(
        Prf::Argon2id,
        b"pw",
        &salt,
        0,
        &CancelToken::new(),
        64 * 1024 * 1024,
    );
    match r {
        Err(CofferError::KdfOutOfMemory { required_bytes }) => {
            assert_eq!(required_bytes, 416 * 1024 * 1024)
        }
        other => panic!("unexpected: {:?}", other.map(|_| ())),
    }
}

#[test]
fn keyfiles_none_keeps_password() {
    assert_eq!(apply_keyfiles(b"abc", &[]).as_slice(), b"abc");
}

#[test]
fn keyfiles_change_password_and_pad_to_pool() {
    let p = apply_keyfiles(b"abc", &[vec![1, 2, 3]]);
    assert_eq!(p.len(), 64);
    assert_ne!(&p[..3], b"abc");
    // Same keyfile twice != once (pool is additive per keyfile).
    let p2 = apply_keyfiles(b"abc", &[vec![1, 2, 3], vec![1, 2, 3]]);
    assert_ne!(p.as_slice(), p2.as_slice());
}

#[test]
fn keyfiles_only_first_mib_counts() {
    let mut a = vec![0x5au8; 1_048_576];
    let p1 = apply_keyfiles(b"", &[a.clone()]);
    a.extend_from_slice(&[1, 2, 3, 4]);
    let p2 = apply_keyfiles(b"", &[a]);
    assert_eq!(p1.as_slice(), p2.as_slice());
}

#[test]
fn long_password_uses_128_byte_pool() {
    let pw = [b'a'; 100];
    assert_eq!(apply_keyfiles(&pw, &[vec![9u8; 10]]).len(), 128);
    assert_eq!(apply_keyfiles(&[b'a'; 64], &[vec![9u8; 10]]).len(), 64);
}

#[test]
fn keyfile_mixing_is_additive() {
    // One keyfile byte 0x00: CRC register = table[0xFF] ^ 0x00FFFFFF, added to pool[0..4].
    let p0 = apply_keyfiles(b"", &[vec![0u8]]);
    let p1 = apply_keyfiles(&[1u8], &[vec![0u8]]);
    assert_eq!(p1[0], p0[0].wrapping_add(1));
    assert_eq!(&p1[1..], &p0[1..]);
}

#[test]
fn cancel_stops_argon2_promptly() {
    let cancel = CancelToken::new();
    let c2 = cancel.clone();
    let t = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        c2.cancel();
    });
    let start = Instant::now();
    // PIM 40: 1 GiB, 22 passes — many seconds without cancellation.
    let r = derive_header_key(Prf::Argon2id, b"pw", &[3u8; 64], 40, &cancel);
    t.join().unwrap();
    assert!(matches!(r, Err(CofferError::Cancelled)));
    assert!(start.elapsed() < Duration::from_secs(1));
}
