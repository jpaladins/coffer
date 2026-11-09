// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.unlock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.data.CofferException
import app.coffer.platform.ContentResolverAccess
import app.coffer.security.authenticate
import app.coffer.ui.components.BannerKind
import app.coffer.ui.components.CofferIcons
import app.coffer.ui.components.HexBadge
import app.coffer.ui.components.StatusBanner
import app.coffer.ui.components.rememberHaptics
import app.coffer.ui.theme.CofferRadii
import app.coffer.ui.userMessage
import kotlinx.coroutines.launch

private val Pill = RoundedCornerShape(CofferRadii.field)

@Composable
fun UnlockScreen(viewModel: UnlockViewModel, onBack: () -> Unit, onUnlocked: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var rememberChecked by remember { mutableStateOf(false) }
    val rememberTitle = stringResource(R.string.unlock_fingerprint_title)
    val cancelText = stringResource(R.string.unlock_cancel)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect {
                if (it is UnlockEffect.NavigateToBrowser) {
                    haptics.confirm()
                    onUnlocked(it.vaultId)
                }
            }
        }
    }
    LaunchedEffect(state.phase) { if (state.phase is UnlockPhase.Error) haptics.reject() }
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
        onDirtyReadOnly = viewModel::onDirtyReadOnly,
        onDirtyContinue = viewModel::onDirtyContinue,
    )
}

/** Copies the field into a CharArray and clears the field. */
private fun TextFieldState.takePassword(): CharArray {
    val chars = CharArray(text.length) { text[it] }
    clearText()
    return chars
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    onDirtyReadOnly: () -> Unit = {},
    onDirtyContinue: () -> Unit = {},
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
    // Open by default when one of the advanced fields is already in use.
    var showAdvanced by remember {
        mutableStateOf(state.prf != null || state.pim.isNotEmpty() || state.keyfiles.isNotEmpty() || state.readOnly || state.protectHidden)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                HexBadge(Icons.Filled.Lock, size = 88.dp)
                Spacer(Modifier.height(16.dp))
                Text(state.label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            }
            SecretField(password, stringResource(R.string.unlock_password), !state.busy) {
                if (!state.busy && !hiddenMissing) submit(false)
            }
            AdvancedSection(showAdvanced, { showAdvanced = !showAdvanced }) {
                OutlinedTextField(
                    value = state.pim,
                    onValueChange = onPimChange,
                    label = { Text(stringResource(R.string.unlock_pim)) },
                    supportingText = { Text(stringResource(R.string.unlock_pim_help)) },
                    enabled = !state.busy,
                    singleLine = true,
                    shape = Pill,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                KdfPicker(state.prf, enabled = !state.busy, onPrfChange)
                state.keyfiles.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { onRemoveKeyfile(k.uri) }, enabled = !state.busy) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.unlock_remove_keyfile, k.name))
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
                    SecretField(hiddenPassword, stringResource(R.string.unlock_hidden_password), !state.busy)
                }
            }
            (state.phase as? UnlockPhase.Error)?.let { phase ->
                StatusBanner(
                    BannerKind.Error,
                    if (phase.hiddenRequested && phase.error is CofferException.WrongCredentials) {
                        stringResource(R.string.error_wrong_credentials_hidden)
                    } else {
                        phase.error.userMessage()
                    },
                )
            }
            if (state.dirtyPrompt) {
                StatusBanner(
                    BannerKind.Warning,
                    stringResource(R.string.dirty_title) + "\n" + stringResource(R.string.dirty_body),
                    action = stringResource(R.string.dirty_read_only) to onDirtyReadOnly,
                    secondAction = stringResource(R.string.dirty_continue) to onDirtyContinue,
                )
            }
            if (state.biometricInvalidated) {
                StatusBanner(BannerKind.Info, stringResource(R.string.unlock_fingerprint_invalidated))
            }
            if (!state.busy && rememberAvailable && !state.hasRemembered) {
                SwitchRow(stringResource(R.string.unlock_remember), remember, true, onRememberChange)
                if (remember) {
                    Text(stringResource(R.string.unlock_remember_warning), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.dirtyPrompt) {
                Button(
                    onClick = { if (!state.busy) submit(false) },
                    enabled = !hiddenMissing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) {
                    if (state.busy) {
                        LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onTertiary)
                    } else {
                        Text(stringResource(R.string.unlock_button), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (state.busy) {
                Text(
                    stringResource(R.string.unlock_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.unlock_cancel))
                }
            } else {
                if (onFingerprint != null) {
                    OutlinedButton(onClick = onFingerprint, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.unlock_with_fingerprint))
                    }
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
private fun AdvancedSection(open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(CofferRadii.card)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.unlock_advanced), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.unlock_advanced_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(turn))
            }
            AnimatedVisibility(open) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Pill password field with show/hide. Accessibility services always get only a
 * masked value: the default semantics exposed the password text in clear.
 */
@Composable
private fun SecretField(state: TextFieldState, label: String, enabled: Boolean, onDone: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    var visible by remember { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        enabled = enabled,
        shape = Pill,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) CofferIcons.VisibilityOff else CofferIcons.Visibility,
                    contentDescription = stringResource(if (visible) R.string.unlock_hide_password else R.string.unlock_show_password),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .clearAndSetSemantics {
                contentDescription = label
                password()
                editableText = AnnotatedString("•".repeat(state.text.length))
                onClick { focus.requestFocus(); true }
            },
        // Password keyboard: no suggestions, no learning, no autocorrect.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
        ),
        onKeyboardAction = { onDone?.invoke() },
    )
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
            singleLine = true,
            enabled = enabled,
            shape = Pill,
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
