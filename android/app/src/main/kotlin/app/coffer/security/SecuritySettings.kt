// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Auto-lock choices offered in the UI, in milliseconds. */
val AUTOLOCK_OPTIONS = listOf(0L, 30_000L, 120_000L, 300_000L)
const val AUTOLOCK_DEFAULT = 30_000L

@Singleton
class SecuritySettings @Inject constructor(private val dataStore: DataStore<Preferences>) : AppLockStore {
    private val key = longPreferencesKey("autolock_ms")
    private val secureKey = booleanPreferencesKey("secure_window")
    private val lockMode = stringPreferencesKey("applock_mode")
    private val pinSalt = stringPreferencesKey("applock_pin_salt")
    private val pinHash = stringPreferencesKey("applock_pin_hash")
    private val failures = intPreferencesKey("applock_failures")
    private val lockedUntil = longPreferencesKey("applock_locked_until")

    val autoLockMs: Flow<Long> = dataStore.data.map { it[key] ?: AUTOLOCK_DEFAULT }

    /** FLAG_SECURE: no screenshots, recordings or recents thumbnails (default on). */
    val secureWindow: Flow<Boolean> = dataStore.data.map { it[secureKey] ?: true }

    val appLockMode: Flow<AppLockMode> = dataStore.data.map { p ->
        p[lockMode]?.let { runCatching { AppLockMode.valueOf(it) }.getOrNull() } ?: AppLockMode.OFF
    }

    suspend fun currentAutoLockMs(): Long = autoLockMs.first()

    suspend fun setAutoLockMs(ms: Long) {
        dataStore.edit { it[key] = ms }
    }

    suspend fun setSecureWindow(on: Boolean) {
        dataStore.edit { it[secureKey] = on }
    }

    override suspend fun load(): AppLockConfig {
        val p = dataStore.data.first()
        val salt = p[pinSalt]
        val hash = p[pinHash]
        return AppLockConfig(
            mode = p[lockMode]?.let { runCatching { AppLockMode.valueOf(it) }.getOrNull() } ?: AppLockMode.OFF,
            pin = if (salt != null && hash != null) {
                PinRecord(Base64.decode(salt, Base64.NO_WRAP), Base64.decode(hash, Base64.NO_WRAP))
            } else {
                null
            },
            failures = p[failures] ?: 0,
            lockedUntil = p[lockedUntil] ?: 0,
        )
    }

    override suspend fun save(config: AppLockConfig) {
        dataStore.edit { p ->
            p[lockMode] = config.mode.name
            val pin = config.pin
            if (pin == null) {
                p.remove(pinSalt)
                p.remove(pinHash)
            } else {
                p[pinSalt] = Base64.encodeToString(pin.salt, Base64.NO_WRAP)
                p[pinHash] = Base64.encodeToString(pin.hash, Base64.NO_WRAP)
            }
            p[failures] = config.failures
            p[lockedUntil] = config.lockedUntil
        }
    }
}
