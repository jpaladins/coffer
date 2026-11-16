// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.settings.UiPrefs
import app.coffer.update.UpdateState
import app.coffer.update.bannerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateBannerTest {
    @Test fun available_update_shows() =
        assertEquals(UpdateState.Available(7), bannerState(UpdateState.Available(7), dismissedCode = 0))

    @Test fun dismissed_version_hides() =
        assertEquals(UpdateState.None, bannerState(UpdateState.Available(7), dismissedCode = 7))

    @Test fun newer_version_after_dismiss_shows_again() =
        assertEquals(UpdateState.Available(8), bannerState(UpdateState.Available(8), dismissedCode = 7))

    @Test fun download_and_restart_ignore_dismiss() {
        assertEquals(UpdateState.Downloading(0.5f), bannerState(UpdateState.Downloading(0.5f), dismissedCode = 7))
        assertEquals(UpdateState.ReadyToInstall, bannerState(UpdateState.ReadyToInstall, dismissedCode = 7))
    }

    @Test fun dismissed_code_round_trips() = runTest {
        val prefs = UiPrefs(MemoryPrefs())
        assertEquals(0, prefs.dismissedUpdate.first())
        prefs.setDismissedUpdate(9)
        assertEquals(9, prefs.dismissedUpdate.first())
    }
}
