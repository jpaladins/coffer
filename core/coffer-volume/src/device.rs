// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::CofferError;
use std::fs::{File, OpenOptions};
use std::path::Path;
use std::sync::Mutex;

/// Random-access byte device (a container file, or a decrypted volume).
pub trait BlockDevice: Send + Sync {
    fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<(), CofferError>;
    fn write_at(&self, off: u64, buf: &[u8]) -> Result<(), CofferError>;
    fn len(&self) -> u64;
    fn flush(&self) -> Result<(), CofferError>;
    fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

pub(crate) fn check_range(off: u64, n: usize, len: u64) -> Result<(), CofferError> {
    match off.checked_add(n as u64) {
        Some(end) if end <= len => Ok(()),
        _ => Err(CofferError::Io(format!(
            "access out of bounds: offset {off}, {n} bytes, device {len} bytes"
        ))),
    }
}

/// Container file accessed with positional I/O (no shared cursor).
pub struct FileDevice {
    file: File,
    len: u64,
    writable: bool,
}

impl FileDevice {
    pub fn open(path: impl AsRef<Path>, writable: bool) -> Result<Self, CofferError> {
        let file = OpenOptions::new().read(true).write(writable).open(path)?;
        Self::from_file(file, writable)
    }

    /// Wraps an already-open file (on Android: from the SAF file descriptor).
    pub fn from_file(file: File, writable: bool) -> Result<Self, CofferError> {
        let len = file.metadata()?.len();
        Ok(Self {
            file,
            len,
            writable,
        })
    }

    pub fn is_writable(&self) -> bool {
        self.writable
    }
}

#[cfg(unix)]
fn pread(f: &File, buf: &mut [u8], off: u64) -> std::io::Result<()> {
    std::os::unix::fs::FileExt::read_exact_at(f, buf, off)
}
#[cfg(unix)]
fn pwrite(f: &File, buf: &[u8], off: u64) -> std::io::Result<()> {
    std::os::unix::fs::FileExt::write_all_at(f, buf, off)
}
#[cfg(windows)]
fn pread(f: &File, mut buf: &mut [u8], mut off: u64) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        let n = f.seek_read(buf, off)?;
        if n == 0 {
            return Err(std::io::ErrorKind::UnexpectedEof.into());
        }
        buf = &mut buf[n..];
        off += n as u64;
    }
    Ok(())
}
#[cfg(windows)]
fn pwrite(f: &File, mut buf: &[u8], mut off: u64) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        let n = f.seek_write(buf, off)?;
        if n == 0 {
            return Err(std::io::ErrorKind::WriteZero.into());
        }
        buf = &buf[n..];
        off += n as u64;
    }
    Ok(())
}

impl BlockDevice for FileDevice {
    fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<(), CofferError> {
        check_range(off, buf.len(), self.len)?;
        Ok(pread(&self.file, buf, off)?)
    }

    fn write_at(&self, off: u64, buf: &[u8]) -> Result<(), CofferError> {
        if !self.writable {
            return Err(CofferError::Io("device is read-only".into()));
        }
        check_range(off, buf.len(), self.len)?;
        Ok(pwrite(&self.file, buf, off)?)
    }

    fn len(&self) -> u64 {
        self.len
    }

    fn flush(&self) -> Result<(), CofferError> {
        if self.writable {
            self.file.sync_data()?;
        }
        Ok(())
    }
}

/// In-memory device, for tests and small scratch volumes.
pub struct MemDevice {
    data: Mutex<Vec<u8>>,
}

impl MemDevice {
    pub fn new(data: Vec<u8>) -> Self {
        Self {
            data: Mutex::new(data),
        }
    }

    pub fn into_inner(self) -> Vec<u8> {
        self.data.into_inner().unwrap_or_else(|e| e.into_inner())
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<u8>> {
        self.data.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl BlockDevice for MemDevice {
    fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<(), CofferError> {
        let d = self.lock();
        check_range(off, buf.len(), d.len() as u64)?;
        buf.copy_from_slice(&d[off as usize..off as usize + buf.len()]);
        Ok(())
    }

    fn write_at(&self, off: u64, buf: &[u8]) -> Result<(), CofferError> {
        let mut d = self.lock();
        check_range(off, buf.len(), d.len() as u64)?;
        d[off as usize..off as usize + buf.len()].copy_from_slice(buf);
        Ok(())
    }

    fn len(&self) -> u64 {
        self.lock().len() as u64
    }

    fn flush(&self) -> Result<(), CofferError> {
        Ok(())
    }
}
