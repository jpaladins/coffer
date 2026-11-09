// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.settings.BrowserLayout
import app.coffer.settings.SortKey
import app.coffer.settings.SortOrder
import app.coffer.settings.UiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPrefsTest {
    private val prefs = UiPrefs(MemoryPrefs())

    @Test fun defaults_are_list_name_ascending_remember_on() = runTest {
        assertEquals(BrowserLayout.List, prefs.layout.first())
        assertEquals(SortOrder(SortKey.Name, true), prefs.sort.first())
        assertTrue(prefs.rememberLastAccess.first())
        assertFalse(prefs.onboardingDone.first())
        assertTrue(prefs.lastAccess.first().isEmpty())
    }

    @Test fun record_then_read_last_access() = runTest {
        prefs.recordAccess("a", 100L)
        prefs.recordAccess("b\"x", 200L)
        assertEquals(mapOf("a" to 100L, "b\"x" to 200L), prefs.lastAccess.first())
    }

    @Test fun disabling_remember_clears_all_timestamps() = runTest {
        prefs.recordAccess("a", 100L)
        prefs.setRememberLastAccess(false)
        assertTrue(prefs.lastAccess.first().isEmpty())
        assertFalse(prefs.rememberLastAccess.first())
    }

    @Test fun record_ignored_when_disabled() = runTest {
        prefs.setRememberLastAccess(false)
        prefs.recordAccess("a", 100L)
        assertTrue(prefs.lastAccess.first().isEmpty())
    }

    @Test fun forget_removes_one_id() = runTest {
        prefs.recordAccess("a", 1L)
        prefs.recordAccess("b", 2L)
        prefs.forget("a")
        assertEquals(mapOf("b" to 2L), prefs.lastAccess.first())
    }

    @Test fun sort_and_layout_round_trip() = runTest {
        prefs.setSort(SortOrder(SortKey.Size, false))
        prefs.setLayout(BrowserLayout.Grid)
        prefs.setOnboardingDone()
        assertEquals(SortOrder(SortKey.Size, false), prefs.sort.first())
        assertEquals(BrowserLayout.Grid, prefs.layout.first())
        assertTrue(prefs.onboardingDone.first())
    }
}
