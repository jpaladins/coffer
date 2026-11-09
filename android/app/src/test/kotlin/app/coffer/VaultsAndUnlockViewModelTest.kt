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
        val vm = VaultsViewModel(store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)), docs, app.coffer.settings.UiPrefs(MemoryPrefs()))
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
        val vm = VaultsViewModel(store, sm, FakeDocuments(), app.coffer.settings.UiPrefs(MemoryPrefs()))
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
    fun unlock_success_records_last_access() = runTest {
        val store = FakeStore(ref)
        val prefs = app.coffer.settings.UiPrefs(MemoryPrefs())
        val vm = UnlockViewModel(
            "v1", store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)),
            uiPrefs = prefs,
        )
        advanceUntilIdle()
        vm.onUnlock("secret".toCharArray())
        advanceUntilIdle()
        assertTrue(prefs.lastAccess.first().containsKey("v1"))
    }

    @Test
    fun open_item_has_free_space_and_fs() = runTest {
        val store = FakeStore(ref)
        val sm = sessions(FakeNative(), store, StandardTestDispatcher(testScheduler))
        sm.unlock(ref, app.coffer.data.UnlockRequest("secret".toCharArray(), 0, emptyList(), null), sm.newCancel())
        val docs = FakeDocuments().apply { sizes["content://p/test.hc"] = 4096L }
        val vm = VaultsViewModel(store, sm, docs, app.coffer.settings.UiPrefs(MemoryPrefs()), StandardTestDispatcher(testScheduler))
        val item = vm.uiState.first { s -> s.vaults.any { it.isOpen } }.vaults.single()
        assertEquals("exFAT", item.fsKind)
        assertEquals(512L, item.freeBytes)
        assertEquals((1L shl 20), item.volumeBytes)
        assertEquals(4096L, item.fileBytes)
    }

    @Test
    fun closed_item_has_last_access_from_prefs() = runTest {
        val store = FakeStore(ref)
        val prefs = app.coffer.settings.UiPrefs(MemoryPrefs())
        prefs.recordAccess("v1", 1234L)
        val vm = VaultsViewModel(
            store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)), FakeDocuments(), prefs,
            StandardTestDispatcher(testScheduler),
        )
        val item = vm.uiState.first { it.vaults.isNotEmpty() }.vaults.single()
        assertFalse(item.isOpen)
        assertEquals(1234L, item.lastAccess)
        assertEquals(null, item.freeBytes)
    }

    @Test
    fun remove_forgets_last_access() = runTest {
        val store = FakeStore(ref)
        val prefs = app.coffer.settings.UiPrefs(MemoryPrefs())
        prefs.recordAccess("v1", 5L)
        val vm = VaultsViewModel(store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)), FakeDocuments(), prefs)
        vm.onRemove("v1")
        advanceUntilIdle()
        assertTrue(prefs.lastAccess.first().isEmpty())
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

    @Test
    fun default_unlock_is_writable_without_hidden() = runTest {
        val store = FakeStore(ref)
        val native = FakeNative()
        val vm = UnlockViewModel("v1", store, sessions(native, store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onUnlock("secret".toCharArray(), hiddenPassword = "ignored".toCharArray())
        advanceUntilIdle()
        assertTrue(native.lastCreds!!.writable)
        assertEquals(null, native.lastCreds!!.hiddenPassword)
    }

    @Test
    fun read_only_switch_opens_read_only_and_disables_hidden_protection() = runTest {
        val store = FakeStore(ref)
        val native = FakeNative()
        val vm = UnlockViewModel("v1", store, sessions(native, store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onProtectHiddenChange(true)
        vm.onReadOnlyChange(true)
        assertFalse(vm.uiState.value.protectHidden)
        vm.onProtectHiddenChange(true)
        assertFalse(vm.uiState.value.protectHidden)
        vm.onUnlock("secret".toCharArray())
        advanceUntilIdle()
        assertFalse(native.lastCreds!!.writable)
    }

    @Test
    fun hidden_protection_passes_and_wipes_hidden_password() = runTest {
        val store = FakeStore(ref)
        val native = FakeNative()
        val vm = UnlockViewModel("v1", store, sessions(native, store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onProtectHiddenChange(true)
        val hidden = "hidden-pw".toCharArray()
        vm.onUnlock("secret".toCharArray(), hiddenPassword = hidden)
        advanceUntilIdle()
        val c = native.lastCreds!!
        assertTrue(c.writable)
        assertTrue(c.hiddenPassword!!.all { it == 0.toByte() })
        assertTrue(hidden.all { it == '\u0000' })
        assertFalse(vm.uiState.value.toString().contains("hidden-pw"))
    }

    @Test
    fun wrong_password_with_hidden_protection_is_flagged() = runTest {
        val store = FakeStore(ref)
        val vm = UnlockViewModel("v1", store, sessions(FakeNative(), store, StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.onProtectHiddenChange(true)
        vm.onUnlock("wrong".toCharArray(), hiddenPassword = "h".toCharArray())
        advanceUntilIdle()
        val phase = vm.uiState.value.phase
        assertTrue(phase is UnlockPhase.Error && phase.hiddenRequested)
    }
}
