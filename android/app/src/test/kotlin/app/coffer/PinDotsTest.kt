// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.security.MAX_PIN
import app.coffer.security.pinDotCount
import org.junit.Assert.assertEquals
import org.junit.Test

class PinDotsTest {
    @Test fun at_least_min_pin_dots() = assertEquals(4, pinDotCount(0))

    @Test fun one_free_dot_after_the_typed_digits() = assertEquals(13, pinDotCount(12))

    @Test fun capped_at_max_pin_for_long_legacy_pins() {
        assertEquals(MAX_PIN, pinDotCount(MAX_PIN))
        assertEquals(MAX_PIN, pinDotCount(40))
    }
}
