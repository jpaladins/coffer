// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.coffer.ui.theme.DarkColors
import app.coffer.ui.theme.LightColors
import app.coffer.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun pairs(s: ColorScheme) = listOf(
        s.onSurface to s.surface, s.onSurfaceVariant to s.surface,
        s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
        s.onTertiary to s.tertiary, s.onTertiaryContainer to s.tertiaryContainer,
        s.onSurface to s.surfaceContainerHigh, s.onError to s.error,
        s.onSecondaryContainer to s.secondaryContainer, s.onErrorContainer to s.errorContainer,
    )

    private fun check(s: ColorScheme) = pairs(s).forEachIndexed { i, (fg, bg) ->
        val r = contrastRatio(fg, bg)
        assertTrue("pair $i ratio $r", r >= 4.5)
    }

    @Test fun light_pairs_meet_aa() = check(LightColors)
    @Test fun dark_pairs_meet_aa() = check(DarkColors)
    @Test fun ratio_black_white_is_21() = assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
}
