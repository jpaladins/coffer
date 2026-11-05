// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.data.CofferException
import app.coffer.data.SessionManager
import app.coffer.data.VaultRef
import app.coffer.unlock.UnlockEffect
import app.coffer.unlock.UnlockPhase
import app.coffer.unlock.UnlockViewModel
import app.coffer.vaults.VaultsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultsAndUnlockViewModelTest {
    private val ref = VaultRef("v1", "test.hc", "content://p/test.hc")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun sessions(native: FakeNative, store: FakeStore, io: kotlinx.coroutines.CoroutineDispatcher) =
        SessionManager(store, native, FakeOpener(), io, { 1L shl 30 })

    @Test
    fun add_persists_permission_and_lists_vault() = runTest {
        val store = FakeStore()
        val docs = FakeDocuments()
        val vm = VaultsViewModel(store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)), docs)
        vm.onDocumentPicked("content://p/new.hc")
        advanceUntilIdle()
        assertEquals(listOf("content://p/new.hc"), docs.persisted)
        assertEquals(listOf("new.hc"), vm.uiState.first { it.vaults.isNotEmpty() }.vaults.map { it.label })
    }

    @Test
    fun remove_closes_session_and_forgets_vault() = runTest {
        val store = FakeStore(ref)
        val sm = sessions(FakeNative(), store, StandardTestDispatcher(testScheduler))
        sm.unlock(ref, app.coffer.data.UnlockRequest("secret".toCharArray(), 0, emptyList(), null), sm.newCancel())
        val vm = VaultsViewModel(store, sm, FakeDocuments())
        vm.onRemove("v1")
        advanceUntilIdle()
        assertTrue(store.state.value.isEmpty())
        assertTrue(sm.openIds.value.isEmpty())
    }

    @Test
    fun unlock_success_emits_navigate() = runTest {
        val store = FakeStore(ref)
        val vm = UnlockViewModel("v1", store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onUnlock("secret".toCharArray())
        advanceUntilIdle()
        assertEquals(UnlockEffect.NavigateToBrowser("v1"), vm.effects.first())
        assertEquals(UnlockPhase.Idle, vm.uiState.value.phase)
    }

    @Test
    fun wrong_password_shows_error_and_offers_backup_once() = runTest {
        val store = FakeStore(ref)
        val native = FakeNative()
        val vm = UnlockViewModel("v1", store, sessions(native, store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onUnlock("wrong".toCharArray())
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue((s.phase as UnlockPhase.Error).error is CofferException.WrongCredentials)
        assertTrue(s.offerBackupHeaders)
        vm.onUnlock("wrong".toCharArray(), tryBackupHeaders = true)
        advanceUntilIdle()
        assertTrue(native.lastCreds!!.tryBackupHeaders)
        assertFalse(vm.uiState.value.offerBackupHeaders)
    }

    @Test
    fun pim_and_kdf_are_passed() = runTest {
        val store = FakeStore(ref)
        val native = FakeNative()
        val vm = UnlockViewModel("v1", store, sessions(native, store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onPimChange("485")
        vm.onPimChange("48a") // ignored: digits only
        vm.onPrfChange("Whirlpool")
        vm.onUnlock("secret".toCharArray())
        advanceUntilIdle()
        assertEquals(485, native.lastCreds!!.pim)
        assertEquals("Whirlpool", native.lastCreds!!.prf)
    }

    @Test
    fun cancel_returns_to_idle_within_1s() = runBlocking {
        val store = FakeStore(ref)
        val native = FakeNative().apply { blockUntilCancel = true }
        val vm = UnlockViewModel("v1", store, sessions(native, store, Dispatchers.IO))
        vm.uiState.first { it.label.isNotEmpty() }
        vm.onUnlock("secret".toCharArray())
        vm.uiState.first { it.phase == UnlockPhase.Unlocking }
        val t = System.nanoTime()
        vm.onCancel()
        withTimeout(1000) { vm.uiState.first { it.phase == UnlockPhase.Idle } }
        assertTrue(System.nanoTime() - t < 1_000_000_000)
    }

    @Test
    fun ui_state_never_contains_password() = runTest {
        val store = FakeStore(ref)
        val vm = UnlockViewModel("v1", store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        val pw = "hunter2-secret".toCharArray()
        vm.onUnlock(pw)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.toString().contains("hunter2"))
        assertTrue(pw.all { it == '\u0000' })
    }
}
