// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coffer.security.LockGuard
import app.coffer.settings.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: AppUpdates,
    private val prefs: UiPrefs,
    private val guard: LockGuard,
) : ViewModel() {
    val banner: StateFlow<UpdateState> = combine(updates.state, prefs.dismissedUpdate, ::bannerState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateState.None)

    init {
        updates.check()
    }

    fun onUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        // The Play Store dialog is not "leaving the app": keep the app lock away.
        guard.acquirePicker()
        if (!updates.start(launcher)) guard.releasePicker()
    }

    fun onFlowClosed() {
        guard.releasePicker()
        updates.check()
    }

    fun onInstall() = updates.install()

    fun onDismiss() {
        val s = updates.state.value
        if (s is UpdateState.Available) viewModelScope.launch { prefs.setDismissedUpdate(s.versionCode) }
    }
}
