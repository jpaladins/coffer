// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.data

import app.coffer.ffi.FfiException

/** Errors of the data layer; native and platform exceptions never leak past it. */
sealed class CofferException(message: String) : Exception(message) {
    class WrongCredentials : CofferException("wrong password, PIM or keyfiles")
    class Cancelled : CofferException("cancelled")
    class OutOfMemory(val requiredBytes: Long) : CofferException("KDF needs $requiredBytes bytes")
    class Corrupted(val detail: String) : CofferException(detail)
    class Unsupported(val detail: String) : CofferException(detail)
    class NotFound(val path: String) : CofferException(path)
    class NotADirectory(val path: String) : CofferException(path)
    class IsADirectory(val path: String) : CofferException(path)
    class InvalidPath(val path: String) : CofferException(path)
    class Io(val detail: String) : CofferException(detail)
    class Closed : CofferException("vault is locked")
    /** The SAF grant was revoked or the file moved/deleted. */
    class PermissionLost(val detail: String) : CofferException(detail)
    /** The write would touch the protected hidden volume; nothing was written. */
    class HiddenVolumeProtected : CofferException("write blocked to protect the hidden volume")
    class NoSpace : CofferException("no space left in the volume")
    class AlreadyExists(val path: String) : CofferException(path)
    class NotEmpty(val path: String) : CofferException(path)
    /** Opened read-only (by choice, or the file/provider is not writable). */
    class ReadOnly : CofferException("volume is read-only")
}

internal fun FfiException.toCoffer(): CofferException = when (this) {
    is FfiException.WrongCredentials -> CofferException.WrongCredentials()
    is FfiException.Cancelled -> CofferException.Cancelled()
    is FfiException.OutOfMemory -> CofferException.OutOfMemory(requiredBytes.toLong())
    is FfiException.Corrupted -> CofferException.Corrupted(detail)
    is FfiException.Unsupported -> CofferException.Unsupported(detail)
    is FfiException.NotFound -> CofferException.NotFound(path)
    is FfiException.NotADirectory -> CofferException.NotADirectory(path)
    is FfiException.IsADirectory -> CofferException.IsADirectory(path)
    is FfiException.InvalidPath -> CofferException.InvalidPath(path)
    is FfiException.Io -> CofferException.Io(detail)
    is FfiException.Closed -> CofferException.Closed()
    is FfiException.HiddenVolumeProtected -> CofferException.HiddenVolumeProtected()
    is FfiException.NoSpace -> CofferException.NoSpace()
    is FfiException.AlreadyExists -> CofferException.AlreadyExists(path)
    is FfiException.NotEmpty -> CofferException.NotEmpty(path)
    is FfiException.ReadOnly -> CofferException.ReadOnly()
}
