// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.vaults

import app.coffer.ui.components.rememberHaptics
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coffer.R
import app.coffer.ui.components.CofferIcons
import app.coffer.ui.components.CofferTopBar
import app.coffer.ui.components.EmptyState
import app.coffer.ui.components.rememberAnimationsEnabled
import kotlinx.coroutines.delay

@Composable
fun VaultsScreen(
    viewModel: VaultsViewModel,
    onSettings: () -> Unit,
    onCreate: () -> Unit,
    openPicker: Boolean = false,
    onOpen: (id: String, isOpen: Boolean) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onDocumentPicked(it.toString()) }
    }
    LaunchedEffect(Unit) { if (openPicker) picker.launch(arrayOf("*/*")) }
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
    VaultsContent(
        state = state,
        onAdd = { picker.launch(arrayOf("*/*")) },
        onCreate = onCreate,
        onOpen = { onOpen(it.id, viewModel.isOpen(it.id)) },
        onLock = viewModel::onLock,
        onRemove = viewModel::onRemove,
        onLockAll = viewModel::onLockAll,
        onSettings = onSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultsContent(
    state: VaultsUiState,
    onAdd: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onRemove: (String) -> Unit,
    onLockAll: () -> Unit,
    onSettings: () -> Unit = {},
    onCreate: () -> Unit = {},
    onLock: (String) -> Unit = {},
) {
    var addSheet by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val haptics = rememberHaptics()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            CofferTopBar(stringResource(R.string.vaults_title), scroll, large = false) {
                AnimatedVisibility(state.anyOpen, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    IconButton(onClick = { haptics.confirm(); onLockAll() }) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.vaults_lock_all))
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.vaults_settings))
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(state.vaults.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                ExtendedFloatingActionButton(
                    onClick = { addSheet = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.vaults_add)) },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                )
            }
        },
    ) { padding ->
      // Empty state and list cross-fade when the last container is removed or the first added.
      AnimatedContent(state.loaded && state.vaults.isEmpty(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "vaults") { empty ->
        if (empty) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                EmptyState(
                    CofferIcons.FolderOpen,
                    stringResource(R.string.vaults_empty_title),
                    stringResource(R.string.vaults_empty_body),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onAdd,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.vaults_add_existing))
                        }
                        OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.vaults_create_new))
                        }
                    }
                }
            }
        } else {
            val animate = rememberAnimationsEnabled()
            // Staggered entrance only the first time the list appears.
            var entered by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.vaults.isNotEmpty()) { if (state.vaults.isNotEmpty()) entered = true }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.vaults, key = { _, it -> it.id }) { index, item ->
                    val appear = remember { Animatable(if (entered || !animate) 1f else 0f) }
                    LaunchedEffect(Unit) {
                        if (appear.value < 1f) {
                            delay(40L * index)
                            appear.animateTo(1f)
                        }
                    }
                    VaultCard(
                        item,
                        onOpen = { onOpen(item) },
                        onLock = { onLock(item.id) },
                        onRemove = { onRemove(item.id) },
                        onDetails = { details = item.id },
                        modifier = Modifier.animateItem().graphicsLayer {
                            alpha = appear.value
                            translationY = (1f - appear.value) * 24.dp.toPx()
                        },
                    )
                }
            }
        }
      }
    }
    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.vaults_add_existing)) },
                    leadingContent = { Icon(CofferIcons.FolderOpen, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickableRow { addSheet = false; onAdd() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.vaults_create_new)) },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickableRow { addSheet = false; onCreate() },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    details?.let { id -> state.vaults.firstOrNull { it.id == id } }?.let { item ->
        VaultDetailsSheet(item) { details = null }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = clickable(onClick = onClick).heightIn(min = 56.dp)
