// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import app.coffer.ui.components.CofferIcons
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.coffer.data.Entry
import app.coffer.util.FileKind
import app.coffer.util.MimeTypes

@Composable
fun FileIcon(e: Entry) {
    val (icon, tint) = when {
        e.isDir -> CofferIcons.Folder to MaterialTheme.colorScheme.primary
        MimeTypes.kind(e.name) == FileKind.Image -> CofferIcons.Photo to MaterialTheme.colorScheme.secondary
        MimeTypes.kind(e.name) == FileKind.Text -> CofferIcons.TextDoc to MaterialTheme.colorScheme.onSurfaceVariant
        else -> CofferIcons.Doc to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}
