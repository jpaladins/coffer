// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.CancelSignal
import app.coffer.data.CofferException
import app.coffer.data.SessionManager
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UnlockPhase {
    data object Idle : UnlockPhase
    data object Unlocking : UnlockPhase
    data class Error(val error: CofferException) : UnlockPhase
}

data class KeyfileUi(val uri: String, val name: String)

/** The password is never part of this state (it would survive configuration changes). */
data class UnlockUiState(
    val label: String = "",
    val pim: String = "",
    val prf: String? = null,
    val keyfiles: List<KeyfileUi> = emptyList(),
    val phase: UnlockPhase = UnlockPhase.Idle,
    val offerBackupHeaders: Boolean = false,
) {
    val busy: Boolean get() = phase == UnlockPhase.Unlocking
}

sealed interface UnlockEffect {
    data class NavigateToBrowser(val vaultId: String) : UnlockEffect
}

/** KDF names as the core reports them (Prf::name). */
val KDF_CHOICES = listOf("SHA-512", "SHA-256", "BLAKE2s-256", "Whirlpool", "Streebog", "Argon2id")

@HiltViewModel(assistedFactory = UnlockViewModel.Factory::class)
class UnlockViewModel @AssistedInject constructor(
    @Assisted private val vaultId: String,
    private val store: VaultStore,
    private val sessions: SessionManager,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(vaultId: String): UnlockViewModel
    }

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _effects = Channel<UnlockEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var cancel: CancelSignal? = null

    init {
        viewModelScope.launch {
            val ref = store.vaults.first().firstOrNull { it.id == vaultId }
            _uiState.update { it.copy(label = ref?.label ?: "") }
        }
    }

    fun onPimChange(value: String) {
        if (value.length <= 7 && value.all(Char::isDigit)) _uiState.update { it.copy(pim = value) }
    }

    fun onPrfChange(prf: String?) = _uiState.update { it.copy(prf = prf) }

    fun onAddKeyfile(uri: String, name: String) = _uiState.update {
        if (it.keyfiles.any { k -> k.uri == uri }) it else it.copy(keyfiles = it.keyfiles + KeyfileUi(uri, name))
    }

    fun onRemoveKeyfile(uri: String) = _uiState.update { it.copy(keyfiles = it.keyfiles.filterNot { k -> k.uri == uri }) }

    /** Takes ownership of [password] and wipes it. */
    fun onUnlock(password: CharArray, tryBackupHeaders: Boolean = false) {
        if (_uiState.value.busy) {
            password.fill('\u0000')
            return
        }
        val signal = sessions.newCancel().also { cancel = it }
        _uiState.update { it.copy(phase = UnlockPhase.Unlocking) }
        viewModelScope.launch {
            val s = _uiState.value
            try {
                val ref = store.vaults.first().firstOrNull { it.id == vaultId }
                    ?: throw CofferException.NotFound(vaultId)
                val req = UnlockRequest(password, s.pim.toIntOrNull() ?: 0, s.keyfiles.map { it.uri }, s.prf, tryBackupHeaders)
                sessions.unlock(ref, req, signal)
                _uiState.update { it.copy(phase = UnlockPhase.Idle, offerBackupHeaders = false) }
                _effects.send(UnlockEffect.NavigateToBrowser(vaultId))
            } catch (_: CofferException.Cancelled) {
                _uiState.update { it.copy(phase = UnlockPhase.Idle) }
            } catch (e: CofferException) {
                _uiState.update {
                    it.copy(
                        phase = UnlockPhase.Error(e),
                        offerBackupHeaders = e is CofferException.WrongCredentials && !tryBackupHeaders,
                    )
                }
            } finally {
                password.fill('\u0000')
                cancel = null
            }
        }
    }

    fun onCancel() {
        cancel?.cancel()
    }

    override fun onCleared() {
        cancel?.cancel()
    }
}
