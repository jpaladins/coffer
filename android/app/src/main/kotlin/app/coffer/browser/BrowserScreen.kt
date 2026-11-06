// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.data.CofferException
import app.coffer.data.Entry
import app.coffer.data.childPath
import app.coffer.provider.VaultFileProvider
import app.coffer.transfer.ImportResult
import app.coffer.ui.userMessage
import app.coffer.util.MimeTypes
import java.text.DateFormat
import java.util.Date

/** Callbacks of the browser content; the ViewModel-backed screen wires them. */
interface BrowserActions {
    fun onUp()
    fun onLock()
    fun onOpen(entry: Entry)
    fun onBreadcrumb(index: Int)
    fun onExport(entry: Entry)
    fun onOpenWith(entry: Entry)
    fun onImport()
    fun onCancelImport()
    fun onNewFolder(name: String)
    fun onRename(entry: Entry, name: String)
    fun onDelete(entry: Entry)
    fun onStartMove(entry: Entry)
    fun onMoveHere()
    fun onCancelMove()
}

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, onExit: () -> Unit, onOpenFile: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var message by remember { mutableStateOf<BrowserEffect?>(null) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { e ->
                when (e) {
                    BrowserEffect.Exit -> onExit()
                    is BrowserEffect.OpenFile -> onOpenFile(e.path)
                    else -> message = e
                }
            }
        }
    }
    message?.let { m ->
        val text = effectText(m)
        LaunchedEffect(m) {
            snackbar.showSnackbar(text)
            message = null
        }
    }
    var pendingExport by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        viewModel.onPickerClosed()
        val p = pendingExport
        if (uri != null && p != null) viewModel.onExport(p.first, p.second, uri.toString())
        pendingExport = null
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onPickerClosed()
        viewModel.onImport(uris.map { it.toString() })
    }
    BackHandler(onBack = viewModel::onUp)
    val actions = remember(viewModel) {
        object : BrowserActions {
            override fun onUp() = viewModel.onUp()
            override fun onLock() = viewModel.onLock()
            override fun onOpen(entry: Entry) = viewModel.onOpen(entry)
            override fun onBreadcrumb(index: Int) = viewModel.onBreadcrumb(index)
            override fun onExport(entry: Entry) {
                pendingExport = childPath(viewModel.uiState.value.path, entry.name) to entry.size
                viewModel.onPickerOpening()
                exportPicker.launch(entry.name)
            }
            override fun onOpenWith(entry: Entry) {
                val token = viewModel.shareToken() ?: return
                val uri = VaultFileProvider.uriFor(token, childPath(viewModel.uiState.value.path, entry.name))
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MimeTypes.mime(entry.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(view, entry.name)) }
            }
            override fun onImport() {
                viewModel.onPickerOpening()
                importPicker.launch(arrayOf("*/*"))
            }
            override fun onCancelImport() = viewModel.onCancelImport()
            override fun onNewFolder(name: String) = viewModel.onNewFolder(name)
            override fun onRename(entry: Entry, name: String) = viewModel.onRename(entry, name)
            override fun onDelete(entry: Entry) = viewModel.onDelete(entry)
            override fun onStartMove(entry: Entry) = viewModel.onStartMove(entry)
            override fun onMoveHere() = viewModel.onMoveHere()
            override fun onCancelMove() = viewModel.onCancelMove()
        }
    }
    BrowserContent(state, snackbar, actions)
}

@Composable
private fun effectText(e: BrowserEffect): String = when (e) {
    is BrowserEffect.Exported -> stringResource(R.string.export_done, e.name)
    is BrowserEffect.ExportFailed -> stringResource(R.string.export_failed, e.error.userMessage())
    is BrowserEffect.Failed -> e.error.userMessage()
    is BrowserEffect.Imported -> importText(e.result)
    else -> ""
}

@Composable
private fun importText(r: ImportResult): String {
    val first = r.failures.firstOrNull()
    return if (first != null) {
        stringResource(R.string.import_failed, first.first, first.second.userMessage())
    } else {
        stringResource(R.string.import_done, r.imported, r.total)
    }
}

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val entry: Entry) : Dialog
    data class Delete(val entry: Entry) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserContent(state: BrowserUiState, snackbar: SnackbarHostState, actions: BrowserActions) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var overflow by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = actions::onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    },
                    actions = {
                        IconButton(onClick = actions::onLock) {
                            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.browser_lock))
                        }
                        if (state.writable) {
                            Box {
                                IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browser_new_folder)) },
                                        enabled = state.canEdit,
                                        onClick = { overflow = false; dialog = Dialog.NewFolder },
                                    )
                                }
                            }
                        }
                    },
                )
                Badges(state)
                Breadcrumbs(state.breadcrumbs, actions::onBreadcrumb)
                state.exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.transfer?.let { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.import_progress, t.name, t.index, t.total) + " · " + humanSize(t.bytes),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = actions::onCancelImport) { Text(stringResource(R.string.import_stop)) }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("import_progress"))
                }
            }
        },
        bottomBar = {
            state.moving?.let { m ->
                BottomAppBar {
                    Text(
                        stringResource(R.string.browser_moving, m.substringAfterLast('/')),
                        Modifier.weight(1f).padding(start = 16.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = actions::onCancelMove) { Text(stringResource(R.string.browser_cancel)) }
                    TextButton(onClick = actions::onMoveHere, enabled = state.canMoveHere && state.canEdit) {
                        Text(stringResource(R.string.browser_move_here))
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.writable && state.transfer == null && state.moving == null) {
                ExtendedFloatingActionButton(
                    onClick = actions::onImport,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.browser_import)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error.userMessage(), Modifier.align(Alignment.Center).padding(24.dp))
                state.entries.isEmpty() -> Text(stringResource(R.string.browser_empty), Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.name }) { e ->
                        EntryRow(
                            e,
                            writable = state.canEdit && state.moving == null,
                            actions = actions,
                            onRename = { dialog = Dialog.Rename(e) },
                            onDelete = { dialog = Dialog.Delete(e) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog(stringResource(R.string.browser_new_folder), "", { dialog = null }) {
            dialog = null
            actions.onNewFolder(it)
        }
        is Dialog.Rename -> NameDialog(stringResource(R.string.browser_rename), d.entry.name, { dialog = null }) {
            dialog = null
            actions.onRename(d.entry, it)
        }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.browser_delete_title, d.entry.name)) },
            text = { Text(stringResource(R.string.browser_delete_body)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; actions.onDelete(d.entry) }) {
                    Text(stringResource(R.string.browser_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.browser_cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun Badges(state: BrowserUiState) {
    state.freeBytes?.let {
        Text(
            stringResource(R.string.browser_free, humanSize(it)),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.writable && !state.hiddenProtected) return
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.writable) AssistChip(onClick = {}, label = { Text(stringResource(R.string.browser_read_only)) })
        if (state.hiddenProtected) AssistChip(onClick = {}, label = { Text(stringResource(R.string.browser_hidden_protected)) })
    }
    if (state.writeDenied) {
        Text(
            stringResource(R.string.browser_write_denied),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Preselect the name without its extension, as file managers do.
    val stem = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val ok = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.browser_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.testTag("name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = ok) { Text(stringResource(R.string.browser_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
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
private fun EntryRow(
    e: Entry,
    writable: Boolean,
    actions: BrowserActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val hasMenu = !e.isDir || writable
    ListItem(
        modifier = Modifier.combinedClickable(onClick = { actions.onOpen(e) }, onLongClick = { if (hasMenu) menu = true }),
        leadingContent = { FileIcon(e) },
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!e.isDir) {
                val date = e.modified?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) }
                Text(listOfNotNull(humanSize(e.size), date).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            if (hasMenu) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!e.isDir) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.browser_open_with)) }, onClick = { menu = false; actions.onOpenWith(e) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.browser_export)) }, onClick = { menu = false; actions.onExport(e) })
                        }
                        if (writable) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.browser_rename)) }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.browser_move)) }, onClick = { menu = false; actions.onStartMove(e) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.browser_delete)) }, onClick = { menu = false; onDelete() })
                        }
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
