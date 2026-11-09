// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.coffer.ui.theme.CofferTheme

/** Segmented step bar with the current step's name; done and current segments are gold. */
@Composable
fun StepIndicator(steps: List<String>, current: Int, modifier: Modifier = Modifier) {
    val label = "${current + 1}/${steps.size} · ${steps[current]}"
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.indices.forEach { i ->
                val color by animateColorAsState(
                    if (i <= current) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    label = "step",
                )
                Box(Modifier.weight(1f).height(6.dp).background(color, CircleShape))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val sample = listOf("Location", "Size", "Password", "Review")
@Preview @Composable private fun StepsLight() = CofferTheme(dark = false) { Surface { StepIndicator(sample, 1) } }
@Preview @Composable private fun StepsDark() = CofferTheme(dark = true) { Surface { StepIndicator(sample, 3) } }
