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

    private fun key(vaultId: String, path: String) = "$vaultId\u0000$path"

    fun get(vaultId: String, path: String): ImageBitmap? = lru[key(vaultId, path)]

    fun put(vaultId: String, path: String, bmp: ImageBitmap) {
        lru.put(key(vaultId, path), bmp)
    }

    fun clear(vaultId: String) {
        val prefix = "$vaultId\u0000"
        lru.snapshot().keys.filter { it.startsWith(prefix) }.forEach { lru.remove(it) }
    }
}
