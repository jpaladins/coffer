// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import java.io.FileNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeHandle(override val info: VolumeInfo) : VaultHandle {
    var locked = false
    override fun list(path: String) = listOf(Entry("a.txt", false, 3, null))
    override fun stat(path: String) = Entry("a.txt", false, 3, null)
    override fun read(path: String, offset: Long, len: Int) = ByteArray(0)
    override fun lock() { locked = true }
}

class FakeNative(private val password: String) : NativeVaults {
    val seenPasswords = mutableListOf<ByteArray>()
    val handles = mutableListOf<FakeHandle>()
    var lastCreds: NativeCredentials? = null
    override fun newCancel() = object : CancelSignal { override fun cancel() {} }
    override fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle {
        lastCreds = creds
        seenPasswords += creds.password
        if (creds.prf == "STALE" || String(creds.password.copyOf(), Charsets.UTF_8) != password) {
            throw CofferException.WrongCredentials()
        }
        return FakeHandle(VolumeInfo("AES", "SHA-512", false, false, "exFAT", 1000)).also { handles += it }
    }
}

class FakeOpener(private val fail: Exception? = null) : FdOpener {
    var opened = 0
    override fun openReadFd(uri: String): Int {
        fail?.let { throw it }
        opened++
        return 42
    }
    override fun readPrefix(uri: String, maxBytes: Int) = byteArrayOf(1, 2, 3)
}

class FakeStore : VaultStore {
    val state = MutableStateFlow(listOf(VaultRef("v1", "test.hc", "content://x/test.hc", null)))
    override val vaults: Flow<List<VaultRef>> = state
    override suspend fun add(uri: String, label: String) = VaultRef("v2", label, uri, null).also { state.value += it }
    override suspend fun remove(id: String) { state.value = state.value.filter { it.id != id } }
    override suspend fun rememberPrf(id: String, prf: String) {
        state.value = state.value.map { if (it.id == id) it.copy(lastPrf = prf) else it }
    }
}

class SessionManagerTest {
    private val ref = VaultRef("v1", "test.hc", "content://x/test.hc", null)

    private fun manager(native: NativeVaults, opener: FdOpener = FakeOpener(), store: FakeStore = FakeStore()) =
        SessionManager(store, native, opener, StandardTestDispatcher(), maxKdfMemory = { 1L shl 30 })

    private fun req(pw: String) = UnlockRequest(pw.toCharArray(), pim = 0, keyfileUris = emptyList(), prf = null)

    @Test
    fun unlock_success_registers_session_and_remembers_prf() = runTest {
        val store = FakeStore()
        val sm = SessionManager(store, FakeNative("secret"), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        val s = sm.unlock(ref, req("secret"), sm.newCancel())
        assertEquals("v1", s.ref.id)
        assertEquals(setOf("v1"), sm.openIds.value)
        assertEquals(s, sm.session("v1"))
        assertEquals("SHA-512", store.state.value.first().lastPrf)
    }

    @Test
    fun wrong_password_keeps_no_session() = runTest {
        val sm = SessionManager(FakeStore(), FakeNative("secret"), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        try {
            sm.unlock(ref, req("nope"), sm.newCancel())
            fail("expected WrongCredentials")
        } catch (_: CofferException.WrongCredentials) {
        }
        assertTrue(sm.openIds.value.isEmpty())
        assertNull(sm.session("v1"))
    }

    @Test
    fun permission_lost_maps_to_PermissionLost() = runTest {
        for (e in listOf(SecurityException("revoked"), FileNotFoundException("gone"))) {
            val sm = SessionManager(FakeStore(), FakeNative("secret"), FakeOpener(e), StandardTestDispatcher(testScheduler), { 1L shl 30 })
            try {
                sm.unlock(ref, req("secret"), sm.newCancel())
                fail("expected PermissionLost")
            } catch (_: CofferException.PermissionLost) {
            }
        }
    }

    @Test
    fun password_bytes_zeroed_after_unlock() = runTest {
        val native = FakeNative("secret")
        val sm = SessionManager(FakeStore(), native, FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        val chars = "secret".toCharArray()
        sm.unlock(ref, UnlockRequest(chars, 0, listOf("content://kf"), null), sm.newCancel())
        assertTrue(native.seenPasswords.single().all { it == 0.toByte() })
        assertTrue(native.lastCreds!!.keyfiles.single().all { it == 0.toByte() })
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun hints_and_options_are_passed_through() = runTest {
        val native = FakeNative("secret")
        val sm = SessionManager(FakeStore(), native, FakeOpener(), StandardTestDispatcher(testScheduler), { 123L })
        sm.unlock(ref, UnlockRequest("secret".toCharArray(), 485, emptyList(), "Whirlpool", tryBackupHeaders = true), sm.newCancel())
        val c = native.lastCreds!!
        assertEquals(485, c.pim)
        assertEquals("Whirlpool", c.prf)
        assertEquals(123L, c.maxKdfMemory)
        assertTrue(c.tryBackupHeaders)
    }

    @Test
    fun closeAll_locks_every_session() = runTest {
        val native = FakeNative("secret")
        val sm = SessionManager(FakeStore(), native, FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        sm.unlock(ref, req("secret"), sm.newCancel())
        sm.unlock(ref.copy(id = "v2"), req("secret"), sm.newCancel())
        sm.closeAll()
        assertTrue(native.handles.all { it.locked })
        assertTrue(sm.openIds.value.isEmpty())
    }

    @Test
    fun utf8_password_encoding() {
        assertArrayEquals("pàsswörd€".toByteArray(Charsets.UTF_8), "pàsswörd€".toCharArray().toUtf8Bytes())
    }

    @Test
    fun stale_remembered_prf_falls_back_to_detection() = runTest {
        val native = FakeNative("secret")
        val sm = SessionManager(FakeStore(), native, FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        val s = sm.unlock(ref.copy(lastPrf = "STALE"), req("secret"), sm.newCancel())
        assertEquals("v1", s.ref.id)
        assertNull(native.lastCreds!!.prf)
    }
}
