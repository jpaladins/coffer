// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.platform

import android.content.ContentResolver
import androidx.core.net.toUri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Qualifier

/** Destination chosen by the user (SAF CreateDocument) for an exported file. */
interface Exporter {
    fun openSink(uri: String): OutputStream

    /** Creates [name] in a folder picked with OpenDocumentTree; returns the new document URI. */
    fun createInTree(treeUri: String, name: String, mime: String): String
}

class ContentResolverExporter(private val resolver: ContentResolver) : Exporter {
    override fun openSink(uri: String): OutputStream =
        resolver.openOutputStream(uri.toUri(), "wt") ?: throw FileNotFoundException(uri)

    override fun createInTree(treeUri: String, name: String, mime: String): String {
        val tree = treeUri.toUri()
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, dir, mime, name)?.toString() ?: throw FileNotFoundException(name)
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
