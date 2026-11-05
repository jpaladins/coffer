// SPDX-License-Identifier: GPL-3.0-or-later
//! exFAT writing (Microsoft exFAT specification §5–§7): allocation bitmap,
//! cluster chains (NoFatChain and FAT), entry sets with NameHash, directory
//! growth and the VolumeDirty flag.
use crate::dir::{set_checksum, DirLoc, ExEntry};
use crate::fat::{chain_for_len, chain_until_end};
use crate::fs::{ExFatFs, Node};
use coffer_vfs::{names_equal, split_parent, split_path, validate_name, CofferError};
use std::sync::MutexGuard;
use std::time::{SystemTime, UNIX_EPOCH};

const END_OF_CHAIN: u32 = 0xFFFF_FFFF;
const ATTR_ARCHIVE: u16 = 0x20;
const ATTR_DIRECTORY: u16 = 0x10;
const VOLUME_DIRTY: u16 = 0x0002;

/// In-memory allocation state of a writable volume.
pub struct WriteState {
    bitmap: Vec<u8>,
    bitmap_chain: Vec<u32>,
    upcase: Vec<u16>,
    dirty_marked: bool,
}

/// Expands the up-case table (§7.2.5): `0xFFFF n` is a run of n identity
/// mappings.
pub(crate) fn decompress_upcase(raw: &[u8]) -> Vec<u16> {
    let words: Vec<u16> = raw
        .as_chunks::<2>()
        .0
        .iter()
        .map(|c| u16::from_le_bytes(*c))
        .collect();
    let mut table = Vec::with_capacity(65_536);
    let mut i = 0;
    while i < words.len() && table.len() < 65_536 {
        if words[i] == 0xFFFF && i + 1 < words.len() {
            for _ in 0..words[i + 1] {
                if table.len() >= 65_536 {
                    break;
                }
                table.push(table.len() as u16);
            }
            i += 2;
        } else {
            table.push(words[i]);
            i += 1;
        }
    }
    table
}

/// NameHash (§7.6.4): rotate-add over the up-cased name in UTF-16LE.
pub fn name_hash(name: &str, upcase: &[u16]) -> u16 {
    let mut hash: u16 = 0;
    for u in name.encode_utf16() {
        let up = upcase.get(usize::from(u)).copied().unwrap_or(u);
        for b in up.to_le_bytes() {
            let high: u16 = if hash & 1 != 0 { 0x8000 } else { 0 };
            hash = high.wrapping_add(hash >> 1).wrapping_add(u16::from(b));
        }
    }
    hash
}

/// Current time as an exFAT timestamp in UTC (offset field: valid, +0).
fn now_timestamp() -> (u32, u8) {
    let d = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = d.as_secs() as i64;
    let days = secs.div_euclid(86_400);
    let tod = secs.rem_euclid(86_400) as u32;
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = (yoe + era * 400 + i64::from(month <= 2)).clamp(1980, 2107) as u32;
    let ts = ((year - 1980) << 25)
        | (month << 21)
        | (day << 16)
        | ((tod / 3600) << 11)
        | ((tod / 60 % 60) << 5)
        | (tod % 60 / 2);
    let tens = ((tod % 2) * 100 + d.subsec_millis() / 10) as u8;
    (ts, tens)
}

/// A stream's allocation while it is being resized.
struct Stream {
    first: u32,
    contiguous: bool,
    chain: Vec<u32>,
}

fn is_contiguous(c: &[u32]) -> bool {
    c.windows(2).all(|w| w[1] == w[0] + 1)
}

impl ExFatFs {
    /// Opens for reading and writing (the device must be writable).
    pub fn open_rw(
        dev: std::sync::Arc<dyn coffer_volume::BlockDevice>,
    ) -> Result<Self, CofferError> {
        let mut fs = Self::open(dev)?;
        let (bm_first, bm_len) = fs
            .root_primary(0x81)?
            .ok_or_else(|| CofferError::Corrupted("exFAT: no allocation bitmap".into()))?;
        let need = u64::from(fs.boot.cluster_count).div_ceil(8);
        if bm_len < need {
            return Err(CofferError::Corrupted(
                "exFAT: allocation bitmap too small".into(),
            ));
        }
        let bitmap_chain = chain_for_len(fs.dev.as_ref(), &fs.boot, bm_first, false, need)
            .or_else(|_| chain_for_len(fs.dev.as_ref(), &fs.boot, bm_first, true, need))?;
        let mut bitmap = vec![0u8; need as usize];
        fs.read_clusters(&bitmap_chain, 0, &mut bitmap)?;
        let upcase = match fs.root_primary(0x82)? {
            Some((first, len)) if len <= 256 * 1024 => {
                let chain = chain_for_len(fs.dev.as_ref(), &fs.boot, first, false, len)
                    .or_else(|_| chain_for_len(fs.dev.as_ref(), &fs.boot, first, true, len))?;
                let mut raw = vec![0u8; len as usize];
                fs.read_clusters(&chain, 0, &mut raw)?;
                decompress_upcase(&raw)
            }
            _ => return Err(CofferError::Corrupted("exFAT: no up-case table".into())),
        };
        fs.writer = Some(std::sync::Mutex::new(WriteState {
            bitmap,
            bitmap_chain,
            upcase,
            dirty_marked: false,
        }));
        Ok(fs)
    }

    /// First in-use primary entry of `entry_type` in the root directory.
    fn root_primary(&self, entry_type: u8) -> Result<Option<(u32, u64)>, CofferError> {
        let chain = chain_until_end(self.dev.as_ref(), &self.boot, self.boot.root_cluster)?;
        let len = (chain.len() as u64 * self.boot.cluster_size()).min(256 * 1024 * 1024);
        let mut raw = vec![0u8; len as usize];
        self.read_clusters(&chain, 0, &mut raw)?;
        for e in raw.chunks(32) {
            if e[0] == 0 {
                break;
            }
            if e[0] == entry_type {
                let first = u32::from_le_bytes([e[20], e[21], e[22], e[23]]);
                let mut l = [0u8; 8];
                l.copy_from_slice(&e[24..32]);
                return Ok(Some((first, u64::from_le_bytes(l))));
            }
        }
        Ok(None)
    }

    fn ws(&self) -> Result<MutexGuard<'_, WriteState>, CofferError> {
        Ok(self
            .writer
            .as_ref()
            .ok_or(CofferError::ReadOnly)?
            .lock()
            .unwrap_or_else(|p| p.into_inner()))
    }

    fn invalidate(&self) {
        self.path_cache
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clear();
        self.chain_cache
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clear();
    }

    fn update_volume_flags(&self, f: impl Fn(u16) -> u16) -> Result<(), CofferError> {
        let mut b = [0u8; 2];
        self.dev.read_at(106, &mut b)?;
        self.dev
            .write_at(106, &f(u16::from_le_bytes(b)).to_le_bytes())
    }

    /// VolumeDirty is set before the first metadata change of a session.
    fn mark_dirty(&self, ws: &mut WriteState) -> Result<(), CofferError> {
        if !ws.dirty_marked {
            self.update_volume_flags(|f| f | VOLUME_DIRTY)?;
            self.dev.flush()?;
            ws.dirty_marked = true;
        }
        Ok(())
    }

    fn write_clusters(
        &self,
        clusters: &[u32],
        offset: u64,
        data: &[u8],
    ) -> Result<(), CofferError> {
        let cs = self.boot.cluster_size();
        let mut done = 0usize;
        while done < data.len() {
            let pos = offset + done as u64;
            let idx = (pos / cs) as usize;
            let first = *clusters
                .get(idx)
                .ok_or_else(|| CofferError::Corrupted("exFAT: write beyond chain".into()))?;
            let mut run = 1;
            while idx + run < clusters.len() && clusters[idx + run] == first + run as u32 {
                run += 1;
            }
            let in_run = run as u64 * cs - pos % cs;
            let n = ((data.len() - done) as u64).min(in_run) as usize;
            self.dev.write_at(
                self.boot.cluster_offset(first) + pos % cs,
                &data[done..done + n],
            )?;
            done += n;
        }
        Ok(())
    }

    fn zero_range(&self, clusters: &[u32], from: u64, to: u64) -> Result<(), CofferError> {
        let zeros = vec![0u8; 256 * 1024];
        let mut pos = from;
        while pos < to {
            let n = (to - pos).min(zeros.len() as u64) as usize;
            self.write_clusters(clusters, pos, &zeros[..n])?;
            pos += n as u64;
        }
        Ok(())
    }

    fn write_fat(&self, cluster: u32, value: u32) -> Result<(), CofferError> {
        self.dev
            .write_at(self.boot.fat_entry_offset(cluster), &value.to_le_bytes())
    }

    fn link(&self, chain: &[u32]) -> Result<(), CofferError> {
        for w in chain.windows(2) {
            self.write_fat(w[0], w[1])?;
        }
        if let Some(last) = chain.last() {
            self.write_fat(*last, END_OF_CHAIN)?;
        }
        Ok(())
    }

    fn is_free(ws: &WriteState, c: u32) -> bool {
        let bit = (c - 2) as usize;
        ws.bitmap
            .get(bit / 8)
            .is_some_and(|b| b & (1 << (bit % 8)) == 0)
    }

    /// Sets or clears bitmap bits and writes the touched bytes back.
    fn set_bits(
        &self,
        ws: &mut WriteState,
        clusters: &[u32],
        used: bool,
    ) -> Result<(), CofferError> {
        let (mut lo, mut hi) = (usize::MAX, 0usize);
        for c in clusters {
            let bit = (*c - 2) as usize;
            let byte = bit / 8;
            if used {
                ws.bitmap[byte] |= 1 << (bit % 8);
            } else {
                ws.bitmap[byte] &= !(1 << (bit % 8));
            }
            lo = lo.min(byte);
            hi = hi.max(byte);
        }
        if lo <= hi {
            self.write_clusters(&ws.bitmap_chain, lo as u64, &ws.bitmap[lo..=hi])?;
        }
        Ok(())
    }

    /// Allocates `n` clusters: right after `after` if possible, else the
    /// first contiguous run, else any free clusters.
    fn alloc(
        &self,
        ws: &mut WriteState,
        n: usize,
        after: Option<u32>,
    ) -> Result<Vec<u32>, CofferError> {
        if n == 0 {
            return Ok(Vec::new());
        }
        let max = u64::from(self.boot.cluster_count) + 1;
        let fits =
            |start: u64| (start..start + n as u64).all(|c| c <= max && Self::is_free(ws, c as u32));
        let mut pick = after.map(|a| u64::from(a) + 1).filter(|s| fits(*s));
        if pick.is_none() {
            let (mut start, mut len) = (2u64, 0usize);
            for c in 2..=max {
                if Self::is_free(ws, c as u32) {
                    if len == 0 {
                        start = c;
                    }
                    len += 1;
                    if len == n {
                        pick = Some(start);
                        break;
                    }
                } else {
                    len = 0;
                }
            }
        }
        let clusters: Vec<u32> = match pick {
            Some(s) => (0..n as u32).map(|i| s as u32 + i).collect(),
            None => {
                let v: Vec<u32> = (2..=max as u32)
                    .filter(|c| Self::is_free(ws, *c))
                    .take(n)
                    .collect();
                if v.len() < n {
                    return Err(CofferError::NoSpace);
                }
                v
            }
        };
        self.set_bits(ws, &clusters, true)?;
        Ok(clusters)
    }

    fn stream_of(&self, e: &ExEntry) -> Result<Stream, CofferError> {
        let chain = chain_for_len(
            self.dev.as_ref(),
            &self.boot,
            e.first_cluster,
            e.no_fat_chain,
            e.data_length,
        )?;
        Ok(Stream {
            first: e.first_cluster,
            contiguous: e.no_fat_chain,
            chain,
        })
    }

    /// Grows or shrinks the allocation of a stream to hold `new_len` bytes.
    fn resize_stream(
        &self,
        ws: &mut WriteState,
        st: &mut Stream,
        new_len: u64,
    ) -> Result<(), CofferError> {
        let need = new_len.div_ceil(self.boot.cluster_size()) as usize;
        let have = st.chain.len();
        if need > have {
            let new = self.alloc(ws, need - have, st.chain.last().copied())?;
            let appends_in_place =
                st.chain.last().is_none_or(|l| new[0] == l + 1) && is_contiguous(&new);
            if st.chain.is_empty() {
                st.first = new[0];
                st.contiguous = is_contiguous(&new);
                st.chain = new;
                if !st.contiguous {
                    self.link(&st.chain)?;
                }
            } else if st.contiguous && appends_in_place {
                st.chain.extend(new);
            } else {
                // NoFatChain cannot describe a fragmented stream: switch to
                // (or extend) a FAT chain.
                st.chain.extend(new);
                st.contiguous = false;
                self.link(&st.chain)?;
            }
        } else if need < have {
            let tail = st.chain.split_off(need);
            if need == 0 {
                st.first = 0;
                st.contiguous = false;
            } else if !st.contiguous {
                if let Some(last) = st.chain.last() {
                    self.write_fat(*last, END_OF_CHAIN)?;
                }
            }
            self.set_bits(ws, &tail, false)?;
        }
        Ok(())
    }

    /// Builds a File + Stream + FileName entry set (§7.4–7.7).
    #[allow(clippy::too_many_arguments)]
    fn build_set(
        ws: &WriteState,
        name: &str,
        attrs: u16,
        base: Option<&[u8; 32]>,
        first: u32,
        contiguous: bool,
        data_len: u64,
        valid_len: u64,
    ) -> Result<Vec<u8>, CofferError> {
        let units: Vec<u16> = name.encode_utf16().collect();
        if units.is_empty() || units.len() > 255 {
            return Err(CofferError::InvalidPath(name.to_string()));
        }
        let names = units.len().div_ceil(15);
        let mut set = vec![0u8; 32 * (2 + names)];
        let (ts, tens) = now_timestamp();
        match base {
            Some(b) => set[..32].copy_from_slice(b),
            None => {
                set[8..12].copy_from_slice(&ts.to_le_bytes()); // create
                set[16..20].copy_from_slice(&ts.to_le_bytes()); // access
                set[20] = tens;
                set[22] = 0x80;
                set[24] = 0x80;
            }
        }
        set[0] = 0x85;
        set[1] = (1 + names) as u8;
        set[4..6].copy_from_slice(&attrs.to_le_bytes());
        set[12..16].copy_from_slice(&ts.to_le_bytes()); // last modified
        set[21] = tens;
        set[23] = 0x80; // UTC offset valid, +0
        let s = &mut set[32..64];
        s[0] = 0xC0;
        s[1] = 0x01 | if contiguous && first != 0 { 0x02 } else { 0 };
        s[3] = units.len() as u8;
        s[4..6].copy_from_slice(&name_hash(name, &ws.upcase).to_le_bytes());
        s[8..16].copy_from_slice(&valid_len.to_le_bytes());
        s[20..24].copy_from_slice(&first.to_le_bytes());
        s[24..32].copy_from_slice(&data_len.to_le_bytes());
        for (i, chunk) in units.chunks(15).enumerate() {
            let e = &mut set[64 + i * 32..96 + i * 32];
            e[0] = 0xC1;
            for (j, u) in chunk.iter().enumerate() {
                e[2 + j * 2..4 + j * 2].copy_from_slice(&u.to_le_bytes());
            }
        }
        let c = set_checksum(&set);
        set[2..4].copy_from_slice(&c.to_le_bytes());
        Ok(set)
    }

    fn dir_chain(&self, loc: &DirLoc) -> Result<Vec<u32>, CofferError> {
        if loc.is_root {
            chain_until_end(self.dev.as_ref(), &self.boot, self.boot.root_cluster)
        } else {
            chain_for_len(
                self.dev.as_ref(),
                &self.boot,
                loc.first,
                loc.contiguous,
                loc.len,
            )
        }
    }

    fn write_set(&self, loc: &DirLoc, slot: usize, set: &[u8]) -> Result<(), CofferError> {
        let chain = self.dir_chain(loc)?;
        self.write_clusters(&chain, slot as u64 * 32, set)
    }

    /// Marks an entry set as not in use (clears the InUse bit of each entry).
    fn mark_unused(&self, e: &ExEntry) -> Result<(), CofferError> {
        let chain = self.dir_chain(&e.parent)?;
        let mut raw = vec![0u8; (usize::from(e.secondary) + 1) * 32];
        self.read_clusters(&chain, e.slot as u64 * 32, &mut raw)?;
        for entry in raw.chunks_mut(32) {
            entry[0] &= 0x7F;
        }
        self.write_clusters(&chain, e.slot as u64 * 32, &raw)
    }

    fn dir_loc(&self, node: &Node) -> Result<DirLoc, CofferError> {
        match node {
            Node::Root => {
                let c = chain_until_end(self.dev.as_ref(), &self.boot, self.boot.root_cluster)?;
                Ok(DirLoc {
                    first: self.boot.root_cluster,
                    contiguous: false,
                    len: c.len() as u64 * self.boot.cluster_size(),
                    is_root: true,
                })
            }
            Node::Entry(e) if e.is_dir => Ok(DirLoc {
                first: e.first_cluster,
                contiguous: e.no_fat_chain,
                len: e.data_length,
                is_root: false,
            }),
            Node::Entry(e) => Err(CofferError::NotADirectory(e.name.clone())),
        }
    }

    /// Finds `k` consecutive free slots in a directory, growing it if needed.
    fn find_slots(
        &self,
        ws: &mut WriteState,
        dir: &Node,
        k: usize,
    ) -> Result<(DirLoc, usize), CofferError> {
        let loc = self.dir_loc(dir)?;
        let chain = self.dir_chain(&loc)?;
        let mut raw = vec![0u8; loc.len as usize];
        self.read_clusters(&chain, 0, &mut raw)?;
        let slots = raw.len() / 32;
        let (mut run_start, mut run_len) = (0usize, 0usize);
        for i in 0..slots {
            let t = raw[i * 32];
            if t == 0 {
                // End of directory: every following slot is free.
                if run_len == 0 {
                    run_start = i;
                }
                run_len += slots - i;
                break;
            } else if t & 0x80 == 0 {
                if run_len == 0 {
                    run_start = i;
                }
                run_len += 1;
                if run_len >= k {
                    return Ok((loc, run_start));
                }
            } else {
                run_len = 0;
            }
        }
        if run_len >= k {
            return Ok((loc, run_start));
        }
        // Grow: the trailing free run continues into the new clusters.
        let start = if run_len > 0 { run_start } else { slots };
        let cs = self.boot.cluster_size();
        let missing = ((k - run_len) * 32) as u64;
        let new_len = loc.len + missing.div_ceil(cs) * cs;
        if new_len > 256 * 1024 * 1024 {
            return Err(CofferError::NoSpace);
        }
        let mut st = Stream {
            first: loc.first,
            contiguous: loc.contiguous,
            chain,
        };
        if loc.is_root {
            st.contiguous = false;
        }
        self.resize_stream(ws, &mut st, new_len)?;
        // New directory clusters must read as end-of-directory (zeros).
        self.zero_range(&st.chain, loc.len, new_len)?;
        let new_loc = DirLoc {
            first: st.first,
            contiguous: st.contiguous,
            len: new_len,
            is_root: loc.is_root,
        };
        if let Node::Entry(d) = dir {
            let set = Self::build_set(
                ws,
                &d.name,
                d.attrs,
                Some(&d.file_raw),
                st.first,
                st.contiguous,
                new_len,
                new_len,
            )?;
            self.write_set(&d.parent, d.slot, &set)?;
        }
        Ok((new_loc, start))
    }

    fn parent_dir(&self, path: &str) -> Result<(Node, String), CofferError> {
        let (parent, name) = split_parent(path)?;
        let node = self.resolve(&parent)?;
        if let Node::Entry(e) = &node {
            if !e.is_dir {
                return Err(CofferError::NotADirectory(parent));
            }
        }
        Ok((node, name.to_string()))
    }

    fn file(&self, path: &str) -> Result<ExEntry, CofferError> {
        match self.resolve(path)? {
            Node::Entry(e) if !e.is_dir => Ok(e),
            _ => Err(CofferError::IsADirectory(path.to_string())),
        }
    }

    pub(crate) fn do_create(&self, path: &str, dir: bool) -> Result<(), CofferError> {
        let mut ws = self.ws()?;
        let (parent, name) = self.parent_dir(path)?;
        validate_name(&name)?;
        if self
            .dir_entries(&parent, path)?
            .iter()
            .any(|e| names_equal(&e.name, &name))
        {
            return Err(CofferError::AlreadyExists(path.to_string()));
        }
        self.mark_dirty(&mut ws)?;
        let set = if dir {
            let cs = self.boot.cluster_size();
            let c = self.alloc(&mut ws, 1, None)?;
            self.zero_range(&c, 0, cs)?;
            Self::build_set(&ws, &name, ATTR_DIRECTORY, None, c[0], true, cs, cs)?
        } else {
            Self::build_set(&ws, &name, ATTR_ARCHIVE, None, 0, false, 0, 0)?
        };
        let (loc, slot) = self.find_slots(&mut ws, &parent, set.len() / 32)?;
        self.write_set(&loc, slot, &set)?;
        self.invalidate();
        Ok(())
    }

    pub(crate) fn do_write(&self, path: &str, offset: u64, data: &[u8]) -> Result<(), CofferError> {
        let mut ws = self.ws()?;
        let e = self.file(path)?;
        let end = offset
            .checked_add(data.len() as u64)
            .ok_or_else(|| CofferError::InvalidPath(path.to_string()))?;
        let new_len = e.data_length.max(end);
        self.mark_dirty(&mut ws)?;
        let mut st = self.stream_of(&e)?;
        self.resize_stream(&mut ws, &mut st, new_len)?;
        // Bytes between ValidDataLength and the write must read as zero.
        if offset > e.valid_data_length {
            self.zero_range(&st.chain, e.valid_data_length, offset)?;
        }
        self.write_clusters(&st.chain, offset, data)?;
        let valid = e.valid_data_length.max(end);
        let set = Self::build_set(
            &ws,
            &e.name,
            e.attrs,
            Some(&e.file_raw),
            st.first,
            st.contiguous,
            new_len,
            valid,
        )?;
        self.write_set(&e.parent, e.slot, &set)?;
        self.invalidate();
        Ok(())
    }

    pub(crate) fn do_set_len(&self, path: &str, len: u64) -> Result<(), CofferError> {
        let mut ws = self.ws()?;
        let e = self.file(path)?;
        self.mark_dirty(&mut ws)?;
        let mut st = self.stream_of(&e)?;
        self.resize_stream(&mut ws, &mut st, len)?;
        // Growing keeps ValidDataLength: the new tail reads as zero.
        let valid = e.valid_data_length.min(len);
        let set = Self::build_set(
            &ws,
            &e.name,
            e.attrs,
            Some(&e.file_raw),
            st.first,
            st.contiguous,
            len,
            valid,
        )?;
        self.write_set(&e.parent, e.slot, &set)?;
        self.invalidate();
        Ok(())
    }

    pub(crate) fn do_remove(&self, path: &str) -> Result<(), CofferError> {
        let mut ws = self.ws()?;
        let e = match self.resolve(path)? {
            Node::Root => return Err(CofferError::InvalidPath(path.to_string())),
            Node::Entry(e) => e,
        };
        if e.is_dir && !self.dir_entries(&Node::Entry(e.clone()), path)?.is_empty() {
            return Err(CofferError::NotEmpty(path.to_string()));
        }
        self.mark_dirty(&mut ws)?;
        let st = self.stream_of(&e)?;
        // Drop the entry first so nothing points at clusters being freed.
        self.mark_unused(&e)?;
        self.set_bits(&mut ws, &st.chain, false)?;
        self.invalidate();
        Ok(())
    }

    pub(crate) fn do_rename(&self, from: &str, to: &str) -> Result<(), CofferError> {
        let mut ws = self.ws()?;
        let (src_parent, src_name) = split_parent(from)?;
        let (dst_parent_path, dst_name) = split_parent(to)?;
        validate_name(dst_name)?;
        let from_parts = split_path(from)?;
        let to_parts = split_path(to)?;
        if to_parts.len() > from_parts.len()
            && from_parts
                .iter()
                .zip(&to_parts)
                .all(|(a, b)| names_equal(a, b))
        {
            return Err(CofferError::InvalidPath(to.to_string()));
        }
        let e = match self.resolve(from)? {
            Node::Root => return Err(CofferError::InvalidPath(from.to_string())),
            Node::Entry(e) => e,
        };
        let (dst_parent, _) = self.parent_dir(to)?;
        let same_dir = split_path(&src_parent)?.len() == split_path(&dst_parent_path)?.len()
            && split_path(&src_parent)?
                .iter()
                .zip(split_path(&dst_parent_path)?)
                .all(|(a, b)| names_equal(a, b));
        let case_only = same_dir && names_equal(src_name, dst_name);
        if !case_only
            && self
                .dir_entries(&dst_parent, to)?
                .iter()
                .any(|x| names_equal(&x.name, dst_name))
        {
            return Err(CofferError::AlreadyExists(to.to_string()));
        }
        self.mark_dirty(&mut ws)?;
        let set = Self::build_set(
            &ws,
            dst_name,
            e.attrs,
            Some(&e.file_raw),
            e.first_cluster,
            e.no_fat_chain,
            e.data_length,
            e.valid_data_length,
        )?;
        let (loc, slot) = self.find_slots(&mut ws, &dst_parent, set.len() / 32)?;
        // New entry first, then the old one: an interruption leaves a
        // duplicate (repairable), never a lost file.
        self.write_set(&loc, slot, &set)?;
        let mut old = e.clone();
        if same_dir {
            old.parent = loc;
        }
        self.mark_unused(&old)?;
        self.invalidate();
        Ok(())
    }

    pub(crate) fn do_sync(&self) -> Result<(), CofferError> {
        let Some(w) = self.writer.as_ref() else {
            return Ok(());
        };
        let mut ws = w.lock().unwrap_or_else(|p| p.into_inner());
        self.dev.flush()?;
        if ws.dirty_marked {
            self.update_volume_flags(|f| f & !VOLUME_DIRTY)?;
            self.dev.flush()?;
            ws.dirty_marked = false;
        }
        Ok(())
    }
}
