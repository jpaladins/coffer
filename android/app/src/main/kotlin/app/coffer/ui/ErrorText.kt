// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.coffer.R
import app.coffer.data.CofferException

/** User-facing copy for every data-layer error (tone: brand.md). */
@Composable
fun CofferException.userMessage(): String = when (this) {
    is CofferException.WrongCredentials -> stringResource(R.string.error_wrong_credentials)
    is CofferException.OutOfMemory -> stringResource(R.string.error_out_of_memory, (requiredBytes shr 20).toInt())
    is CofferException.Corrupted -> stringResource(R.string.error_corrupted, detail)
    is CofferException.Unsupported -> stringResource(R.string.error_unsupported, detail)
    is CofferException.PermissionLost -> stringResource(R.string.error_permission_lost)
    is CofferException.Closed -> stringResource(R.string.error_closed)
    else -> stringResource(R.string.error_generic, message ?: "")
}
