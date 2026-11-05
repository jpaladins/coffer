// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.provider

import app.coffer.data.VaultHandle

/**
 * Serves reads of one file inside a vault to a proxy file descriptor. Throws
 * [app.coffer.data.CofferException] (e.g. Closed after auto-lock).
 */
class ProxyReader(private val handle: VaultHandle, private val path: String) {
    fun read(offset: Long, size: Int, into: ByteArray): Int {
        val bytes = handle.read(path, offset, size)
        bytes.copyInto(into)
        val n = bytes.size
        bytes.fill(0)
        return n
    }
}
