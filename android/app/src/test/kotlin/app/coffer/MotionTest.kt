// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.ui.components.animationsEnabledFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun zero_scale_disables() = assertFalse(animationsEnabledFor(0f))
    @Test fun normal_scale_enables() = assertTrue(animationsEnabledFor(1f))
}
