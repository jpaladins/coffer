// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.transfer

import app.coffer.data.CofferException
import app.coffer.data.SessionManager
import app.coffer.data.childPath
import app.coffer.data.importStream
import app.coffer.data.sanitizeName
import app.coffer.data.uniqueName
import app.coffer.security.LockGuard
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Documents picked by the user to import (SAF). */
interface ImportSource {
    fun openInput(uri: String): InputStream
    fun displayName(uri: String): String?
}

/** [index] is 1-based. */
data class TransferProgress(
    val vaultId: String,
    val name: String,
    val index: Int,
    val total: Int,
    val bytes: Long = 0,
)

data class ImportResult(
    val vaultId: String,
    val dir: String,
    val imported: Int,
    val total: Int,
    val failures: List<Pair<String, CofferException>>,
    val cancelled: Boolean,
)

/**
 * Runs imports in an application scope, so they survive the screen that
 * started them. One transfer at a time; while it runs, [LockGuard] keeps
 * auto-lock away and a foreground service keeps the process alive.
 */
class TransferManager(
    private val sessions: SessionManager,
    private val source: ImportSource,
    private val guard: LockGuard,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    // Shared, not a Channel: every open browser sees the result for its vault.
    private val _results = MutableSharedFlow<ImportResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ImportResult> = _results.asSharedFlow()

    private val lock = Any()
    private var job: Job? = null

    /** Returns false when another transfer is still running. */
    fun import(vaultId: String, dir: String, uris: List<String>): Boolean = synchronized(lock) {
        if (job?.isActive == true || uris.isEmpty()) return false
        guard.acquire()
        _progress.value = TransferProgress(vaultId, "", 1, uris.size)
        job = scope.launch(io) {
            try {
                _results.tryEmit(run(vaultId, dir, uris))
            } finally {
                _progress.value = null
                guard.release()
            }
        }
        true
    }

    fun cancel() {
        synchronized(lock) { job }?.cancel()
    }

    /** Stops the running transfer and waits until its partial file is removed. */
    suspend fun cancelAndJoin() {
        synchronized(lock) { job }?.cancelAndJoin()
    }

    private suspend fun run(vaultId: String, dir: String, uris: List<String>): ImportResult {
        val ctx = currentCoroutineContext()
        var imported = 0
        var cancelled = false
        val failures = mutableListOf<Pair<String, CofferException>>()
        for ((i, uri) in uris.withIndex()) {
            val name = sanitizeName(runCatching { source.displayName(uri) }.getOrNull() ?: uri.substringAfterLast('/'))
            _progress.value = TransferProgress(vaultId, name, i + 1, uris.size)
            val handle = sessions.session(vaultId)?.handle
            if (handle == null) {
                failures += name to CofferException.Closed()
                break
            }
            try {
                val target = childPath(dir, handle.uniqueName(dir, name))
                source.openInput(uri).use { input ->
                    handle.importStream(target, input, isActive = { ctx.isActive }) { b ->
                        _progress.update { it?.copy(bytes = b) }
                    }
                }
                imported++
            } catch (_: CofferException.Cancelled) {
                cancelled = true
                break
            } catch (e: CofferException) {
                failures += name to e
                // Later files would fail the same way.
                if (e is CofferException.NoSpace || e is CofferException.HiddenVolumeProtected ||
                    e is CofferException.Closed || e is CofferException.ReadOnly
                ) {
                    break
                }
            } catch (e: IOException) {
                failures += name to CofferException.Io(e.message ?: "read failed")
            } catch (e: SecurityException) {
                failures += name to CofferException.PermissionLost(e.message ?: "permission denied")
            }
            if (!ctx.isActive) {
                cancelled = true
                break
            }
        }
        return ImportResult(vaultId, dir, imported, uris.size, failures, cancelled)
    }
}
