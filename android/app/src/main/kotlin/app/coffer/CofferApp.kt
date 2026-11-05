// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.app.Application
import android.content.Intent
import app.coffer.provider.VaultFileProvider
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
        // Apps that received an "Open with" grant lose it when the vault locks.
        sessions.addCloseListener { s ->
            revokeUriPermission(VaultFileProvider.sessionRoot(s.token), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val autoLock = AutoLock(MainScope(), timeoutMs = settings::currentAutoLockMs, lockAll = sessions::closeAll)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = autoLock.onBackground()
            override fun onStart(owner: LifecycleOwner) = autoLock.onForeground()
        })
    }
}
