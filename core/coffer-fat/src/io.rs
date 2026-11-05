// SPDX-License-Identifier: GPL-3.0-or-later
//! `Read + Write + Seek` view of a `BlockDevice` for fatfs. Read-only in P2.
use coffer_volume::BlockDevice;
use std::collections::{HashMap, VecDeque};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::sync::{Arc, Mutex};

/// Marker message for a read budget overrun (cyclic or corrupted chains).
pub const BUDGET_EXCEEDED: &str = "Coffer FAT read budget exceeded";

const SECTOR: u64 = 512;
/// Reads up to this size go through the sector cache (FAT entries,
/// directory entries, small clusters).
const SMALL_READ: usize = 4096;

/// FIFO-evicted cache of decrypted 512-byte sectors, shared across the
/// fatfs instances of one `FatFs`. fatfs reads FAT entries 4 bytes at a time
/// and re-walks chains from the start on every seek; without this cache
/// sequential reads are quadratic in device I/O.
pub struct SectorCache {
    map: HashMap<u64, Vec<u8>>,
    order: VecDeque<u64>,
    cap: usize,
}

impl SectorCache {
    pub fn new(cap: usize) -> Self {
        Self {
            map: HashMap::new(),
            order: VecDeque::new(),
            cap,
        }
    }

    fn get(&mut self, dev: &dyn BlockDevice, sector: u64) -> io::Result<&[u8]> {
        if !self.map.contains_key(&sector) {
            let mut b = vec![0u8; SECTOR as usize];
            dev.read_at(sector * SECTOR, &mut b)
                .map_err(|e| io::Error::other(e.to_string()))?;
            if self.map.len() >= self.cap {
                if let Some(old) = self.order.pop_front() {
                    self.map.remove(&old);
                }
            }
            self.map.insert(sector, b);
            self.order.push_back(sector);
        }
        self.map
            .get(&sector)
            .map(Vec::as_slice)
            .ok_or_else(|| io::Error::other("sector cache"))
    }
}

/// Each operation gets a byte budget: fatfs follows cluster chains without
/// cycle detection, so an exhausted budget is how a cyclic chain surfaces.
/// Cache hits are charged too, so the bound holds with the cache.
pub struct VolumeIo {
    dev: Arc<dyn BlockDevice>,
    pos: u64,
    budget: u64,
    cache: Arc<Mutex<SectorCache>>,
}

impl VolumeIo {
    pub fn new(dev: Arc<dyn BlockDevice>, budget: u64, cache: Arc<Mutex<SectorCache>>) -> Self {
        Self {
            dev,
            pos: 0,
            budget,
            cache,
        }
    }
}

impl Read for VolumeIo {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let len = self.dev.len();
        if self.pos >= len || buf.is_empty() {
            return Ok(0);
        }
        // Clamp in u64 before narrowing: usize is 32-bit on armeabi-v7a.
        let n = (buf.len() as u64).min(len - self.pos) as usize;
        if n as u64 > self.budget {
            return Err(io::Error::other(BUDGET_EXCEEDED));
        }
        self.budget -= n as u64;
        let end = self.pos + n as u64;
        if n <= SMALL_READ && end.div_ceil(SECTOR) * SECTOR <= len {
            let mut cache = self.cache.lock().unwrap_or_else(|p| p.into_inner());
            let mut done = 0usize;
            while done < n {
                let p = self.pos + done as u64;
                let s = cache.get(self.dev.as_ref(), p / SECTOR)?;
                let skip = (p % SECTOR) as usize;
                let take = (n - done).min(SECTOR as usize - skip);
                buf[done..done + take].copy_from_slice(&s[skip..skip + take]);
                done += take;
            }
        } else {
            self.dev
                .read_at(self.pos, &mut buf[..n])
                .map_err(|e| io::Error::other(e.to_string()))?;
        }
        self.pos = end;
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
