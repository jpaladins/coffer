// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import app.coffer.ui.theme.CofferTheme
import kotlin.math.cos
import kotlin.math.sin

/** Pointy-top hexagon with soft corners, like the brand mark. */
internal val Hexagon: RoundedPolygon = run {
    val v = FloatArray(12)
    for (i in 0..5) {
        val a = Math.toRadians(60.0 * i - 90)
        v[2 * i] = cos(a).toFloat()
        v[2 * i + 1] = sin(a).toFloat()
    }
    RoundedPolygon(v, rounding = CornerRounding(0.2f)).normalized()
}

/** A decorative icon inside the brand hexagon. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HexBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier.size(size).background(container, Hexagon.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.45f))
    }
}

@Preview @Composable private fun HexBadgeLight() = CofferTheme(dark = false) { Surface { HexBadge(Icons.Filled.Lock) } }
@Preview @Composable private fun HexBadgeDark() = CofferTheme(dark = true) { Surface { HexBadge(Icons.Filled.Lock, size = 96.dp) } }
