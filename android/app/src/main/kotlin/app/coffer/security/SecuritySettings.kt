// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Auto-lock choices offered in the UI, in milliseconds. */
val AUTOLOCK_OPTIONS = listOf(0L, 30_000L, 120_000L, 300_000L)
const val AUTOLOCK_DEFAULT = 30_000L

@Singleton
class SecuritySettings @Inject constructor(private val dataStore: DataStore<Preferences>) {
    private val key = longPreferencesKey("autolock_ms")

    val autoLockMs: Flow<Long> = dataStore.data.map { it[key] ?: AUTOLOCK_DEFAULT }

    suspend fun currentAutoLockMs(): Long = autoLockMs.first()

    suspend fun setAutoLockMs(ms: Long) {
        dataStore.edit { it[key] = ms }
    }
}
