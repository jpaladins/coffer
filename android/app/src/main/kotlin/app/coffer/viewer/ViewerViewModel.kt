// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.CofferException
import app.coffer.data.SessionManager
import app.coffer.platform.IoDispatcher
import app.coffer.util.FileKind
import app.coffer.util.MimeTypes
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerContent {
    data object Loading : ViewerContent
    /** Encoded image bytes, decoded (downsampled) by the UI. */
    class Image(val bytes: ByteArray) : ViewerContent
    data class Text(val text: String, val truncated: Boolean) : ViewerContent
    data object TooLarge : ViewerContent
    data object Unsupported : ViewerContent
    data class Failed(val error: CofferException) : ViewerContent
}

data class ViewerUiState(val name: String = "", val path: String = "", val content: ViewerContent = ViewerContent.Loading)

const val IMAGE_LIMIT = 64L shl 20
const val TEXT_LIMIT = 1 shl 20

@HiltViewModel(assistedFactory = ViewerViewModel.Factory::class)
class ViewerViewModel @AssistedInject constructor(
    @Assisted("vault") val vaultId: String,
    @Assisted("path") val path: String,
    private val sessions: SessionManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    /** Test seam for the image size limit. */
    internal var imageLimit: Long = IMAGE_LIMIT

    internal constructor(
        vaultId: String,
        path: String,
        sessions: SessionManager,
        io: CoroutineDispatcher,
        imageLimit: Long,
    ) : this(vaultId, path, sessions, io) {
        this.imageLimit = imageLimit
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("vault") vaultId: String, @Assisted("path") path: String): ViewerViewModel
    }

    private val _uiState = MutableStateFlow(ViewerUiState(path.substringAfterLast('/'), path))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { _uiState.value = _uiState.value.copy(content = load()) }
    }

    private suspend fun load(): ViewerContent = withContext(io) {
        val h = sessions.session(vaultId)?.handle ?: return@withContext ViewerContent.Failed(CofferException.Closed())
        try {
            val size = h.stat(path).size
            when (MimeTypes.kind(path)) {
                FileKind.Image -> if (size > imageLimit) ViewerContent.TooLarge else ViewerContent.Image(readAll(h, size))
                FileKind.Text -> {
                    val n = minOf(size, TEXT_LIMIT.toLong())
                    val bytes = readAll(h, n)
                    ViewerContent.Text(String(bytes, Charsets.UTF_8), truncated = size > TEXT_LIMIT)
                }
                FileKind.Other -> ViewerContent.Unsupported
            }
        } catch (e: CofferException) {
            ViewerContent.Failed(e)
        }
    }

    private fun readAll(h: app.coffer.data.VaultHandle, limit: Long): ByteArray {
        val out = ByteArrayOutputStream(limit.toInt())
        var off = 0L
        while (off < limit) {
            val chunk = h.read(path, off, minOf(1L shl 20, limit - off).toInt())
            if (chunk.isEmpty()) break
            out.write(chunk)
            off += chunk.size
        }
        return out.toByteArray()
    }
}
