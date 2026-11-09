// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.coffer.R
import app.coffer.data.Entry
import app.coffer.ui.theme.TabularNums
import java.text.DateFormat
import java.util.Date

/** Size and date line of a file; folders have none. */
internal fun entryDetail(e: Entry): String? {
    if (e.isDir) return null
    val date = e.modified?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) }
    return listOfNotNull(humanSize(e.size), date).joinToString(" · ")
}

/** Per-entry actions; [writable] adds the editing ones. */
@Composable
internal fun EntryMenu(
    e: Entry,
    writable: Boolean,
    actions: BrowserActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    if (e.isDir && !writable) return
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.browser_more, e.name))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!e.isDir) {
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_open_with)) }, onClick = { menu = false; actions.onOpenWith(e) })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_export)) }, onClick = { menu = false; actions.onExport(e) })
            }
            if (writable) {
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_move)) }, onClick = { menu = false; actions.onStartMove(e) })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** One entry in the list view. Long press starts the selection; while selecting, a tap toggles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    e: Entry,
    selected: Boolean,
    selecting: Boolean,
    writable: Boolean,
    actions: BrowserActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = { FileIcon(e) },
) {
    val c = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier
            .semantics { this.selected = selected }
            .combinedClickable(
                onClick = { if (selecting) actions.onToggleSelect(e.name) else actions.onOpen(e) },
                onLongClick = { actions.onToggleSelect(e.name) },
            ),
        colors = ListItemDefaults.colors(containerColor = if (selected) c.secondaryContainer else c.surface),
        leadingContent = {
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.primary, modifier = Modifier.size(40.dp))
            else leading()
        },
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = entryDetail(e)?.let { d ->
            { Text(d, style = MaterialTheme.typography.bodySmall.merge(TabularNums)) }
        },
        trailingContent = { if (!selecting) EntryMenu(e, writable, actions, onRename, onDelete) },
    )
}
