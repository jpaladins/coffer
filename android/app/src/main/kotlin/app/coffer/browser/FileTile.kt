// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.coffer.data.Entry

/** One entry in the grid view: square preview, name, menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    e: Entry,
    selected: Boolean,
    selecting: Boolean,
    writable: Boolean,
    actions: BrowserActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = { FileIcon(e) },
) {
    val c = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .semantics { this.selected = selected }
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = { if (selecting) actions.onToggleSelect(e.name) else actions.onOpen(e) },
                onLongClick = { actions.onToggleSelect(e.name) },
            ),
        color = if (selected) c.secondaryContainer else c.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp)
                    .clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                preview()
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = c.primary,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(28.dp),
                    )
                }
                if (!selecting) {
                    Box(Modifier.align(Alignment.TopEnd)) { EntryMenu(e, writable, actions, onRename, onDelete) }
                }
            }
            Text(
                e.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
            )
        }
    }
}
