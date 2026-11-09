// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.coffer.R
import app.coffer.ui.components.CofferIcons
import app.coffer.ui.theme.TabularNums

/** Replaces the top bar while entries are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    count: Int,
    canExport: Boolean,
    canDelete: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selection_count, count, count), style = MaterialTheme.typography.titleLarge.merge(TabularNums)) },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_clear)) }
        },
        actions = {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.selection_all)) }
            IconButton(onClick = onExport, enabled = canExport) {
                Icon(CofferIcons.Download, contentDescription = stringResource(R.string.browser_export))
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.browser_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}
