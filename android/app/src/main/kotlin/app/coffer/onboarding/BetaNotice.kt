// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.coffer.R
import app.coffer.ui.components.HexBadge
import app.coffer.ui.theme.TabularNums
import kotlinx.coroutines.delay

/** After the onboarding, until accepted once (also shown to people updating from earlier versions). */
fun shouldShowBetaNotice(onboardingDone: Boolean, accepted: Boolean): Boolean = onboardingDone && !accepted

/** Seconds the accept button stays disabled, so the notice is actually read. */
private const val READ_SECONDS = 5

@Composable
fun BetaNotice(onAccept: () -> Unit) {
    var left by remember { mutableIntStateOf(READ_SECONDS) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    // Back does not dismiss it: the notice needs an explicit answer.
    BackHandler {}
    Surface(Modifier.fillMaxSize()) {
        // The text scrolls on small screens; the button stays pinned at the bottom.
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            HexBadge(
                Icons.Filled.Warning,
                size = 96.dp,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                stringResource(R.string.beta_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.beta_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(R.string.beta_point_backup, R.string.beta_point_bugs, R.string.beta_point_report).forEach { point ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(point), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAccept,
                enabled = left == 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                if (left > 0) {
                    Text(
                        stringResource(R.string.beta_accept_wait, left),
                        style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                    )
                } else {
                    Text(stringResource(R.string.beta_accept), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
