// SPDX-License-Identifier: GPL-3.0-or-later
//! Test-only exFAT image builder, written from the Microsoft exFAT
//! specification. 512-byte sectors, one FAT.
#![allow(dead_code, clippy::too_many_arguments)]

pub const BPS: usize = 512;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Layout {
    /// Consecutive clusters, NoFatChain set (FAT entries left at 0).
    Contiguous,
    /// Consecutive clusters described by the FAT.
    FatChain,
    /// Clusters with a gap between each, described by the FAT.
    Fragmented,
}

pub struct Dir {
    pub clusters: Vec<u32>,
    pub used_slots: usize,
}

pub struct Img {
    pub bytes: Vec<u8>,
    pub spc_shift: u8,
    pub cluster_size: usize,
    pub fat_offset: usize,  // sectors
    pub heap_offset: usize, // sectors
    pub cluster_count: u32,
    pub next_free: u32,
    pub dirs: Vec<Dir>,
}

pub const ROOT: usize = 0;

/// Packs a timestamp as exFAT stores it (local time fields).
pub fn timestamp(y: u32, mo: u32, d: u32, h: u32, mi: u32, s: u32) -> u32 {
    ((y - 1980) << 25) | (mo << 21) | (d << 16) | (h << 11) | (mi << 5) | (s / 2)
}

fn boot_checksum(region: &[u8]) -> u32 {
    let mut c: u32 = 0;
    for (i, b) in region[..11 * BPS].iter().enumerate() {
        if i == 106 || i == 107 || i == 112 {
            continue;
        }
        let h: u32 = if c & 1 != 0 { 0x8000_0000 } else { 0 };
        c = h.wrapping_add(c >> 1).wrapping_add(u32::from(*b));
    }
    c
}

pub fn entry_set_checksum(set: &[u8]) -> u16 {
    let mut c: u16 = 0;
    for (i, b) in set.iter().enumerate() {
        if i == 2 || i == 3 {
            continue;
        }
        let h: u16 = if c & 1 != 0 { 0x8000 } else { 0 };
        c = h.wrapping_add(c >> 1).wrapping_add(u16::from(*b));
    }
    c
}

impl Img {
    pub fn new(cluster_count: u32, spc_shift: u8) -> Img {
        let cluster_size = BPS << spc_shift;
        let fat_offset = 24;
        let fat_length = ((cluster_count as usize + 2) * 4).div_ceil(BPS);
        let heap_offset = (fat_offset + fat_length).div_ceil(8) * 8;
        let total_sectors = heap_offset + cluster_count as usize * (cluster_size / BPS);
        let mut img = Img {
            bytes: vec![0u8; total_sectors * BPS],
            spc_shift,
            cluster_size,
            fat_offset,
            heap_offset,
            cluster_count,
            next_free: 2,
            dirs: Vec::new(),
        };
        let b = &mut img.bytes;
        b[0..3].copy_from_slice(&[0xEB, 0x76, 0x90]);
        b[3..11].copy_from_slice(b"EXFAT   ");
        b[72..80].copy_from_slice(&(total_sectors as u64).to_le_bytes());
        b[80..84].copy_from_slice(&(fat_offset as u32).to_le_bytes());
        b[84..88].copy_from_slice(&(fat_length as u32).to_le_bytes());
        b[88..92].copy_from_slice(&(heap_offset as u32).to_le_bytes());
        b[92..96].copy_from_slice(&cluster_count.to_le_bytes());
        b[100..104].copy_from_slice(&0x1234_5678u32.to_le_bytes());
        b[104..106].copy_from_slice(&0x0100u16.to_le_bytes());
        b[108] = 9;
        b[109] = spc_shift;
        b[110] = 1;
        b[111] = 0x80;
        b[112] = 0xFF;
        b[510] = 0x55;
        b[511] = 0xAA;
        for s in 1..=8 {
            let o = s * BPS + BPS - 4;
            b[o..o + 4].copy_from_slice(&0xAA55_0000u32.to_le_bytes());
        }
        // FAT media and reserved entries.
        img.set_fat(0, 0xFFFF_FFF8);
        img.set_fat(1, 0xFFFF_FFFF);
        // Bitmap (cluster 2), up-case table (cluster 3), root (cluster 4).
        let bitmap = img.alloc(Layout::FatChain, 1)[0];
        let upcase = img.alloc(Layout::FatChain, 1)[0];
        let root = img.alloc(Layout::FatChain, 1)[0];
        img.bytes[96..100].copy_from_slice(&root.to_le_bytes());
        img.dirs.push(Dir {
            clusters: vec![root],
            used_slots: 0,
        });
        let mut e = [0u8; 32];
        e[0] = 0x81;
        e[20..24].copy_from_slice(&bitmap.to_le_bytes());
        e[24..32].copy_from_slice(&u64::from(cluster_count.div_ceil(8)).to_le_bytes());
        img.push_slot(ROOT, &e);
        let mut e = [0u8; 32];
        e[0] = 0x82;
        e[20..24].copy_from_slice(&upcase.to_le_bytes());
        e[24..32].copy_from_slice(&128u64.to_le_bytes());
        img.push_slot(ROOT, &e);
        img.seal_boot();
        img
    }

    /// Recomputes the boot checksum sector and the backup boot region.
    pub fn seal_boot(&mut self) {
        let c = boot_checksum(&self.bytes);
        for i in 0..BPS / 4 {
            let o = 11 * BPS + i * 4;
            self.bytes[o..o + 4].copy_from_slice(&c.to_le_bytes());
        }
        let (main, rest) = self.bytes.split_at_mut(12 * BPS);
        rest[..12 * BPS].copy_from_slice(main);
    }

    pub fn set_fat(&mut self, cluster: u32, value: u32) {
        let o = self.fat_offset * BPS + cluster as usize * 4;
        self.bytes[o..o + 4].copy_from_slice(&value.to_le_bytes());
    }

    pub fn cluster_offset(&self, c: u32) -> usize {
        self.heap_offset * BPS + (c as usize - 2) * self.cluster_size
    }

    /// Allocates `n` clusters with the given layout; returns them in order.
    pub fn alloc(&mut self, layout: Layout, n: usize) -> Vec<u32> {
        let step = if layout == Layout::Fragmented { 2 } else { 1 };
        let v: Vec<u32> = (0..n).map(|i| self.next_free + (i * step) as u32).collect();
        self.next_free += (n * step) as u32;
        if layout != Layout::Contiguous {
            for w in v.windows(2) {
                self.set_fat(w[0], w[1]);
            }
            if let Some(last) = v.last() {
                self.set_fat(*last, 0xFFFF_FFFF);
            }
        }
        v
    }

    pub fn write_data(&mut self, clusters: &[u32], data: &[u8]) {
        for (i, chunk) in data.chunks(self.cluster_size).enumerate() {
            let o = self.cluster_offset(clusters[i]);
            self.bytes[o..o + chunk.len()].copy_from_slice(chunk);
        }
    }

    fn push_slot(&mut self, dir: usize, entry: &[u8; 32]) {
        let slot = self.dirs[dir].used_slots;
        let per = self.cluster_size / 32;
        let c = self.dirs[dir].clusters[slot / per];
        let o = self.cluster_offset(c) + (slot % per) * 32;
        self.bytes[o..o + 32].copy_from_slice(entry);
        self.dirs[dir].used_slots += 1;
    }

    /// Builds a File + Stream + Name entry set (raw bytes, checksum set).
    pub fn entry_set(
        name: &str,
        attrs: u16,
        first_cluster: u32,
        data_len: u64,
        valid_len: u64,
        no_fat_chain: bool,
        mtime: u32,
        utc_offset: u8,
    ) -> Vec<u8> {
        let units: Vec<u16> = name.encode_utf16().collect();
        let name_entries = units.len().div_ceil(15);
        let mut set = vec![0u8; 32 * (2 + name_entries)];
        set[0] = 0x85;
        set[1] = (1 + name_entries) as u8;
        set[4..6].copy_from_slice(&attrs.to_le_bytes());
        set[8..12].copy_from_slice(&mtime.to_le_bytes());
        set[12..16].copy_from_slice(&mtime.to_le_bytes());
        set[16..20].copy_from_slice(&mtime.to_le_bytes());
        set[23] = utc_offset;
        let s = &mut set[32..64];
        s[0] = 0xC0;
        s[1] = u8::from(first_cluster != 0) | if no_fat_chain { 2 } else { 0 };
        s[3] = units.len() as u8;
        s[8..16].copy_from_slice(&valid_len.to_le_bytes());
        s[20..24].copy_from_slice(&first_cluster.to_le_bytes());
        s[24..32].copy_from_slice(&data_len.to_le_bytes());
        for (i, chunk) in units.chunks(15).enumerate() {
            let e = &mut set[64 + i * 32..96 + i * 32];
            e[0] = 0xC1;
            for (j, u) in chunk.iter().enumerate() {
                e[2 + j * 2..4 + j * 2].copy_from_slice(&u.to_le_bytes());
            }
        }
        let c = entry_set_checksum(&set);
        set[2..4].copy_from_slice(&c.to_le_bytes());
        set
    }

    pub fn push_set(&mut self, dir: usize, set: &[u8]) {
        for e in set.chunks(32) {
            let mut a = [0u8; 32];
            a.copy_from_slice(e);
            self.push_slot(dir, &a);
        }
    }

    pub fn add_file(&mut self, dir: usize, name: &str, data: &[u8], layout: Layout) -> Vec<u32> {
        self.add_file_valid(dir, name, data, data.len() as u64, layout)
    }

    /// File whose ValidDataLength may be shorter than its data.
    pub fn add_file_valid(
        &mut self,
        dir: usize,
        name: &str,
        data: &[u8],
        valid_len: u64,
        layout: Layout,
    ) -> Vec<u32> {
        let n = data.len().div_ceil(self.cluster_size);
        let clusters = self.alloc(layout, n);
        self.write_data(&clusters, &data[..valid_len as usize]);
        let first = clusters.first().copied().unwrap_or(0);
        let set = Self::entry_set(
            name,
            0x20,
            first,
            data.len() as u64,
            valid_len,
            layout == Layout::Contiguous,
            timestamp(2026, 11, 5, 13, 34, 56),
            0x80 | 4, // UTC+1h
        );
        self.push_set(dir, &set);
        clusters
    }

    pub fn add_dir(&mut self, parent: usize, name: &str, clusters: usize) -> usize {
        let cs = self.alloc(Layout::FatChain, clusters);
        let len = (clusters * self.cluster_size) as u64;
        let set = Self::entry_set(
            name,
            0x10,
            cs[0],
            len,
            len,
            false,
            timestamp(2026, 1, 1, 0, 0, 0),
            0,
        );
        self.push_set(parent, &set);
        self.dirs.push(Dir {
            clusters: cs,
            used_slots: 0,
        });
        self.dirs.len() - 1
    }
}
