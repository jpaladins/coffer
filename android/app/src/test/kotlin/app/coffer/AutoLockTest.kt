// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.security.AutoLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockTest {
    @Test
    fun lock_after_timeout_closes_all() = runTest {
        var locks = 0
        val a = AutoLock(backgroundScope, timeoutMs = { 30_000 }) { locks++ }
        a.onBackground()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, locks)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, locks)
    }

    @Test
    fun return_before_timeout_keeps_sessions() = runTest {
        var locks = 0
        val a = AutoLock(backgroundScope, timeoutMs = { 30_000 }) { locks++ }
        a.onBackground()
        advanceTimeBy(10_000)
        a.onForeground()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, locks)
    }

    @Test
    fun immediate_option_closes_on_stop() = runTest {
        var locks = 0
        val a = AutoLock(backgroundScope, timeoutMs = { 0 }) { locks++ }
        a.onBackground()
        runCurrent()
        assertEquals(1, locks)
    }

    @Test
    fun repeated_background_does_not_double_lock_or_extend() = runTest {
        var locks = 0
        val a = AutoLock(backgroundScope, timeoutMs = { 1_000 }) { locks++ }
        a.onBackground()
        advanceTimeBy(600)
        a.onBackground() // e.g. a second stop event: must not restart the timer
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, locks)
    }
}
