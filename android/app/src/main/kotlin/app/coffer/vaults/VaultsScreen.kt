// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.vaults

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import app.coffer.security.AUTOLOCK_OPTIONS
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coffer.R
import app.coffer.ui.CofferMark

@Composable
fun VaultsScreen(
    viewModel: VaultsViewModel,
    autoLockMs: Long?,
    onAutoLockChange: (Long) -> Unit,
    onCreate: () -> Unit,
    onOpen: (id: String, isOpen: Boolean) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings && autoLockMs != null) {
        SettingsDialog(autoLockMs, onAutoLockChange) { showSettings = false }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onDocumentPicked(it.toString()) }
    }
    VaultsContent(
        state = state,
        onAdd = { picker.launch(arrayOf("*/*")) },
        onCreate = onCreate,
        onOpen = { onOpen(it.id, viewModel.isOpen(it.id)) },
        onRemove = viewModel::onRemove,
        onLockAll = viewModel::onLockAll,
        onSettings = { showSettings = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultsContent(
    state: VaultsUiState,
    onAdd: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onRemove: (String) -> Unit,
    onLockAll: () -> Unit,
    onSettings: () -> Unit = {},
    onCreate: () -> Unit = {},
) {
    var addMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vaults_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_autolock))
                    }
                    if (state.anyOpen) {
                        IconButton(onClick = onLockAll) {
                            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.vaults_lock_all))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.vaults.isNotEmpty()) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.vaults_add)) },
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.vaults_add_existing)) }, onClick = { addMenu = false; onAdd() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.vaults_create_new)) }, onClick = { addMenu = false; onCreate() })
                    }
                }
            }
        },
    ) { padding ->
        if (state.loaded && state.vaults.isEmpty()) {
            EmptyVaults(Modifier.padding(padding), onAdd, onCreate)
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.vaults, key = { it.id }) { VaultCard(it, onOpen, onRemove) }
            }
        }
    }
}

@Composable
private fun VaultCard(item: VaultItem, onOpen: (VaultItem) -> Unit, onRemove: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CofferMark(size = 40.dp, open = item.isOpen)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.isOpen) {
                    AssistChip(onClick = { onOpen(item) }, label = { Text(stringResource(R.string.vaults_open_badge)) })
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.vaults_remove))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.vaults_remove))
                                Text(stringResource(R.string.vaults_remove_hint), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { menu = false; onRemove(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyVaults(modifier: Modifier, onAdd: () -> Unit, onCreate: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CofferMark(size = 96.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.vaults_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.vaults_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vaults_add_existing))
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(onClick = onCreate) {
            Text(stringResource(R.string.vaults_create_new))
        }
    }
}

@Composable
private fun SettingsDialog(current: Long, onChange: (Long) -> Unit, onDismiss: () -> Unit) {
    val labels = mapOf(
        0L to R.string.autolock_immediately,
        30_000L to R.string.autolock_30s,
        120_000L to R.string.autolock_2m,
        300_000L to R.string.autolock_5m,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(stringResource(R.string.settings_autolock)) },
        text = {
            Column {
                AUTOLOCK_OPTIONS.forEach { ms ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = ms == current, onClick = { onChange(ms) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ms == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(labels.getValue(ms)))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.about_trademark), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
