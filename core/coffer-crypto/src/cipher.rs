// SPDX-License-Identifier: GPL-3.0-or-later
//! VeraCrypt encryption algorithms and cascades (see format-notes.md, "Cascate").
use crate::xts::{Xts, DATA_UNIT};
use crate::CofferError;
use rayon::prelude::*;

/// A single cipher or a cascade, named as VeraCrypt names it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CipherSuite {
    Aes,
    Serpent,
    Twofish,
    Camellia,
    Kuznyechik,
    AesTwofish,
    AesTwofishSerpent,
    CamelliaKuznyechik,
    CamelliaSerpent,
    KuznyechikAes,
    KuznyechikSerpentCamellia,
    KuznyechikTwofish,
    SerpentAes,
    SerpentTwofishAes,
    TwofishSerpent,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Algo {
    Aes,
    Serpent,
    Twofish,
    Camellia,
    Kuznyechik,
}

impl CipherSuite {
    /// Search order when auto-detecting: AES first (default and most common).
    pub const ALL: [CipherSuite; 15] = [
        CipherSuite::Aes,
        CipherSuite::Serpent,
        CipherSuite::Twofish,
        CipherSuite::Camellia,
        CipherSuite::Kuznyechik,
        CipherSuite::AesTwofish,
        CipherSuite::AesTwofishSerpent,
        CipherSuite::CamelliaKuznyechik,
        CipherSuite::CamelliaSerpent,
        CipherSuite::KuznyechikAes,
        CipherSuite::KuznyechikSerpentCamellia,
        CipherSuite::KuznyechikTwofish,
        CipherSuite::SerpentAes,
        CipherSuite::SerpentTwofishAes,
        CipherSuite::TwofishSerpent,
    ];

    pub fn name(self) -> &'static str {
        use CipherSuite::*;
        match self {
            Aes => "AES",
            Serpent => "Serpent",
            Twofish => "Twofish",
            Camellia => "Camellia",
            Kuznyechik => "Kuznyechik",
            AesTwofish => "AES-Twofish",
            AesTwofishSerpent => "AES-Twofish-Serpent",
            CamelliaKuznyechik => "Camellia-Kuznyechik",
            CamelliaSerpent => "Camellia-Serpent",
            KuznyechikAes => "Kuznyechik-AES",
            KuznyechikSerpentCamellia => "Kuznyechik-Serpent-Camellia",
            KuznyechikTwofish => "Kuznyechik-Twofish",
            SerpentAes => "Serpent-AES",
            SerpentTwofishAes => "Serpent-Twofish-AES",
            TwofishSerpent => "Twofish-Serpent",
        }
    }

    pub fn from_name(name: &str) -> Option<CipherSuite> {
        CipherSuite::ALL
            .into_iter()
            .find(|s| s.name().eq_ignore_ascii_case(name))
    }

    /// Algorithms in the order they are applied when encrypting. VeraCrypt
    /// names cascades outermost-first, so this is the reverse of the name.
    pub fn encryption_order(self) -> Vec<Algo> {
        let mut v: Vec<Algo> = self
            .name()
            .split('-')
            .filter_map(|n| match n {
                "AES" => Some(Algo::Aes),
                "Serpent" => Some(Algo::Serpent),
                "Twofish" => Some(Algo::Twofish),
                "Camellia" => Some(Algo::Camellia),
                "Kuznyechik" => Some(Algo::Kuznyechik),
                _ => None,
            })
            .collect();
        v.reverse();
        v
    }

    /// Key material needed: 32-byte primary + 32-byte secondary key per cipher.
    pub fn key_len(self) -> usize {
        64 * self.encryption_order().len()
    }
}

enum Layer {
    Aes(Box<Xts<aes::Aes256>>),
    Serpent(Box<Xts<serpent::Serpent>>),
    Twofish(Box<Xts<twofish::Twofish>>),
    Camellia(Box<Xts<camellia::Camellia256>>),
    Kuznyechik(Box<Xts<kuznyechik::Kuznyechik>>),
}

impl Layer {
    fn new(a: Algo, k1: &[u8], k2: &[u8]) -> Result<Self, CofferError> {
        Ok(match a {
            Algo::Aes => Layer::Aes(Box::new(Xts::new(k1, k2)?)),
            Algo::Serpent => Layer::Serpent(Box::new(Xts::new(k1, k2)?)),
            Algo::Twofish => Layer::Twofish(Box::new(Xts::new(k1, k2)?)),
            Algo::Camellia => Layer::Camellia(Box::new(Xts::new(k1, k2)?)),
            Algo::Kuznyechik => Layer::Kuznyechik(Box::new(Xts::new(k1, k2)?)),
        })
    }

    fn unit(&self, data: &mut [u8], unit: u64, encrypt: bool) {
        macro_rules! go {
            ($x:expr) => {
                if encrypt {
                    $x.encrypt_unit(data, unit)
                } else {
                    $x.decrypt_unit(data, unit)
                }
            };
        }
        match self {
            Layer::Aes(x) => go!(x),
            Layer::Serpent(x) => go!(x),
            Layer::Twofish(x) => go!(x),
            Layer::Camellia(x) => go!(x),
            Layer::Kuznyechik(x) => go!(x),
        }
    }
}

/// XTS over a cipher cascade. Cipher keys are zeroized on drop by the cipher
/// crates (`zeroize` feature).
pub struct XtsCascade {
    suite: CipherSuite,
    layers: Vec<Layer>,
}

const PARALLEL_THRESHOLD_UNITS: usize = 64;

impl XtsCascade {
    /// `key` = n primary keys (32 bytes each, in encryption order) followed by
    /// n secondary keys, as in format-notes.md.
    pub fn new(suite: CipherSuite, key: &[u8]) -> Result<Self, CofferError> {
        if key.len() != suite.key_len() {
            return Err(CofferError::Unsupported(format!(
                "{} needs {} key bytes, got {}",
                suite.name(),
                suite.key_len(),
                key.len()
            )));
        }
        let order = suite.encryption_order();
        let n = order.len();
        let layers = order
            .iter()
            .enumerate()
            .map(|(i, a)| {
                Layer::new(
                    *a,
                    &key[32 * i..32 * (i + 1)],
                    &key[32 * (n + i)..32 * (n + i + 1)],
                )
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(Self { suite, layers })
    }

    pub fn suite(&self) -> CipherSuite {
        self.suite
    }

    /// Encrypts consecutive 512-byte data units starting at `first_unit`.
    /// `data.len()` must be a multiple of 16; a trailing partial unit is
    /// processed as a short unit (used for the 448-byte header area).
    pub fn encrypt_units(&self, data: &mut [u8], first_unit: u64) {
        self.run(data, first_unit, true)
    }

    pub fn decrypt_units(&self, data: &mut [u8], first_unit: u64) {
        self.run(data, first_unit, false)
    }

    fn run(&self, data: &mut [u8], first_unit: u64, encrypt: bool) {
        let one = |(i, chunk): (usize, &mut [u8])| {
            let u = first_unit + i as u64;
            if encrypt {
                self.layers.iter().for_each(|l| l.unit(chunk, u, true));
            } else {
                self.layers
                    .iter()
                    .rev()
                    .for_each(|l| l.unit(chunk, u, false));
            }
        };
        if data.len() / DATA_UNIT >= PARALLEL_THRESHOLD_UNITS {
            data.par_chunks_mut(DATA_UNIT).enumerate().for_each(one);
        } else {
            data.chunks_mut(DATA_UNIT).enumerate().for_each(one);
        }
    }
}
