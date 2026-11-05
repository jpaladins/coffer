// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.CofferException
import app.coffer.data.Entry
import app.coffer.data.SessionManager
import app.coffer.platform.Exporter
import app.coffer.platform.IoDispatcher
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
) {
    val breadcrumbs: List<String> get() = path.split('/').filter { it.isNotEmpty() }
}

sealed interface BrowserEffect {
    data object Exit : BrowserEffect
    data class OpenFile(val path: String) : BrowserEffect
    data class Exported(val name: String) : BrowserEffect
    data class ExportFailed(val error: CofferException) : BrowserEffect
}

private const val CHUNK = 1 shl 20

fun childPath(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

@HiltViewModel(assistedFactory = BrowserViewModel.Factory::class)
class BrowserViewModel @AssistedInject constructor(
    @Assisted private val vaultId: String,
    private val sessions: SessionManager,
    private val exporter: Exporter,
    @IoDispatcher private val io: CoroutineDispatcher,
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
        load("/")
    }

    private fun handle() = sessions.session(vaultId)?.handle

    fun vaultIdForUi(): String = vaultId

    private fun load(path: String) {
        val session = sessions.session(vaultId)
        if (session == null) {
            _effects.trySend(BrowserEffect.Exit)
            return
        }
        _uiState.update { it.copy(label = session.ref.label, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val list = withContext(io) { session.handle.list(path) }
                    .sortedWith(compareBy<Entry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _uiState.update { it.copy(path = path, entries = list, loading = false) }
            } catch (e: CofferException.Closed) {
                _effects.send(BrowserEffect.Exit)
            } catch (e: CofferException) {
                _uiState.update { it.copy(loading = false, error = e) }
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
        sessions.close(vaultId)
        _effects.trySend(BrowserEffect.Exit)
    }

    /** Streams the file to the SAF destination in 1 MiB chunks. */
    fun onExport(path: String, size: Long, targetUri: String) {
        val h = handle() ?: return run { _effects.trySend(BrowserEffect.Exit) }
        _uiState.update { it.copy(exportProgress = 0f) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    exporter.openSink(targetUri).use { out ->
                        var off = 0L
                        while (true) {
                            val chunk = h.read(path, off, CHUNK)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            chunk.fill(0)
                            off += chunk.size
                            val p = if (size > 0) off.toFloat() / size else 1f
                            _uiState.update { it.copy(exportProgress = p.coerceAtMost(1f)) }
                        }
                    }
                }
                _effects.send(BrowserEffect.Exported(path.substringAfterLast('/')))
            } catch (e: CofferException) {
                _effects.send(BrowserEffect.ExportFailed(e))
            } catch (e: java.io.IOException) {
                _effects.send(BrowserEffect.ExportFailed(CofferException.Io(e.message ?: "write failed")))
            } finally {
                _uiState.update { it.copy(exportProgress = null) }
            }
        }
    }
}
