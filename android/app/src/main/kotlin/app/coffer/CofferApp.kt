// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.coffer.data.SessionManager
import app.coffer.security.AutoLock
import app.coffer.security.SecuritySettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.MainScope

@HiltAndroidApp
class CofferApp : Application() {
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var settings: SecuritySettings

    override fun onCreate() {
        super.onCreate()
        val autoLock = AutoLock(MainScope(), timeoutMs = settings::currentAutoLockMs, lockAll = sessions::closeAll)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = autoLock.onBackground()
            override fun onStart(owner: LifecycleOwner) = autoLock.onForeground()
        })
    }
}
