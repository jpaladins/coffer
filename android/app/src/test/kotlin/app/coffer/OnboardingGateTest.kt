// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.onboarding.shouldShowOnboarding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import org.junit.Test

class OnboardingGateTest {
    @Test fun new_user_sees_onboarding() = assertTrue(shouldShowOnboarding(false, 0))
    @Test fun upgrade_with_vaults_skips() = assertFalse(shouldShowOnboarding(false, 2))
    @Test fun done_never_shows() = assertFalse(shouldShowOnboarding(true, 0))
}

class BetaNoticeGateTest {
    @Test fun shown_after_onboarding_until_accepted() =
        org.junit.Assert.assertTrue(app.coffer.onboarding.shouldShowBetaNotice(onboardingDone = true, accepted = false))

    @Test fun not_during_onboarding() =
        org.junit.Assert.assertFalse(app.coffer.onboarding.shouldShowBetaNotice(onboardingDone = false, accepted = false))

    @Test fun never_after_accepting() =
        org.junit.Assert.assertFalse(app.coffer.onboarding.shouldShowBetaNotice(onboardingDone = true, accepted = true))

    @Test fun acceptance_is_remembered() = kotlinx.coroutines.test.runTest {
        val prefs = app.coffer.settings.UiPrefs(MemoryPrefs())
        org.junit.Assert.assertFalse(prefs.betaAccepted.first())
        prefs.setBetaAccepted()
        org.junit.Assert.assertTrue(prefs.betaAccepted.first())
    }
}
