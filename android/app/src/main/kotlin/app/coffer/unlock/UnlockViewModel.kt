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
import app.coffer.security.KeyInvalidatedException
import app.coffer.security.NoRememberedPasswords
import app.coffer.security.RememberedPasswords
import javax.crypto.Cipher

sealed interface UnlockPhase {
    data object Idle : UnlockPhase
    data object Unlocking : UnlockPhase
    /** [hiddenRequested]: a wrong password may be the hidden volume's one. */
    data class Error(val error: CofferException, val hiddenRequested: Boolean = false) : UnlockPhase
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
    val readOnly: Boolean = false,
    /** Asks for the hidden volume's password to keep it safe from writes. */
    val protectHidden: Boolean = false,
    /** Unlocked, but the volume was not cleanly closed: read-only or continue? */
    val dirtyPrompt: Boolean = false,
    /** A password for this vault is saved behind the fingerprint. */
    val hasRemembered: Boolean = false,
    val biometricAvailable: Boolean = false,
    /** The saved password was dropped because the fingerprints changed. */
    val biometricInvalidated: Boolean = false,
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
    private val remembered: RememberedPasswords = NoRememberedPasswords,
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
            val has = runCatching { remembered.has(vaultId) }.getOrDefault(false)
            _uiState.update {
                it.copy(label = ref?.label ?: "", hasRemembered = has, biometricAvailable = remembered.isAvailable())
            }
        }
    }

    /** Cipher for BiometricPrompt to recall the saved password; null if unusable. */
    suspend fun decryptCipher(): Cipher? = try {
        remembered.decrypter(vaultId)
    } catch (_: KeyInvalidatedException) {
        runCatching { remembered.forget(vaultId) }
        _uiState.update { it.copy(hasRemembered = false, biometricInvalidated = true) }
        null
    }

    /** Cipher for BiometricPrompt to save the password; null if unusable. */
    fun encryptCipher(): Cipher? = try {
        remembered.encrypter(vaultId)
    } catch (_: Exception) {
        null
    }

    /** After BiometricPrompt succeeded with [decryptCipher]'s cipher. */
    fun onBiometricAuthenticated(cipher: Cipher) {
        viewModelScope.launch {
            val password = try {
                remembered.recall(vaultId, cipher)
            } catch (_: Exception) {
                runCatching { remembered.forget(vaultId) }
                _uiState.update { it.copy(hasRemembered = false, biometricInvalidated = true) }
                return@launch
            }
            onUnlock(password, fromRemembered = true)
        }
    }

    /** Unlocks and, only if that succeeds, saves the password with [cipher]. */
    fun onUnlockRemembering(password: CharArray, cipher: Cipher) {
        onUnlock(password, rememberWith = cipher)
    }

    /** Volume not cleanly closed: continue read-only (recommended). */
    fun onDirtyReadOnly() {
        _uiState.update { it.copy(dirtyPrompt = false) }
        runCatching { sessions.session(vaultId)?.handle?.makeReadOnly() }
        _effects.trySend(UnlockEffect.NavigateToBrowser(vaultId))
    }

    fun onDirtyContinue() {
        _uiState.update { it.copy(dirtyPrompt = false) }
        _effects.trySend(UnlockEffect.NavigateToBrowser(vaultId))
    }

    fun onPimChange(value: String) {
        if (value.length <= 7 && value.all(Char::isDigit)) _uiState.update { it.copy(pim = value) }
    }

    fun onPrfChange(prf: String?) = _uiState.update { it.copy(prf = prf) }

    fun onAddKeyfile(uri: String, name: String) = _uiState.update {
        if (it.keyfiles.any { k -> k.uri == uri }) it else it.copy(keyfiles = it.keyfiles + KeyfileUi(uri, name))
    }

    fun onReadOnlyChange(value: Boolean) = _uiState.update {
        it.copy(readOnly = value, protectHidden = it.protectHidden && !value)
    }

    fun onProtectHiddenChange(value: Boolean) = _uiState.update { it.copy(protectHidden = value && !it.readOnly) }

    fun onRemoveKeyfile(uri: String) = _uiState.update { it.copy(keyfiles = it.keyfiles.filterNot { k -> k.uri == uri }) }

    /** Takes ownership of [password] and [hiddenPassword] and wipes them. */
    fun onUnlock(
        password: CharArray,
        tryBackupHeaders: Boolean = false,
        hiddenPassword: CharArray? = null,
        fromRemembered: Boolean = false,
        rememberWith: Cipher? = null,
    ) {
        val st = _uiState.value
        val hidden = hiddenPassword?.takeIf { st.protectHidden && !st.readOnly && it.isNotEmpty() }
        if (hidden == null) hiddenPassword?.fill('\u0000')
        if (st.busy) {
            password.fill('\u0000')
            hidden?.fill('\u0000')
            return
        }
        val signal = sessions.newCancel().also { cancel = it }
        // SessionManager wipes the password: keep a copy only to save it.
        val toRemember = rememberWith?.let { password.copyOf() }
        _uiState.update { it.copy(phase = UnlockPhase.Unlocking) }
        viewModelScope.launch {
            val s = _uiState.value
            try {
                val ref = store.vaults.first().firstOrNull { it.id == vaultId }
                    ?: throw CofferException.NotFound(vaultId)
                val req = UnlockRequest(
                    password, s.pim.toIntOrNull() ?: 0, s.keyfiles.map { it.uri }, s.prf, tryBackupHeaders,
                    writable = !s.readOnly, hiddenPassword = hidden,
                )
                val session = sessions.unlock(ref, req, signal)
                if (toRemember != null && rememberWith != null) {
                    runCatching { remembered.save(vaultId, rememberWith, toRemember) }
                        .onSuccess { _uiState.update { it.copy(hasRemembered = true) } }
                }
                val info = session.handle.info
                if (info.wasDirty && info.writable) {
                    _uiState.update { it.copy(phase = UnlockPhase.Idle, offerBackupHeaders = false, dirtyPrompt = true) }
                } else {
                    _uiState.update { it.copy(phase = UnlockPhase.Idle, offerBackupHeaders = false) }
                    _effects.send(UnlockEffect.NavigateToBrowser(vaultId))
                }
            } catch (_: CofferException.Cancelled) {
                _uiState.update { it.copy(phase = UnlockPhase.Idle) }
            } catch (e: CofferException) {
                if (fromRemembered && e is CofferException.WrongCredentials) {
                    // The volume password changed since it was saved.
                    runCatching { remembered.forget(vaultId) }
                    _uiState.update { it.copy(hasRemembered = false) }
                }
                _uiState.update {
                    it.copy(
                        phase = UnlockPhase.Error(e, hiddenRequested = hidden != null),
                        offerBackupHeaders = e is CofferException.WrongCredentials && !tryBackupHeaders,
                    )
                }
            } finally {
                password.fill('\u0000')
                hidden?.fill('\u0000')
                toRemember?.fill('\u0000')
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
