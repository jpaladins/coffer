// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.coffer.ui.theme.CofferGold
import app.coffer.ui.theme.CofferGoldLight
import app.coffer.ui.theme.CofferGreen
import kotlin.math.cos
import kotlin.math.sin

/** The brand mark (nested hexagons), drawn so it scales without assets. */
@Composable
fun CofferMark(modifier: Modifier = Modifier, size: Dp = 40.dp, open: Boolean = false) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        fun hex(r: Float) = Path().apply {
            for (i in 0..5) {
                val a = Math.toRadians(60.0 * i - 90).toFloat()
                val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        val r = this.size.minDimension / 2
        drawPath(hex(r), CofferGold)
        drawPath(hex(r * 0.5f), if (open) CofferGoldLight else CofferGreen)
        drawPath(hex(r * 0.25f), if (open) Color.White else CofferGold)
    }
}
