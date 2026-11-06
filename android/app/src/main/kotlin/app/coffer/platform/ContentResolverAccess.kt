// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.platform

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.provider.OpenableColumns
import app.coffer.data.FdOpener
import app.coffer.transfer.ImportSource
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/** SAF document operations the UI layer needs (seam for tests). */
interface DocumentAccess {
    /** Keeps access to [uri] across restarts. */
    fun persist(uri: String)
    fun displayName(uri: String): String?
    /** Free bytes on the storage holding [uri], or null when unknown. */
    fun availableBytes(uri: String): Long? = null
}

class ContentResolverAccess(private val resolver: ContentResolver) : FdOpener, DocumentAccess, ImportSource {
    override fun openReadFd(uri: String): Int =
        resolver.openFileDescriptor(Uri.parse(uri), "r")?.detachFd()
            ?: throw FileNotFoundException(uri)

    // "rw", never "w": some providers truncate on "w".
    override fun openWriteFd(uri: String): Int =
        resolver.openFileDescriptor(Uri.parse(uri), "rw")?.detachFd()
            ?: throw FileNotFoundException(uri)

    override fun deleteDocument(uri: String) {
        DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    }

    override fun openInput(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)

    override fun readPrefix(uri: String, maxBytes: Int): ByteArray {
        val input = resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)
        return input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val n = it.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            buf.fill(0)
            out.toByteArray()
        }
    }

    override fun persist(uri: String) {
        val u = Uri.parse(uri)
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(u, rw)
        } catch (_: SecurityException) {
            // Read-only providers grant no write access: the vault opens read-only.
            resolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun availableBytes(uri: String): Long? = runCatching {
        resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
            val st = Os.fstatvfs(pfd.fileDescriptor)
            st.f_bavail * st.f_frsize
        }
    }.getOrNull()

    override fun displayName(uri: String): String? =
        resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
