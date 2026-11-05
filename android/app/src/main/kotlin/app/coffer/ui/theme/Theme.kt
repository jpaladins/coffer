// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CofferGreen = Color(0xFF0B3D2E)
val CofferGold = Color(0xFFE8B04A)
val CofferGoldLight = Color(0xFFF5D38A)

private val Light = lightColorScheme(
    primary = CofferGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9DC),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF7A5A00),
    onSecondary = Color.White,
    secondaryContainer = CofferGoldLight,
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = CofferGold,
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFF7FAF7),
    surfaceContainer = Color(0xFFEBF0EC),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD5B7),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF13513F),
    onPrimaryContainer = Color(0xFFCDE9DC),
    secondary = CofferGold,
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = CofferGoldLight,
    tertiary = CofferGoldLight,
    background = Color(0xFF0E1512),
    surface = Color(0xFF0E1512),
    surfaceContainer = Color(0xFF1A221E),
    error = Color(0xFFFFB4AB),
)

/** Brand colours by default; Material You stays off so Coffer is recognisable (brand.md). */
@Composable
fun CofferTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
