// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Photo previews, in memory only: never written to disk, cleared when the
 * container locks. Sized in bytes (4 per pixel).
 */
class ThumbnailCache(maxBytes: Int) {
    private val lru = object : LruCache<String, ImageBitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4).coerceAtLeast(1)
    }

    /** Bumped by [clear], so a decode that started before a lock cannot refill the cache. */
    private val generations = HashMap<String, Long>()

    /** [version] identifies the file content (size and modification time), so a changed file misses. */
    private fun key(vaultId: String, path: String, version: String) = "$vaultId\u0000$path\u0000$version"

    @Synchronized
    fun get(vaultId: String, path: String, version: String = ""): ImageBitmap? = lru[key(vaultId, path, version)]

    @Synchronized
    fun put(vaultId: String, path: String, bmp: ImageBitmap, version: String = "") {
        lru.put(key(vaultId, path, version), bmp)
    }

    @Synchronized
    fun generation(vaultId: String): Long = generations[vaultId] ?: 0L

    /** Stores only if the vault was not cleared since [generation] was read. */
    @Synchronized
    fun putIfCurrent(vaultId: String, path: String, version: String, bmp: ImageBitmap, generation: Long): Boolean {
        if (generation(vaultId) != generation) return false
        put(vaultId, path, bmp, version)
        return true
    }

    @Synchronized
    fun clear(vaultId: String) {
        generations[vaultId] = generation(vaultId) + 1
        val prefix = "$vaultId\u0000"
        lru.snapshot().keys.filter { it.startsWith(prefix) }.forEach { lru.remove(it) }
    }
}
