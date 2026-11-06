// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.create

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.platform.ContentResolverAccess
import app.coffer.ui.userMessage
import java.nio.ByteBuffer

@Composable
fun CreateScreen(viewModel: CreateViewModel, onBack: () -> Unit, onOpened: (String) -> Unit) {
    val guardPicker = viewModel::onPicker
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { if (it is CreateEffect.Opened) onOpened(it.vaultId) }
        }
    }
    val resolver = LocalContext.current.contentResolver
    val location = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        guardPicker(false)
        uri?.let { viewModel.onLocationPicked(it.toString()) }
    }
    val keyfilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        guardPicker(false)
        uri?.let {
            val name = ContentResolverAccess(resolver).displayName(it.toString()) ?: it.lastPathSegment ?: "keyfile"
            viewModel.onAddKeyfile(it.toString(), name)
        }
    }
    BackHandler(enabled = state.busy) { viewModel.onCancel() }
    CreateContent(
        state = state,
        viewModel = viewModel,
        onBack = { if (state.busy) viewModel.onCancel() else onBack() },
        onPickLocation = { guardPicker(true); location.launch("Coffer.hc") },
        onAddKeyfile = { guardPicker(true); keyfilePicker.launch(arrayOf("*/*")) },
    )
}

private fun TextFieldState.take(): CharArray {
    val chars = CharArray(text.length) { text[it] }
    clearText()
    return chars
}

private fun TextFieldState.peek(): CharArray = CharArray(text.length) { text[it] }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContent(
    state: CreateUiState,
    viewModel: CreateViewModel,
    onBack: () -> Unit,
    onPickLocation: () -> Unit,
    onAddKeyfile: () -> Unit,
) {
    // Not rememberSaveable: passwords must never reach the saved-state Bundle.
    val password = remember { TextFieldState() }
    val confirm = remember { TextFieldState() }
    val pw = password.peek()
    val cf = confirm.peek()
    val blocker = viewModel.blocker(pw, cf)
    val strength = PasswordStrength.estimate(pw)
    val shortPassword = pw.isNotEmpty() && pw.size < PasswordStrength.RECOMMENDED_LENGTH
    pw.fill('\u0000')
    cf.fill('\u0000')
    val enabled = !state.busy
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section(stringResource(R.string.create_location))
            OutlinedButton(onClick = onPickLocation, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(state.locationName.ifEmpty { stringResource(R.string.create_pick_location) })
            }

            Section(stringResource(R.string.create_size))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.sizeText,
                    onValueChange = viewModel::onSizeChange,
                    label = { Text(stringResource(R.string.create_size)) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Choice("MB", !state.sizeGb, enabled) { viewModel.onSizeUnitGb(false) }
                Choice("GB", state.sizeGb, enabled) { viewModel.onSizeUnitGb(true) }
            }

            Section(stringResource(R.string.create_encryption))
            Picker(stringResource(R.string.create_cipher), state.cipher, state.ciphers, enabled, viewModel::onCipherChange)
            Picker(stringResource(R.string.unlock_kdf), state.prf, state.prfs, enabled, viewModel::onPrfChange)
            OutlinedTextField(
                value = state.pim,
                onValueChange = viewModel::onPimChange,
                label = { Text(stringResource(R.string.unlock_pim)) },
                supportingText = { Text(stringResource(R.string.create_pim_help)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            Section(stringResource(R.string.unlock_password))
            SecretField(password, stringResource(R.string.unlock_password), enabled)
            SecretField(confirm, stringResource(R.string.create_confirm), enabled)
            StrengthMeter(strength)
            if (shortPassword) {
                Text(stringResource(R.string.create_password_short), style = MaterialTheme.typography.bodySmall)
            }
            state.keyfiles.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.name, Modifier.weight(1f))
                    IconButton(onClick = { viewModel.onRemoveKeyfile(k.uri) }, enabled = enabled) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            }
            OutlinedButton(onClick = onAddKeyfile, enabled = enabled) { Text(stringResource(R.string.unlock_add_keyfile)) }

            Section(stringResource(R.string.create_filesystem))
            Choice("exFAT", state.fs == "exFAT", enabled) { viewModel.onFsChange("exFAT") }
            Choice("FAT", state.fs == "FAT", enabled) { viewModel.onFsChange("FAT") }
            if (state.fs == "FAT") {
                Text(stringResource(R.string.create_fat_warning), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth().toggleable(state.quick, enabled = enabled, role = Role.Switch, onValueChange = viewModel::onQuickChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.create_quick), Modifier.weight(1f))
                Switch(checked = state.quick, onCheckedChange = null, enabled = enabled)
            }
            if (state.quick) {
                Text(stringResource(R.string.create_quick_warning), style = MaterialTheme.typography.bodySmall)
            }

            Section(stringResource(R.string.create_entropy))
            EntropyPad(enabled, state.entropyBytes, viewModel::onEntropy)

            HorizontalDivider()
            when (val phase = state.phase) {
                is CreatePhase.Creating -> {
                    LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.create_working, (phase.progress * 100).toInt()))
                    OutlinedButton(onClick = viewModel::onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unlock_cancel))
                    }
                }
                is CreatePhase.Error -> Text(phase.error.userMessage(), color = MaterialTheme.colorScheme.error)
                CreatePhase.Idle -> Unit
            }
            if (!state.busy) {
                blocker?.let { Text(blockerText(it, state), style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = { viewModel.onCreate(password.take(), confirm.take()) },
                    enabled = blocker == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.create_button))
                }
            }
        }
    }
}

@Composable
private fun blockerText(b: Blocker, state: CreateUiState): String = when (b) {
    Blocker.NO_LOCATION -> stringResource(R.string.create_need_location)
    Blocker.SIZE_INVALID -> stringResource(R.string.create_need_size)
    Blocker.SIZE_TOO_SMALL -> stringResource(R.string.create_size_min, (state.minBytes shr 20).toInt(), state.fs)
    Blocker.SIZE_TOO_LARGE -> stringResource(R.string.create_size_max, app.coffer.browser.humanSize(state.availableBytes ?: 0))
    Blocker.NO_PASSWORD -> stringResource(R.string.create_need_password)
    Blocker.PASSWORD_MISMATCH -> stringResource(R.string.create_password_mismatch)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, selected: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); expanded = false }) }
        }
    }
}

/** Same masking rules as the unlock screen: accessibility sees only dots. */
@Composable
private fun SecretField(state: TextFieldState, label: String, enabled: Boolean) {
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
private fun StrengthMeter(s: Strength) {
    val (label, fraction) = when (s) {
        Strength.WEAK -> stringResource(R.string.create_strength_weak) to 0.25f
        Strength.FAIR -> stringResource(R.string.create_strength_fair) to 0.5f
        Strength.GOOD -> stringResource(R.string.create_strength_good) to 0.75f
        Strength.STRONG -> stringResource(R.string.create_strength_strong) to 1f
    }
    Column {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Finger movements add entropy to the system random generator (optional). */
@Composable
private fun EntropyPad(enabled: Boolean, collected: Int, onSample: (ByteArray) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.create_entropy_help), style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { c ->
                                val b = ByteBuffer.allocate(16)
                                    .putFloat(c.position.x).putFloat(c.position.y).putLong(System.nanoTime())
                                onSample(b.array())
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.create_entropy_bytes, collected))
        }
    }
}
