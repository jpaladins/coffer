// SPDX-License-Identifier: GPL-3.0-or-later
//! `Read + Write + Seek` view of a `BlockDevice` for fatfs. Read-only in P2.
use coffer_volume::BlockDevice;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::sync::Arc;

/// Marker message for a read budget overrun (cyclic or corrupted chains).
pub const BUDGET_EXCEEDED: &str = "Coffer FAT read budget exceeded";

/// Each operation gets a byte budget: fatfs follows cluster chains without
/// cycle detection, so an exhausted budget is how a cyclic chain surfaces.
pub struct VolumeIo {
    dev: Arc<dyn BlockDevice>,
    pos: u64,
    budget: u64,
}

impl VolumeIo {
    pub fn new(dev: Arc<dyn BlockDevice>, budget: u64) -> Self {
        Self {
            dev,
            pos: 0,
            budget,
        }
    }
}

impl Read for VolumeIo {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let len = self.dev.len();
        if self.pos >= len || buf.is_empty() {
            return Ok(0);
        }
        let n = buf.len().min((len - self.pos) as usize);
        if n as u64 > self.budget {
            return Err(io::Error::other(BUDGET_EXCEEDED));
        }
        self.budget -= n as u64;
        self.dev
            .read_at(self.pos, &mut buf[..n])
            .map_err(|e| io::Error::other(e.to_string()))?;
        self.pos += n as u64;
        Ok(n)
    }
}

impl Write for VolumeIo {
    fn write(&mut self, _buf: &[u8]) -> io::Result<usize> {
        Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "Coffer FAT is read-only",
        ))
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Seek for VolumeIo {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let new = match pos {
            SeekFrom::Start(p) => Some(p),
            SeekFrom::End(d) => self.dev.len().checked_add_signed(d),
            SeekFrom::Current(d) => self.pos.checked_add_signed(d),
        };
        match new {
            Some(p) => {
                self.pos = p;
                Ok(p)
            }
            None => Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek out of range",
            )),
        }
    }
}
