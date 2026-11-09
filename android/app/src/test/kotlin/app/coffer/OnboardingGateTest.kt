// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.onboarding.shouldShowOnboarding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {
    @Test fun new_user_sees_onboarding() = assertTrue(shouldShowOnboarding(false, 0))
    @Test fun upgrade_with_vaults_skips() = assertFalse(shouldShowOnboarding(false, 2))
    @Test fun done_never_shows() = assertFalse(shouldShowOnboarding(true, 0))
}
