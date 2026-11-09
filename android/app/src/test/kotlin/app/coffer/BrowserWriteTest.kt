// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.browser.BrowserEffect
import app.coffer.browser.BrowserViewModel
import app.coffer.data.CofferException
import app.coffer.data.Entry
import app.coffer.data.SessionManager
import app.coffer.data.UnlockRequest
import app.coffer.data.VaultHandle
import app.coffer.data.VaultRef
import app.coffer.security.AutoLock
import app.coffer.security.LockGuard
import app.coffer.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserWriteTest {
    private val ref = VaultRef("v1", "test.hc", "content://p/test.hc")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Env(val vm: BrowserViewModel, val sm: SessionManager, val guard: LockGuard, val transfers: TransferManager)

    private suspend fun TestScope.env(
        h: VaultHandle,
        docs: Map<String, ByteArray> = emptyMap(),
    ): Env {
        val sm = SessionManager(FakeStore(ref), FakeNative(handle = { h }), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 0, emptyList(), null, writable = true), sm.newCancel())
        val guard = LockGuard()
        val transfers = TransferManager(sm, FakeSource(docs), guard, StandardTestDispatcher(testScheduler), this)
        val vm = BrowserViewModel("v1", sm, FakeExporter(), StandardTestDispatcher(testScheduler), transfers, guard)
        advanceUntilIdle()
        return Env(vm, sm, guard, transfers)
    }

    private fun names(e: Env) = e.vm.uiState.value.entries.map { it.name }

    @Test
    fun read_only_volume_reports_and_rejects_writes() = runTest {
        val e = env(FakeHandle(dirs = mapOf("/" to emptyList())))
        assertFalse(e.vm.uiState.value.writable)
        e.vm.onNewFolder("x")
        advanceUntilIdle()
        val fx = e.vm.effects.first()
        assertTrue(fx is BrowserEffect.Failed && fx.error is CofferException.ReadOnly)
    }

    @Test
    fun new_folder_rename_delete_and_reload() = runTest {
        val h = WritableHandle()
        val e = env(h)
        assertTrue(e.vm.uiState.value.writable)
        e.vm.onNewFolder("  Foto  ")
        advanceUntilIdle()
        assertEquals(listOf("Foto"), names(e))
        e.vm.onRename(Entry("Foto", true, 0, null), "Vacanze")
        advanceUntilIdle()
        assertEquals(listOf("Vacanze"), names(e))
        h.createFile("/Vacanze/a.txt")
        e.vm.onDelete(Entry("Vacanze", true, 0, null))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), names(e))
        assertTrue(h.files.isEmpty())
        assertTrue(h.syncs >= 3)
        assertFalse(e.vm.uiState.value.busy)
    }

    @Test
    fun name_conflict_is_reported() = runTest {
        val h = WritableHandle()
        h.mkdir("/a")
        h.createFile("/b")
        val e = env(h)
        e.vm.onRename(Entry("b", false, 0, null), "A")
        advanceUntilIdle()
        val fx = e.vm.effects.first()
        assertTrue(fx is BrowserEffect.Failed && fx.error is CofferException.AlreadyExists)
        assertEquals(listOf("a", "b"), names(e).sorted())
    }

    @Test
    fun move_into_other_folder() = runTest {
        val h = WritableHandle()
        h.mkdir("/dst")
        h.createFile("/f.txt")
        val e = env(h)
        e.vm.onStartMove(Entry("f.txt", false, 0, null))
        assertFalse(e.vm.uiState.value.canMoveHere) // same folder
        e.vm.onOpen(Entry("dst", true, 0, null))
        advanceUntilIdle()
        assertTrue(e.vm.uiState.value.canMoveHere)
        e.vm.onMoveHere()
        advanceUntilIdle()
        assertNull(e.vm.uiState.value.moving)
        assertEquals(listOf("f.txt"), names(e))
        assertTrue("/dst/f.txt" in h.files)
    }

    @Test
    fun cannot_move_folder_into_itself() = runTest {
        val h = WritableHandle()
        h.mkdir("/a")
        h.mkdir("/a/b")
        val e = env(h)
        e.vm.onStartMove(Entry("a", true, 0, null))
        e.vm.onOpen(Entry("a", true, 0, null))
        advanceUntilIdle()
        assertFalse(e.vm.uiState.value.canMoveHere)
        e.vm.onOpen(Entry("b", true, 0, null))
        advanceUntilIdle()
        assertFalse(e.vm.uiState.value.canMoveHere)
        e.vm.onMoveHere()
        advanceUntilIdle()
        assertTrue("/a/b" in h.dirs)
    }

    @Test
    fun import_copies_files_with_unique_names_and_releases_guard() = runTest {
        val h = WritableHandle()
        h.createFile("/foto.jpg")
        val big = ByteArray(3 * 1024 * 1024 + 5) { (it % 253).toByte() }
        val e = env(h, mapOf("content://d/foto.jpg" to big, "content://d/n:ote?.txt" to "ciao".toByteArray()))
        e.vm.onImport(listOf("content://d/foto.jpg", "content://d/n:ote?.txt"))
        assertEquals(1, e.guard.held.value)
        advanceUntilIdle()
        assertArrayEquals(big, h.files["/foto (1).jpg"])
        assertEquals(listOf("/foto (1).jpg", "/foto.jpg", "/n_ote_.txt"), h.files.keys.toList())
        assertArrayEquals("ciao".toByteArray(), h.files["/n_ote_.txt"])
        val fx = e.vm.effects.first()
        assertTrue(fx is BrowserEffect.Imported && fx.result.imported == 2 && fx.result.failures.isEmpty())
        assertEquals(0, e.guard.held.value)
        assertNull(e.vm.uiState.value.transfer)
        assertTrue("foto (1).jpg" in names(e))
    }

    @Test
    fun import_out_of_space_stops_and_leaves_no_partial_file() = runTest {
        val h = WritableHandle(failWritesAfter = 1_500_000)
        val e = env(h, mapOf("content://d/a.bin" to ByteArray(3_000_000), "content://d/b.bin" to ByteArray(10)))
        e.vm.onImport(listOf("content://d/a.bin", "content://d/b.bin"))
        advanceUntilIdle()
        val fx = e.vm.effects.first()
        assertTrue(fx is BrowserEffect.Imported)
        val r = (fx as BrowserEffect.Imported).result
        assertEquals(0, r.imported)
        assertTrue(r.failures.single().second is CofferException.NoSpace)
        assertTrue(h.files.isEmpty())
        assertEquals(0, e.guard.held.value)
    }

    @Test
    fun export_holds_lock_guard_until_done() = runTest {
        val h = WritableHandle()
        h.createFile("/x")
        h.write("/x", 0, ByteArray(10))
        val e = env(h)
        e.vm.onExport("/x", 10, "content://out/x")
        assertEquals(1, e.guard.held.value)
        advanceUntilIdle()
        assertEquals(0, e.guard.held.value)
    }

    @Test
    fun lock_from_browser_locks_and_exits() = runTest {
        val h = WritableHandle()
        val e = env(h)
        e.vm.onLock()
        advanceUntilIdle()
        assertTrue(h.locked)
        assertEquals(BrowserEffect.Exit, e.vm.effects.first())
    }

    @Test
    fun autolock_waits_for_guard_release() = runTest {
        var locks = 0
        val guard = LockGuard()
        val a = AutoLock(backgroundScope, { 1_000 }, guard.held) { locks++ }
        runCurrent()
        guard.acquire()
        runCurrent()
        a.onBackground()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, locks)
        guard.release()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, locks)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, locks)
    }

    @Test
    fun guard_acquired_while_timer_runs_stops_it() = runTest {
        var locks = 0
        val guard = LockGuard()
        val a = AutoLock(backgroundScope, { 1_000 }, guard.held) { locks++ }
        runCurrent()
        a.onBackground()
        advanceTimeBy(500)
        guard.acquire()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, locks)
        guard.release()
        guard.release() // extra release is harmless
        assertEquals(0, guard.held.value)
    }

    @Test
    fun delete_selected_removes_all_and_clears_selection() = runTest {
        val h = WritableHandle()
        h.files["/a.txt"] = byteArrayOf(1); h.files["/b.txt"] = byteArrayOf(2); h.files["/keep.txt"] = byteArrayOf(3)
        val e = env(h)
        e.vm.onToggleSelect("a.txt")
        e.vm.onToggleSelect("b.txt")
        assertEquals(setOf("a.txt", "b.txt"), e.vm.uiState.value.selected)
        e.vm.onDeleteSelected()
        advanceUntilIdle()
        assertEquals(listOf("keep.txt"), names(e))
        assertTrue(e.vm.uiState.value.selected.isEmpty())
    }

    @Test
    fun delete_selected_skips_entries_hidden_by_search() = runTest {
        val h = WritableHandle()
        h.files["/a.txt"] = byteArrayOf(1); h.files["/b.txt"] = byteArrayOf(2)
        val e = env(h)
        e.vm.onToggleSelect("a.txt")
        e.vm.onToggleSelect("b.txt")
        e.vm.onQuery("b")
        assertEquals(setOf("b.txt"), e.vm.uiState.value.actionable)
        e.vm.onDeleteSelected()
        advanceUntilIdle()
        assertEquals(listOf("a.txt"), names(e))
    }

    @Test
    fun delete_selected_blocked_during_import() = runTest {
        val h = WritableHandle()
        h.files["/keep.txt"] = byteArrayOf(3)
        val sm = SessionManager(FakeStore(ref), FakeNative(handle = { h }), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 0, emptyList(), null, writable = true), sm.newCancel())
        var vm: BrowserViewModel? = null
        var sawTransfer = false
        val src = object : app.coffer.transfer.ImportSource {
            override fun displayName(uri: String) = "in.bin"
            override fun openInput(uri: String) = object : java.io.InputStream() {
                var first = true
                override fun read() = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!first) return -1
                    first = false
                    val v = vm!!
                    sawTransfer = v.uiState.value.transfer != null
                    v.onToggleSelect("keep.txt")
                    v.onDeleteSelected()
                    b[off] = 1
                    return 1
                }
            }
        }
        val guard = LockGuard()
        val transfers = TransferManager(sm, src, guard, StandardTestDispatcher(testScheduler), this)
        vm = BrowserViewModel("v1", sm, FakeExporter(), StandardTestDispatcher(testScheduler), transfers, guard)
        advanceUntilIdle()
        vm.onImport(listOf("content://d/in.bin"))
        advanceUntilIdle()
        assertTrue(sawTransfer)
        assertTrue("/keep.txt" in h.files)
    }

    @Test
    fun navigating_clears_selection_and_query() = runTest {
        val h = WritableHandle()
        h.dirs += "/sub"
        h.files["/a.txt"] = byteArrayOf(1)
        val e = env(h)
        e.vm.onToggleSelect("a.txt")
        e.vm.onQuery("a")
        e.vm.onOpen(app.coffer.data.Entry("sub", true, 0, null))
        advanceUntilIdle()
        assertEquals("/sub", e.vm.uiState.value.path)
        assertTrue(e.vm.uiState.value.selected.isEmpty())
        assertEquals("", e.vm.uiState.value.query)
    }

    /** Review I3: "Lock all" during an import stops it first: no truncated file stays. */
    @Test
    fun lock_all_during_import_leaves_no_partial_file() = kotlinx.coroutines.runBlocking {
        val h = WritableHandle()
        val io = Dispatchers.IO
        val sm = SessionManager(FakeStore(ref), FakeNative(handle = { h }), FakeOpener(), io, { 1L shl 30 })
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 0, emptyList(), null, writable = true), sm.newCancel())
        val slow = object : app.coffer.transfer.ImportSource {
            override fun displayName(uri: String) = "slow.bin"
            override fun openInput(uri: String) = object : java.io.InputStream() {
                var left = 50L shl 20
                override fun read() = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left == 0L) return -1
                    Thread.sleep(2)
                    val n = minOf(len, 64 * 1024, left.toInt())
                    left -= n
                    return n
                }
            }
        }
        val transfers = TransferManager(sm, slow, LockGuard(), io, kotlinx.coroutines.CoroutineScope(io))
        assertTrue(transfers.import("v1", "/", listOf("content://d/slow.bin")))
        kotlinx.coroutines.withTimeout(5_000) { transfers.progress.first { (it?.bytes ?: 0) > 0 } }
        sm.closeAll()
        assertTrue(h.locked)
        assertTrue("partial file left: ${h.files.keys}", h.files.isEmpty())
    }
}
