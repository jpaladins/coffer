// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultRef
import app.coffer.data.importStream
import app.coffer.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Photo previews are decoded in memory: loading them creates no file anywhere in the app's storage. */
@RunWith(AndroidJUnit4::class)
class ThumbnailNoDiskTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, AppEntryPoint::class.java)
    private val sessions = entry.sessions()
    private val file = File(ctx.cacheDir, "fat12-thumbs.hc").also { f ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("fat12.hc").use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
    }
    private val ref = VaultRef("thumbs", "fat12-thumbs.hc", Uri.fromFile(file).toString())

    @After
    fun tearDown() {
        sessions.closeAll()
        file.delete()
    }

    private fun png(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Every file under the app's private and cache folders, with its size. */
    private fun snapshot(): Set<Pair<String, Long>> =
        listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.absolutePath to it.length() }.toList() }
            .toSet()

    @Test
    fun loading_thumbnails_writes_nothing() = runBlocking {
        val h = sessions.unlock(
            ref,
            UnlockRequest("coffer-test".toCharArray(), 0, emptyList(), "SHA-512", writable = true),
            sessions.newCancel(),
        ).handle
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        colors.forEachIndexed { i, c -> h.importStream("/p$i.png", ByteArrayInputStream(png(c))) }
        h.sync()

        val before = snapshot()
        val loader = entry.thumbnails()
        colors.indices.forEach { i ->
            assertNotNull("thumbnail $i", loader.load("thumbs", "/p$i.png", h.stat("/p$i.png").size, 32))
        }
        assertEquals(before, snapshot())
    }
}
