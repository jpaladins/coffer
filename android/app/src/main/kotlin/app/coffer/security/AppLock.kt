// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AppLockMode { OFF, PIN, PIN_OR_BIOMETRIC }

/** PBKDF2-HMAC-SHA256 of the PIN; the PIN itself is never stored. */
class PinRecord(val salt: ByteArray, val hash: ByteArray)

data class AppLockConfig(
    val mode: AppLockMode = AppLockMode.OFF,
    val pin: PinRecord? = null,
    /** Wrong PINs in a row, kept across restarts (killing the app must not reset it). */
    val failures: Int = 0,
    val lockedUntil: Long = 0,
)

interface AppLockStore {
    suspend fun load(): AppLockConfig
    suspend fun save(config: AppLockConfig)
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    data class LockedOut(val untilMs: Long) : PinResult
}

const val MIN_PIN = 4
private const val FREE_ATTEMPTS = 5
private const val FIRST_LOCKOUT_MS = 30_000L
private const val PIN_ITERATIONS = 100_000

internal fun hashPin(pin: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin, salt, PIN_ITERATIONS, 256)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/**
 * App lock screen state: locked at start and when the app comes back after
 * the auto-lock timeout. After 5 wrong PINs, each further wrong PIN locks
 * the screen for 30 s, doubling every time.
 */
class AppLock(private val store: AppLockStore, private val clock: () -> Long) {
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    private val _ready = MutableStateFlow(false)
    /** False until the lock settings are loaded: draw nothing before that. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _covered = MutableStateFlow(false)
    /**
     * True from the moment the app leaves the foreground until the timeout
     * check on return: the content is hidden without waiting for a disk
     * read, so it never flashes before the lock screen.
     */
    val covered: StateFlow<Boolean> = _covered.asStateFlow()
    private val mutex = Mutex()
    private var backgroundAt: Long? = null
    @Volatile private var mode: AppLockMode = AppLockMode.OFF

    suspend fun mode(): AppLockMode = store.load().mode

    suspend fun onAppStart() {
        mode = store.load().mode
        _isLocked.value = mode != AppLockMode.OFF
        _ready.value = true
    }

    fun onBackground() {
        backgroundAt = clock()
        if (mode != AppLockMode.OFF) _covered.value = true
    }

    suspend fun onForeground(timeoutMs: Long) {
        val since = backgroundAt ?: return
        backgroundAt = null
        mode = store.load().mode
        if (mode != AppLockMode.OFF && clock() - since >= timeoutMs) _isLocked.value = true
        _covered.value = false
    }

    /** Wipes [pin]. */
    suspend fun submitPin(pin: CharArray): PinResult = mutex.withLock {
        try {
            val c = store.load()
            val rec = c.pin ?: return PinResult.Ok.also { _isLocked.value = false }
            val now = clock()
            if (now < c.lockedUntil) return PinResult.LockedOut(c.lockedUntil)
            if (hashPin(pin, rec.salt).contentEquals(rec.hash)) {
                store.save(c.copy(failures = 0, lockedUntil = 0))
                _isLocked.value = false
                return PinResult.Ok
            }
            val failures = c.failures + 1
            if (failures < FREE_ATTEMPTS) {
                store.save(c.copy(failures = failures))
                return PinResult.Wrong(FREE_ATTEMPTS - failures)
            }
            val until = now + (FIRST_LOCKOUT_MS shl minOf(failures - FREE_ATTEMPTS, 20))
            store.save(c.copy(failures = failures, lockedUntil = until))
            PinResult.LockedOut(until)
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Called after BiometricPrompt succeeded (only offered in PIN_OR_BIOMETRIC). */
    fun onBiometricSuccess() {
        _isLocked.value = false
    }

    /** Sets the PIN and mode; false (nothing changed) if the PIN is too short. Wipes [pin]. */
    suspend fun setPin(pin: CharArray, mode: AppLockMode): Boolean {
        try {
            if (pin.size < MIN_PIN || mode == AppLockMode.OFF) return false
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            store.save(AppLockConfig(mode = mode, pin = PinRecord(salt, hashPin(pin, salt))))
            this.mode = mode
            return true
        } finally {
            pin.fill('\u0000')
        }
    }

    suspend fun setMode(mode: AppLockMode) {
        val c = store.load()
        if (c.pin != null && mode != AppLockMode.OFF) {
            store.save(c.copy(mode = mode))
            this.mode = mode
        }
    }

    suspend fun disable() {
        store.save(AppLockConfig())
        mode = AppLockMode.OFF
        _isLocked.value = false
        _covered.value = false
    }
}
