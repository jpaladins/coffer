// SPDX-License-Identifier: GPL-3.0-or-later
//! Isolates which operation leaves a FAT volume that chkdsk rejects.
//! Usage: fat_probe <container> <password> <scenario: movedir|manyfiles|longname|filemove>
use coffer_crypto::Prf;
use coffer_fs::mount_rw;
use coffer_volume::{open_volume, BlockDevice, CancelToken, Credentials, FileDevice};
use std::sync::Arc;

fn main() -> Result<(), String> {
    let a: Vec<String> = std::env::args().collect();
    let e = |x: coffer_vfs::CofferError| x.to_string();
    let dev: Arc<dyn BlockDevice> = Arc::new(FileDevice::open(&a[1], true).map_err(e)?);
    let mut c = Credentials::new(a[2].as_bytes());
    c.prf_hint = Some(Prf::Sha512);
    let vol = Arc::new(open_volume(dev, &c, &CancelToken::new()).map_err(e)?);
    let fs = mount_rw(vol).map_err(e)?;
    fs.mkdir("/coffer").map_err(e)?;
    match a[3].as_str() {
        "movedir" => {
            fs.mkdir("/coffer/sub").map_err(e)?;
            fs.mkdir("/coffer/sub/deep").map_err(e)?;
            fs.rename("/coffer/sub/deep", "/coffer/deep-moved")
                .map_err(e)?;
        }
        "manyfiles" => {
            fs.mkdir("/coffer/many").map_err(e)?;
            for i in 0..300 {
                let p = format!("/coffer/many/f{i:03}.txt");
                fs.create_file(&p).map_err(e)?;
                fs.write(&p, 0, b"x").map_err(e)?;
            }
        }
        "longname" => {
            fs.create_file(&format!("/coffer/{}.txt", "n".repeat(120)))
                .map_err(e)?;
            fs.create_file("/coffer/àèìòù €.txt").map_err(e)?;
        }
        "filemove" => {
            fs.mkdir("/coffer/sub").map_err(e)?;
            fs.create_file("/coffer/hello.txt").map_err(e)?;
            fs.rename("/coffer/hello.txt", "/coffer/sub/saluti.txt")
                .map_err(e)?;
        }
        other => return Err(format!("unknown scenario {other}")),
    }
    fs.sync().map_err(e)?;
    println!("{} done", a[3]);
    Ok(())
}
