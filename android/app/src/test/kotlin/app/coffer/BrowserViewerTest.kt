// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.browser.BrowserEffect
import app.coffer.browser.BrowserViewModel
import app.coffer.data.CofferException
import app.coffer.data.Entry
import app.coffer.data.SessionManager
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultRef
import app.coffer.provider.ProxyReader
import app.coffer.util.FileKind
import app.coffer.util.MimeTypes
import app.coffer.viewer.ViewerContent
import app.coffer.viewer.ViewerViewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewerTest {
    private val ref = VaultRef("v1", "test.hc", "content://p/test.hc")
    private val big = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
    private val handle = FakeHandle(
        files = mapOf(
            "/notes.txt" to "ciao".toByteArray(),
            "/big.bin" to big,
            "/long.txt" to ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() },
            "/huge.jpg" to ByteArray(10),
        ),
        dirs = mapOf(
            "/" to listOf(
                Entry("zeta.txt", false, 1, null), Entry("Beta", true, 0, null),
                Entry("alpha.txt", false, 1, null), Entry("alfa", true, 0, null),
            ),
            "/Beta" to listOf(Entry("inner.txt", false, 1, null)),
        ),
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.openSessions(h: FakeHandle = handle): SessionManager {
        val store = FakeStore(ref)
        val sm = SessionManager(store, FakeNative(handle = { h }), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 0, emptyList(), null), sm.newCancel())
        return sm
    }

    @Test
    fun entries_sorted_dirs_first_case_insensitive() = runTest {
        val vm = BrowserViewModel("v1", openSessions(), FakeExporter(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(listOf("alfa", "Beta", "alpha.txt", "zeta.txt"), vm.uiState.value.entries.map { it.name })
    }

    @Test
    fun navigate_into_dir_and_up_then_exit_from_root() = runTest {
        val vm = BrowserViewModel("v1", openSessions(), FakeExporter(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.onOpen(Entry("Beta", true, 0, null))
        advanceUntilIdle()
        assertEquals("/Beta", vm.uiState.value.path)
        assertEquals(listOf("inner.txt"), vm.uiState.value.entries.map { it.name })
        assertEquals(listOf("Beta"), vm.uiState.value.breadcrumbs)
        vm.onUp()
        advanceUntilIdle()
        assertEquals("/", vm.uiState.value.path)
        vm.onUp()
        advanceUntilIdle()
        assertEquals(BrowserEffect.Exit, vm.effects.first())
    }

    @Test
    fun open_file_emits_open_effect() = runTest {
        val vm = BrowserViewModel("v1", openSessions(), FakeExporter(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.onOpen(Entry("zeta.txt", false, 1, null))
        assertEquals(BrowserEffect.OpenFile("/zeta.txt"), vm.effects.first())
    }

    @Test
    fun export_copies_exact_bytes() = runTest {
        val exporter = FakeExporter()
        val vm = BrowserViewModel("v1", openSessions(), exporter, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.onExport("/big.bin", big.size.toLong(), "content://out/big.bin")
        advanceUntilIdle()
        assertArrayEquals(big, exporter.sinks.getValue("content://out/big.bin").toByteArray())
        assertTrue(vm.effects.first() is BrowserEffect.Exported)
    }

    @Test
    fun locked_session_exits_browser() = runTest {
        val sm = openSessions()
        sm.close("v1")
        val vm = BrowserViewModel("v1", sm, FakeExporter(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(BrowserEffect.Exit, vm.effects.first())
    }

    @Test
    fun mime_classification() {
        assertEquals(FileKind.Image, MimeTypes.kind("Foto.JPG"))
        assertEquals(FileKind.Text, MimeTypes.kind("note.md"))
        assertEquals(FileKind.Other, MimeTypes.kind("archive.zip"))
        assertEquals("image/jpeg", MimeTypes.mime("x.jpeg"))
        assertEquals("application/octet-stream", MimeTypes.mime("noext"))
    }

    @Test
    fun image_over_limit_rejected_without_reading() = runTest {
        val h = FakeHandle(files = mapOf("/huge.jpg" to ByteArray(10)), dirs = mapOf("/" to emptyList()))
        val sm = openSessions(h)
        val vm = ViewerViewModel("v1", "/huge.jpg", sm, StandardTestDispatcher(testScheduler), imageLimit = 5)
        advanceUntilIdle()
        assertEquals(ViewerContent.TooLarge, vm.uiState.value.content)
    }

    @Test
    fun text_truncated_with_flag() = runTest {
        val vm = ViewerViewModel("v1", "/long.txt", openSessions(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val c = vm.uiState.value.content as ViewerContent.Text
        assertEquals(1024 * 1024, c.text.length)
        assertTrue(c.truncated)
        val small = ViewerViewModel("v1", "/notes.txt", openSessions(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(ViewerContent.Text("ciao", false), small.uiState.value.content)
    }

    @Test
    fun out_of_memory_while_loading_image_shows_too_large() = runTest {
        val h = object : app.coffer.data.VaultHandle by FakeHandle(files = mapOf("/p.jpg" to ByteArray(10))) {
            override fun read(path: String, offset: Long, len: Int): ByteArray = throw OutOfMemoryError("test")
        }
        val store = FakeStore(ref)
        val sm = SessionManager(store, FakeNative(handle = { h }), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 0, emptyList(), null), sm.newCancel())
        val vm = ViewerViewModel("v1", "/p.jpg", sm, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(ViewerContent.TooLarge, vm.uiState.value.content)
    }

    @Test
    fun proxy_reader_reads_ranges_and_fails_after_lock() {
        val h = FakeHandle(files = mapOf("/f" to ByteArray(100) { it.toByte() }))
        val r = ProxyReader(h, "/f")
        val buf = ByteArray(30)
        assertEquals(30, r.read(10, 30, buf))
        assertEquals(10.toByte(), buf[0])
        assertEquals(5, r.read(95, 30, buf))
        assertEquals(0, r.read(100, 30, buf))
        h.lock()
        try {
            r.read(0, 10, buf)
            fail("expected Closed")
        } catch (_: CofferException.Closed) {
        }
    }
}

class FakeExporter : app.coffer.platform.Exporter {
    val sinks = mutableMapOf<String, ByteArrayOutputStream>()
    override fun openSink(uri: String) = ByteArrayOutputStream().also { sinks[uri] = it }
}
