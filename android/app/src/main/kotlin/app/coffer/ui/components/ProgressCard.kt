// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.coffer.R
import app.coffer.ui.theme.CofferRadii
import app.coffer.ui.theme.CofferTheme
import app.coffer.ui.theme.TabularNums

/** A running job (import, export, creation): title, counter, wavy progress and Stop. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgressCard(
    title: String,
    detail: String,
    progress: Float?,
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CofferRadii.card),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onStop != null) TextButton(onClick = onStop) { Text(stringResource(R.string.import_stop)) }
            }
            Spacer(Modifier.height(12.dp))
            val color = MaterialTheme.colorScheme.tertiary
            if (progress == null) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = color)
            } else {
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = color)
            }
        }
    }
}

@Preview @Composable private fun ProgressLight() = CofferTheme(dark = false) {
    Surface { ProgressCard("Importing photo.jpg", "3 of 12", 0.4f, onStop = {}) }
}
@Preview @Composable private fun ProgressDark() = CofferTheme(dark = true) {
    Surface { ProgressCard("Importing", "1 of 2", null, onStop = {}) }
}
