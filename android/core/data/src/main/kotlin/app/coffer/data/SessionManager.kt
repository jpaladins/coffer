// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import java.io.FileNotFoundException
import java.nio.CharBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Opens SAF documents for the native layer. */
interface FdOpener {
    /** Returns a detached, read-only file descriptor the caller now owns. */
    fun openReadFd(uri: String): Int
    /** First [maxBytes] bytes of a document (keyfiles). */
    fun readPrefix(uri: String, maxBytes: Int): ByteArray
}

/** [password] is wiped by [SessionManager.unlock]; callers must not reuse it. */
class UnlockRequest(
    val password: CharArray,
    val pim: Int,
    val keyfileUris: List<String>,
    val prf: String?,
    val tryBackupHeaders: Boolean = false,
)

/**
 * [token] is random per unlock: share URIs ("Open with") use it instead of
 * the stable vault id, so a grant cannot be replayed after a later unlock.
 */
data class VaultSession(
    val ref: VaultRef,
    val handle: VaultHandle,
    val token: String = UUID.randomUUID().toString(),
)

/** VeraCrypt processes only the first 1 MiB of each keyfile. */
private const val KEYFILE_MAX = 1 shl 20

internal fun CharArray.toUtf8Bytes(): ByteArray {
    val buf = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    val out = ByteArray(buf.remaining())
    buf.get(out)
    if (buf.hasArray()) buf.array().fill(0)
    return out
}

class SessionManager(
    private val store: VaultStore,
    private val native: NativeVaults,
    private val opener: FdOpener,
    private val io: CoroutineDispatcher,
    private val maxKdfMemory: () -> Long,
) {
    private val lock = Any()
    private val sessions = HashMap<String, VaultSession>()
    private val _openIds = MutableStateFlow<Set<String>>(emptySet())
    val openIds: StateFlow<Set<String>> = _openIds.asStateFlow()

    /** Bumped by [closeAll]; unlocks started in an older epoch are discarded. */
    private var epoch = 0L
    private val inFlight = ConcurrentHashMap.newKeySet<CancelSignal>()
    private val closeListeners = CopyOnWriteArrayList<(VaultSession) -> Unit>()

    fun newCancel(): CancelSignal = native.newCancel()

    fun session(id: String): VaultSession? = synchronized(lock) { sessions[id] }

    fun sessionByToken(token: String): VaultSession? =
        synchronized(lock) { sessions.values.firstOrNull { it.token == token } }

    /** Called after a session is locked (e.g. to revoke share grants). */
    fun addCloseListener(listener: (VaultSession) -> Unit) {
        closeListeners += listener
    }

    /** @throws CofferException */
    suspend fun unlock(ref: VaultRef, req: UnlockRequest, cancel: CancelSignal): VaultSession =
        withContext(io) {
            val startEpoch = synchronized(lock) { epoch }
            inFlight += cancel
            val password = req.password.toUtf8Bytes()
            req.password.fill('\u0000')
            val keyfiles = mutableListOf<ByteArray>()
            try {
                req.keyfileUris.forEach { keyfiles += platform { opener.readPrefix(it, KEYFILE_MAX) } }
                val fd = platform { opener.openReadFd(ref.uri) }
                val creds = NativeCredentials(
                    password, req.pim, keyfiles, req.prf ?: ref.lastPrf, maxKdfMemory(), req.tryBackupHeaders,
                )
                val handle = try {
                    native.open(fd, creds, cancel)
                } catch (e: CofferException.WrongCredentials) {
                    // A remembered KDF may be stale (volume re-keyed): retry with detection.
                    if (req.prf == null && ref.lastPrf != null) {
                        native.open(platform { opener.openReadFd(ref.uri) }, NativeCredentials(
                            password, req.pim, keyfiles, null, maxKdfMemory(), req.tryBackupHeaders,
                        ), cancel)
                    } else {
                        throw e
                    }
                }
                val session = VaultSession(ref, handle)
                val replaced = synchronized(lock) {
                    if (epoch != startEpoch) {
                        // closeAll() (auto-lock) ran while the KDF was busy.
                        handle.lock()
                        throw CofferException.Cancelled()
                    }
                    sessions.put(ref.id, session).also { _openIds.value = sessions.keys.toSet() }
                }
                replaced?.let(::locked)
                runCatching { store.rememberPrf(ref.id, handle.info.prf) }
                session
            } finally {
                inFlight -= cancel
                password.fill(0)
                keyfiles.forEach { it.fill(0) }
            }
        }

    private fun locked(s: VaultSession) {
        s.handle.lock()
        closeListeners.forEach { runCatching { it(s) } }
    }

    fun close(id: String) {
        val s = synchronized(lock) {
            sessions.remove(id).also { _openIds.value = sessions.keys.toSet() }
        }
        s?.let(::locked)
    }

    /** Locks everything and aborts unlocks still running (auto-lock). */
    fun closeAll() {
        val all = synchronized(lock) {
            epoch++
            sessions.values.toList().also {
                sessions.clear()
                _openIds.value = emptySet()
            }
        }
        inFlight.forEach { it.cancel() }
        all.forEach(::locked)
    }

    private inline fun <T> platform(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw CofferException.PermissionLost(e.message ?: "permission revoked")
        } catch (e: FileNotFoundException) {
            throw CofferException.PermissionLost(e.message ?: "file not found")
        } catch (e: IllegalArgumentException) {
            throw CofferException.PermissionLost(e.message ?: "invalid document")
        }
}
