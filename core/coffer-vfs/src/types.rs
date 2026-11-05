// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::CofferError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FsKind {
    Fat12,
    Fat16,
    Fat32,
    ExFat,
    Ntfs,
    Unknown,
}

impl FsKind {
    pub fn name(self) -> &'static str {
        match self {
            FsKind::Fat12 => "FAT12",
            FsKind::Fat16 => "FAT16",
            FsKind::Fat32 => "FAT32",
            FsKind::ExFat => "exFAT",
            FsKind::Ntfs => "NTFS",
            FsKind::Unknown => "unknown",
        }
    }
}

/// One file or directory.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    /// Last modification time, seconds since the Unix epoch (UTC). FAT stores
    /// local time without a zone; it is reported as if it were UTC.
    pub modified: Option<i64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FsStats {
    pub total_bytes: u64,
    pub free_bytes: Option<u64>,
    pub cluster_size: u32,
}

/// Read access to a filesystem inside an opened volume. Paths are absolute,
/// `/`-separated, UTF-8; name comparison is case-insensitive.
pub trait Filesystem: Send + Sync {
    fn kind(&self) -> FsKind;
    fn stat(&self, path: &str) -> Result<DirEntry, CofferError>;
    fn list(&self, path: &str) -> Result<Vec<DirEntry>, CofferError>;
    /// Reads up to `buf.len()` bytes at `offset`; returns the number of bytes
    /// read (0 at or beyond end of file).
    fn read(&self, path: &str, offset: u64, buf: &mut [u8]) -> Result<usize, CofferError>;
    fn stats(&self) -> Result<FsStats, CofferError>;
}
