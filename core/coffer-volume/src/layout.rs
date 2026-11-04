// SPDX-License-Identifier: GPL-3.0-or-later
//! Container layout (format-notes.md, "Layout del container").

/// Each header area (standard, hidden, and their backups) is 64 KiB.
pub const HEADER_AREA: u64 = 65_536;
pub const STD_HEADER: u64 = 0;
pub const HIDDEN_HEADER: u64 = 65_536;
/// Start of the data area of a standard volume.
pub const DATA_START: u64 = 131_072;
/// Smallest container that can hold headers and backup headers.
pub const MIN_CONTAINER: u64 = 2 * DATA_START;
pub const SECTOR: u64 = 512;

pub fn backup_std_header(len: u64) -> u64 {
    len - DATA_START
}

pub fn backup_hidden_header(len: u64) -> u64 {
    len - HEADER_AREA
}

/// XTS data unit number of the volume sector at host byte offset `host_off`.
pub fn data_unit(host_off: u64) -> u64 {
    host_off / SECTOR
}
