// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CofferGreen = Color(0xFF0B3D2E)
val CofferGold = Color(0xFFE8B04A)
val CofferGoldLight = Color(0xFFF5D38A)

internal val LightColors: ColorScheme = lightColorScheme(
    primary = CofferGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9DC),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF7A5A00),
    onSecondary = Color.White,
    secondaryContainer = CofferGoldLight,
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = CofferGold,
    onTertiary = Color(0xFF3A2700),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF7FAF7),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF7FAF7),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEBF0EC),
    surfaceContainerHigh = Color(0xFFE5EBE7),
    surfaceContainerHighest = Color(0xFFDFE5E1),
    outline = Color(0xFF707973),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8FD5B7),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF13513F),
    onPrimaryContainer = Color(0xFFCDE9DC),
    secondary = CofferGold,
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = CofferGoldLight,
    tertiary = CofferGold,
    onTertiary = Color(0xFF3A2700),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF0E1512),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0E1512),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceContainerLowest = Color(0xFF09100D),
    surfaceContainerLow = Color(0xFF161E1A),
    surfaceContainer = Color(0xFF1A221E),
    surfaceContainerHigh = Color(0xFF242D28),
    surfaceContainerHighest = Color(0xFF2F3833),
    outline = Color(0xFF8A938D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Brand colours by default; Material You stays off so Coffer is recognisable (brand.md). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CofferTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = CofferTypography,
        shapes = CofferShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
