// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CreatingNative(private val failWith: CofferException? = null) : NativeVaults {
    var lastCreds: NativeCredentials? = null
    var lastOptions: CreateOptions? = null
    var passwordAtCall: String? = null
    override fun newCancel() = object : CancelSignal { override fun cancel() {} }
    override fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle = throw CofferException.WrongCredentials()
    override fun create(
        fd: Int,
        creds: NativeCredentials,
        options: CreateOptions,
        progress: (Long, Long) -> Unit,
        cancel: CancelSignal,
    ): VaultHandle {
        lastCreds = creds
        lastOptions = options
        passwordAtCall = String(creds.password.copyOf(), Charsets.UTF_8)
        progress(options.size, options.size)
        failWith?.let { throw it }
        return FakeHandle(VolumeInfo(options.cipher, options.prf, false, false, options.fs, options.size, writable = true))
    }
}

class DeletingOpener : FdOpener {
    val deleted = mutableListOf<String>()
    var writeFds = 0
    override fun openReadFd(uri: String) = 1
    override fun openWriteFd(uri: String): Int { writeFds++; return 2 }
    override fun readPrefix(uri: String, maxBytes: Int) = byteArrayOf(7, 7)
    override fun deleteDocument(uri: String) { deleted += uri }
}

class CreateTest {
    private fun req(pw: String = "new-pass", keyfiles: List<String> = emptyList()) = CreateRequest(
        password = pw.toCharArray(), keyfileUris = keyfiles, pim = 0, cipher = "AES", prf = "SHA-512",
        fs = "exFAT", size = 64L shl 20, quick = false, extraEntropy = byteArrayOf(1, 2),
    )

    @Test
    fun create_success_registers_vault_and_session() = runTest {
        val store = FakeStore()
        val native = CreatingNative()
        val opener = DeletingOpener()
        val sm = SessionManager(store, native, opener, StandardTestDispatcher(testScheduler), { 1L shl 30 })
        var last = 0L
        val pw = "new-pass".toCharArray()
        val s = sm.create("content://d/new.hc", "new.hc", req().let {
            CreateRequest(pw, it.keyfileUris, it.pim, it.cipher, it.prf, it.fs, it.size, it.quick, it.extraEntropy)
        }, { done, _ -> last = done }, sm.newCancel())
        assertEquals("new.hc", s.ref.label)
        assertTrue(store.state.value.any { it.uri == "content://d/new.hc" })
        assertTrue(s.ref.id in sm.openIds.value)
        assertEquals("new-pass", native.passwordAtCall)
        assertTrue(native.lastCreds!!.password.all { it == 0.toByte() })
        assertTrue(pw.all { it == '\u0000' })
        assertEquals(64L shl 20, last)
        assertEquals(1, opener.writeFds)
        assertEquals("SHA-512", store.state.value.first { it.uri == "content://d/new.hc" }.lastPrf)
        assertEquals("exFAT", native.lastOptions!!.fs)
    }

    @Test
    fun create_failure_deletes_document_and_adds_nothing() = runTest {
        for (err in listOf(CofferException.NoSpace(), CofferException.Cancelled())) {
            val store = FakeStore()
            val opener = DeletingOpener()
            val sm = SessionManager(store, CreatingNative(err), opener, StandardTestDispatcher(testScheduler), { 1L shl 30 })
            try {
                sm.create("content://d/x.hc", "x.hc", req(), { _, _ -> }, sm.newCancel())
                fail("expected ${err::class.simpleName}")
            } catch (e: CofferException) {
                assertEquals(err::class, e::class)
            }
            assertEquals(listOf("content://d/x.hc"), opener.deleted)
            assertTrue(store.state.value.none { it.uri == "content://d/x.hc" })
            assertTrue(sm.openIds.value.isEmpty())
        }
    }

    @Test
    fun keyfiles_are_read_and_wiped() = runTest {
        val native = CreatingNative()
        val sm = SessionManager(FakeStore(), native, DeletingOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        sm.create("content://d/k.hc", "k.hc", req("", listOf("content://kf")), { _, _ -> }, sm.newCancel())
        assertTrue(native.lastCreds!!.keyfiles.single().all { it == 0.toByte() })
    }

    @Test
    fun invalid_size_maps_from_native() {
        val e = mapNativeError(app.coffer.ffi.FfiException.InvalidSize("too small"))
        assertTrue(e is CofferException.InvalidSize)
    }
}
