// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.vaults

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.SessionManager
import app.coffer.data.VaultStore
import app.coffer.platform.DocumentAccess
import app.coffer.settings.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VaultItem(val id: String, val label: String, val isOpen: Boolean)

data class VaultsUiState(val vaults: List<VaultItem> = emptyList(), val loaded: Boolean = false) {
    val anyOpen: Boolean get() = vaults.any { it.isOpen }
}

@HiltViewModel
class VaultsViewModel @Inject constructor(
    private val store: VaultStore,
    private val sessions: SessionManager,
    private val documents: DocumentAccess,
    private val uiPrefs: UiPrefs,
) : ViewModel() {
    val uiState: StateFlow<VaultsUiState> =
        combine(store.vaults, sessions.openIds) { vaults, open ->
            VaultsUiState(vaults.map { VaultItem(it.id, it.label, it.id in open) }, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultsUiState())

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

    fun onLockAll() = sessions.closeAll()

    fun isOpen(id: String) = sessions.session(id) != null
}
