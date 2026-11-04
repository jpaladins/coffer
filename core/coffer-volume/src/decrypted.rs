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
        })
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
