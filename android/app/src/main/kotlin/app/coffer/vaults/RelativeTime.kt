// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.vaults

import android.content.res.Resources
import app.coffer.R
import java.text.DateFormat
import java.util.Date

sealed interface RelativeAge {
    data object Now : RelativeAge
    data class Minutes(val n: Int) : RelativeAge
    data class Hours(val n: Int) : RelativeAge
    data class Days(val n: Int) : RelativeAge
    data object Date : RelativeAge
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Under a minute "now"; minutes, hours and days under a week; then a date. */
fun relativeAge(now: Long, then: Long): RelativeAge {
    val d = now - then
    return when {
        d < MINUTE -> RelativeAge.Now
        d < HOUR -> RelativeAge.Minutes((d / MINUTE).toInt())
        d < DAY -> RelativeAge.Hours((d / HOUR).toInt())
        d < 7 * DAY -> RelativeAge.Days((d / DAY).toInt())
        else -> RelativeAge.Date
    }
}

fun relativeTime(now: Long, then: Long, res: Resources): String = when (val a = relativeAge(now, then)) {
    RelativeAge.Now -> res.getString(R.string.time_now)
    is RelativeAge.Minutes -> res.getQuantityString(R.plurals.time_minutes_ago, a.n, a.n)
    is RelativeAge.Hours -> res.getQuantityString(R.plurals.time_hours_ago, a.n, a.n)
    is RelativeAge.Days -> res.getQuantityString(R.plurals.time_days_ago, a.n, a.n)
    RelativeAge.Date -> DateFormat.getDateInstance(DateFormat.SHORT, res.configuration.locales[0]).format(Date(then))
}
