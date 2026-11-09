// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.vaults

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.SessionManager
import app.coffer.data.VaultStore
import app.coffer.platform.DocumentAccess
import app.coffer.platform.IoDispatcher
import app.coffer.settings.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VaultItem(
    val id: String,
    val label: String,
    val isOpen: Boolean,
    /** Size of the container file. */
    val fileBytes: Long? = null,
    /** The fields below are known only while the container is open. */
    val fsKind: String? = null,
    val freeBytes: Long? = null,
    val volumeBytes: Long? = null,
    val cipher: String? = null,
    val prf: String? = null,
    val lastAccess: Long? = null,
)

data class VaultsUiState(val vaults: List<VaultItem> = emptyList(), val loaded: Boolean = false) {
    val anyOpen: Boolean get() = vaults.any { it.isOpen }
}

@HiltViewModel
class VaultsViewModel @Inject constructor(
    private val store: VaultStore,
    private val sessions: SessionManager,
    private val documents: DocumentAccess,
    private val uiPrefs: UiPrefs,
    @IoDispatcher private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    /** Bumped when the list comes back on screen: free space may have changed. */
    private val refresh = MutableStateFlow(0)

    val uiState: StateFlow<VaultsUiState> =
        combine(store.vaults, sessions.openIds, uiPrefs.lastAccess, refresh) { vaults, open, access, _ ->
            VaultsUiState(
                vaults.map { ref ->
                    val handle = if (ref.id in open) sessions.session(ref.id)?.handle else null
                    val info = handle?.let { runCatching { it.info }.getOrNull() }
                    VaultItem(
                        id = ref.id,
                        label = ref.label,
                        isOpen = ref.id in open,
                        fileBytes = runCatching { documents.fileSize(ref.uri) }.getOrNull(),
                        fsKind = info?.fsKind,
                        freeBytes = handle?.let { h -> runCatching { h.freeBytes() }.getOrNull() },
                        volumeBytes = info?.size,
                        cipher = info?.cipher,
                        prf = info?.prf,
                        lastAccess = access[ref.id],
                    )
                },
                loaded = true,
            )
        }.flowOn(io).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultsUiState())

    fun onResume() = refresh.update { it + 1 }

    fun onDocumentPicked(uri: String) {
        viewModelScope.launch {
            documents.persist(uri)
            store.add(uri, documents.displayName(uri) ?: uri.substringAfterLast('/'))
        }
    }

    fun onRemove(id: String) {
        sessions.close(id)
        viewModelScope.launch {
            store.remove(id)
            uiPrefs.forget(id)
        }
    }

    fun onLock(id: String) = sessions.close(id)

    fun onLockAll() = sessions.closeAll()

    fun isOpen(id: String) = sessions.session(id) != null
}
