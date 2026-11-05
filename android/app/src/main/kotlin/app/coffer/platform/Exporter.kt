// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.platform

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Qualifier

/** Destination chosen by the user (SAF CreateDocument) for an exported file. */
interface Exporter {
    fun openSink(uri: String): OutputStream
}

class ContentResolverExporter(private val resolver: ContentResolver) : Exporter {
    override fun openSink(uri: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw FileNotFoundException(uri)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
