// SPDX-License-Identifier: GPL-3.0-or-later
//! XTS mode (IEEE 1619) for 128-bit block ciphers, without ciphertext
//! stealing: data lengths are always multiples of 16 bytes.
use crate::CofferError;
use cipher::{consts::U16, Block, BlockCipherDecrypt, BlockCipherEncrypt, BlockSizeUser, KeyInit};

pub const DATA_UNIT: usize = 512;
const BLOCK: usize = 16;
const BLOCKS_PER_UNIT: usize = DATA_UNIT / BLOCK;

/// One XTS instance: `k1` encrypts data, `k2` encrypts the tweak.
pub struct Xts<C> {
    k1: C,
    k2: C,
}

impl<C> Xts<C>
where
    C: BlockCipherEncrypt + BlockCipherDecrypt + KeyInit + BlockSizeUser<BlockSize = U16>,
{
    pub fn new(k1: &[u8], k2: &[u8]) -> Result<Self, CofferError> {
        let bad = |_| CofferError::Unsupported("invalid cipher key length".into());
        Ok(Self {
            k1: C::new_from_slice(k1).map_err(bad)?,
            k2: C::new_from_slice(k2).map_err(bad)?,
        })
    }

    fn first_tweak(&self, unit: u64) -> [u8; BLOCK] {
        let mut t = Block::<C>::default();
        t[..8].copy_from_slice(&unit.to_le_bytes());
        self.k2.encrypt_block(&mut t);
        let mut out = [0u8; BLOCK];
        out.copy_from_slice(&t);
        out
    }

    /// Encrypts one data unit (`data.len()` a multiple of 16, at most 512).
    pub fn encrypt_unit(&self, data: &mut [u8], unit: u64) {
        self.process(data, unit, true);
    }

    pub fn decrypt_unit(&self, data: &mut [u8], unit: u64) {
        self.process(data, unit, false);
    }

    fn process(&self, data: &mut [u8], unit: u64, encrypt: bool) {
        debug_assert!(data.len().is_multiple_of(BLOCK) && data.len() <= DATA_UNIT);
        let n = data.len() / BLOCK;
        let mut tweaks = [[0u8; BLOCK]; BLOCKS_PER_UNIT];
        let mut blocks: [Block<C>; BLOCKS_PER_UNIT] = Default::default();
        let mut t = self.first_tweak(unit);
        for i in 0..n {
            tweaks[i] = t;
            let src = &data[i * BLOCK..(i + 1) * BLOCK];
            for j in 0..BLOCK {
                blocks[i][j] = src[j] ^ t[j];
            }
            mul_alpha(&mut t);
        }
        if encrypt {
            self.k1.encrypt_blocks(&mut blocks[..n]);
        } else {
            self.k1.decrypt_blocks(&mut blocks[..n]);
        }
        for i in 0..n {
            let dst = &mut data[i * BLOCK..(i + 1) * BLOCK];
            for j in 0..BLOCK {
                dst[j] = blocks[i][j] ^ tweaks[i][j];
            }
        }
        tweaks.iter_mut().for_each(|t| *t = [0; BLOCK]);
        blocks.iter_mut().for_each(|b| *b = Block::<C>::default());
    }
}

/// Multiplies the tweak by alpha in GF(2^128) (little-endian convention).
fn mul_alpha(t: &mut [u8; BLOCK]) {
    let mut carry = 0u8;
    for b in t.iter_mut() {
        let next = *b >> 7;
        *b = (*b << 1) | carry;
        carry = next;
    }
    if carry != 0 {
        t[0] ^= 0x87;
    }
}
