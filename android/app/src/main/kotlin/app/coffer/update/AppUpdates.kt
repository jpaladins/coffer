// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val versionCode: Int) : UpdateState
    /** [fraction] is null until the Play Store knows the download size. */
    data class Downloading(val fraction: Float?) : UpdateState
    data object ReadyToInstall : UpdateState
}

/** A dismissed offer stays hidden until a newer version shows up; progress is always shown. */
fun bannerState(state: UpdateState, dismissedCode: Int): UpdateState =
    if (state is UpdateState.Available && state.versionCode <= dismissedCode) UpdateState.None else state

interface AppUpdates {
    val state: StateFlow<UpdateState>
    fun check()
    /** Returns false when the Play Store cannot start the update now. */
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean
    fun install()
}

/**
 * Asks the Play Store (not the network: Coffer has no internet permission)
 * whether a newer version exists, and runs its flexible update flow.
 * Installs from outside the Play Store simply never report an update.
 */
class PlayAppUpdates(context: Context) : AppUpdates {
    private val manager = AppUpdateManagerFactory.create(context)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val listener = InstallStateUpdatedListener { s ->
        _state.value = when (s.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> UpdateState.Downloading(
                s.totalBytesToDownload().takeIf { it > 0 }?.let { s.bytesDownloaded().toFloat() / it },
            )
            InstallStatus.DOWNLOADED -> UpdateState.ReadyToInstall
            InstallStatus.INSTALLED -> UpdateState.None
            // Failed or cancelled: offer it again.
            else -> _state.value.let { if (it is UpdateState.Downloading) UpdateState.None else it }
        }
        if (s.installStatus() == InstallStatus.FAILED || s.installStatus() == InstallStatus.CANCELED) check()
    }

    init {
        manager.registerListener(listener)
    }

    override fun check() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            _state.value = when {
                info.installStatus() == InstallStatus.DOWNLOADED -> UpdateState.ReadyToInstall
                info.installStatus() == InstallStatus.DOWNLOADING -> UpdateState.Downloading(null)
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> UpdateState.Available(info.availableVersionCode())
                else -> UpdateState.None
            }
        }
        // No Play Store or not installed from it: nothing to offer.
        manager.appUpdateInfo.addOnFailureListener { _state.value = UpdateState.None }
    }

    override fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val task = manager.appUpdateInfo
        task.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                runCatching {
                    manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
                }
            }
        }
        return true
    }

    override fun install() {
        manager.completeUpdate()
    }
}
