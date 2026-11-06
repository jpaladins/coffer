// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.data.CancelSignal
import app.coffer.data.CofferException
import app.coffer.data.CreateRequest
import app.coffer.data.NativeVaults
import app.coffer.data.SessionManager
import app.coffer.platform.DocumentAccess
import app.coffer.security.LockGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Smallest containers per filesystem (the core enforces the same). */
const val MIN_FAT_BYTES = 1L shl 20
const val MIN_EXFAT_BYTES = 4L shl 20

enum class Blocker { NO_LOCATION, SIZE_INVALID, SIZE_TOO_SMALL, NO_PASSWORD, PASSWORD_MISMATCH }

sealed interface CreatePhase {
    data object Idle : CreatePhase
    /** [progress] 0..1 while the data area is encrypted. */
    data class Creating(val progress: Float) : CreatePhase
    data class Error(val error: CofferException) : CreatePhase
}

data class KeyfileItem(val uri: String, val name: String)

/** Passwords are never part of this state. */
data class CreateUiState(
    val locationUri: String? = null,
    val locationName: String = "",
    val sizeText: String = "",
    val sizeGb: Boolean = false,
    val cipher: String = "AES",
    val prf: String = "SHA-512",
    val pim: String = "",
    val fs: String = "exFAT",
    val quick: Boolean = false,
    val keyfiles: List<KeyfileItem> = emptyList(),
    val entropyBytes: Int = 0,
    val ciphers: List<String> = listOf("AES"),
    val prfs: List<String> = listOf("SHA-512"),
    val phase: CreatePhase = CreatePhase.Idle,
) {
    val busy: Boolean get() = phase is CreatePhase.Creating
    val sizeBytes: Long?
        get() = sizeText.toLongOrNull()?.takeIf { it > 0 }?.let { it shl if (sizeGb) 30 else 20 }
    val minBytes: Long get() = if (fs == "FAT") MIN_FAT_BYTES else MIN_EXFAT_BYTES
}

sealed interface CreateEffect {
    data class Opened(val vaultId: String) : CreateEffect
}

private const val MAX_ENTROPY = 512

@HiltViewModel
class CreateViewModel @Inject constructor(
    private val sessions: SessionManager,
    native: NativeVaults,
    private val documents: DocumentAccess,
    private val guard: LockGuard,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CreateUiState(ciphers = native.ciphers(), prfs = native.prfs()))
    val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()
    private val _effects = Channel<CreateEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var cancel: CancelSignal? = null
    private var entropy = ByteArray(0)

    fun onLocationPicked(uri: String) {
        runCatching { documents.persist(uri) }
        val name = runCatching { documents.displayName(uri) }.getOrNull() ?: uri.substringAfterLast('/')
        _uiState.update { it.copy(locationUri = uri, locationName = name, phase = CreatePhase.Idle) }
    }

    fun onSizeChange(text: String) {
        if (text.length <= 6 && text.all(Char::isDigit)) _uiState.update { it.copy(sizeText = text) }
    }

    fun onSizeUnitGb(gb: Boolean) = _uiState.update { it.copy(sizeGb = gb) }
    fun onCipherChange(c: String) = _uiState.update { it.copy(cipher = c) }
    fun onPrfChange(p: String) = _uiState.update { it.copy(prf = p) }
    fun onFsChange(fs: String) = _uiState.update { it.copy(fs = fs) }
    fun onQuickChange(q: Boolean) = _uiState.update { it.copy(quick = q) }

    fun onPimChange(v: String) {
        if (v.length <= 7 && v.all(Char::isDigit)) _uiState.update { it.copy(pim = v) }
    }

    fun onAddKeyfile(uri: String, name: String) = _uiState.update {
        if (it.keyfiles.any { k -> k.uri == uri }) it else it.copy(keyfiles = it.keyfiles + KeyfileItem(uri, name))
    }

    fun onRemoveKeyfile(uri: String) = _uiState.update { it.copy(keyfiles = it.keyfiles.filterNot { k -> k.uri == uri }) }

    /** Touch samples (coordinates and timing) from the entropy pad. */
    fun onEntropy(sample: ByteArray) {
        if (entropy.size >= MAX_ENTROPY) return
        val next = (entropy + sample).copyOf(minOf(MAX_ENTROPY, entropy.size + sample.size))
        entropy.fill(0)
        entropy = next
        _uiState.update { it.copy(entropyBytes = entropy.size) }
    }

    /** Why "Create" is disabled, or null when the form is complete. */
    fun blocker(password: CharArray, confirm: CharArray): Blocker? {
        val s = _uiState.value
        val size = s.sizeBytes
        return when {
            s.locationUri == null -> Blocker.NO_LOCATION
            size == null -> Blocker.SIZE_INVALID
            size < s.minBytes -> Blocker.SIZE_TOO_SMALL
            password.isEmpty() && s.keyfiles.isEmpty() -> Blocker.NO_PASSWORD
            !password.contentEquals(confirm) -> Blocker.PASSWORD_MISMATCH
            else -> null
        }
    }

    /** Takes ownership of both arrays and wipes them. */
    fun onCreate(password: CharArray, confirm: CharArray) {
        val blocked = blocker(password, confirm) != null || _uiState.value.busy
        confirm.fill('\u0000')
        val s = _uiState.value
        val uri = s.locationUri
        val size = s.sizeBytes
        if (blocked || uri == null || size == null) {
            password.fill('\u0000')
            return
        }
        val signal = sessions.newCancel().also { cancel = it }
        val req = CreateRequest(
            password, s.keyfiles.map { it.uri }, s.pim.toIntOrNull() ?: 0, s.cipher, s.prf, s.fs, size, s.quick,
            entropy.copyOf(),
        )
        entropy.fill(0)
        entropy = ByteArray(0)
        _uiState.update { it.copy(phase = CreatePhase.Creating(0f), entropyBytes = 0) }
        guard.acquire()
        viewModelScope.launch {
            try {
                val session = sessions.create(uri, s.locationName, req, { done, total ->
                    val p = if (total > 0) done.toFloat() / total else 1f
                    _uiState.update { it.copy(phase = CreatePhase.Creating(p.coerceIn(0f, 1f))) }
                }, signal)
                _uiState.update { it.copy(phase = CreatePhase.Idle) }
                _effects.send(CreateEffect.Opened(session.ref.id))
            } catch (_: CofferException.Cancelled) {
                // The document was deleted: a new location must be chosen.
                _uiState.update { it.copy(phase = CreatePhase.Idle, locationUri = null, locationName = "") }
            } catch (e: CofferException) {
                _uiState.update { it.copy(phase = CreatePhase.Error(e), locationUri = null, locationName = "") }
            } finally {
                password.fill('\u0000')
                req.extraEntropy.fill(0)
                guard.release()
                cancel = null
            }
        }
    }

    /** Keeps auto-lock away while a system picker is in front. */
    fun onPicker(open: Boolean) = if (open) guard.acquire() else guard.release()

    fun onCancel() {
        cancel?.cancel()
    }

    override fun onCleared() {
        cancel?.cancel()
        entropy.fill(0)
    }
}
