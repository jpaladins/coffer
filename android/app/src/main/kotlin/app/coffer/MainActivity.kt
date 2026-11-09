// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import app.coffer.data.SessionManager
import app.coffer.nav.CofferNavHost
import app.coffer.nav.Routes
import app.coffer.onboarding.shouldShowOnboarding
import app.coffer.settings.UiPrefs
import app.coffer.data.VaultRef
import app.coffer.data.VaultStore
import androidx.compose.runtime.remember
import app.coffer.security.AppLock
import app.coffer.security.AppLockMode
import app.coffer.security.LockScreen
import app.coffer.security.RememberedPasswords
import app.coffer.security.SecuritySettings
import app.coffer.ui.theme.CofferTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A FragmentActivity: BiometricPrompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var settings: SecuritySettings
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var remembered: RememberedPasswords
    @Inject lateinit var uiPrefs: UiPrefs
    @Inject lateinit var store: VaultStore

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: E2E tests may lift FLAG_SECURE to capture screenshots.
        val e2e = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_E2E_SCREENS, false)
        applySecure(!e2e)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(settings.secureWindow, settings.appLockMode) { secure, mode -> secure to mode }
                    .collect { (secure, mode) ->
                        // Below API 33 the recents thumbnail cannot be disabled on its own:
                        // with an app lock, keep FLAG_SECURE on.
                        val lockNeedsSecure = mode != AppLockMode.OFF && Build.VERSION.SDK_INT < 33
                        applySecure((secure || lockNeedsSecure) && !e2e)
                        // With FLAG_SECURE off, an app lock still hides the recents thumbnail.
                        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!secure && mode == AppLockMode.OFF)
                    }
            }
        }
        enableEdgeToEdge()
        setContent {
            CofferTheme {
                val nav = rememberNavController()
                val locked by appLock.isLocked.collectAsStateWithLifecycle()
                val ready by appLock.ready.collectAsStateWithLifecycle()
                val covered by appLock.covered.collectAsStateWithLifecycle()
                val mode by settings.appLockMode.collectAsStateWithLifecycle(initialValue = AppLockMode.OFF)
                if (locked) {
                    LockScreen(appLock, mode)
                } else if (!ready || covered) {
                    // Nothing of the vaults is drawn until the lock state is known.
                    Surface(Modifier.fillMaxSize()) {}
                } else {
                    val done by uiPrefs.onboardingDone.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
                    val vaults by store.vaults.collectAsStateWithLifecycle<List<VaultRef>?>(initialValue = null)
                    val d = done
                    val v = vaults
                    if (d == null || v == null) {
                        Surface(Modifier.fillMaxSize()) {}
                    } else {
                        val start = remember { if (shouldShowOnboarding(d, v.size)) Routes.ONBOARDING else Routes.VAULTS }
                        // Updated from 0.0.1a with containers: never show it later either.
                        LaunchedEffect(d, v.isEmpty()) { if (!d && v.isNotEmpty()) uiPrefs.setOnboardingDone() }
                        // The notification request comes after the onboarding.
                        if (d) LaunchedEffect(Unit) { if (!e2e) askNotificationsOnce() }
                        CofferNavHost(
                            sessions.openIds, settings, appLock, remembered.isAvailable(), nav,
                            startDestination = start,
                            onOnboardingDone = { lifecycleScope.launch { uiPrefs.setOnboardingDone() } },
                        )
                    }
                }
            }
        }
    }

    /**
     * Android 13+: the import progress notification needs this permission.
     * Asked once on first launch, after any app lock; if refused, the
     * browser asks again right before the first import.
     */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        if (granted || prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun applySecure(on: Boolean) {
        if (on) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        const val EXTRA_E2E_SCREENS = "e2e_screens"
        private const val PREFS_UI = "ui"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
