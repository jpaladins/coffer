// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.coffer.R
import app.coffer.ui.CofferMark
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
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val pin = remember { TextFieldState() }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
    val waiting = now < lockedUntil
    fun submit() {
        if (waiting) return
        val chars = CharArray(pin.text.length) { pin.text[it] }
        pin.clearText()
        scope.launch {
            when (val r = appLock.submitPin(chars)) {
                PinResult.Ok -> message = null
                is PinResult.Wrong -> message = wrong.format(r.attemptsLeft)
                is PinResult.LockedOut -> {
                    lockedUntil = r.untilMs
                    message = null
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().imePadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CofferMark(size = 96.dp)
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            OutlinedSecureTextField(
                state = pin,
                label = { Text(stringResource(R.string.lock_pin)) },
                textObfuscationMode = TextObfuscationMode.Hidden,
                enabled = !waiting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                onKeyboardAction = { submit() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (waiting) {
                Text(stringResource(R.string.lock_wait, ((lockedUntil - now) / 1000 + 1).toInt()), color = MaterialTheme.colorScheme.error)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::submit, enabled = !waiting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock_unlock))
            }
            if (bio) {
                OutlinedButton(onClick = ::fingerprint, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lock_fingerprint))
                }
            }
        }
    }
}
