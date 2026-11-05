// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import app.coffer.data.CofferException
import app.coffer.data.SessionManager
import app.coffer.util.MimeTypes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException

/**
 * Lets other apps read a file inside an unlocked vault without any plaintext
 * copy on disk: every read is served from the vault through a proxy file
 * descriptor (spec §5.3). Once the vault is locked, reads fail with EIO.
 *
 * URI: content://app.coffer.files/<vaultId>/<path segments...>
 */
class VaultFileProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sessions(): SessionManager
    }

    private val sessions by lazy {
        EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, Deps::class.java).sessions()
    }
    private val handler by lazy { Handler(HandlerThread("coffer-proxy").apply { start() }.looper) }

    override fun onCreate() = true

    private fun target(uri: Uri): Pair<String, String> {
        val seg = uri.pathSegments
        if (seg.size < 2) throw FileNotFoundException(uri.toString())
        return seg[0] to "/" + seg.drop(1).joinToString("/")
    }

    override fun getType(uri: Uri): String = MimeTypes.mime(uri.lastPathSegment.orEmpty())

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val (id, path) = target(uri)
        val entry = sessions.session(id)?.handle?.let { runCatching { it.stat(path) }.getOrNull() }
            ?: throw FileNotFoundException(uri.toString())
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) entry.size else entry.name })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val (id, path) = target(uri)
        val handle = sessions.session(id)?.handle ?: throw FileNotFoundException("vault locked")
        val size = try {
            handle.stat(path).size
        } catch (e: CofferException) {
            throw FileNotFoundException(e.message)
        }
        val reader = ProxyReader(handle, path)
        val sm = requireNotNull(context).getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = size
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    reader.read(offset, size, data)
                } catch (e: CofferException) {
                    throw ErrnoException("read", OsConstants.EIO)
                }
                override fun onRelease() = Unit
            },
            handler,
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "app.coffer.files"

        fun uriFor(vaultId: String, path: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(vaultId)
            .apply { path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }
            .build()
    }
}
