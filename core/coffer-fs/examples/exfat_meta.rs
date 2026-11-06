// SPDX-License-Identifier: GPL-3.0-or-later
//! Oracle aid: prints the boot-sector geometry and root metadata entries of
//! an exFAT volume inside a container, and optionally saves its up-case
//! table. Usage: exfat_meta <container> <password> [upcase-out.bin]
use coffer_exfat::{chain_for_len, parse_boot};
use coffer_volume::{open_volume, BlockDevice, CancelToken, Credentials, FileDevice};
use std::sync::Arc;

fn main() -> Result<(), String> {
    let a: Vec<String> = std::env::args().collect();
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&a[1], false).map_err(e)?);
    let v = open_volume(dev, &Credentials::new(a[2].as_bytes()), &CancelToken::new()).map_err(e)?;
    let mut region = vec![0u8; 12 * 512];
    v.read_at(0, &mut region).map_err(e)?;
    let b = parse_boot(&region).map_err(e)?;
    println!("{b:?}");
    println!(
        "percent_in_use={} flags={:#x} fsrev={:#x}",
        region[112],
        u16::from_le_bytes([region[106], region[107]]),
        u16::from_le_bytes([region[104], region[105]])
    );
    let cs = b.cluster_size();
    let heap = u64::from(b.cluster_heap_offset) * 512;
    let mut root = vec![0u8; cs as usize];
    v.read_at(heap + u64::from(b.root_cluster - 2) * cs, &mut root)
        .map_err(e)?;
    for ent in root.chunks(32) {
        if ent[0] == 0 {
            break;
        }
        let first = u32::from_le_bytes([ent[20], ent[21], ent[22], ent[23]]);
        let len = u64::from_le_bytes(ent[24..32].try_into().map_err(|_| "len")?);
        println!(
            "entry {:#04x} first={first} len={len} raw={}",
            ent[0],
            ent.iter().map(|x| format!("{x:02x}")).collect::<String>()
        );
        if ent[0] == 0x82 {
            let chain = chain_for_len(&v, &b, first, false, len).map_err(e)?;
            let mut t = vec![0u8; len as usize];
            let mut off = 0;
            for c in chain {
                let n = (len as usize - off).min(cs as usize);
                v.read_at(heap + u64::from(c - 2) * cs, &mut t[off..off + n])
                    .map_err(e)?;
                off += n;
            }
            if let Some(out) = a.get(3) {
                std::fs::write(out, &t).map_err(|x| x.to_string())?;
                println!("up-case table saved ({len} bytes)");
            }
        }
    }
    Ok(())
}
