// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner radii of the redesign: 28 dp cards, 32 dp sheets, pill fields and buttons. */
object CofferRadii {
    val card = 28.dp
    val sheet = 32.dp
    val field = CornerSize(50)
}

val CofferShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(CofferRadii.card),
    extraLarge = RoundedCornerShape(CofferRadii.sheet),
)
