// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.unlock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.password
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.security.authenticate
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import app.coffer.data.CofferException
import app.coffer.platform.ContentResolverAccess
import app.coffer.ui.CofferMark
import app.coffer.ui.userMessage

@Composable
fun UnlockScreen(viewModel: UnlockViewModel, onBack: () -> Unit, onUnlocked: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var rememberChecked by remember { mutableStateOf(false) }
    val rememberTitle = stringResource(R.string.unlock_fingerprint_title)
    val cancelText = stringResource(R.string.unlock_cancel)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { if (it is UnlockEffect.NavigateToBrowser) onUnlocked(it.vaultId) }
        }
    }
    val resolver = LocalContext.current.contentResolver
    val keyfilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val name = ContentResolverAccess(resolver).displayName(it.toString()) ?: it.lastPathSegment ?: "keyfile"
            viewModel.onAddKeyfile(it.toString(), name)
        }
    }
    UnlockContent(
        state = state,
        onBack = { viewModel.onCancel(); onBack() },
        onPimChange = viewModel::onPimChange,
        onPrfChange = viewModel::onPrfChange,
        onAddKeyfile = { keyfilePicker.launch(arrayOf("*/*")) },
        onRemoveKeyfile = viewModel::onRemoveKeyfile,
        onReadOnlyChange = viewModel::onReadOnlyChange,
        onProtectHiddenChange = viewModel::onProtectHiddenChange,
        onUnlock = { pw, backup, hidden ->
            val cipher = if (rememberChecked && activity != null) viewModel.encryptCipher() else null
            if (cipher == null || activity == null) {
                viewModel.onUnlock(pw, backup, hidden)
            } else {
                authenticate(activity, rememberTitle, cancelText, cipher, onSuccess = { c ->
                    viewModel.onUnlock(pw, backup, hidden, rememberWith = c)
                }, onError = { viewModel.onUnlock(pw, backup, hidden) })
            }
        },
        onCancel = viewModel::onCancel,
        rememberAvailable = state.biometricAvailable,
        remember = rememberChecked,
        onRememberChange = { rememberChecked = it },
        onFingerprint = if (state.hasRemembered && activity != null) {
            {
                scope.launch {
                    val c = viewModel.decryptCipher() ?: return@launch
                    authenticate(activity, rememberTitle, cancelText, c, onSuccess = { unlocked ->
                        unlocked?.let(viewModel::onBiometricAuthenticated)
                    })
                }
            }
        } else {
            null
        },
    )
    if (state.dirtyPrompt) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.dirty_title)) },
            text = { Text(stringResource(R.string.dirty_body)) },
            confirmButton = { TextButton(onClick = viewModel::onDirtyReadOnly) { Text(stringResource(R.string.dirty_read_only)) } },
            dismissButton = { TextButton(onClick = viewModel::onDirtyContinue) { Text(stringResource(R.string.dirty_continue)) } },
        )
    }
}

/** Copies the field into a CharArray and clears the field. */
private fun TextFieldState.takePassword(): CharArray {
    val chars = CharArray(text.length) { text[it] }
    clearText()
    return chars
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockContent(
    state: UnlockUiState,
    onBack: () -> Unit,
    onPimChange: (String) -> Unit,
    onPrfChange: (String?) -> Unit,
    onAddKeyfile: () -> Unit,
    onRemoveKeyfile: (String) -> Unit,
    onReadOnlyChange: (Boolean) -> Unit,
    onProtectHiddenChange: (Boolean) -> Unit,
    onUnlock: (CharArray, Boolean, CharArray?) -> Unit,
    onCancel: () -> Unit,
    rememberAvailable: Boolean = false,
    remember: Boolean = false,
    onRememberChange: (Boolean) -> Unit = {},
    onFingerprint: (() -> Unit)? = null,
) {
    // Deliberately not rememberSaveable: the password must not be written
    // to the saved-instance Bundle, so it is lost on rotation.
    val password = remember { TextFieldState() }
    val hiddenPassword = remember { TextFieldState() }
    fun submit(backup: Boolean) {
        val hidden = hiddenPassword.takePassword()
        onUnlock(password.takePassword(), backup, hidden)
    }
    val hiddenMissing = state.protectHidden && hiddenPassword.text.isEmpty()
    val passwordFocus = remember { FocusRequester() }
    val passwordLabel = stringResource(R.string.unlock_password)
    var showAdvanced by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.unlock_title, state.label)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CofferMark(size = 56.dp)
                Spacer(Modifier.padding(8.dp))
                Text(state.label, style = MaterialTheme.typography.titleLarge)
            }
            OutlinedSecureTextField(
                state = password,
                label = { Text(stringResource(R.string.unlock_password)) },
                textObfuscationMode = TextObfuscationMode.RevealLastTyped,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    // Accessibility services get only a masked value: the
                    // default semantics exposed the password text in clear.
                    .clearAndSetSemantics {
                        contentDescription = passwordLabel
                        password()
                        editableText = AnnotatedString("•".repeat(password.text.length))
                        onClick { passwordFocus.requestFocus(); true }
                    },
                // Password keyboard: no suggestions, no learning, no autocorrect.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { if (!state.busy && !hiddenMissing) submit(false) },
            )
            OutlinedTextField(
                value = state.pim,
                onValueChange = onPimChange,
                label = { Text(stringResource(R.string.unlock_pim)) },
                supportingText = { Text(stringResource(R.string.unlock_pim_help)) },
                enabled = !state.busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text("${stringResource(R.string.unlock_keyfiles)} · ${stringResource(R.string.unlock_kdf)}")
            }
            if (showAdvanced) {
                KdfPicker(state.prf, enabled = !state.busy, onPrfChange)
                state.keyfiles.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k.name, Modifier.weight(1f))
                        IconButton(onClick = { onRemoveKeyfile(k.uri) }, enabled = !state.busy) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
                OutlinedButton(onClick = onAddKeyfile, enabled = !state.busy) { Text(stringResource(R.string.unlock_add_keyfile)) }
                SwitchRow(stringResource(R.string.unlock_read_only), state.readOnly, !state.busy, onReadOnlyChange)
                SwitchRow(
                    stringResource(R.string.unlock_protect_hidden),
                    state.protectHidden,
                    !state.busy && !state.readOnly,
                    onProtectHiddenChange,
                )
                if (state.protectHidden) {
                    Text(stringResource(R.string.unlock_protect_hidden_help), style = MaterialTheme.typography.bodySmall)
                    PasswordField(hiddenPassword, stringResource(R.string.unlock_hidden_password), !state.busy)
                }
            }
            when (val phase = state.phase) {
                UnlockPhase.Unlocking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.unlock_working), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.unlock_cancel)) }
                }
                is UnlockPhase.Error -> ErrorCard(
                    if (phase.hiddenRequested && phase.error is CofferException.WrongCredentials) {
                        stringResource(R.string.error_wrong_credentials_hidden)
                    } else {
                        phase.error.userMessage()
                    },
                )
                UnlockPhase.Idle -> Unit
            }
            if (state.biometricInvalidated) {
                Text(stringResource(R.string.unlock_fingerprint_invalidated), style = MaterialTheme.typography.bodySmall)
            }
            if (!state.busy && onFingerprint != null) {
                OutlinedButton(onClick = onFingerprint, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unlock_with_fingerprint))
                }
            }
            if (!state.busy && rememberAvailable && !state.hasRemembered) {
                SwitchRow(stringResource(R.string.unlock_remember), remember, true, onRememberChange)
                if (remember) {
                    Text(stringResource(R.string.unlock_remember_warning), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.busy) {
                Button(onClick = { submit(false) }, enabled = !hiddenMissing, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.unlock_button))
                }
                if (state.offerBackupHeaders) {
                    TextButton(onClick = { submit(true) }, enabled = !hiddenMissing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unlock_try_backup))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Second secret field, with the same masking rules as the main password. */
@Composable
private fun PasswordField(state: TextFieldState, label: String, enabled: Boolean) {
    val focus = remember { FocusRequester() }
    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        textObfuscationMode = TextObfuscationMode.RevealLastTyped,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .clearAndSetSemantics {
                contentDescription = label
                password()
                editableText = AnnotatedString("•".repeat(state.text.length))
                onClick { focus.requestFocus(); true }
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.padding(6.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KdfPicker(selected: String?, enabled: Boolean, onChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.unlock_kdf_auto)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected ?: auto,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.unlock_kdf)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(auto) }, onClick = { onChange(null); expanded = false })
            KDF_CHOICES.forEach { k -> DropdownMenuItem(text = { Text(k) }, onClick = { onChange(k); expanded = false }) }
        }
    }
}
