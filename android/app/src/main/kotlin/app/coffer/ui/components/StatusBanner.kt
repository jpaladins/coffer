// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.coffer.ui.theme.CofferTheme

enum class BannerKind { Error, Warning, Info }

/** Inline message with icon and optional actions; TalkBack announces it when it appears. */
@Composable
fun StatusBanner(
    kind: BannerKind,
    text: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
    secondAction: Pair<String, () -> Unit>? = null,
) {
    val c = MaterialTheme.colorScheme
    val (bg, fg, icon) = when (kind) {
        BannerKind.Error -> Triple(c.errorContainer, c.onErrorContainer, CofferIcons.Error)
        BannerKind.Warning -> Triple(c.tertiaryContainer, c.onTertiaryContainer, Icons.Filled.Warning)
        BannerKind.Info -> Triple(c.secondaryContainer, c.onSecondaryContainer, Icons.Filled.Info)
    }
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (action != null || secondAction != null) {
                Row(Modifier.padding(start = 24.dp)) {
                    listOfNotNull(action, secondAction).forEach { (label, onClick) ->
                        TextButton(onClick = onClick) { Text(label, color = fg) }
                    }
                }
            }
        }
    }
}

@Preview @Composable private fun BannerLight() = CofferTheme(dark = false) {
    Surface { Column { BannerKind.entries.forEach { StatusBanner(it, "Wrong password or settings.", action = "Retry" to {}) } } }
}
@Preview @Composable private fun BannerDark() = CofferTheme(dark = true) {
    Surface { Column { BannerKind.entries.forEach { StatusBanner(it, "Wrong password or settings.") } } }
}
