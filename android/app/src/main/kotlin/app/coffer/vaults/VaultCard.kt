// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.vaults

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.coffer.R
import app.coffer.browser.humanSize
import app.coffer.ui.components.HexBadge
import app.coffer.ui.components.pressScale
import app.coffer.ui.theme.CofferRadii
import app.coffer.ui.theme.CofferTheme
import app.coffer.ui.theme.TabularNums

/** One container: hexagon, name, state badge; space when open, last access when locked. */
@Composable
fun VaultCard(
    item: VaultItem,
    onOpen: () -> Unit,
    onLock: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val source = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().pressScale(source),
        shape = RoundedCornerShape(CofferRadii.card),
        colors = CardDefaults.cardColors(containerColor = if (item.isOpen) c.surfaceContainerHigh else c.surfaceContainerLow),
        interactionSource = source,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HexBadge(
                    Icons.Filled.Lock,
                    size = 48.dp,
                    container = if (item.isOpen) c.tertiary else c.primaryContainer,
                    content = if (item.isOpen) c.onTertiary else c.onPrimaryContainer,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StateBadge(item.isOpen)
                        item.fileBytes?.let {
                            Text(
                                humanSize(it),
                                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                                color = c.onSurfaceVariant,
                            )
                        }
                    }
                }
                CardMenu(onDetails, onRemove)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(end = 12.dp)) {
                if (item.isOpen) OpenDetails(item, onLock) else LastAccess(item, now)
            }
        }
    }
}

@Composable
private fun StateBadge(open: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = if (open) c.tertiaryContainer else c.surfaceVariant,
        contentColor = if (open) c.onTertiaryContainer else c.onSurfaceVariant,
        shape = CircleShape,
    ) {
        Text(
            stringResource(if (open) R.string.vaults_open_badge else R.string.vault_closed_badge),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun OpenDetails(item: VaultItem, onLock: () -> Unit) {
    val total = item.volumeBytes
    val free = item.freeBytes
    item.fsKind?.let {
        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (total != null && free != null && total > 0) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (1f - free.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.vault_free_of, humanSize(free), humanSize(total)),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = onLock) {
        Icon(Icons.Filled.Lock, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.vault_lock))
    }
}

@Composable
private fun LastAccess(item: VaultItem, now: Long) {
    val res = LocalContext.current.resources
    val text = item.lastAccess?.let { stringResource(R.string.vault_last_access, relativeTime(now, it, res)) }
        ?: stringResource(R.string.vault_never_opened)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CardMenu(onDetails: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.vault_details)) }, onClick = { menu = false; onDetails() })
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.vaults_remove))
                        Text(stringResource(R.string.vaults_remove_hint), style = MaterialTheme.typography.bodySmall)
                    }
                },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}

/** Name, size and, when open, encryption details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultDetailsSheet(item: VaultItem, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(item.label, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            val unknown = stringResource(R.string.vault_details_unknown)
            DetailRow(stringResource(R.string.vault_details_size), item.fileBytes?.let(::humanSize) ?: unknown)
            if (item.isOpen) {
                DetailRow(stringResource(R.string.vault_details_cipher), item.cipher ?: unknown)
                DetailRow(stringResource(R.string.vault_details_prf), item.prf ?: unknown)
                DetailRow(stringResource(R.string.vault_details_fs), item.fsKind ?: unknown)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.vault_details_locked_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge.merge(TabularNums))
    }
}

private val openSample = VaultItem("a", "Documents.hc", true, 2L shl 30, "exFAT", 700L shl 20, 2L shl 30, "AES", "SHA-512")
private val closedSample = VaultItem("b", "Photos.hc", false, 512L shl 20, lastAccess = 0L)

@Composable private fun Samples() = Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    VaultCard(openSample, {}, {}, {}, {})
    VaultCard(closedSample, {}, {}, {}, {}, now = 2 * 3_600_000L)
}

@Preview @Composable private fun CardsLight() = CofferTheme(dark = false) { Surface { Samples() } }
@Preview @Composable private fun CardsDark() = CofferTheme(dark = true) { Surface { Samples() } }
