// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.CofferException
import app.coffer.data.Entry
import app.coffer.data.SessionManager
import app.coffer.data.VaultHandle
import app.coffer.data.childPath
import app.coffer.data.removeRecursive
import app.coffer.platform.Exporter
import app.coffer.platform.IoDispatcher
import app.coffer.security.LockGuard
import app.coffer.transfer.ImportResult
import app.coffer.transfer.TransferManager
import app.coffer.transfer.TransferProgress
import app.coffer.settings.BrowserLayout
import app.coffer.settings.SortOrder
import app.coffer.settings.UiPrefs
import app.coffer.util.MimeTypes
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowserUiState(
    val label: String = "",
    val path: String = "/",
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = true,
    val error: CofferException? = null,
    /** 0..1 while an export runs. */
    val exportProgress: Float? = null,
    val writable: Boolean = false,
    val hiddenProtected: Boolean = false,
    /** Writing was requested but the file grant or provider is read-only. */
    val writeDenied: Boolean = false,
    val freeBytes: Long? = null,
    /** Import running in this vault. */
    val transfer: TransferProgress? = null,
    /** Path picked with "Move", waiting for "Move here". */
    val moving: String? = null,
    /** A write operation (mkdir, rename, delete, move) is running. */
    val busy: Boolean = false,
    /** Name filter for the current folder. */
    val query: String = "",
    val searchOpen: Boolean = false,
    /** Selected names in the current folder. */
    val selected: Set<String> = emptySet(),
    val layout: BrowserLayout = BrowserLayout.List,
    val sort: SortOrder = SortOrder(),
) {
    /** What the list shows: filtered by [query], then sorted. */
    val visible: List<Entry> get() = sortEntries(filterEntries(entries, query), sort)
    val selecting: Boolean get() = selected.isNotEmpty()
    /** Edits wait for a running import: both would change the same folders. */
    val canEdit: Boolean get() = writable && !busy && transfer == null
    val breadcrumbs: List<String> get() = path.split('/').filter { it.isNotEmpty() }
    val canMoveHere: Boolean
        get() = moving != null && moving.substringBeforeLast('/').ifEmpty { "/" } != path &&
            path != moving && !path.startsWith("$moving/")
}

sealed interface BrowserEffect {
    data object Exit : BrowserEffect
    data class OpenFile(val path: String) : BrowserEffect
    data class Exported(val name: String) : BrowserEffect
    data class ExportedMany(val count: Int) : BrowserEffect
    data class ExportFailed(val error: CofferException) : BrowserEffect
    data class Failed(val error: CofferException) : BrowserEffect
    data class Imported(val result: ImportResult) : BrowserEffect
}

private const val CHUNK = 1 shl 20

@HiltViewModel(assistedFactory = BrowserViewModel.Factory::class)
class BrowserViewModel @AssistedInject constructor(
    @Assisted private val vaultId: String,
    private val sessions: SessionManager,
    private val exporter: Exporter,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val transfers: TransferManager,
    private val guard: LockGuard,
    private val uiPrefs: UiPrefs? = null,
    private val thumbnails: ThumbnailLoader? = null,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(vaultId: String): BrowserViewModel
    }

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()
    private val _effects = Channel<BrowserEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        sessions.session(vaultId)?.let { s ->
            val info = s.handle.info
            _uiState.update {
                it.copy(writable = info.writable, hiddenProtected = info.hiddenProtected, writeDenied = s.writeDenied)
            }
        }
        load("/")
        uiPrefs?.let { prefs ->
            viewModelScope.launch { prefs.layout.collect { l -> _uiState.update { it.copy(layout = l) } } }
            viewModelScope.launch { prefs.sort.collect { s -> _uiState.update { it.copy(sort = s) } } }
        }
        viewModelScope.launch {
            transfers.progress.collect { p -> _uiState.update { it.copy(transfer = p?.takeIf { p.vaultId == vaultId }) } }
        }
        viewModelScope.launch {
            transfers.results.collect { r ->
                if (r.vaultId != vaultId) return@collect
                _effects.send(BrowserEffect.Imported(r))
                if (r.dir == _uiState.value.path) load(r.dir)
            }
        }
    }

    private fun handle() = sessions.session(vaultId)?.handle

    /** Per-session token for share URIs; null once the vault is locked. */
    fun shareToken(): String? = sessions.session(vaultId)?.token

    private fun load(path: String) {
        val session = sessions.session(vaultId)
        if (session == null) {
            _effects.trySend(BrowserEffect.Exit)
            return
        }
        _uiState.update { it.copy(label = session.ref.label, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val list = sortEntries(withContext(io) { session.handle.list(path) }, SortOrder())
                val free = withContext(io) { runCatching { session.handle.freeBytes() }.getOrNull() }
                _uiState.update {
                    val moved = path != it.path
                    val names = list.mapTo(HashSet()) { e -> e.name }
                    it.copy(
                        path = path, entries = list, loading = false, freeBytes = free,
                        // Another folder: selection and search start over.
                        selected = if (moved) emptySet() else it.selected.filterTo(HashSet()) { n -> n in names },
                        query = if (moved) "" else it.query,
                        searchOpen = if (moved) false else it.searchOpen,
                    )
                }
            } catch (e: CofferException.Closed) {
                _effects.send(BrowserEffect.Exit)
            } catch (e: CofferException) {
                _uiState.update { it.copy(loading = false, error = e) }
            }
        }
    }

    /** Photo preview of an entry in the current folder, from memory or decoded now. */
    suspend fun thumbnail(entry: Entry, targetPx: Int): androidx.compose.ui.graphics.ImageBitmap? =
        thumbnails?.load(vaultId, childPath(_uiState.value.path, entry.name), entry.size, targetPx, entry.modified)

    fun onQuery(q: String) = _uiState.update { it.copy(query = q) }

    fun onToggleSearch() = _uiState.update { it.copy(searchOpen = !it.searchOpen, query = "") }

    fun onSort(order: SortOrder) {
        _uiState.update { it.copy(sort = order) }
        uiPrefs?.let { viewModelScope.launch { it.setSort(order) } }
    }

    fun onLayout(l: BrowserLayout) {
        _uiState.update { it.copy(layout = l) }
        uiPrefs?.let { viewModelScope.launch { it.setLayout(l) } }
    }

    fun onToggleSelect(name: String) = _uiState.update {
        it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name)
    }

    fun onSelectAll() = _uiState.update { s -> s.copy(selected = s.visible.mapTo(HashSet()) { it.name }) }

    fun onClearSelection() = _uiState.update { it.copy(selected = emptySet()) }

    /** Deletes every selected entry; refused like any edit while an import runs. */
    fun onDeleteSelected() {
        val s = _uiState.value
        if (!s.canEdit || s.selected.isEmpty()) return
        val names = s.selected.toList()
        _uiState.update { it.copy(selected = emptySet()) }
        mutate { h, dir -> names.forEach { h.removeRecursive(childPath(dir, it)) } }
    }

    /** Exports the selected files (folders are skipped) into a folder picked with OpenDocumentTree. */
    fun onExportSelected(treeUri: String) {
        val s = _uiState.value
        val files = s.entries.filter { !it.isDir && it.name in s.selected }
        if (files.isEmpty()) return
        val h = handle() ?: return run { _effects.trySend(BrowserEffect.Exit) }
        val total = files.sumOf { it.size }.coerceAtLeast(1)
        val dir = s.path
        _uiState.update { it.copy(exportProgress = 0f, selected = emptySet()) }
        guard.acquire()
        viewModelScope.launch {
            try {
                var done = 0L
                withContext(io) {
                    files.forEach { e ->
                        val target = exporter.createInTree(treeUri, e.name, MimeTypes.mime(e.name))
                        copyOut(h, childPath(dir, e.name), target) { n ->
                            done += n
                            _uiState.update { it.copy(exportProgress = (done.toFloat() / total).coerceAtMost(1f)) }
                        }
                    }
                }
                _effects.send(BrowserEffect.ExportedMany(files.size))
            } catch (e: CofferException) {
                _effects.send(BrowserEffect.ExportFailed(e))
            } catch (e: java.io.IOException) {
                _effects.send(BrowserEffect.ExportFailed(CofferException.Io(e.message ?: "write failed")))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // A revoked folder permission or a provider bug: report it, do not crash.
                _effects.send(BrowserEffect.ExportFailed(CofferException.Io(e.message ?: "write failed")))
            } finally {
                guard.release()
                _uiState.update { it.copy(exportProgress = null) }
            }
        }
    }

    /** Streams one file to a SAF document in 1 MiB chunks, wiping each chunk after use. */
    private fun copyOut(h: VaultHandle, path: String, targetUri: String, onBytes: (Int) -> Unit) {
        exporter.openSink(targetUri).use { out ->
            var off = 0L
            while (true) {
                val chunk = h.read(path, off, CHUNK)
                if (chunk.isEmpty()) break
                out.write(chunk)
                onBytes(chunk.size)
                chunk.fill(0)
                off += chunk.size
            }
        }
    }

    fun onOpen(entry: Entry) {
        val path = childPath(_uiState.value.path, entry.name)
        if (entry.isDir) load(path) else _effects.trySend(BrowserEffect.OpenFile(path))
    }

    fun onBreadcrumb(index: Int) {
        load("/" + _uiState.value.breadcrumbs.take(index + 1).joinToString("/"))
    }

    fun onUp() {
        val p = _uiState.value.path
        if (p == "/") _effects.trySend(BrowserEffect.Exit) else load(p.substringBeforeLast('/').ifEmpty { "/" })
    }

    fun onLock() {
        viewModelScope.launch {
            // SessionManager stops a running import first (TransferManager).
            withContext(io) { sessions.close(vaultId) }
            _effects.send(BrowserEffect.Exit)
        }
    }

    /** Call before launching a system picker; the result callback calls [onPickerClosed]. */
    fun onPickerOpening() = guard.acquirePicker()

    fun onPickerClosed() = guard.releasePicker()

    fun onImport(uris: List<String>) {
        if (uris.isEmpty()) return
        if (!transfers.import(vaultId, _uiState.value.path, uris)) {
            _effects.trySend(BrowserEffect.Failed(CofferException.Io("another import is running")))
        }
    }

    fun onCancelImport() = transfers.cancel()

    fun onNewFolder(name: String) = mutate { h, dir -> h.mkdir(childPath(dir, name.trim())) }

    fun onRename(entry: Entry, newName: String) = mutate { h, dir ->
        val name = newName.trim()
        if (name != entry.name) h.rename(childPath(dir, entry.name), childPath(dir, name))
    }

    fun onDelete(entry: Entry) = mutate { h, dir -> h.removeRecursive(childPath(dir, entry.name)) }

    fun onStartMove(entry: Entry) = _uiState.update { it.copy(moving = childPath(it.path, entry.name)) }

    fun onCancelMove() = _uiState.update { it.copy(moving = null) }

    fun onMoveHere() {
        val from = _uiState.value.moving ?: return
        if (!_uiState.value.canMoveHere) return
        _uiState.update { it.copy(moving = null) }
        mutate { h, dir -> h.rename(from, childPath(dir, from.substringAfterLast('/'))) }
    }

    /** Runs a write in the current folder, then syncs and reloads it. */
    private fun mutate(op: (VaultHandle, String) -> Unit) {
        if (_uiState.value.transfer != null) return
        val h = handle() ?: return run { _effects.trySend(BrowserEffect.Exit) }
        val dir = _uiState.value.path
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    op(h, dir)
                    h.sync()
                }
            } catch (e: CofferException.Closed) {
                _effects.send(BrowserEffect.Exit)
            } catch (e: CofferException) {
                _effects.send(BrowserEffect.Failed(e))
            } finally {
                _uiState.update { it.copy(busy = false) }
                load(dir)
            }
        }
    }

    /** Streams the file to the SAF destination in 1 MiB chunks. */
    fun onExport(path: String, size: Long, targetUri: String) {
        val h = handle() ?: return run { _effects.trySend(BrowserEffect.Exit) }
        _uiState.update { it.copy(exportProgress = 0f) }
        guard.acquire()
        viewModelScope.launch {
            try {
                withContext(io) {
                    var off = 0L
                    copyOut(h, path, targetUri) { n ->
                        off += n
                        val p = if (size > 0) off.toFloat() / size else 1f
                        _uiState.update { it.copy(exportProgress = p.coerceAtMost(1f)) }
                    }
                }
                _effects.send(BrowserEffect.Exported(path.substringAfterLast('/')))
            } catch (e: CofferException) {
                _effects.send(BrowserEffect.ExportFailed(e))
            } catch (e: java.io.IOException) {
                _effects.send(BrowserEffect.ExportFailed(CofferException.Io(e.message ?: "write failed")))
            } finally {
                guard.release()
                _uiState.update { it.copy(exportProgress = null) }
            }
        }
    }
}
