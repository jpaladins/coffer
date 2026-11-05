// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.data.Entry
import app.coffer.provider.VaultFileProvider
import app.coffer.ui.userMessage
import app.coffer.util.MimeTypes
import java.text.DateFormat
import java.util.Date

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, onExit: () -> Unit, onOpenFile: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val exportDone = stringResource(R.string.export_done, "%s")
    val exportFailed = stringResource(R.string.export_failed, "%s")
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { e ->
                when (e) {
                    BrowserEffect.Exit -> onExit()
                    is BrowserEffect.OpenFile -> onOpenFile(e.path)
                    is BrowserEffect.Exported -> snackbar.showSnackbar(exportDone.replace("%s", e.name))
                    is BrowserEffect.ExportFailed -> snackbar.showSnackbar(exportFailed.replace("%s", e.error.message.orEmpty()))
                }
            }
        }
    }
    var pendingExport by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pendingExport
        if (uri != null && p != null) viewModel.onExport(p.first, p.second, uri.toString())
        pendingExport = null
    }
    BackHandler(onBack = viewModel::onUp)
    BrowserContent(
        state = state,
        snackbar = snackbar,
        onUp = viewModel::onUp,
        onLock = viewModel::onLock,
        onOpen = viewModel::onOpen,
        onBreadcrumb = viewModel::onBreadcrumb,
        onExport = { entry ->
            pendingExport = childPath(state.path, entry.name) to entry.size
            exportPicker.launch(entry.name)
        },
        onOpenWith = { entry ->
            val uri = VaultFileProvider.uriFor(viewModel.vaultIdForUi(), childPath(state.path, entry.name))
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MimeTypes.mime(entry.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(view, entry.name)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserContent(
    state: BrowserUiState,
    snackbar: SnackbarHostState,
    onUp: () -> Unit,
    onLock: () -> Unit,
    onOpen: (Entry) -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onExport: (Entry) -> Unit,
    onOpenWith: (Entry) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    },
                    actions = {
                        IconButton(onClick = onLock) {
                            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.browser_lock))
                        }
                    },
                )
                Breadcrumbs(state.breadcrumbs, onBreadcrumb)
                state.exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error.userMessage(), Modifier.align(Alignment.Center).padding(24.dp))
                state.entries.isEmpty() -> Text(stringResource(R.string.browser_empty), Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.name }) { e ->
                        EntryRow(e, onOpen, onExport, onOpenWith)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(parts: List<String>, onClick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onClick(-1) }) { Text("/") }
        parts.forEachIndexed { i, p ->
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
            TextButton(onClick = { onClick(i) }) { Text(p, maxLines = 1) }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, onOpen: (Entry) -> Unit, onExport: (Entry) -> Unit, onOpenWith: (Entry) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = { onOpen(e) }, onLongClick = { if (!e.isDir) menu = true }),
        leadingContent = { FileIcon(e) },
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!e.isDir) {
                val date = e.modified?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) }
                Text(listOfNotNull(humanSize(e.size), date).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            if (!e.isDir) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.browser_open_with)) }, onClick = { menu = false; onOpenWith(e) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.browser_export)) }, onClick = { menu = false; onExport(e) })
                    }
                }
            }
        },
    )
}

fun humanSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
}
