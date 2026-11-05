// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A container the user added. Only the SAF URI and a label are stored, never secrets. */
@Serializable
data class VaultRef(
    val id: String,
    val label: String,
    val uri: String,
    /** KDF that opened it last time, tried first to unlock faster. */
    val lastPrf: String? = null,
)

interface VaultStore {
    val vaults: Flow<List<VaultRef>>
    suspend fun add(uri: String, label: String): VaultRef
    suspend fun remove(id: String)
    suspend fun rememberPrf(id: String, prf: String)
}

class DataStoreVaultStore(
    private val dataStore: DataStore<Preferences>,
    private val idGenerator: (String) -> String = { UUID.randomUUID().toString() },
) : VaultStore {
    private val key = stringPreferencesKey("vaults")
    private val serializer = ListSerializer(VaultRef.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(p: Preferences): List<VaultRef> =
        p[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    override val vaults: Flow<List<VaultRef>> = dataStore.data.map(::decode)

    private suspend fun update(f: (List<VaultRef>) -> List<VaultRef>) {
        dataStore.edit { it[key] = json.encodeToString(serializer, f(decode(it))) }
    }

    override suspend fun add(uri: String, label: String): VaultRef {
        var result: VaultRef? = null
        update { list ->
            val existing = list.firstOrNull { it.uri == uri }
            if (existing != null) {
                result = existing
                list
            } else {
                val ref = VaultRef(idGenerator(uri), label, uri)
                result = ref
                list + ref
            }
        }
        return checkNotNull(result)
    }

    override suspend fun remove(id: String) = update { list -> list.filter { it.id != id } }

    override suspend fun rememberPrf(id: String, prf: String) =
        update { list -> list.map { if (it.id == id) it.copy(lastPrf = prf) else it } }
}
