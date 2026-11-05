// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import app.coffer.ffi.FfiCancel
import app.coffer.ffi.FfiCredentials
import app.coffer.ffi.FfiException
import app.coffer.ffi.FfiVault
import app.coffer.ffi.openFd

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
        val ffiCreds = FfiCredentials(
            password = creds.password,
            pim = creds.pim.toUInt(),
            keyfiles = creds.keyfiles,
            prf = creds.prf,
            maxKdfMemory = creds.maxKdfMemory.toULong(),
            tryBackupHeaders = creds.tryBackupHeaders,
        )
        val vault = native { openFd(fd, false, ffiCreds, (cancel as Cancel).ffi) }
        return FfiHandle(vault)
    }
}

private inline fun <T> native(block: () -> T): T =
    try {
        block()
    } catch (e: FfiException) {
        throw e.toCoffer()
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

    override fun lock() {
        vault.lock()
        vault.close()
    }
}
