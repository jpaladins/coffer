// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.coffer.data.MAX_IO
import app.coffer.data.SessionManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Larger photos get only an icon: decoding them would cost too much memory. */
const val THUMB_MAX_FILE = 30L shl 20

private val PHOTO = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

fun isThumbnailable(name: String) = name.substringAfterLast('.', "").lowercase() in PHOTO

/** Two-pass decode: bounds first, then the largest power-of-two sample that keeps the short side ≥ [targetPx]. */
fun decodeSampled(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val short = minOf(bounds.outWidth, bounds.outHeight)
    if (short <= 0) return null
    var sample = 1
    while (short / (sample * 2) >= targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * Reads a photo from an open container and decodes a small preview in memory.
 * Any failure (corrupt file, out of memory, container locked meanwhile) gives
 * null and the caller shows the file icon.
 */
class ThumbnailLoader(
    private val sessions: SessionManager,
    private val cache: ThumbnailCache,
    private val io: CoroutineDispatcher,
    private val decode: (ByteArray, Int) -> ImageBitmap? = ::decodeSampled,
) {
    /** At most two decodes at once: they are the memory-heavy part. */
    private val gate = Semaphore(2)

    suspend fun load(vaultId: String, path: String, size: Long, targetPx: Int, modified: Long? = null): ImageBitmap? {
        if (size > THUMB_MAX_FILE || !isThumbnailable(path)) return null
        val version = "$size:$modified"
        cache.get(vaultId, path, version)?.let { return it }
        val generation = cache.generation(vaultId)
        val handle = sessions.session(vaultId)?.handle ?: return null
        return gate.withPermit {
            withContext(io) {
                var bytes: ByteArray? = null
                var out: ByteArrayOutputStream? = null
                try {
                    val buffer = WipeableBuffer(size.toInt().coerceIn(0, MAX_IO)).also { out = it }
                    var off = 0L
                    while (true) {
                        val chunk = handle.read(path, off, MAX_IO)
                        if (chunk.isEmpty()) break
                        buffer.write(chunk)
                        chunk.fill(0)
                        off += chunk.size
                        ensureActive()
                    }
                    bytes = buffer.toByteArray()
                    if (!stillOpen(vaultId, handle)) return@withContext null
                    val bmp = decode(bytes, targetPx) ?: return@withContext null
                    // Locked while decoding: drop it, the cache was already cleared.
                    if (!stillOpen(vaultId, handle)) return@withContext null
                    if (!cache.putIfCurrent(vaultId, path, version, bmp, generation)) return@withContext null
                    bmp
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                } finally {
                    bytes?.fill(0)
                    (out as? WipeableBuffer)?.wipe()
                }
            }
        }
    }

    /** Lets the internal buffer be zeroed: it holds the plaintext photo. */
    private class WipeableBuffer(size: Int) : ByteArrayOutputStream(size) {
        fun wipe() = buf.fill(0)
    }

    private fun stillOpen(vaultId: String, handle: Any) = sessions.session(vaultId)?.handle === handle
}
