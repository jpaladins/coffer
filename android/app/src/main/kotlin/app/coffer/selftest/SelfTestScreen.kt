// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.selftest

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.coffer.BuildConfig
import app.coffer.R
import app.coffer.ui.components.CofferTopBar
import app.coffer.ui.components.HexBadge
import app.coffer.ui.components.SettingsSection
import app.coffer.ui.components.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(runner: SelfTestRunner, state: () -> AppSecurityState, onBack: () -> Unit) {
    val results = remember { mutableStateListOf<CheckResult>() }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { CofferTopBar(stringResource(R.string.selftest_title), scroll, onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.selftest_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(done, enter = fadeIn() + expandVertically()) { Summary(overall(results)) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = {
                    results.clear()
                    done = false
                    running = true
                    scope.launch {
                        runner.run(state()).flowOn(Dispatchers.IO).collect { results.add(it) }
                        running = false
                        done = true
                        if (overall(results) == CheckStatus.Fail) haptics.reject() else haptics.confirm()
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(
                    stringResource(if (done) R.string.selftest_run_again else R.string.selftest_run),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            CheckGroup.entries.forEach { group ->
                val inGroup = results.filter { it.group == group }
                if (inGroup.isNotEmpty()) {
                    SettingsSection(stringResource(groupTitle(group)), Modifier.animateContentSize()) {
                        inGroup.forEach { CheckRow(it) }
                    }
                }
            }
            if (done) {
                OutlinedButton(
                    onClick = {
                        val text = report(results, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, Build.MODEL)
                        clipboard.setText(AnnotatedString(text))
                        android.widget.Toast.makeText(ctx, R.string.selftest_copied, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.selftest_copy)) }
            }
        }
    }
}

@Composable
private fun Summary(status: CheckStatus) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        CheckStatus.Pass -> c.primaryContainer to c.onPrimaryContainer
        CheckStatus.Warn -> c.tertiaryContainer to c.onTertiaryContainer
        CheckStatus.Fail -> c.errorContainer to c.onErrorContainer
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HexBadge(statusIcon(status), size = 56.dp, container = bg, content = fg)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                stringResource(
                    when (status) {
                        CheckStatus.Pass -> R.string.selftest_pass_title
                        CheckStatus.Warn -> R.string.selftest_warn_title
                        CheckStatus.Fail -> R.string.selftest_fail_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(
                    when (status) {
                        CheckStatus.Pass -> R.string.selftest_pass_body
                        CheckStatus.Warn -> R.string.selftest_warn_body
                        CheckStatus.Fail -> R.string.selftest_fail_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheckRow(r: CheckResult) {
    val tint: Color = when (r.status) {
        CheckStatus.Pass -> MaterialTheme.colorScheme.primary
        CheckStatus.Warn -> MaterialTheme.colorScheme.tertiary
        CheckStatus.Fail -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(statusIcon(r.status), contentDescription = stringResource(statusLabel(r.status)), tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(checkTitle(r), style = MaterialTheme.typography.bodyLarge)
            hint(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun statusIcon(s: CheckStatus) = when (s) {
    CheckStatus.Pass -> Icons.Filled.CheckCircle
    CheckStatus.Warn -> Icons.Filled.Info
    CheckStatus.Fail -> Icons.Filled.Warning
}

private fun statusLabel(s: CheckStatus) = when (s) {
    CheckStatus.Pass -> R.string.selftest_status_pass
    CheckStatus.Warn -> R.string.selftest_status_warn
    CheckStatus.Fail -> R.string.selftest_status_fail
}

private fun groupTitle(g: CheckGroup) = when (g) {
    CheckGroup.Crypto -> R.string.selftest_group_crypto
    CheckGroup.Volume -> R.string.selftest_group_volume
    CheckGroup.Device -> R.string.selftest_group_device
}

/** Crypto checks keep the algorithm name; the others have localized titles. */
@Composable
private fun checkTitle(r: CheckResult): String = when (r.id) {
    "create_write_read" -> stringResource(R.string.selftest_create_write_read, r.arg.orEmpty())
    "reopen" -> stringResource(R.string.selftest_reopen, r.arg.orEmpty())
    "wrong_password" -> stringResource(R.string.selftest_wrong_password, r.arg.orEmpty())
    "backup_header" -> stringResource(R.string.selftest_backup_header, r.arg.orEmpty())
    "no_internet" -> stringResource(R.string.selftest_no_internet)
    "no_backup" -> stringResource(R.string.selftest_no_backup)
    "release_build" -> stringResource(R.string.selftest_release_build)
    "screenshots_blocked" -> stringResource(R.string.selftest_screenshots)
    "screen_lock" -> stringResource(R.string.selftest_screen_lock)
    "app_lock" -> stringResource(R.string.selftest_app_lock)
    "hardware_keystore" -> stringResource(R.string.selftest_keystore)
    "System RNG" -> stringResource(R.string.selftest_rng)
    else -> r.id
}

/** Advice shown under a warning or failure. */
@Composable
private fun hint(r: CheckResult): String? {
    if (r.status == CheckStatus.Pass) return null
    return when (r.id) {
        "screenshots_blocked" -> stringResource(R.string.selftest_hint_screenshots)
        "screen_lock" -> stringResource(R.string.selftest_hint_screen_lock)
        "app_lock" -> stringResource(R.string.selftest_hint_app_lock)
        "hardware_keystore" -> stringResource(R.string.selftest_hint_keystore)
        "release_build" -> stringResource(R.string.selftest_hint_release)
        else -> stringResource(R.string.selftest_hint_fail)
    }
}
