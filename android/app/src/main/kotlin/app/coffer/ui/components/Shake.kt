// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Offsets in dp of the "no" shake, the same as the app-lock PIN dots. */
internal val SHAKE_STEPS = listOf(16f, -14f, 10f, -6f, 0f)

/**
 * Shakes sideways each time [trigger] changes to a value above zero
 * (for example a count of failed attempts); still when animations are off.
 */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val offset = remember { Animatable(0f) }
    val animate = rememberAnimationsEnabled()
    LaunchedEffect(trigger) {
        if (trigger > 0 && animate) {
            SHAKE_STEPS.forEach { offset.animateTo(it, spring(stiffness = Spring.StiffnessHigh)) }
        }
    }
    graphicsLayer { translationX = offset.value.dp.toPx() }
}
