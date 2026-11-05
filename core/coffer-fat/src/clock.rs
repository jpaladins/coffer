// SPDX-License-Identifier: GPL-3.0-or-later
//! FAT stores local time without a zone. The app sets the device's UTC
//! offset; the default (0) writes UTC.
use std::sync::atomic::{AtomicI32, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static OFFSET_MINUTES: AtomicI32 = AtomicI32::new(0);

/// Sets the local-time offset from UTC used for new FAT timestamps.
pub fn set_local_offset_minutes(minutes: i32) {
    OFFSET_MINUTES.store(minutes, Ordering::Relaxed);
}

/// (year, month, day) for days since 1970-01-01 (proleptic Gregorian).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[derive(Debug)]
pub(crate) struct Clock;

pub(crate) static CLOCK: Clock = Clock;

impl fatfs::TimeProvider for Clock {
    fn get_current_date(&self) -> fatfs::Date {
        self.get_current_date_time().date
    }

    fn get_current_date_time(&self) -> fatfs::DateTime {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| (d.as_secs() as i64, d.subsec_millis()))
            .unwrap_or((0, 0));
        let local = now.0 + i64::from(OFFSET_MINUTES.load(Ordering::Relaxed)) * 60;
        let (y, m, d) = civil_from_days(local.div_euclid(86_400));
        let secs = local.rem_euclid(86_400) as u32;
        // FAT cannot represent dates before 1980 or after 2107.
        let year = y.clamp(1980, 2107) as u16;
        fatfs::DateTime {
            date: fatfs::Date {
                year,
                month: m as u16,
                day: d as u16,
            },
            time: fatfs::Time {
                hour: (secs / 3600) as u16,
                min: (secs / 60 % 60) as u16,
                sec: (secs % 60) as u16,
                millis: now.1 as u16,
            },
        }
    }
}
