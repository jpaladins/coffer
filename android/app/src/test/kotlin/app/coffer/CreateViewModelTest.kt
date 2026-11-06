// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.create.Blocker
import app.coffer.create.CreateEffect
import app.coffer.create.CreatePhase
import app.coffer.create.CreateViewModel
import app.coffer.create.PasswordStrength
import app.coffer.create.Strength
import app.coffer.data.CancelSignal
import app.coffer.data.CofferException
import app.coffer.data.CreateOptions
import app.coffer.data.FdOpener
import app.coffer.data.NativeCredentials
import app.coffer.data.NativeVaults
import app.coffer.data.SessionManager
import app.coffer.data.VaultHandle
import app.coffer.data.VolumeInfo
import app.coffer.security.LockGuard
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Native(val fail: CofferException? = null) : NativeVaults {
        var options: CreateOptions? = null
        override fun newCancel() = object : CancelSignal { override fun cancel() {} }
        override fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle = throw CofferException.WrongCredentials()
        override fun ciphers() = listOf("AES", "Serpent")
        override fun prfs() = listOf("SHA-512", "Argon2id")
        override fun create(fd: Int, creds: NativeCredentials, options: CreateOptions, progress: (Long, Long) -> Unit, cancel: CancelSignal): VaultHandle {
            this.options = options
            progress(options.size / 2, options.size)
            fail?.let { throw it }
            return FakeHandle()
        }
    }

    private class Opener : FdOpener {
        val deleted = mutableListOf<String>()
        override fun openReadFd(uri: String) = 1
        override fun openWriteFd(uri: String) = 2
        override fun readPrefix(uri: String, maxBytes: Int) = byteArrayOf(1)
        override fun deleteDocument(uri: String) { deleted += uri }
    }

    private class Env(val vm: CreateViewModel, val native: Native, val opener: Opener, val store: FakeStore, val guard: LockGuard)

    private fun TestScope.env(fail: CofferException? = null): Env {
        val store = FakeStore()
        val native = Native(fail)
        val opener = Opener()
        val sm = SessionManager(store, native, opener, StandardTestDispatcher(testScheduler), { 1L shl 30 })
        val guard = LockGuard()
        return Env(CreateViewModel(sm, native, FakeDocuments(), guard), native, opener, store, guard)
    }

    private fun CreateViewModel.ready() {
        onLocationPicked("content://d/Coffer.hc")
        onSizeChange("64")
    }

    @Test
    fun create_disabled_until_valid() = runTest {
        val vm = env().vm
        assertEquals(Blocker.NO_LOCATION, vm.blocker("pw".toCharArray(), "pw".toCharArray()))
        vm.onLocationPicked("content://d/Coffer.hc")
        vm.onSizeChange("")
        assertEquals(Blocker.SIZE_INVALID, vm.blocker("pw".toCharArray(), "pw".toCharArray()))
        vm.onSizeUnitGb(false)
        vm.onSizeChange("3") // 3 MiB < exFAT minimum
        assertEquals(Blocker.SIZE_TOO_SMALL, vm.blocker("pw".toCharArray(), "pw".toCharArray()))
        vm.onFsChange("FAT")
        assertNull(vm.blocker("pw".toCharArray(), "pw".toCharArray()))
        assertEquals(Blocker.PASSWORD_MISMATCH, vm.blocker("pw".toCharArray(), "px".toCharArray()))
        assertEquals(Blocker.NO_PASSWORD, vm.blocker(CharArray(0), CharArray(0)))
        vm.onAddKeyfile("content://kf", "kf")
        assertNull(vm.blocker(CharArray(0), CharArray(0)))
    }

    @Test
    fun create_success_adds_vault_and_navigates() = runTest {
        val e = env()
        e.vm.ready()
        e.vm.onCreate("good-pass".toCharArray(), "good-pass".toCharArray())
        advanceUntilIdle()
        val fx = e.vm.effects.first()
        assertTrue(fx is CreateEffect.Opened)
        assertEquals(64L shl 20, e.native.options!!.size)
        assertEquals("exFAT", e.native.options!!.fs)
        assertFalse(e.native.options!!.quick)
        assertEquals("AES", e.native.options!!.cipher)
        assertEquals("SHA-512", e.native.options!!.prf)
        assertTrue(e.store.state.value.any { it.uri == "content://d/Coffer.hc" })
        assertEquals(0, e.guard.held.value)
    }

    @Test
    fun create_failure_deletes_document_and_adds_nothing() = runTest {
        val e = env(CofferException.NoSpace())
        e.vm.ready()
        e.vm.onCreate("good-pass".toCharArray(), "good-pass".toCharArray())
        advanceUntilIdle()
        val phase = e.vm.uiState.value.phase
        assertTrue(phase is CreatePhase.Error && phase.error is CofferException.NoSpace)
        assertEquals(listOf("content://d/Coffer.hc"), e.opener.deleted)
        assertTrue(e.store.state.value.isEmpty())
        assertNull(e.vm.uiState.value.locationUri) // must pick a new file
    }

    @Test
    fun cancel_deletes_document() = runTest {
        val e = env(CofferException.Cancelled())
        e.vm.ready()
        e.vm.onCreate("good-pass".toCharArray(), "good-pass".toCharArray())
        advanceUntilIdle()
        assertEquals(CreatePhase.Idle, e.vm.uiState.value.phase)
        assertEquals(listOf("content://d/Coffer.hc"), e.opener.deleted)
    }

    @Test
    fun passwords_wiped_after_create() = runTest {
        val e = env()
        e.vm.ready()
        val pw = "good-pass".toCharArray()
        val confirm = "good-pass".toCharArray()
        e.vm.onCreate(pw, confirm)
        advanceUntilIdle()
        assertTrue(pw.all { it == '\u0000' })
        assertTrue(confirm.all { it == '\u0000' })
        assertFalse(e.vm.uiState.value.toString().contains("good-pass"))
    }

    @Test
    fun invalid_request_does_not_start() = runTest {
        val e = env()
        e.vm.ready()
        e.vm.onCreate("a".toCharArray(), "b".toCharArray())
        advanceUntilIdle()
        assertNull(e.native.options)
    }

    @Test
    fun strength_levels() {
        assertEquals(Strength.WEAK, PasswordStrength.estimate("password".toCharArray()))
        assertEquals(Strength.FAIR, PasswordStrength.estimate("Password12".toCharArray()))
        assertEquals(Strength.GOOD, PasswordStrength.estimate("Tr0ub4dor&3".toCharArray()))
        assertEquals(Strength.STRONG, PasswordStrength.estimate("correct horse battery staple 42!".toCharArray()))
        assertEquals(Strength.WEAK, PasswordStrength.estimate(CharArray(0)))
    }
}
