// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Locks every vault after the app has stayed in the background for the
 * configured time (0 = as soon as it leaves the foreground).
 */
class AutoLock(
    private val scope: CoroutineScope,
    private val timeoutMs: suspend () -> Long,
    private val lockAll: () -> Unit,
) {
    private var pending: Job? = null

    fun onBackground() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            val t = timeoutMs()
            if (t > 0) delay(t)
            lockAll()
        }
    }

    fun onForeground() {
        pending?.cancel()
        pending = null
    }
}
