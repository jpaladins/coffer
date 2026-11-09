// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.data.Entry
import app.coffer.data.childPath
import app.coffer.provider.VaultFileProvider
import app.coffer.settings.BrowserLayout
import app.coffer.settings.SortKey
import app.coffer.settings.SortOrder
import app.coffer.transfer.ImportResult
import app.coffer.ui.components.CofferIcons
import app.coffer.ui.components.EmptyState
import app.coffer.ui.components.ProgressCard
import app.coffer.ui.components.rememberHaptics
import app.coffer.ui.userMessage
import app.coffer.util.MimeTypes

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
    fun onQuery(q: String) {}
    fun onToggleSearch() {}
    fun onSort(order: SortOrder) {}
    fun onLayout(layout: BrowserLayout) {}
    fun onToggleSelect(name: String) {}
    fun onSelectAll() {}
    fun onClearSelection() {}
    fun onDeleteSelected() {}
    fun onExportSelected() {}
}

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, onExit: () -> Unit, onOpenFile: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = rememberHaptics()
    var message by remember { mutableStateOf<BrowserEffect?>(null) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { e ->
                when (e) {
                    BrowserEffect.Exit -> onExit()
                    is BrowserEffect.OpenFile -> onOpenFile(e.path)
                    else -> {
                        when (e) {
                            is BrowserEffect.Imported, is BrowserEffect.Exported, is BrowserEffect.ExportedMany -> haptics.confirm()
                            else -> haptics.reject()
                        }
                        message = e
                    }
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
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onPickerClosed()
        if (uri != null) viewModel.onExportSelected(uri.toString())
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onPickerClosed()
        viewModel.onImport(uris.map { it.toString() })
    }
    // Android 13+: without this permission the import's progress notification
    // is hidden. Asked once, right before the first import; the import runs
    // whatever the answer.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPickerOpening()
        importPicker.launch(arrayOf("*/*"))
    }
    BackHandler {
        val s = viewModel.uiState.value
        when {
            s.selecting -> viewModel.onClearSelection()
            s.searchOpen -> viewModel.onToggleSearch()
            else -> viewModel.onUp()
        }
    }
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
                val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsAsk) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.onPickerOpening()
                    importPicker.launch(arrayOf("*/*"))
                }
            }
            override fun onCancelImport() = viewModel.onCancelImport()
            override fun onNewFolder(name: String) = viewModel.onNewFolder(name)
            override fun onRename(entry: Entry, name: String) = viewModel.onRename(entry, name)
            override fun onDelete(entry: Entry) = viewModel.onDelete(entry)
            override fun onStartMove(entry: Entry) = viewModel.onStartMove(entry)
            override fun onMoveHere() = viewModel.onMoveHere()
            override fun onCancelMove() = viewModel.onCancelMove()
            override fun onQuery(q: String) = viewModel.onQuery(q)
            override fun onToggleSearch() = viewModel.onToggleSearch()
            override fun onSort(order: SortOrder) = viewModel.onSort(order)
            override fun onLayout(layout: BrowserLayout) = viewModel.onLayout(layout)
            override fun onToggleSelect(name: String) = viewModel.onToggleSelect(name)
            override fun onSelectAll() = viewModel.onSelectAll()
            override fun onClearSelection() = viewModel.onClearSelection()
            override fun onDeleteSelected() = viewModel.onDeleteSelected()
            override fun onExportSelected() {
                viewModel.onPickerOpening()
                treePicker.launch(null)
            }
        }
    }
    val thumbs: ThumbSource = remember(viewModel) { { e, px -> viewModel.thumbnail(e, px) } }
    BrowserContent(state, snackbar, actions, thumbs)
}

@Composable
private fun effectText(e: BrowserEffect): String = when (e) {
    is BrowserEffect.Exported -> stringResource(R.string.export_done, e.name)
    is BrowserEffect.ExportedMany -> stringResource(R.string.export_many_done, e.count)
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
    data class DeleteSelected(val count: Int) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserContent(state: BrowserUiState, snackbar: SnackbarHostState, actions: BrowserActions, thumbs: ThumbSource? = null) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    // Sorting and filtering run outside composition of the rows, once per change.
    val visible = remember(state.entries, state.query, state.sort) { state.visible }
    val selectedFiles = remember(state.entries, state.selected) {
        state.entries.any { !it.isDir && it.name in state.selected }
    }
    Scaffold(
        topBar = {
            Column {
                if (state.selecting) {
                    SelectionBar(
                        count = state.selected.size,
                        canExport = selectedFiles && state.exportProgress == null,
                        canDelete = state.canEdit,
                        onClear = actions::onClearSelection,
                        onSelectAll = actions::onSelectAll,
                        onExport = actions::onExportSelected,
                        onDelete = { dialog = Dialog.DeleteSelected(state.selected.size) },
                    )
                } else {
                    BrowserTopBar(state, actions) { dialog = Dialog.NewFolder }
                }
                Badges(state)
                Breadcrumbs(state.breadcrumbs, actions::onBreadcrumb)
                if (state.busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportProgress?.let {
                    ProgressCard(
                        stringResource(R.string.export_running),
                        "${(it * 100).toInt()} %",
                        it,
                        onStop = null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.transfer?.let { t ->
                    ProgressCard(
                        t.name,
                        stringResource(R.string.import_progress, t.name, t.index, t.total) + " · " + humanSize(t.bytes),
                        null,
                        onStop = actions::onCancelImport,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("import_progress"),
                    )
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
            if (state.writable && state.transfer == null && state.moving == null && !state.selecting) {
                ExtendedFloatingActionButton(
                    onClick = actions::onImport,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.browser_import)) },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val writable = state.canEdit && state.moving == null
            val rename = { e: Entry -> dialog = Dialog.Rename(e) }
            val delete = { e: Entry -> dialog = Dialog.Delete(e) }
            when {
                state.loading && state.entries.isEmpty() -> LoadingIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error.userMessage(), Modifier.align(Alignment.Center).padding(24.dp))
                state.entries.isEmpty() -> EmptyState(
                    CofferIcons.FolderOpen,
                    stringResource(R.string.browser_empty),
                    if (state.writable) stringResource(R.string.browser_empty_body) else "",
                    Modifier.align(Alignment.Center),
                )
                visible.isEmpty() -> Text(
                    stringResource(R.string.browser_no_results),
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.layout == BrowserLayout.Grid -> LazyVerticalGrid(
                    GridCells.Adaptive(104.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.name }) { e ->
                        FileTile(
                            e, e.name in state.selected, state.selecting, writable, actions,
                            onRename = { rename(e) }, onDelete = { delete(e) },
                            modifier = Modifier.animateItem(),
                            preview = { Thumb(e, thumbs, 256) { FileIcon(e) } },
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
                    items(visible, key = { it.name }) { e ->
                        FileRow(
                            e, e.name in state.selected, state.selecting, writable, actions,
                            onRename = { rename(e) }, onDelete = { delete(e) },
                            modifier = Modifier.animateItem(),
                            leading = {
                                Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                                    Thumb(e, thumbs, 96) { FileIcon(e) }
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
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
        is Dialog.Delete -> DeleteDialog(stringResource(R.string.browser_delete_title, d.entry.name), { dialog = null }) {
            dialog = null
            actions.onDelete(d.entry)
        }
        is Dialog.DeleteSelected -> DeleteDialog(stringResource(R.string.selection_delete_title, d.count), { dialog = null }) {
            dialog = null
            actions.onDeleteSelected()
        }
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(state: BrowserUiState, actions: BrowserActions, onNewFolder: () -> Unit) {
    var overflow by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (state.searchOpen) {
                val focus = remember { FocusRequester() }
                TextField(
                    value = state.query,
                    onValueChange = actions::onQuery,
                    placeholder = { Text(stringResource(R.string.browser_search), maxLines = 1) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Text(state.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = actions::onUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = actions::onToggleSearch) {
                if (state.searchOpen) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_search_close))
                } else {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.browser_search))
                }
            }
            AnimatedVisibility(!state.searchOpen) {
                Row {
                    IconButton(onClick = actions::onLock) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.browser_lock))
                    }
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        val grid = state.layout == BrowserLayout.Grid
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_sort)) },
                                leadingIcon = { Icon(CofferIcons.Sort, contentDescription = null) },
                                onClick = { overflow = false; sortMenu = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (grid) R.string.browser_view_list else R.string.browser_view_grid)) },
                                leadingIcon = { Icon(if (grid) CofferIcons.ViewList else CofferIcons.GridView, contentDescription = null) },
                                onClick = {
                                    overflow = false
                                    actions.onLayout(if (grid) BrowserLayout.List else BrowserLayout.Grid)
                                },
                            )
                            if (state.writable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_new_folder)) },
                                    leadingIcon = { Icon(CofferIcons.NewFolder, contentDescription = null) },
                                    enabled = state.canEdit,
                                    onClick = { overflow = false; onNewFolder() },
                                )
                            }
                        }
                        SortMenu(sortMenu, state.sort, { sortMenu = false }, actions::onSort)
                    }
                }
            }
        },
    )
}

@Composable
private fun SortMenu(expanded: Boolean, current: SortOrder, onDismiss: () -> Unit, onSort: (SortOrder) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(SortKey.Name to R.string.sort_name, SortKey.Modified to R.string.sort_modified, SortKey.Size to R.string.sort_size)
            .forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { if (current.key == key) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { onSort(current.copy(key = key)); onDismiss() },
                )
            }
        HorizontalDivider()
        listOf(true to R.string.sort_ascending, false to R.string.sort_descending).forEach { (asc, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { if (current.ascending == asc) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = { onSort(current.copy(ascending = asc)); onDismiss() },
            )
        }
    }
}

@Composable
private fun DeleteDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(R.string.browser_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.browser_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

@Composable
private fun Badges(state: BrowserUiState) {
    val chips = buildList {
        state.freeBytes?.let { add(stringResource(R.string.browser_free, humanSize(it))) }
        if (!state.writable) add(stringResource(R.string.browser_read_only))
        if (state.hiddenProtected) add(stringResource(R.string.browser_hidden_protected))
    }
    if (chips.isNotEmpty()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelMedium) }) }
        }
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

/** Path as scrollable chips; the first one is the volume root. */
@Composable
private fun Breadcrumbs(parts: List<String>, onClick: (Int) -> Unit) {
    if (parts.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilterChip(selected = false, onClick = { onClick(-1) }, label = { Text("/") }) }
        itemsIndexed(parts) { i, p ->
            FilterChip(
                selected = i == parts.lastIndex,
                onClick = { onClick(i) },
                label = { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

fun humanSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
}
