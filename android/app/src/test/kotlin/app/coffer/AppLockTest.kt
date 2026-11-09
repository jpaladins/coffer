// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.security.AppLock
import app.coffer.security.AppLockConfig
import app.coffer.security.AppLockMode
import app.coffer.security.MAX_PIN
import app.coffer.security.AppLockStore
import app.coffer.security.PinResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemLockStore : AppLockStore {
    var config = AppLockConfig()
    override suspend fun load() = config
    override suspend fun save(config: AppLockConfig) { this.config = config }
}

class AppLockTest {
    private var now = 1_000_000L
    private val store = MemLockStore()
    private val lock = AppLock(store) { now }

    private suspend fun withPin(pin: String = "123456") {
        lock.setPin(pin.toCharArray(), AppLockMode.PIN)
    }

    @Test
    fun off_never_locks() = runTest {
        lock.onAppStart()
        assertFalse(lock.isLocked.value)
        lock.onBackground()
        now += 3_600_000
        lock.onForeground(timeoutMs = 0)
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun locks_on_start_and_after_timeout() = runTest {
        withPin()
        lock.onAppStart()
        assertTrue(lock.isLocked.value)
        assertEquals(PinResult.Ok, lock.submitPin("123456".toCharArray()))
        assertFalse(lock.isLocked.value)
        lock.onBackground()
        now += 10_000
        lock.onForeground(timeoutMs = 30_000)
        assertFalse(lock.isLocked.value) // back within the timeout
        lock.onBackground()
        now += 30_000
        lock.onForeground(timeoutMs = 30_000)
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun pin_backoff_after_five_failures() = runTest {
        withPin()
        lock.onAppStart()
        repeat(4) { assertTrue(lock.submitPin("000000".toCharArray()) is PinResult.Wrong) }
        val fifth = lock.submitPin("000000".toCharArray())
        assertEquals(PinResult.LockedOut(now + 30_000), fifth)
        // Even the right PIN is refused while locked out.
        assertTrue(lock.submitPin("123456".toCharArray()) is PinResult.LockedOut)
        now += 30_001
        assertTrue(lock.submitPin("000000".toCharArray()) is PinResult.LockedOut) // 6th: 60 s
        assertEquals(now + 60_000, (store.config.lockedUntil))
        now += 60_001
        assertEquals(PinResult.Ok, lock.submitPin("123456".toCharArray()))
        assertEquals(0, store.config.failures)
    }

    @Test
    fun failures_survive_a_restart() = runTest {
        withPin()
        lock.onAppStart()
        repeat(5) { lock.submitPin("000000".toCharArray()) }
        val restarted = AppLock(store) { now }
        restarted.onAppStart()
        assertTrue(restarted.submitPin("123456".toCharArray()) is PinResult.LockedOut)
    }

    @Test
    fun pin_never_stored_in_clear() = runTest {
        val pin = "482916".toCharArray()
        lock.setPin(pin, AppLockMode.PIN)
        assertTrue(pin.all { it == '\u0000' })
        val rec = store.config.pin!!
        assertEquals(16, rec.salt.size)
        assertEquals(32, rec.hash.size)
        assertFalse(String(rec.hash, Charsets.ISO_8859_1).contains("482916"))
        assertFalse(store.config.toString().contains("482916"))
    }

    @Test
    fun biometric_unlock_and_disable() = runTest {
        lock.setPin("1234".toCharArray(), AppLockMode.PIN_OR_BIOMETRIC)
        lock.onAppStart()
        assertTrue(lock.isLocked.value)
        lock.onBiometricSuccess()
        assertFalse(lock.isLocked.value)
        lock.disable()
        assertEquals(AppLockMode.OFF, store.config.mode)
        assertEquals(null, store.config.pin)
    }

    @Test
    fun short_pin_is_rejected() = runTest {
        assertFalse(lock.setPin("12".toCharArray(), AppLockMode.PIN))
        assertEquals(AppLockMode.OFF, store.config.mode)
    }

    @Test
    fun pin_must_be_digits_and_at_most_max() = runTest {
        assertFalse(lock.setPin("12a4".toCharArray(), AppLockMode.PIN))
        assertFalse(lock.setPin("1".repeat(MAX_PIN + 1).toCharArray(), AppLockMode.PIN))
        assertEquals(AppLockMode.OFF, store.config.mode)
        assert(lock.setPin("1".repeat(MAX_PIN).toCharArray(), AppLockMode.PIN))
    }

    /** Review P5 #2: only a Coffer-launched picker exempts the app lock, not a transfer. */
    @Test
    fun transfer_hold_does_not_exempt_app_lock() {
        val guard = app.coffer.security.LockGuard()
        guard.acquire() // import or creation running
        assertFalse(guard.inPicker)
        assertEquals(1, guard.held.value)
        guard.acquirePicker()
        assertTrue(guard.inPicker)
        assertEquals(2, guard.held.value)
        guard.releasePicker()
        guard.releasePicker() // extra release is harmless
        assertFalse(guard.inPicker)
        assertEquals(1, guard.held.value)
    }

    /** Review P5 #3: nothing is drawn before the lock state is known. */
    @Test
    fun content_hidden_until_config_loaded() = runTest {
        withPin()
        val fresh = AppLock(store) { now }
        assertFalse(fresh.ready.value)
        fresh.onAppStart()
        assertTrue(fresh.ready.value)
        assertTrue(fresh.isLocked.value)
    }

    /** Review P5 #3: leaving the app covers the content at once, before the async timeout check. */
    @Test
    fun content_covered_while_in_background() = runTest {
        withPin()
        lock.onAppStart()
        lock.submitPin("123456".toCharArray())
        lock.onBackground()
        assertTrue(lock.covered.value) // synchronous, no DataStore read needed
        now += 5_000
        lock.onForeground(timeoutMs = 30_000)
        assertFalse(lock.covered.value)
        assertFalse(lock.isLocked.value)
        lock.disable()
        lock.onBackground()
        assertFalse(lock.covered.value) // app lock off: nothing to hide
    }
}
