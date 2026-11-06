// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.coffer.data.Entry
import app.coffer.util.FileKind
import app.coffer.util.MimeTypes

/** Folder glyph (24dp grid); core icons have no folder and icons-extended is several MB. */
private val Folder: ImageVector by lazy {
    ImageVector.Builder("folder", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M10,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"),
        fill = SolidColor(Color.Black),
    ).build()
}

private fun glyph(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(path),
        fill = SolidColor(Color.Black),
    ).build()

// Material Design icon shapes (Apache-2.0), 24dp grid: "image", "description", "insert_drive_file".
private val Photo by lazy {
    glyph("photo", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
}
private val TextDoc by lazy {
    glyph("text", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z")
}
private val Doc by lazy {
    glyph("file", "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zM13,9V3.5L18.5,9H13z")
}

@Composable
fun FileIcon(e: Entry) {
    val (icon, tint) = when {
        e.isDir -> Folder to MaterialTheme.colorScheme.primary
        MimeTypes.kind(e.name) == FileKind.Image -> Photo to MaterialTheme.colorScheme.secondary
        MimeTypes.kind(e.name) == FileKind.Text -> TextDoc to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Doc to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}
