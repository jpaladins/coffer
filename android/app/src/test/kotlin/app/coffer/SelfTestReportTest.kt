// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.selftest.CheckGroup
import app.coffer.selftest.CheckResult
import app.coffer.selftest.CheckStatus
import app.coffer.selftest.overall
import app.coffer.selftest.report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestReportTest {
    private val pass = CheckResult(CheckGroup.Crypto, "AES-256", CheckStatus.Pass)
    private val warn = CheckResult(CheckGroup.Device, "screen_lock", CheckStatus.Warn)
    private val fail = CheckResult(CheckGroup.Volume, "roundtrip_exfat", CheckStatus.Fail)

    @Test fun all_pass_is_pass() = assertEquals(CheckStatus.Pass, overall(listOf(pass, pass)))
    @Test fun a_warning_downgrades() = assertEquals(CheckStatus.Warn, overall(listOf(pass, warn)))
    @Test fun a_failure_wins() = assertEquals(CheckStatus.Fail, overall(listOf(pass, warn, fail)))
    @Test fun nothing_run_is_not_a_failure() = assertEquals(CheckStatus.Pass, overall(emptyList()))

    @Test
    fun report_lists_groups_and_outcomes_without_personal_data() {
        val r = report(listOf(pass, warn, fail), "0.1.6b", "17", "Pixel")
        assertTrue(r.contains("Coffer 0.1.6b"))
        assertTrue(r.contains("Result: Fail"))
        assertTrue(r.contains("[Crypto]") && r.contains("[Volume]") && r.contains("[Device]"))
        assertTrue(r.contains("FAIL  roundtrip_exfat"))
        assertFalse(r.contains(".hc"))
    }
}
