// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.coffer.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** FLAG_SECURE is on by default and follows the "Block screenshots" setting. */
@RunWith(AndroidJUnit4::class)
class SecureWindowTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = EntryPointAccessors.fromApplication(ctx.applicationContext, AppEntryPoint::class.java).settings()

    @After
    fun restore() = runBlocking { settings.setSecureWindow(true) }

    private fun secure(s: ActivityScenario<MainActivity>): Boolean {
        var flags = 0
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        s.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    @Test
    fun flag_secure_follows_setting() {
        runBlocking { settings.setSecureWindow(true) }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertEquals(true, secure(s))
            runBlocking { settings.setSecureWindow(false) }
            assertEquals(false, secure(s))
            runBlocking { settings.setSecureWindow(true) }
            assertEquals(true, secure(s))
        }
    }
}
