// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory DataStore: the file-backed one cannot replace its file on a
 * Windows JVM (atomic rename), which Android's filesystem allows.
 */
private class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

class VaultStoreTest {
    @Test
    fun store_add_remove_roundtrip() = runTest {
        val store = DataStoreVaultStore(MemoryDataStore(), idGenerator = { "id-${it.length}" })
        assertTrue(store.vaults.first().isEmpty())
        val a = store.add("content://p/a.hc", "a.hc")
        store.add("content://p/bb.hc", "bb.hc")
        assertEquals(listOf("a.hc", "bb.hc"), store.vaults.first().map { it.label })
        store.rememberPrf(a.id, "SHA-512")
        assertEquals("SHA-512", store.vaults.first().first().lastPrf)
        store.remove(a.id)
        assertEquals(listOf("bb.hc"), store.vaults.first().map { it.label })
        store.add("content://p/bb.hc", "bb.hc")
        assertEquals(1, store.vaults.first().size)
    }

    @Test
    fun corrupt_json_reads_as_empty() = runTest {
        val ds = MemoryDataStore()
        ds.updateData { p -> p.toMutablePreferences().apply { set(androidx.datastore.preferences.core.stringPreferencesKey("vaults"), "{not json") } }
        assertTrue(DataStoreVaultStore(ds).vaults.first().isEmpty())
    }
}
