// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.create

import kotlin.math.log2

enum class Strength { WEAK, FAIR, GOOD, STRONG }

/**
 * Rough entropy estimate (length x log2 of the character pool) for the
 * creation form. It cannot see dictionary words, so it is a hint, not a
 * guarantee; VeraCrypt recommends at least 20 characters.
 */
object PasswordStrength {
    const val RECOMMENDED_LENGTH = 20

    fun bits(password: CharArray): Double {
        if (password.isEmpty()) return 0.0
        var pool = 0
        if (password.any { it in 'a'..'z' }) pool += 26
        if (password.any { it in 'A'..'Z' }) pool += 26
        if (password.any { it in '0'..'9' }) pool += 10
        if (password.any { it.code in 0x20..0x7E && !it.isLetterOrDigit() }) pool += 33
        if (password.any { it.code > 0x7E }) pool += 100
        return password.size * log2(pool.toDouble())
    }

    fun estimate(password: CharArray): Strength {
        val b = bits(password)
        return when {
            b < 40 -> Strength.WEAK
            b < 60 -> Strength.FAIR
            b < 80 -> Strength.GOOD
            else -> Strength.STRONG
        }
    }
}
