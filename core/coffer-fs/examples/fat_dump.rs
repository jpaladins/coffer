// SPDX-License-Identifier: GPL-3.0-or-later
//! Debug aid: prints the raw root entry and directory entries of /coffer of
//! a FAT12/16 container (decrypted in memory).
//! Usage: fat_dump <container> <password>
use coffer_crypto::Prf;
use coffer_volume::{open_volume, BlockDevice, CancelToken, Credentials, FileDevice};
use std::sync::Arc;

fn le16(b: &[u8], o: usize) -> usize {
    u16::from_le_bytes([b[o], b[o + 1]]) as usize
}

fn show(e: &[u8]) {
    let name: String = e[..11].iter().map(|c| if c.is_ascii_graphic() || *c == b' ' { *c as char } else { '.' }).collect();
    println!(
        "  {:02x} '{}' attr={:02x} ntres={:02x} ctime={:02x}{:02x}{:02x} cdate={:04x} adate={:04x} hi={:04x} mtime={:04x} mdate={:04x} clus={} size={}",
        e[0], name, e[11], e[12], e[13], e[14], e[15], le16(e, 16), le16(e, 18), le16(e, 20), le16(e, 22), le16(e, 24), le16(e, 26),
        u32::from_le_bytes([e[28], e[29], e[30], e[31]])
    );
}

fn main() -> Result<(), String> {
    let a: Vec<String> = std::env::args().collect();
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&a[1], false).map_err(e)?);
    let mut c = Credentials::new(a[2].as_bytes());
    c.prf_hint = Some(Prf::Sha512);
    let v = open_volume(dev, &c, &CancelToken::new()).map_err(e)?;
    let mut bs = [0u8; 512];
    v.read_at(0, &mut bs).map_err(e)?;
    let bps = le16(&bs, 11);
    let spc = bs[13] as usize;
    let rsvd = le16(&bs, 14);
    let nfats = bs[16] as usize;
    let root_ent = le16(&bs, 17);
    let fatsz = le16(&bs, 22);
    let root_off = (rsvd + nfats * fatsz) * bps;
    let data_off = root_off + root_ent * 32;
    let mut root = vec![0u8; root_ent * 32];
    v.read_at(root_off as u64, &mut root).map_err(e)?;
    let mut coffer_cluster = 0;
    println!("root entries in use:");
    for ent in root.chunks(32) {
        if ent[0] == 0 {
            break;
        }
        if ent[0] == 0xE5 {
            continue;
        }
        if ent[11] != 0x0F && ent[..6] == *b"COFFER" {
            coffer_cluster = le16(ent, 26);
        }
        if ent[11] != 0x0F {
            show(ent);
        }
    }
    println!("/coffer cluster {coffer_cluster}:");
    let mut d = vec![0u8; spc * bps];
    v.read_at((data_off + (coffer_cluster - 2) * spc * bps) as u64, &mut d).map_err(e)?;
    for ent in d.chunks(32).take(12) {
        if ent[0] == 0 {
            println!("  (end)");
            break;
        }
        if ent[11] == 0x0F {
            println!("  LFN ord={:02x} chk={:02x}", ent[0], ent[13]);
        } else {
            show(ent);
        }
    }
    Ok(())
}
