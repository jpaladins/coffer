// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coffer.R
import app.coffer.ui.theme.CofferRadii
import androidx.compose.foundation.shape.RoundedCornerShape

/** Shown on top of the container list when the Play Store has a newer Coffer. */
@Composable
fun UpdateBanner(viewModel: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.banner.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onFlowClosed()
    }
    // Keeps the last content while the banner collapses away.
    var shown by remember { mutableStateOf<UpdateState>(UpdateState.None) }
    if (state != UpdateState.None) shown = state
    AnimatedVisibility(
        state != UpdateState.None,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        UpdateCard(
            shown,
            onUpdate = { viewModel.onUpdate(launcher) },
            onInstall = viewModel::onInstall,
            onDismiss = viewModel::onDismiss,
        )
    }
}

@Composable
private fun UpdateCard(state: UpdateState, onUpdate: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(CofferRadii.card),
        colors = CardDefaults.cardColors(containerColor = c.tertiaryContainer, contentColor = c.onTertiaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when (state) {
                                UpdateState.ReadyToInstall -> R.string.update_ready_title
                                is UpdateState.Downloading -> R.string.update_downloading_title
                                else -> R.string.update_available_title
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            if (state == UpdateState.ReadyToInstall) R.string.update_ready_body else R.string.update_available_body,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state is UpdateState.Available) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.update_dismiss))
                    }
                }
            }
            when (state) {
                is UpdateState.Downloading -> {
                    val p = state.fraction
                    val mod = Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp)
                    if (p == null) LinearProgressIndicator(mod) else LinearProgressIndicator(progress = { p }, modifier = mod)
                }
                is UpdateState.Available, UpdateState.ReadyToInstall -> Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = if (state == UpdateState.ReadyToInstall) onInstall else onUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = c.tertiary, contentColor = c.onTertiary),
                    ) {
                        Text(stringResource(if (state == UpdateState.ReadyToInstall) R.string.update_restart else R.string.update_action))
                    }
                }
                UpdateState.None -> Unit
            }
        }
    }
}
