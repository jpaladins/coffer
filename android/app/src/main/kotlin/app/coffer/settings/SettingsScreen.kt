// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coffer.R
import app.coffer.security.AUTOLOCK_OPTIONS
import app.coffer.security.AppLock
import app.coffer.security.AppLockMode
import app.coffer.security.MIN_PIN
import app.coffer.security.SecuritySettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SecuritySettings,
    appLock: AppLock,
    biometricAvailable: Boolean,
    onBack: () -> Unit,
    onAbout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val autoLock by settings.autoLockMs.collectAsStateWithLifecycle(initialValue = null)
    val secure by settings.secureWindow.collectAsStateWithLifecycle(initialValue = true)
    val mode by settings.appLockMode.collectAsStateWithLifecycle(initialValue = AppLockMode.OFF)
    var pinFor by remember { mutableStateOf<AppLockMode?>(null) }
    val labels = mapOf(
        0L to R.string.autolock_immediately,
        30_000L to R.string.autolock_30s,
        120_000L to R.string.autolock_2m,
        300_000L to R.string.autolock_5m,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_autolock), style = MaterialTheme.typography.titleMedium)
            AUTOLOCK_OPTIONS.forEach { ms ->
                Radio(stringResource(labels.getValue(ms)), ms == autoLock) { scope.launch { settings.setAutoLockMs(ms) } }
            }
            HorizontalDivider()
            Text(stringResource(R.string.settings_app_lock), style = MaterialTheme.typography.titleMedium)
            Radio(stringResource(R.string.settings_app_lock_off), mode == AppLockMode.OFF) { scope.launch { appLock.disable() } }
            Radio(stringResource(R.string.settings_app_lock_pin), mode == AppLockMode.PIN) {
                if (mode == AppLockMode.OFF) pinFor = AppLockMode.PIN else scope.launch { appLock.setMode(AppLockMode.PIN) }
            }
            if (biometricAvailable) {
                Radio(stringResource(R.string.settings_app_lock_bio), mode == AppLockMode.PIN_OR_BIOMETRIC) {
                    if (mode == AppLockMode.OFF) pinFor = AppLockMode.PIN_OR_BIOMETRIC else scope.launch { appLock.setMode(AppLockMode.PIN_OR_BIOMETRIC) }
                }
            }
            if (mode != AppLockMode.OFF) {
                TextButton(onClick = { pinFor = mode }) { Text(stringResource(R.string.settings_change_pin)) }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().toggleable(secure, role = Role.Switch) { scope.launch { settings.setSecureWindow(it) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_secure_window))
                    Text(stringResource(R.string.settings_secure_window_help), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = secure, onCheckedChange = null)
            }
            HorizontalDivider()
            TextButton(onClick = onAbout) { Text(stringResource(R.string.about_title)) }
        }
    }
    pinFor?.let { target ->
        PinDialog(onDismiss = { pinFor = null }) { pin ->
            pinFor = null
            scope.launch { appLock.setPin(pin, target) }
        }
    }
}

@Composable
private fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

/** New PIN typed twice; [onSet] takes ownership of the array. */
@Composable
private fun PinDialog(onDismiss: () -> Unit, onSet: (CharArray) -> Unit) {
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    // Compared in place: no String copy of the PIN is made.
    val ok = first.text.length >= MIN_PIN && first.text.length == second.text.length &&
        first.text.indices.all { first.text[it] == second.text[it] }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_set_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(first to R.string.lock_pin, second to R.string.settings_confirm_pin).forEach { (state, label) ->
                    OutlinedSecureTextField(
                        state = state,
                        label = { Text(stringResource(label)) },
                        textObfuscationMode = TextObfuscationMode.Hidden,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                Text(stringResource(R.string.settings_pin_help, MIN_PIN), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val pin = CharArray(first.text.length) { first.text[it] }
                first.clearText()
                second.clearText()
                onSet(pin)
            }) { Text(stringResource(R.string.browser_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}
