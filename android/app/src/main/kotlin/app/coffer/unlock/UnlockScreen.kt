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
import app.coffer.platform.ContentResolverAccess
import app.coffer.ui.CofferMark
import app.coffer.ui.userMessage

@Composable
fun UnlockScreen(viewModel: UnlockViewModel, onBack: () -> Unit, onUnlocked: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
        onUnlock = viewModel::onUnlock,
        onCancel = viewModel::onCancel,
    )
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
    onUnlock: (CharArray, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // Deliberately not rememberSaveable: the password must not be written
    // to the saved-instance Bundle, so it is lost on rotation.
    val password = remember { TextFieldState() }
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
                onKeyboardAction = { if (!state.busy) onUnlock(password.takePassword(), false) },
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
            }
            when (val phase = state.phase) {
                UnlockPhase.Unlocking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.unlock_working), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.unlock_cancel)) }
                }
                is UnlockPhase.Error -> ErrorCard(phase.error.userMessage())
                UnlockPhase.Idle -> Unit
            }
            if (!state.busy) {
                Button(onClick = { onUnlock(password.takePassword(), false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.unlock_button))
                }
                if (state.offerBackupHeaders) {
                    TextButton(onClick = { onUnlock(password.takePassword(), true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unlock_try_backup))
                    }
                }
            }
        }
    }
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
