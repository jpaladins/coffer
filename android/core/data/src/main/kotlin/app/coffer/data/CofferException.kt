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
}
