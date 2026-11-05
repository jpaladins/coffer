// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.coffer.data.CofferException
import app.coffer.data.SessionManager
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultRef
import app.coffer.data.VaultSession
import app.coffer.platform.Exporter
import app.coffer.provider.VaultFileProvider
import app.coffer.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real native core + real provider on the device, with a container made by
 * VeraCrypt 1.26.29 (corpus aes-sha512-fat-fat12.hc, files written by Windows).
 */
@RunWith(AndroidJUnit4::class)
class ProviderAndLockTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val deps = EntryPointAccessors.fromApplication(ctx.applicationContext, AppEntryPoint::class.java)
    private val sessions = deps.sessions()
    private lateinit var session: VaultSession

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun unlock() {
        val file = File(ctx.cacheDir, "fat12.hc")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fat12.hc").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val ref = VaultRef("e2e", "fat12.hc", Uri.fromFile(file).toString())
        session = runBlocking {
            sessions.unlock(ref, UnlockRequest("coffer-test".toCharArray(), 0, emptyList(), "SHA-512"), sessions.newCancel())
        }
    }

    @After
    fun tearDown() = sessions.closeAll()

    @Test
    fun provider_serves_exact_bytes_and_metadata() {
        val uri = VaultFileProvider.uriFor(session.token, "/sector-plus.bin")
        val bytes = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { FileInputStream(it.fileDescriptor).readBytes() }
        assertEquals(513, bytes.size)
        assertEquals("5475aa58d698b23935d7cca3f329babc0bb0670fa5cba296a5178d9955766c41", sha256(bytes))
        ctx.contentResolver.query(uri, null, null, null, null)!!.use {
            it.moveToFirst()
            assertEquals("sector-plus.bin", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(513L, it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val unicode = VaultFileProvider.uriFor(session.token, "/àèìòù €.txt")
        val u = ctx.contentResolver.openFileDescriptor(unicode, "r")!!.use { FileInputStream(it.fileDescriptor).readBytes() }
        assertEquals("cd8bc8bd6b3353b405f3f3139e9142435bfda71825e2db1fbf320c5aba07c145", sha256(u))
    }

    /** Review P3 #1: locking while a reader is active must fail reads, not crash. */
    @Test
    fun lock_during_reads_fails_cleanly() {
        val uri = VaultFileProvider.uriFor(session.token, "/big.bin")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")!!
        val channel = FileInputStream(pfd.fileDescriptor).channel
        assertEquals(4096, channel.read(java.nio.ByteBuffer.allocate(4096), 0))
        sessions.close("e2e")
        // Pages already fetched may still be served from the kernel's FUSE
        // cache (data the reader already received). Parts never read must fail.
        try {
            channel.read(java.nio.ByteBuffer.allocate(4096), 258_048)
            fail("read of an unread region after lock should fail")
        } catch (_: IOException) {
        } finally {
            runCatching { pfd.close() }
        }
        try {
            session.handle.read("/big.bin", 0, 10)
            fail("expected Closed")
        } catch (_: CofferException.Closed) {
        }
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")
            fail("URI must be dead after lock")
        } catch (_: FileNotFoundException) {
        }
    }

    /** Review P3 #4: a share URI from an earlier session is not valid in a new one. */
    @Test
    fun old_share_uri_dies_with_its_session() {
        val old = VaultFileProvider.uriFor(session.token, "/sector-plus.bin")
        sessions.close("e2e")
        unlock()
        try {
            ctx.contentResolver.openFileDescriptor(old, "r")
            fail("old token must not resolve")
        } catch (_: FileNotFoundException) {
        }
    }

    @Test
    fun export_writes_exact_bytes() {
        val out = File(ctx.cacheDir, "export.bin").apply { delete() }
        deps.exporter().openSink(Uri.fromFile(out).toString()).use { sink ->
            var off = 0L
            while (true) {
                val chunk = session.handle.read("/big.bin", off, 1 shl 20)
                if (chunk.isEmpty()) break
                sink.write(chunk)
                off += chunk.size
            }
        }
        val bytes = out.readBytes()
        out.delete()
        assertEquals(262144, bytes.size)
        assertEquals("107b1b033d38b279be3cd3e60885d70e5685b42b7547bf01e33ea596a00b1c12", sha256(bytes))
        assertTrue(bytes.isNotEmpty())
        assertArrayEquals(bytes.copyOf(16), session.handle.read("/big.bin", 0, 16))
    }
}
