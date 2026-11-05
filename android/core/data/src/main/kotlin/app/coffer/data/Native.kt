// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import app.coffer.ffi.FfiCancel
import app.coffer.ffi.FfiOpenOptions
import app.coffer.ffi.FfiException
import app.coffer.ffi.FfiVault
import app.coffer.ffi.openFdSecret
import com.sun.jna.Memory
import com.sun.jna.Pointer

data class Entry(val name: String, val isDir: Boolean, val size: Long, val modified: Long?)

data class VolumeInfo(
    val cipher: String,
    val prf: String,
    val hidden: Boolean,
    val usedBackupHeader: Boolean,
    val fsKind: String,
    val size: Long,
)

/** Secrets are zeroed by the caller right after [NativeVaults.open] returns. */
class NativeCredentials(
    val password: ByteArray,
    val pim: Int,
    val keyfiles: List<ByteArray>,
    val prf: String?,
    val maxKdfMemory: Long,
    val tryBackupHeaders: Boolean,
)

interface CancelSignal {
    fun cancel()
}

/** An unlocked volume. Every call may throw [CofferException]. */
interface VaultHandle {
    val info: VolumeInfo
    fun list(path: String): List<Entry>
    fun stat(path: String): Entry
    fun read(path: String, offset: Long, len: Int): ByteArray
    fun lock()
}

/** Seam over the Rust core, so the data layer is testable on the JVM. */
interface NativeVaults {
    fun newCancel(): CancelSignal
    /** Takes ownership of [fd]. */
    fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle
}

class FfiNativeVaults : NativeVaults {
    private class Cancel(val ffi: FfiCancel) : CancelSignal {
        override fun cancel() = ffi.cancel()
    }

    override fun newCancel(): CancelSignal = Cancel(FfiCancel())

    override fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle {
        val options = FfiOpenOptions(
            pim = creds.pim.toUInt(),
            prf = creds.prf,
            maxKdfMemory = creds.maxKdfMemory.toULong(),
            tryBackupHeaders = creds.tryBackupHeaders,
        )
        // Secrets go through native memory we own and wipe, never through a
        // UniFFI record (its buffers are freed without being zeroed).
        val secret = secretBlob(creds.password, creds.keyfiles)
        try {
            val vault = native {
                openFdSecret(fd, false, Pointer.nativeValue(secret).toULong(), secret.size().toULong(), options, (cancel as Cancel).ffi)
            }
            return FfiHandle(vault)
        } finally {
            secret.clear()
            secret.close()
        }
    }
}

/** `[u32 len][password][u32 count]([u32 len][keyfile])*`, little endian (Android is LE). */
private fun secretBlob(password: ByteArray, keyfiles: List<ByteArray>): Memory {
    val size = 4L + password.size + 4 + keyfiles.sumOf { 4L + it.size }
    val m = Memory(size)
    var off = 0L
    fun putBytes(b: ByteArray) {
        m.setInt(off, b.size)
        off += 4
        m.write(off, b, 0, b.size)
        off += b.size
    }
    putBytes(password)
    m.setInt(off, keyfiles.size)
    off += 4
    keyfiles.forEach(::putBytes)
    return m
}

/**
 * Native errors to [CofferException]. A UniFFI object used after it was
 * destroyed throws IllegalStateException: that can only mean "locked".
 */
internal fun mapNativeError(t: Throwable): CofferException = when (t) {
    is CofferException -> t
    is FfiException -> t.toCoffer()
    is IllegalStateException -> CofferException.Closed()
    else -> CofferException.Io(t.message ?: t.toString())
}

private inline fun <T> native(block: () -> T): T =
    try {
        block()
    } catch (e: FfiException) {
        throw mapNativeError(e)
    } catch (e: IllegalStateException) {
        throw mapNativeError(e)
    }

private class FfiHandle(private val vault: FfiVault) : VaultHandle {
    override val info: VolumeInfo = vault.info().let {
        VolumeInfo(it.cipher, it.prf, it.hidden, it.usedBackupHeader, it.fsKind, it.size.toLong())
    }

    override fun list(path: String) = native { vault.list(path) }.map {
        Entry(it.name, it.isDir, it.size.toLong(), it.modified)
    }

    override fun stat(path: String) = native { vault.stat(path) }.let {
        Entry(it.name, it.isDir, it.size.toLong(), it.modified)
    }

    override fun read(path: String, offset: Long, len: Int): ByteArray =
        native { vault.read(path, offset.toULong(), len.toUInt()) }

    /**
     * Drops keys and filesystem in Rust; later calls fail with Closed. The
     * Kotlin wrapper is not destroyed here because readers on other threads
     * (export, proxy fds) may still hold it; the Cleaner frees it.
     */
    override fun lock() = vault.lock()
}
