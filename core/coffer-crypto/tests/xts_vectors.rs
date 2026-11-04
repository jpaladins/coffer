// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::{CipherSuite, Xts, XtsCascade};

#[test]
fn ieee1619_vector1_aes128() {
    // IEEE 1619-2007 XTS-AES-128 vector 1: keys 0, unit 0, 32 zero bytes.
    let xts = Xts::<aes::Aes128>::new(&[0u8; 16], &[0u8; 16]).unwrap();
    let mut data = [0u8; 32];
    xts.encrypt_unit(&mut data, 0);
    assert_eq!(
        hex::encode(data),
        "917cf69ebd68b2ec9b9fe9a3eadda692cd43d2f59598ed858c02c2652fbf922e"
    );
    xts.decrypt_unit(&mut data, 0);
    assert_eq!(data, [0u8; 32]);
}

fn pseudo_random(len: usize, seed: u8) -> Vec<u8> {
    let mut x: u32 = 0x9e37_79b9 ^ u32::from(seed);
    (0..len)
        .map(|_| {
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            (x >> 24) as u8
        })
        .collect()
}

#[test]
fn suite_names_and_key_lengths() {
    assert_eq!(CipherSuite::ALL.len(), 15);
    assert_eq!(CipherSuite::Aes.key_len(), 64);
    assert_eq!(CipherSuite::AesTwofish.key_len(), 128);
    assert_eq!(CipherSuite::AesTwofishSerpent.key_len(), 192);
    assert_eq!(CipherSuite::AesTwofishSerpent.name(), "AES-Twofish-Serpent");
    assert_eq!(
        CipherSuite::KuznyechikSerpentCamellia.name(),
        "Kuznyechik-Serpent-Camellia"
    );
    for s in CipherSuite::ALL {
        assert_eq!(CipherSuite::from_name(s.name()), Some(s));
    }
}

#[test]
fn roundtrip_all_suites() {
    for (i, suite) in CipherSuite::ALL.into_iter().enumerate() {
        let key = pseudo_random(suite.key_len(), i as u8);
        let c = XtsCascade::new(suite, &key).unwrap();
        let plain = pseudo_random(4096 + 448, 99);
        let mut data = plain.clone();
        c.encrypt_units(&mut data, 256);
        assert_ne!(data, plain, "{} did not encrypt", suite.name());
        c.decrypt_units(&mut data, 256);
        assert_eq!(data, plain, "{} roundtrip failed", suite.name());
    }
}

#[test]
fn unit_number_matters() {
    let key = pseudo_random(64, 1);
    let c = XtsCascade::new(CipherSuite::Aes, &key).unwrap();
    let mut a = vec![0u8; 512];
    let mut b = vec![0u8; 512];
    c.encrypt_units(&mut a, 256);
    c.encrypt_units(&mut b, 257);
    assert_ne!(a, b);
}

#[test]
fn units_are_independent_of_batching() {
    // Encrypting 3 units at once == encrypting them one by one.
    let key = pseudo_random(192, 3);
    let c = XtsCascade::new(CipherSuite::SerpentTwofishAes, &key).unwrap();
    let plain = pseudo_random(1536, 5);
    let mut all = plain.clone();
    c.encrypt_units(&mut all, 1000);
    let mut parts = plain.clone();
    for (i, chunk) in parts.chunks_mut(512).enumerate() {
        c.encrypt_units(chunk, 1000 + i as u64);
    }
    assert_eq!(all, parts);
}

#[test]
fn cascade_differs_from_single_cipher() {
    let key = pseudo_random(128, 7);
    let single = XtsCascade::new(CipherSuite::Twofish, &key[..64]).unwrap();
    let cascade = XtsCascade::new(CipherSuite::AesTwofish, &key).unwrap();
    let mut a = vec![0u8; 512];
    let mut b = vec![0u8; 512];
    single.encrypt_units(&mut a, 0);
    cascade.encrypt_units(&mut b, 0);
    assert_ne!(a, b);
}

#[test]
fn wrong_key_length_rejected() {
    assert!(XtsCascade::new(CipherSuite::Aes, &[0u8; 32]).is_err());
}

#[test]
fn all_cipher_key_schedules_zeroize_on_drop() {
    fn assert_zod<T: zeroize::ZeroizeOnDrop>() {}
    assert_zod::<aes::Aes256>();
    assert_zod::<serpent::Serpent>();
    assert_zod::<twofish::Twofish>();
    assert_zod::<camellia::Camellia256>();
    assert_zod::<kuznyechik::Kuznyechik>();
}
