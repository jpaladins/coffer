// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.coffer.data.SessionManager
import app.coffer.nav.CofferNavHost
import app.coffer.security.SecuritySettings
import app.coffer.ui.theme.CofferTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var settings: SecuritySettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recents thumbnails of vault contents.
        // Debug builds only: E2E tests may lift it to capture screenshots.
        if (!(BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_E2E_SCREENS, false))) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        setContent {
            CofferTheme { CofferNavHost(sessions.openIds, settings) }
        }
    }

    companion object {
        const val EXTRA_E2E_SCREENS = "e2e_screens"
    }
}
