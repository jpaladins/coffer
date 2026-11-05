// SPDX-License-Identifier: GPL-3.0-or-later
//! exFAT timestamps (specification §7.4.8–7.4.10).

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Converts a timestamp, its 10 ms increment and its UTC offset field to
/// Unix seconds (UTC). Without a valid offset the time is taken as UTC.
pub fn to_unix(ts: u32, ten_ms: u8, utc_offset: u8) -> Option<i64> {
    let sec = i64::from(ts & 0x1F) * 2;
    let min = i64::from((ts >> 5) & 0x3F);
    let hour = i64::from((ts >> 11) & 0x1F);
    let day = i64::from((ts >> 16) & 0x1F);
    let month = i64::from((ts >> 21) & 0x0F);
    let year = 1980 + i64::from(ts >> 25);
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) || hour > 23 || min > 59 || sec > 58 {
        return None;
    }
    let local = days_from_civil(year, month, day) * 86_400
        + hour * 3600
        + min * 60
        + sec
        + i64::from(ten_ms.min(199)) / 100;
    let offset_secs = if utc_offset & 0x80 != 0 {
        // Low 7 bits: signed count of 15-minute intervals.
        let v = i64::from(utc_offset & 0x7F);
        let v = if v >= 64 { v - 128 } else { v };
        v * 15 * 60
    } else {
        0
    };
    Some(local - offset_secs)
}
