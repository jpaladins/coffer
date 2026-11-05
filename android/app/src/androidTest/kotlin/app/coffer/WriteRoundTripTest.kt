// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.coffer.data.CofferException
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultRef
import app.coffer.data.importStream
import app.coffer.data.removeRecursive
import app.coffer.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real native core writing into a VeraCrypt-made FAT12 container on the device. */
@RunWith(AndroidJUnit4::class)
class WriteRoundTripTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions = EntryPointAccessors.fromApplication(ctx.applicationContext, AppEntryPoint::class.java).sessions()
    private val file = File(ctx.cacheDir, "fat12-rw.hc").also { f ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("fat12.hc").use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
    }
    private val ref = VaultRef("rw", "fat12-rw.hc", Uri.fromFile(file).toString())

    private fun open(writable: Boolean) = runBlocking {
        sessions.unlock(
            ref,
            UnlockRequest("coffer-test".toCharArray(), 0, emptyList(), "SHA-512", writable = writable),
            sessions.newCancel(),
        )
    }

    @After
    fun tearDown() {
        sessions.closeAll()
        file.delete()
    }

    @Test
    fun write_lock_reopen_and_read_back() {
        val data = ByteArray(600_000) { (it * 7 + 3).toByte() }
        open(writable = true).handle.apply {
            assertTrue(info.writable)
            mkdir("/Nuova cartella")
            assertEquals(data.size.toLong(), importStream("/Nuova cartella/foto è.bin", ByteArrayInputStream(data)))
            rename("/Nuova cartella", "/Archivio")
            mkdir("/tmp")
            createFile("/tmp/x.txt")
            write("/tmp/x.txt", 0, "ciao".toByteArray())
            removeRecursive("/tmp")
        }
        sessions.close("rw")

        val h = open(writable = false).handle
        assertFalse(h.info.writable)
        val back = h.read("/Archivio/foto è.bin", 0, data.size + 10)
        assertArrayEquals(data, back)
        assertTrue(h.list("/").none { it.name == "tmp" })
        assertTrue(h.list("/").any { it.name == "sector-plus.bin" }) // Windows-written file kept
        try {
            h.createFile("/no.txt")
            fail("expected ReadOnly")
        } catch (_: CofferException.ReadOnly) {
        }
    }
}
