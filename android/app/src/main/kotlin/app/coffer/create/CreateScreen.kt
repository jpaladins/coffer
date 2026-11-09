// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.create

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.coffer.R
import app.coffer.browser.humanSize
import app.coffer.platform.ContentResolverAccess
import app.coffer.ui.components.BannerKind
import app.coffer.ui.components.ProgressCard
import app.coffer.ui.components.StatusBanner
import app.coffer.ui.components.StepIndicator
import app.coffer.ui.components.rememberAnimationsEnabled
import app.coffer.ui.components.rememberHaptics
import app.coffer.ui.theme.CofferRadii
import app.coffer.ui.theme.TabularNums
import app.coffer.ui.userMessage
import java.nio.ByteBuffer

private val Pill = RoundedCornerShape(CofferRadii.field)

@Composable
fun CreateScreen(viewModel: CreateViewModel, onBack: () -> Unit, onOpened: (String) -> Unit) {
    val guardPicker = viewModel::onPicker
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect {
                if (it is CreateEffect.Opened) {
                    haptics.confirm()
                    onOpened(it.vaultId)
                }
            }
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
    val back = {
        when {
            state.busy -> viewModel.onCancel()
            !viewModel.onBack() -> onBack()
        }
    }
    BackHandler(onBack = back)
    CreateContent(
        state = state,
        viewModel = viewModel,
        onBack = back,
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
    val blocker = viewModel.stepBlocker(state.step, pw, cf)
    val strength = PasswordStrength.estimate(pw)
    val shortPassword = pw.isNotEmpty() && pw.size < PasswordStrength.RECOMMENDED_LENGTH
    pw.fill('\u0000')
    cf.fill('\u0000')
    val enabled = !state.busy
    val stepNames = listOf(
        stringResource(R.string.create_step_location),
        stringResource(R.string.create_step_size),
        stringResource(R.string.create_step_password),
        stringResource(R.string.create_step_review),
    )
    val animate = rememberAnimationsEnabled()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            if (!state.busy) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val big = Modifier.weight(1f).heightIn(min = 56.dp)
                    if (state.step != CreateStep.Location) {
                        OutlinedButton(onClick = onBack, modifier = big) { Text(stringResource(R.string.action_back)) }
                    }
                    if (state.step == CreateStep.Review) {
                        Button(
                            onClick = { viewModel.onCreate(password.take(), confirm.take()) },
                            enabled = blocker == null,
                            modifier = big,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                        ) { Text(stringResource(R.string.create_button)) }
                    } else {
                        Button(
                            onClick = { viewModel.onNext(password.peek(), confirm.peek()) },
                            enabled = blocker == null,
                            modifier = big,
                        ) { Text(stringResource(R.string.onb_next)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            StepIndicator(stepNames, state.step.ordinal, Modifier.padding(vertical = 8.dp))
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    if (!animate) {
                        ContentTransform(EnterTransition.None, ExitTransition.None)
                    } else {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        ContentTransform(
                            slideInHorizontally { it / 4 * dir } + fadeIn(),
                            slideOutHorizontally { -it / 4 * dir } + fadeOut(),
                        )
                    }
                },
                label = "step",
                modifier = Modifier.weight(1f),
            ) { step ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (step) {
                        CreateStep.Location -> {
                            Section(stringResource(R.string.create_location))
                            OutlinedButton(
                                onClick = onPickLocation,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            ) {
                                Text(state.locationName.ifEmpty { stringResource(R.string.create_pick_location) })
                            }
                            (state.phase as? CreatePhase.Error)?.let { StatusBanner(BannerKind.Error, it.error.userMessage()) }
                        }
                        CreateStep.Size -> {
                            Section(stringResource(R.string.create_size))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = state.sizeText,
                                    onValueChange = viewModel::onSizeChange,
                                    label = { Text(stringResource(R.string.create_size)) },
                                    singleLine = true,
                                    enabled = enabled,
                                    shape = Pill,
                                    textStyle = MaterialTheme.typography.bodyLarge.merge(TabularNums),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                Segmented(listOf("MB", "GB"), if (state.sizeGb) 1 else 0, enabled) { viewModel.onSizeUnitGb(it == 1) }
                            }
                            state.availableBytes?.let {
                                Text(
                                    stringResource(R.string.browser_free, humanSize(it)),
                                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Section(stringResource(R.string.create_filesystem))
                            Segmented(listOf("exFAT", "FAT"), if (state.fs == "FAT") 1 else 0, enabled) {
                                viewModel.onFsChange(if (it == 1) "FAT" else "exFAT")
                            }
                            if (state.fs == "FAT") {
                                Text(stringResource(R.string.create_fat_warning), style = MaterialTheme.typography.bodySmall)
                            }
                            SwitchRow(stringResource(R.string.create_quick), state.quick, enabled, viewModel::onQuickChange)
                            if (state.quick) {
                                Text(stringResource(R.string.create_quick_warning), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        CreateStep.Password -> {
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
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.create_remove_keyfile, k.name))
                                    }
                                }
                            }
                            OutlinedButton(onClick = onAddKeyfile, enabled = enabled) { Text(stringResource(R.string.unlock_add_keyfile)) }
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
                                shape = Pill,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        CreateStep.Review -> Review(state, viewModel)
                    }
                    if (!state.busy && blocker != null && step == state.step) {
                        Text(
                            blockerText(blocker, state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Review(state: CreateUiState, viewModel: CreateViewModel) {
    Text(stringResource(R.string.create_review_hint), style = MaterialTheme.typography.bodyLarge)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(CofferRadii.card)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val none = stringResource(R.string.create_none)
            Summary(stringResource(R.string.create_location), state.locationName)
            Summary(stringResource(R.string.create_size), state.sizeBytes?.let(::humanSize).orEmpty())
            Summary(stringResource(R.string.create_filesystem), state.fs)
            Summary(stringResource(R.string.create_cipher), state.cipher)
            Summary(stringResource(R.string.unlock_kdf), state.prf)
            Summary(stringResource(R.string.unlock_pim), state.pim.ifEmpty { none })
            Summary(stringResource(R.string.unlock_keyfiles), state.keyfiles.joinToString { it.name }.ifEmpty { none })
        }
    }
    when (val phase = state.phase) {
        is CreatePhase.Creating -> {
            var start by remember { mutableLongStateOf(System.nanoTime()) }
            LaunchedEffect(Unit) { start = System.nanoTime() }
            val elapsed = (System.nanoTime() - start) / 1e9
            val eta = if (phase.progress > 0.02f) (elapsed * (1 - phase.progress) / phase.progress).toLong() else null
            val detail = "${(phase.progress * 100).toInt()} %" +
                (eta?.let { " · " + stringResource(R.string.create_eta, "%d:%02d".format(it / 60, it % 60)) } ?: "")
            ProgressCard(
                stringResource(R.string.create_working, (phase.progress * 100).toInt()),
                detail,
                phase.progress,
                onStop = viewModel::onCancel,
            )
        }
        else -> {
            Section(stringResource(R.string.create_entropy))
            EntropyPad(!state.busy, state.entropyBytes, viewModel::onEntropy)
        }
    }
}

@Composable
private fun Summary(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge.merge(TabularNums))
    }
}

@Composable
private fun blockerText(b: Blocker, state: CreateUiState): String = when (b) {
    Blocker.NO_LOCATION -> stringResource(R.string.create_need_location)
    Blocker.SIZE_INVALID -> stringResource(R.string.create_need_size)
    Blocker.SIZE_TOO_SMALL -> stringResource(R.string.create_size_min, (state.minBytes shr 20).toInt(), state.fs)
    Blocker.SIZE_TOO_LARGE -> stringResource(R.string.create_size_max, humanSize(state.availableBytes ?: 0))
    Blocker.NO_PASSWORD -> stringResource(R.string.create_need_password)
    Blocker.PASSWORD_MISMATCH -> stringResource(R.string.create_password_mismatch)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, selected: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            shape = Pill,
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
        shape = Pill,
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

/** Four segments filled by strength, with the level in words. */
@Composable
private fun StrengthMeter(s: Strength) {
    val label = when (s) {
        Strength.WEAK -> stringResource(R.string.create_strength_weak)
        Strength.FAIR -> stringResource(R.string.create_strength_fair)
        Strength.GOOD -> stringResource(R.string.create_strength_good)
        Strength.STRONG -> stringResource(R.string.create_strength_strong)
    }
    val c = MaterialTheme.colorScheme
    val fill = when (s) {
        Strength.WEAK -> c.error
        Strength.FAIR -> c.tertiary
        Strength.GOOD, Strength.STRONG -> c.primary
    }
    val described = stringResource(R.string.create_strength_label, label)
    Column(Modifier.clearAndSetSemantics { contentDescription = described }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(6.dp)
                        .background(if (i <= s.ordinal) fill else c.surfaceContainerHighest, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(CofferRadii.card))
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
            Text(stringResource(R.string.create_entropy_bytes, collected), style = MaterialTheme.typography.bodyMedium.merge(TabularNums))
        }
    }
}
