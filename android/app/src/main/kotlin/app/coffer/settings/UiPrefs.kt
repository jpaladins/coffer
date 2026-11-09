// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class BrowserLayout { List, Grid }
enum class SortKey { Name, Modified, Size }
data class SortOrder(val key: SortKey = SortKey.Name, val ascending: Boolean = true)

/**
 * Interface preferences. Nothing here comes from inside a container: the
 * last-access map holds only list ids and times, and can be turned off.
 */
class UiPrefs(private val dataStore: DataStore<Preferences>) {
    val onboardingDone: Flow<Boolean> = flag(ONBOARDING, false)
    val rememberLastAccess: Flow<Boolean> = flag(REMEMBER, true)

    val layout: Flow<BrowserLayout> = dataStore.data.map { p ->
        BrowserLayout.entries.firstOrNull { it.name == p[LAYOUT] } ?: BrowserLayout.List
    }.distinctUntilChanged()

    val sort: Flow<SortOrder> = dataStore.data.map { p ->
        SortOrder(SortKey.entries.firstOrNull { it.name == p[SORT_KEY] } ?: SortKey.Name, p[SORT_ASC] ?: true)
    }.distinctUntilChanged()

    val lastAccess: Flow<Map<String, Long>> = dataStore.data.map { decode(it[LAST_ACCESS]) }.distinctUntilChanged()

    suspend fun setOnboardingDone() { dataStore.edit { it[ONBOARDING] = true } }
    suspend fun setLayout(l: BrowserLayout) { dataStore.edit { it[LAYOUT] = l.name } }

    suspend fun setSort(s: SortOrder) {
        dataStore.edit {
            it[SORT_KEY] = s.key.name
            it[SORT_ASC] = s.ascending
        }
    }

    /** Turning it off also deletes every saved time. */
    suspend fun setRememberLastAccess(on: Boolean) {
        dataStore.edit {
            it[REMEMBER] = on
            if (!on) it.remove(LAST_ACCESS)
        }
    }

    /** Best effort: a disk error only loses the time, it must not fail the unlock. */
    suspend fun recordAccess(id: String, at: Long) = bestEffort {
        dataStore.edit {
            if (it[REMEMBER] == false) return@edit
            it[LAST_ACCESS] = encode(decode(it[LAST_ACCESS]) + (id to at))
        }
    }

    suspend fun forget(id: String) = bestEffort {
        dataStore.edit {
            val map = decode(it[LAST_ACCESS])
            if (id in map) it[LAST_ACCESS] = encode(map - id)
        }
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (_: java.io.IOException) {
        }
    }

    private fun flag(key: Preferences.Key<Boolean>, default: Boolean) =
        dataStore.data.map { it[key] ?: default }.distinctUntilChanged()

    internal companion object {
        val ONBOARDING = booleanPreferencesKey("ui_onboarding_done")
        val LAYOUT = stringPreferencesKey("ui_layout")
        val SORT_KEY = stringPreferencesKey("ui_sort_key")
        val SORT_ASC = booleanPreferencesKey("ui_sort_asc")
        val REMEMBER = booleanPreferencesKey("ui_remember_access")
        val LAST_ACCESS = stringPreferencesKey("ui_last_access")

        private val ENTRY = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(-?\\d+)")

        /** Flat JSON object of string ids to epoch milliseconds. */
        fun encode(map: Map<String, Long>): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\":" + v
        }

        fun decode(json: String?): Map<String, Long> {
            if (json.isNullOrBlank()) return emptyMap()
            return ENTRY.findAll(json).associate { m ->
                m.groupValues[1].replace(Regex("\\\\(.)"), "$1") to m.groupValues[2].toLong()
            }
        }
    }
}
