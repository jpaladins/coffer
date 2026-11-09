// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.coffer.R

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope variable font (OFL-1.1), bundled as a resource. */
val Manrope: FontFamily = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Tabular figures for sizes, free space and progress, so digits do not jump. */
val TabularNums: TextStyle = TextStyle(fontFeatureSettings = "tnum")

private val base = Typography()

private fun TextStyle.with(weight: Int, tracking: Float? = null) = copy(
    fontFamily = Manrope,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking?.sp ?: letterSpacing,
)

val CofferTypography: Typography = Typography(
    displayLarge = base.displayLarge.with(800, -0.5f),
    displayMedium = base.displayMedium.with(800, -0.5f),
    displaySmall = base.displaySmall.with(800, -0.5f),
    headlineLarge = base.headlineLarge.with(800, -0.5f),
    headlineMedium = base.headlineMedium.with(800, -0.5f),
    headlineSmall = base.headlineSmall.with(800, -0.5f),
    titleLarge = base.titleLarge.with(700),
    titleMedium = base.titleMedium.with(700),
    titleSmall = base.titleSmall.with(700),
    bodyLarge = base.bodyLarge.with(400),
    bodyMedium = base.bodyMedium.with(400),
    bodySmall = base.bodySmall.with(400),
    labelLarge = base.labelLarge.with(600),
    labelMedium = base.labelMedium.with(600),
    labelSmall = base.labelSmall.with(600),
)
