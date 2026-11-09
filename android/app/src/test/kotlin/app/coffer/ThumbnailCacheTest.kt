// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import app.coffer.browser.THUMB_MAX_FILE
import app.coffer.browser.ThumbnailCache
import app.coffer.browser.ThumbnailLoader
import app.coffer.data.SessionManager
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultRef
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 4 bytes per pixel, like ARGB_8888. */
private class FakeBitmap(override val width: Int, override val height: Int) : ImageBitmap {
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = false
    override val config = ImageBitmapConfig.Argb8888
    override fun prepareToDraw() = Unit
    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
}

class ThumbnailCacheTest {
    private val ref = VaultRef("v1", "test.hc", "content://p/test.hc")

    @Test fun clear_removes_only_that_vault() {
        val cache = ThumbnailCache(1 shl 20)
        val a = FakeBitmap(10, 10)
        val b = FakeBitmap(10, 10)
        cache.put("v1", "/a.jpg", a)
        cache.put("v2", "/a.jpg", b)
        cache.clear("v1")
        assertNull(cache.get("v1", "/a.jpg"))
        assertSame(b, cache.get("v2", "/a.jpg"))
    }

    @Test fun eviction_respects_max_bytes() {
        val cache = ThumbnailCache(1000) // room for two 10x10 bitmaps (400 bytes each)
        cache.put("v", "/1", FakeBitmap(10, 10))
        cache.put("v", "/2", FakeBitmap(10, 10))
        cache.put("v", "/3", FakeBitmap(10, 10))
        assertNull(cache.get("v", "/1"))
        assertNotNull(cache.get("v", "/2"))
        assertNotNull(cache.get("v", "/3"))
    }

    private suspend fun open(sm: SessionManager) =
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 0, emptyList(), null), sm.newCancel())

    @Test fun loader_returns_null_over_size_limit() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val sm = SessionManager(FakeStore(ref), FakeNative(handle = { FakeHandle(files = mapOf("/big.jpg" to byteArrayOf(1))) }), FakeOpener(), io, { 1L shl 30 })
        open(sm)
        var decoded = false
        val loader = ThumbnailLoader(sm, ThumbnailCache(1 shl 20), io) { _, _ -> decoded = true; FakeBitmap(1, 1) }
        assertNull(loader.load("v1", "/big.jpg", THUMB_MAX_FILE + 1, 96))
        assertNull(loader.load("v1", "/notes.txt", 10, 96))
        assert(!decoded)
        assertNotNull(loader.load("v1", "/big.jpg", 1, 96))
    }

    @Test fun loader_returns_null_when_session_closed_after_read() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val sm = SessionManager(FakeStore(ref), FakeNative(handle = { FakeHandle(files = mapOf("/a.jpg" to byteArrayOf(1, 2))) }), FakeOpener(), io, { 1L shl 30 })
        open(sm)
        val cache = ThumbnailCache(1 shl 20)
        val loader = ThumbnailLoader(sm, cache, io) { _, _ -> sm.close("v1"); FakeBitmap(1, 1) }
        assertNull(loader.load("v1", "/a.jpg", 2, 96))
        assertNull(cache.get("v1", "/a.jpg"))
    }

    @Test fun loader_returns_null_on_decode_failure() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val sm = SessionManager(FakeStore(ref), FakeNative(handle = { FakeHandle(files = mapOf("/a.jpg" to byteArrayOf(1))) }), FakeOpener(), io, { 1L shl 30 })
        open(sm)
        val loader = ThumbnailLoader(sm, ThumbnailCache(1 shl 20), io) { _, _ -> throw OutOfMemoryError("huge") }
        assertNull(loader.load("v1", "/a.jpg", 1, 96))
    }

    @Test fun changed_file_misses_the_cache() {
        val cache = ThumbnailCache(1 shl 20)
        cache.put("v", "/a.jpg", FakeBitmap(1, 1), version = "10:100")
        assertNull(cache.get("v", "/a.jpg", version = "12:200"))
        assertNotNull(cache.get("v", "/a.jpg", version = "10:100"))
    }

    @Test fun put_racing_a_clear_is_dropped() {
        val cache = ThumbnailCache(1 shl 20)
        val gen = cache.generation("v")
        cache.clear("v")
        assertFalse(cache.putIfCurrent("v", "/a.jpg", "1:1", FakeBitmap(1, 1), gen))
        assertNull(cache.get("v", "/a.jpg", "1:1"))
        assert(cache.putIfCurrent("v", "/a.jpg", "1:1", FakeBitmap(1, 1), cache.generation("v")))
    }
}
