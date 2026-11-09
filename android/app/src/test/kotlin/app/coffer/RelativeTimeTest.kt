// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.vaults.RelativeAge
import app.coffer.vaults.relativeAge
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val now = 1_800_000_000_000L
    private val sec = 1000L
    private val min = 60 * sec
    private val hour = 60 * min
    private val day = 24 * hour

    @Test fun thirty_seconds_is_now() = assertEquals(RelativeAge.Now, relativeAge(now, now - 30 * sec))
    @Test fun five_minutes() = assertEquals(RelativeAge.Minutes(5), relativeAge(now, now - 5 * min))
    @Test fun three_hours() = assertEquals(RelativeAge.Hours(3), relativeAge(now, now - 3 * hour - 10 * min))
    @Test fun two_days() = assertEquals(RelativeAge.Days(2), relativeAge(now, now - 2 * day - hour))
    @Test fun ten_days_is_date() = assertEquals(RelativeAge.Date, relativeAge(now, now - 10 * day))
    @Test fun future_is_now() = assertEquals(RelativeAge.Now, relativeAge(now, now + hour))
}
