// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
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

@Composable
fun FileIcon(e: Entry) {
    val (icon, tint) = when {
        e.isDir -> Folder to MaterialTheme.colorScheme.primary
        MimeTypes.kind(e.name) == FileKind.Image -> Icons.Filled.Face to MaterialTheme.colorScheme.secondary
        MimeTypes.kind(e.name) == FileKind.Text -> Icons.Filled.Edit to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}
