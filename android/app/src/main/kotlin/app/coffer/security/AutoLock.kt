// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Postpones auto-lock while the user is in a system picker launched by
 * Coffer, or while a transfer runs: locking then would break the operation
 * (and leave a partial file in the volume).
 */
class LockGuard {
    private val count = AtomicInteger()
    private val _held = MutableStateFlow(0)
    val held: StateFlow<Int> = _held.asStateFlow()

    fun acquire() {
        _held.value = count.incrementAndGet()
    }

    /** Extra releases are ignored, so a picker callback may release twice safely. */
    fun release() {
        _held.value = count.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    private val pickers = AtomicInteger()

    /**
     * True while a system picker launched by Coffer is in front: leaving
     * for it is not leaving the app, so the app lock does not arm. A
     * transfer or creation ([acquire]) only postpones auto-lock: the app
     * lock must still ask for the PIN when the user comes back.
     */
    val inPicker: Boolean get() = pickers.get() > 0

    fun acquirePicker() {
        pickers.incrementAndGet()
        acquire()
    }

    fun releasePicker() {
        var released = false
        pickers.updateAndGet { if (it > 0) { released = true; it - 1 } else 0 }
        if (released) release()
    }
}

/**
 * Locks every vault after the app has stayed in the background for the
 * configured time (0 = as soon as it leaves the foreground). While [held]
 * is above zero the timer does not run; it starts when the last hold ends.
 */
class AutoLock(
    private val scope: CoroutineScope,
    private val timeoutMs: suspend () -> Long,
    private val held: StateFlow<Int> = MutableStateFlow(0),
    private val lockAll: () -> Unit,
) {
    private var pending: Job? = null
    private var background = false

    init {
        scope.launch {
            held.collect { n ->
                if (n > 0) disarm() else if (background) arm()
            }
        }
    }

    fun onBackground() {
        background = true
        if (held.value == 0) arm()
    }

    fun onForeground() {
        background = false
        disarm()
    }

    private fun arm() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            val t = timeoutMs()
            if (t > 0) delay(t)
            lockAll()
        }
    }

    private fun disarm() {
        pending?.cancel()
        pending = null
    }
}
