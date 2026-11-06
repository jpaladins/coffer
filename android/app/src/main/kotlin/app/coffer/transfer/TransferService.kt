// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.coffer.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while [TransferManager] imports files, so leaving
 * the app does not kill a long copy halfway. The notification never shows
 * file or vault names (it can appear on the lock screen).
 */
@AndroidEntryPoint
class TransferService : Service() {
    @Inject lateinit var transfers: TransferManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must follow every startForegroundService call.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!started) {
            started = true
            scope.launch {
                var lastIndex = 0
                var lastAt = 0L
                transfers.progress.collect { p ->
                    if (p == null) {
                        ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    // Byte progress changes many times a second; Android drops
                    // updates above ~5/s, so refresh on a new file or once a second.
                    val now = SystemClock.elapsedRealtime()
                    if (p.index != lastIndex || now - lastAt >= 1000) {
                        lastIndex = p.index
                        lastAt = now
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(p))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync services get about 6 hours a day; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        transfers.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(p: TransferProgress?): Notification {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(p?.let { text(it) })
            // One file: no total size to measure against, so a moving bar.
            .setProgress(p?.total ?: 0, (p?.index ?: 1) - 1, p == null || p.total == 1)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(true)
            // A user-started import: show it at once (Android otherwise waits ~10 s).
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun text(p: TransferProgress): String {
        val files = getString(R.string.transfer_notification, p.index, p.total)
        return if (p.bytes > 0) "$files · ${Formatter.formatShortFileSize(this, p.bytes)}" else files
    }

    companion object {
        // Default importance: shown with an icon in the status bar, not hidden
        // among the silent notifications; setSilent keeps it from making a sound.
        private const val CHANNEL_ID = "transfer_progress"
        private const val LEGACY_CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // A channel's importance cannot change once created: replace the old one.
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
