// SPDX-License-Identifier: GPL-3.0-or-later
use crate::device::{check_range, BlockDevice};
use crate::layout::{data_unit, SECTOR};
use coffer_crypto::{CofferError, VolumeHeader, XtsCascade};
use std::sync::Arc;
use zeroize::Zeroizing;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VolumeKind {
    Standard,
    Hidden,
}

/// An opened volume: offset 0 is the first plaintext byte of the data area.
pub struct OpenedVolume {
    pub kind: VolumeKind,
    pub used_backup_header: bool,
    pub header: VolumeHeader,
    dev: Arc<dyn BlockDevice>,
    cascade: XtsCascade,
    data_start: u64,
    len: u64,
    /// Hidden-volume area `[start, end)` in this volume's coordinates that
    /// writes must not touch (hidden volume protection).
    protected: Option<(u64, u64)>,
}

/// Bytes processed per I/O round (bounds memory for large reads/writes).
const CHUNK: u64 = 1024 * 1024;

impl OpenedVolume {
    pub(crate) fn new(
        kind: VolumeKind,
        used_backup_header: bool,
        header: VolumeHeader,
        dev: Arc<dyn BlockDevice>,
    ) -> Result<Self, CofferError> {
        let suite = header.suite;
        let cascade = XtsCascade::new(suite, &header.master_key[..suite.key_len()])?;
        let data_start = header.encrypted_area_start;
        let len = header.volume_size;
        Ok(Self {
            kind,
            used_backup_header,
            header,
            dev,
            cascade,
            data_start,
            len,
            protected: None,
        })
    }

    /// Protects the hidden volume inside this (outer) volume: from now on,
    /// writes that would touch its area fail with `HiddenVolumeProtected`.
    pub fn protect_hidden_volume(
        &mut self,
        hidden_creds: &coffer_crypto::Credentials,
        cancel: &coffer_crypto::CancelToken,
    ) -> Result<(), CofferError> {
        use crate::layout::{backup_hidden_header, HIDDEN_HEADER};
        if self.kind != VolumeKind::Standard {
            return Err(CofferError::Unsupported(
                "hidden volume protection applies to the outer volume".into(),
            ));
        }
        let mut last = CofferError::WrongCredentials;
        for off in [HIDDEN_HEADER, backup_hidden_header(self.dev.len())] {
            let mut raw = [0u8; coffer_crypto::HEADER_LEN];
            self.dev.read_at(off, &mut raw)?;
            match coffer_crypto::decrypt_header(&raw, hidden_creds, cancel) {
                Ok(h) => {
                    let start = h
                        .encrypted_area_start
                        .checked_sub(self.data_start)
                        .ok_or_else(|| {
                            CofferError::Corrupted("hidden volume before outer data".into())
                        })?;
                    let end = start
                        .checked_add(h.volume_size)
                        .filter(|e| *e <= self.len)
                        .ok_or_else(|| {
                            CofferError::Corrupted("hidden volume outside outer volume".into())
                        })?;
                    self.protected = Some((start, end));
                    return Ok(());
                }
                Err(CofferError::WrongCredentials) => last = CofferError::WrongCredentials,
                Err(e) => return Err(e),
            }
        }
        Err(last)
    }

    pub fn is_hidden_protected(&self) -> bool {
        self.protected.is_some()
    }

    /// Reads whole sectors `[first, first + count)` and decrypts them.
    fn read_sectors(&self, first: u64, buf: &mut [u8]) -> Result<(), CofferError> {
        let host = self.data_start + first * SECTOR;
        self.dev.read_at(host, buf)?;
        self.cascade.decrypt_units(buf, data_unit(host));
        Ok(())
    }

    fn write_sectors(&self, first: u64, buf: &mut [u8]) -> Result<(), CofferError> {
        let host = self.data_start + first * SECTOR;
        self.cascade.encrypt_units(buf, data_unit(host));
        self.dev.write_at(host, buf)
    }

    /// Sector-aligned window covering `[off, off + n)`, clipped to `CHUNK`.
    fn window(off: u64, n: usize) -> (u64, u64, usize) {
        let first = off / SECTOR;
        let skip = (off % SECTOR) as usize;
        let take = n.min((CHUNK as usize) - skip);
        let end = off + take as u64;
        let sectors = end.div_ceil(SECTOR) - first;
        (first, sectors, take)
    }
}

impl BlockDevice for OpenedVolume {
    fn read_at(&self, mut off: u64, mut buf: &mut [u8]) -> Result<(), CofferError> {
        check_range(off, buf.len(), self.len)?;
        while !buf.is_empty() {
            let (first, sectors, take) = Self::window(off, buf.len());
            let mut tmp = Zeroizing::new(vec![0u8; (sectors * SECTOR) as usize]);
            self.read_sectors(first, &mut tmp)?;
            let skip = (off % SECTOR) as usize;
            buf[..take].copy_from_slice(&tmp[skip..skip + take]);
            buf = &mut buf[take..];
            off += take as u64;
        }
        Ok(())
    }

    fn write_at(&self, mut off: u64, mut buf: &[u8]) -> Result<(), CofferError> {
        check_range(off, buf.len(), self.len)?;
        if let Some((start, end)) = self.protected {
            // Checked before any byte is written: the whole write is refused.
            if off < end && off + buf.len() as u64 > start {
                return Err(CofferError::HiddenVolumeProtected);
            }
        }
        while !buf.is_empty() {
            let (first, sectors, take) = Self::window(off, buf.len());
            let mut tmp = Zeroizing::new(vec![0u8; (sectors * SECTOR) as usize]);
            let skip = (off % SECTOR) as usize;
            if skip != 0 || !take.is_multiple_of(SECTOR as usize) {
                self.read_sectors(first, &mut tmp)?; // read-modify-write
            }
            tmp[skip..skip + take].copy_from_slice(&buf[..take]);
            self.write_sectors(first, &mut tmp)?;
            buf = &buf[take..];
            off += take as u64;
        }
        Ok(())
    }

    fn len(&self) -> u64 {
        self.len
    }

    fn flush(&self) -> Result<(), CofferError> {
        self.dev.flush()
    }
}
