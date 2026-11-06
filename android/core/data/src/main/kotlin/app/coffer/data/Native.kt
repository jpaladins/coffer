// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import app.coffer.ffi.FfiCancel
import app.coffer.ffi.FfiCreateOptions
import app.coffer.ffi.FfiProgress
import app.coffer.ffi.createFdSecret
import app.coffer.ffi.listCiphers
import app.coffer.ffi.listPrfs
import app.coffer.ffi.FfiOpenOptions
import app.coffer.ffi.FfiException
import app.coffer.ffi.FfiVault
import app.coffer.ffi.openFdSecret
import app.coffer.ffi.setLocalOffsetMinutes
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.TimeZone

data class Entry(val name: String, val isDir: Boolean, val size: Long, val modified: Long?)

data class VolumeInfo(
    val cipher: String,
    val prf: String,
    val hidden: Boolean,
    val usedBackupHeader: Boolean,
    val fsKind: String,
    val size: Long,
    val writable: Boolean = false,
    val hiddenProtected: Boolean = false,
)

/** Secrets are zeroed by the caller right after [NativeVaults.open] returns. */
class NativeCredentials(
    val password: ByteArray,
    val pim: Int,
    val keyfiles: List<ByteArray>,
    val prf: String?,
    val maxKdfMemory: Long,
    val tryBackupHeaders: Boolean,
    /** Mount read-write; [fd] must then be open for writing. */
    val writable: Boolean = false,
    /** Password of the hidden volume to protect while writing to the outer one. */
    val hiddenPassword: ByteArray? = null,
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

    // Writes fail with ReadOnly unless the volume was opened writable.
    fun createFile(path: String): Unit = throw CofferException.ReadOnly()
    fun mkdir(path: String): Unit = throw CofferException.ReadOnly()
    /** At most [MAX_IO] bytes per call; grows the file as needed. */
    fun write(path: String, offset: Long, data: ByteArray): Unit = throw CofferException.ReadOnly()
    fun setLen(path: String, len: Long): Unit = throw CofferException.ReadOnly()
    /** Removes a file or an empty directory. */
    fun remove(path: String): Unit = throw CofferException.ReadOnly()
    fun rename(from: String, to: String): Unit = throw CofferException.ReadOnly()
    fun sync() {}
}

/** Largest read/write the native layer accepts in one call. */
const val MAX_IO = 4 shl 20

/** Seam over the Rust core, so the data layer is testable on the JVM. */
/** A new volume: [cipher]/[prf] are core names, [fs] is "exFAT" or "FAT". */
class CreateOptions(
    val size: Long,
    val cipher: String,
    val prf: String,
    val pim: Int,
    val fs: String,
    val quick: Boolean,
    val label: String,
    /** Optional user entropy (touch samples), mixed into the OS RNG. */
    val extraEntropy: ByteArray,
)

interface NativeVaults {
    fun newCancel(): CancelSignal
    /** Takes ownership of [fd]. */
    fun open(fd: Int, creds: NativeCredentials, cancel: CancelSignal): VaultHandle

    /**
     * Creates and formats a volume in the file behind [fd] (opened for
     * writing; takes ownership), resizing it to [CreateOptions.size].
     * [progress] runs on the calling thread.
     */
    fun create(
        fd: Int,
        creds: NativeCredentials,
        options: CreateOptions,
        progress: (Long, Long) -> Unit,
        cancel: CancelSignal,
    ): VaultHandle = throw CofferException.Unsupported("create")

    fun ciphers(): List<String> = listOf("AES")
    fun prfs(): List<String> = listOf("SHA-512")
}

class FfiNativeVaults : NativeVaults {
    private class Cancel(val ffi: FfiCancel) : CancelSignal {
        override fun cancel() = ffi.cancel()
    }

    override fun newCancel(): CancelSignal = Cancel(FfiCancel())

    override fun ciphers(): List<String> = listCiphers()

    override fun prfs(): List<String> = listPrfs()

    override fun create(
        fd: Int,
        creds: NativeCredentials,
        options: CreateOptions,
        progress: (Long, Long) -> Unit,
        cancel: CancelSignal,
    ): VaultHandle {
        val ffiOptions = FfiCreateOptions(
            size = options.size.toULong(),
            cipher = options.cipher,
            prf = options.prf,
            pim = options.pim.toUInt(),
            fs = options.fs,
            quick = options.quick,
            label = options.label,
            extraEntropy = options.extraEntropy,
        )
        val listener = object : FfiProgress {
            override fun onProgress(done: ULong, total: ULong) = progress(done.toLong(), total.toLong())
        }
        val secret = secretBlob(creds.password, creds.keyfiles)
        try {
            val vault = native {
                createFdSecret(
                    fd,
                    Pointer.nativeValue(secret).toULong(),
                    secret.size().toULong(),
                    ffiOptions,
                    listener,
                    (cancel as Cancel).ffi,
                )
            }
            return FfiHandle(vault)
        } finally {
            secret.clear()
            secret.close()
        }
    }

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
        val hidden = creds.hiddenPassword?.let { secretBlob(it, emptyList()) }
        try {
            val vault = native {
                openFdSecret(
                    fd,
                    creds.writable,
                    Pointer.nativeValue(secret).toULong(),
                    secret.size().toULong(),
                    hidden?.let { Pointer.nativeValue(it).toULong() } ?: 0UL,
                    hidden?.size()?.toULong() ?: 0UL,
                    options,
                    (cancel as Cancel).ffi,
                )
            }
            return FfiHandle(vault)
        } finally {
            for (m in listOfNotNull(secret, hidden)) {
                m.clear()
                m.close()
            }
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
        VolumeInfo(
            it.cipher, it.prf, it.hidden, it.usedBackupHeader, it.fsKind, it.size.toLong(),
            it.writable, it.hiddenProtected,
        )
    }

    override fun createFile(path: String) = native { clock(); vault.createFile(path) }
    override fun mkdir(path: String) = native { clock(); vault.mkdir(path) }
    override fun write(path: String, offset: Long, data: ByteArray) =
        native { clock(); vault.write(path, offset.toULong(), data) }
    override fun setLen(path: String, len: Long) = native { clock(); vault.setLen(path, len.toULong()) }
    override fun remove(path: String) = native { vault.remove(path) }
    override fun rename(from: String, to: String) = native { clock(); vault.rename(from, to) }

    /** FAT stores local time: keep the native offset current (time zone, DST). */
    private fun clock() =
        setLocalOffsetMinutes(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
    override fun sync() = native { vault.sync() }

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
