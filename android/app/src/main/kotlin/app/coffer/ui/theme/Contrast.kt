// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG 2 relative luminance of an opaque colour. */
private fun luminance(c: Color): Double {
    fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

/** WCAG contrast ratio between two opaque colours, from 1 to 21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
