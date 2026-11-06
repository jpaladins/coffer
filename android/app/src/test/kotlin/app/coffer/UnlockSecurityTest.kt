// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.data.SessionManager
import app.coffer.data.VaultHandle
import app.coffer.data.VaultRef
import app.coffer.data.VolumeInfo
import app.coffer.security.KeyInvalidatedException
import app.coffer.security.RememberedPasswords
import app.coffer.unlock.UnlockEffect
import app.coffer.unlock.UnlockPhase
import app.coffer.unlock.UnlockViewModel
import javax.crypto.Cipher
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

/** In-memory "Keystore": the cipher argument is ignored. */
class FakeRemembered(var invalidated: Boolean = false) : RememberedPasswords {
    val saved = mutableMapOf<String, String>()
    val forgotten = mutableListOf<String>()
    override fun isAvailable() = true
    override suspend fun has(vaultId: String) = vaultId in saved
    override fun encrypter(vaultId: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    override suspend fun decrypter(vaultId: String): Cipher? {
        if (invalidated) throw KeyInvalidatedException()
        return if (vaultId in saved) Cipher.getInstance("AES/GCM/NoPadding") else null
    }
    override suspend fun save(vaultId: String, cipher: Cipher, password: CharArray) { saved[vaultId] = String(password) }
    override suspend fun recall(vaultId: String, cipher: Cipher): CharArray = saved.getValue(vaultId).toCharArray()
    override suspend fun forget(vaultId: String) { saved.remove(vaultId); forgotten += vaultId }
}

class DirtyHandle(private val base: FakeHandle = FakeHandle()) : VaultHandle by base {
    var readOnly = false
    override val info: VolumeInfo
        get() = VolumeInfo("AES", "SHA-512", false, false, "exFAT", 1, writable = !readOnly, wasDirty = true)
    override fun makeReadOnly() { readOnly = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockSecurityTest {
    private val ref = VaultRef("v1", "test.hc", "content://p/test.hc")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(handle: VaultHandle = FakeHandle(), remembered: RememberedPasswords = FakeRemembered()): UnlockViewModel {
        val store = FakeStore(ref)
        val sm = SessionManager(store, FakeNative(handle = { handle }), FakeOpener(), StandardTestDispatcher(testScheduler), { 1L shl 30 })
        return UnlockViewModel("v1", store, sm, remembered)
    }

    @Test
    fun dirty_volume_offers_read_only() = runTest {
        val h = DirtyHandle()
        val vm = vm(h)
        advanceUntilIdle()
        vm.onUnlock("secret".toCharArray())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.dirtyPrompt)
        vm.onDirtyReadOnly()
        advanceUntilIdle()
        assertTrue(h.readOnly)
        assertFalse(vm.uiState.value.dirtyPrompt)
        assertEquals(UnlockEffect.NavigateToBrowser("v1"), vm.effects.first())
    }

    @Test
    fun dirty_volume_can_continue_writable() = runTest {
        val h = DirtyHandle()
        val vm = vm(h)
        advanceUntilIdle()
        vm.onUnlock("secret".toCharArray())
        advanceUntilIdle()
        vm.onDirtyContinue()
        assertFalse(h.readOnly)
        assertEquals(UnlockEffect.NavigateToBrowser("v1"), vm.effects.first())
    }

    @Test
    fun remember_saves_only_after_a_successful_unlock() = runTest {
        val r = FakeRemembered()
        val vm = vm(remembered = r)
        advanceUntilIdle()
        vm.onUnlockRemembering("wrong".toCharArray(), r.encrypter("v1"))
        advanceUntilIdle()
        assertTrue(r.saved.isEmpty())
        vm.onUnlockRemembering("secret".toCharArray(), r.encrypter("v1"))
        advanceUntilIdle()
        assertEquals("secret", r.saved["v1"])
    }

    @Test
    fun biometric_unlock_uses_remembered_password() = runTest {
        val r = FakeRemembered().apply { saved["v1"] = "secret" }
        val vm = vm(remembered = r)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasRemembered)
        val c = vm.decryptCipher()!!
        vm.onBiometricAuthenticated(c)
        advanceUntilIdle()
        assertEquals(UnlockEffect.NavigateToBrowser("v1"), vm.effects.first())
    }

    @Test
    fun stale_remembered_password_is_forgotten() = runTest {
        val r = FakeRemembered().apply { saved["v1"] = "old-password" }
        val vm = vm(remembered = r)
        advanceUntilIdle()
        vm.onBiometricAuthenticated(vm.decryptCipher()!!)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.phase is UnlockPhase.Error)
        assertEquals(listOf("v1"), r.forgotten)
        assertFalse(vm.uiState.value.hasRemembered)
    }

    @Test
    fun invalidated_key_forgets_and_falls_back() = runTest {
        val r = FakeRemembered(invalidated = true).apply { saved["v1"] = "secret" }
        val vm = vm(remembered = r)
        advanceUntilIdle()
        assertNull(vm.decryptCipher())
        advanceUntilIdle()
        assertEquals(listOf("v1"), r.forgotten)
        assertFalse(vm.uiState.value.hasRemembered)
        assertTrue(vm.uiState.value.biometricInvalidated)
    }

    /** Review P5: any Keystore failure falls back to the password instead of crashing. */
    @Test
    fun keystore_error_falls_back_without_crash() = runTest {
        val base = FakeRemembered()
        base.saved["v1"] = "secret"
        val r = object : RememberedPasswords by base {
            override suspend fun decrypter(vaultId: String): Cipher? = throw IllegalStateException("keystore unavailable")
        }
        val vm = vm(remembered = r)
        advanceUntilIdle()
        assertNull(vm.decryptCipher())
        assertTrue(vm.uiState.value.biometricInvalidated)
        assertFalse(vm.uiState.value.hasRemembered)
    }
}
