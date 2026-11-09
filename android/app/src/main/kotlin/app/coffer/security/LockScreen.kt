// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import app.coffer.ui.theme.CofferGreen
import app.coffer.ui.theme.CofferGold
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.coffer.R
import app.coffer.ui.components.CofferIcons
import app.coffer.ui.components.HexBadge
import app.coffer.ui.components.pressScale
import app.coffer.ui.components.rememberAnimationsEnabled
import app.coffer.ui.components.rememberHaptics
import app.coffer.ui.theme.TabularNums
import javax.crypto.Cipher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch



/**
 * Shows BiometricPrompt (strong biometrics only). With [cipher], the
 * cipher is unlocked by the authentication and handed to [onSuccess].
 */
fun authenticate(
    activity: FragmentActivity,
    title: String,
    negative: String,
    cipher: Cipher?,
    onSuccess: (Cipher?) -> Unit,
    onError: () -> Unit = {},
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onSuccess(result.cryptoObject?.cipher)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError()
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setNegativeButtonText(negative)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
    if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
}

/** Full-screen app lock: covers everything until the PIN or a fingerprint is accepted. */
@Composable
fun LockScreen(appLock: AppLock, mode: AppLockMode) {
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    // Digits live in a CharArray, never in a String; wiped after each attempt.
    val buffer = remember { CharArray(MAX_PIN_INPUT) }
    var length by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shake = remember { Animatable(0f) }
    val animate = rememberAnimationsEnabled()
    val title = stringResource(R.string.lock_title)
    val usePin = stringResource(R.string.lock_use_pin)
    val wrong = stringResource(R.string.lock_wrong_pin)
    val bio = mode == AppLockMode.PIN_OR_BIOMETRIC && activity != null
    fun fingerprint() {
        if (activity != null) authenticate(activity, title, usePin, null, { appLock.onBiometricSuccess() })
    }
    LaunchedEffect(Unit) { if (bio) fingerprint() }
    LaunchedEffect(lockedUntil) {
        while (System.currentTimeMillis() < lockedUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    DisposableEffect(Unit) { onDispose { buffer.fill('\u0000') } }
    val waiting = now < lockedUntil
    fun submit() {
        if (waiting || length == 0) return
        val chars = buffer.copyOf(length)
        buffer.fill('\u0000')
        length = 0
        scope.launch {
            when (val r = appLock.submitPin(chars)) {
                PinResult.Ok -> message = null
                is PinResult.Wrong -> {
                    message = wrong.format(r.attemptsLeft)
                    haptics.reject()
                    if (animate) {
                        listOf(16f, -14f, 10f, -6f, 0f).forEach { shake.animateTo(it, spring(stiffness = Spring.StiffnessHigh)) }
                    }
                }
                is PinResult.LockedOut -> {
                    lockedUntil = r.untilMs
                    message = null
                    haptics.reject()
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            HexBadge(Icons.Filled.Lock, size = 56.dp, container = CofferGreen, content = CofferGold)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            val digits = pluralStringResource(R.plurals.lock_digits, length, length)
            Row(
                Modifier.graphicsLayer { translationX = shake.value.dp.toPx() }
                    .semantics { contentDescription = digits; liveRegion = LiveRegionMode.Polite },
                // Tighter past 8 dots, so 16 still fit a small phone.
                horizontalArrangement = Arrangement.spacedBy(if (pinDotCount(length) > 8) 6.dp else 12.dp),
            ) {
                val small = pinDotCount(length) > 8
                repeat(pinDotCount(length)) { i -> Dot(i < length, if (small) 12.dp else 14.dp) }
            }
            Spacer(Modifier.height(8.dp))
            val note = if (waiting) stringResource(R.string.lock_wait, ((lockedUntil - now) / 1000 + 1).toInt()) else message
            Text(
                note.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(min = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { d ->
                        Key(d, !waiting) {
                            if (length < MAX_PIN_INPUT) buffer[length++] = d[0]
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (bio) {
                    IconKey(CofferIcons.Fingerprint, stringResource(R.string.lock_fingerprint), true, highlight = true, onClick = ::fingerprint)
                } else {
                    Spacer(Modifier.size(KEY))
                }
                Key("0", !waiting) { if (length < MAX_PIN_INPUT) buffer[length++] = '0' }
                IconKey(CofferIcons.Backspace, stringResource(R.string.lock_delete), length > 0) {
                    if (length > 0) buffer[--length] = '\u0000'
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = ::submit,
                enabled = !waiting && length >= MIN_PIN,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) { Text(stringResource(R.string.lock_unlock), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Above [MAX_PIN]: PINs set before 0.1.0a had no maximum and must still unlock. */
private const val MAX_PIN_INPUT = 64
/** Typed digits plus one free dot, at least [MIN_PIN], at most [MAX_PIN] (longer legacy PINs show all filled). */
internal fun pinDotCount(length: Int) = maxOf(MIN_PIN, minOf(length + 1, MAX_PIN))

private val KEY = 72.dp

@Composable
private fun Dot(filled: Boolean, size: androidx.compose.ui.unit.Dp) {
    val c = MaterialTheme.colorScheme
    val fill by animateFloatAsState(if (filled) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "dot")
    Box(
        Modifier.size(size).graphicsLayer { scaleX = 0.7f + 0.3f * fill; scaleY = 0.7f + 0.3f * fill }
            .background(if (filled) c.primary else c.surfaceContainerHighest, CircleShape),
    )
}

@Composable
private fun Key(digit: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = source,
        modifier = Modifier.size(KEY).pressScale(source),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit, style = MaterialTheme.typography.headlineMedium.merge(TabularNums))
        }
    }
}

@Composable
private fun IconKey(icon: ImageVector, label: String, enabled: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (highlight) c.primaryContainer else c.surface,
        contentColor = if (highlight) c.onPrimaryContainer else c.onSurfaceVariant,
        interactionSource = source,
        modifier = Modifier.size(KEY).pressScale(source),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
    }
}
