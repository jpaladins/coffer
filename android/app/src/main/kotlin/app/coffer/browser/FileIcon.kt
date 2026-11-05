// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.coffer.data.Entry
import app.coffer.util.FileKind
import app.coffer.util.MimeTypes

/** Core icon set only (no icons-extended dependency): folder, image, text, other. */
@Composable
fun FileIcon(e: Entry) {
    val (icon, tint) = when {
        e.isDir -> Icons.Filled.Home to MaterialTheme.colorScheme.primary
        MimeTypes.kind(e.name) == FileKind.Image -> Icons.Filled.Face to MaterialTheme.colorScheme.secondary
        MimeTypes.kind(e.name) == FileKind.Text -> Icons.Filled.Edit to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}
