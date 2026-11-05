// SPDX-License-Identifier: GPL-3.0-or-later
//! Directory entry sets (specification §6, §7.4–7.7).
use crate::time::to_unix;
use coffer_vfs::CofferError;

const ENTRY: usize = 32;
const TYPE_END: u8 = 0x00;
const TYPE_FILE: u8 = 0x85;
const TYPE_STREAM: u8 = 0xC0;
const TYPE_NAME: u8 = 0xC1;
const ATTR_DIRECTORY: u16 = 0x10;

/// Where a directory's entries live (its own stream, or the root chain).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct DirLoc {
    pub first: u32,
    pub contiguous: bool,
    pub len: u64,
    pub is_root: bool,
}

/// A file or directory described by a File + Stream + FileName entry set.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExEntry {
    /// Index of the File entry within its directory, and its SecondaryCount.
    pub slot: usize,
    pub secondary: u8,
    pub attrs: u16,
    /// The File entry as stored (keeps create/access times on rewrite).
    pub file_raw: [u8; 32],
    /// The directory holding this entry (filled in by the caller).
    pub parent: DirLoc,
    pub name: String,
    pub is_dir: bool,
    pub first_cluster: u32,
    pub data_length: u64,
    pub valid_data_length: u64,
    pub no_fat_chain: bool,
    pub modified: Option<i64>,
}

fn corrupted(msg: String) -> CofferError {
    CofferError::Corrupted(format!("exFAT directory: {msg}"))
}

pub(crate) fn set_checksum(set: &[u8]) -> u16 {
    let mut c: u16 = 0;
    for (i, b) in set.iter().enumerate() {
        if i == 2 || i == 3 {
            continue;
        }
        let high: u16 = if c & 1 != 0 { 0x8000 } else { 0 };
        c = high.wrapping_add(c >> 1).wrapping_add(u16::from(*b));
    }
    c
}

fn le16(b: &[u8], o: usize) -> u16 {
    u16::from_le_bytes([b[o], b[o + 1]])
}
fn le32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}
fn le64(b: &[u8], o: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[o..o + 8]);
    u64::from_le_bytes(a)
}

fn parse_set(set: &[u8]) -> Result<ExEntry, CofferError> {
    if le16(set, 2) != set_checksum(set) {
        return Err(corrupted("entry set checksum mismatch".into()));
    }
    let stream = &set[ENTRY..2 * ENTRY];
    if stream[0] != TYPE_STREAM {
        return Err(corrupted("file entry without stream extension".into()));
    }
    let name_len = usize::from(stream[3]);
    let names = &set[2 * ENTRY..];
    if name_len == 0 || names.len() / ENTRY < name_len.div_ceil(15) {
        return Err(corrupted("file name entries missing".into()));
    }
    let mut units = Vec::with_capacity(name_len);
    for e in names.chunks(ENTRY) {
        if e[0] != TYPE_NAME {
            break;
        }
        for j in 0..15 {
            if units.len() < name_len {
                units.push(le16(e, 2 + j * 2));
            }
        }
    }
    if units.len() != name_len {
        return Err(corrupted("file name shorter than NameLength".into()));
    }
    let attrs = le16(set, 4);
    let valid = le64(stream, 8);
    let data = le64(stream, 24);
    if valid > data {
        return Err(corrupted("ValidDataLength > DataLength".into()));
    }
    let mut file_raw = [0u8; 32];
    file_raw.copy_from_slice(&set[..ENTRY]);
    Ok(ExEntry {
        slot: 0,
        secondary: set[1],
        attrs,
        file_raw,
        parent: DirLoc::default(),
        name: String::from_utf16_lossy(&units),
        is_dir: attrs & ATTR_DIRECTORY != 0,
        first_cluster: le32(stream, 20),
        data_length: data,
        valid_data_length: valid,
        no_fat_chain: stream[1] & 0x02 != 0,
        modified: to_unix(le32(set, 12), set[21], set[23]),
    })
}

/// Parses the raw bytes of a directory into its file/directory entries.
pub fn parse_dir(raw: &[u8]) -> Result<Vec<ExEntry>, CofferError> {
    let slots = raw.len() / ENTRY;
    let mut out = Vec::new();
    let mut i = 0;
    while i < slots {
        let e = &raw[i * ENTRY..(i + 1) * ENTRY];
        match e[0] {
            TYPE_END => break,
            TYPE_FILE => {
                let secondary = usize::from(e[1]);
                if !(2..=18).contains(&secondary) || i + secondary >= slots {
                    return Err(corrupted(format!("bad SecondaryCount {secondary}")));
                }
                let mut e = parse_set(&raw[i * ENTRY..(i + 1 + secondary) * ENTRY])?;
                e.slot = i;
                out.push(e);
                i += 1 + secondary;
            }
            // Unused entries, other primaries (bitmap, up-case, label, GUID)
            // and stray secondaries are skipped.
            _ => i += 1,
        }
    }
    Ok(out)
}
