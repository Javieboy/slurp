package app.slurp.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.slurp.MainActivity
import app.slurp.R
import app.slurp.model.JobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Exists purely to keep the process alive while downloads run.
 *
 * Android will freeze or kill a backgrounded process within a minute or two,
 * and a half-finished download is worse than none. The notification is the
 * price of admission for staying alive, so it may as well carry the progress.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of startForegroundService() or the
        // system throws ForegroundServiceDidNotStartInTimeException.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Starting…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        scope.launch {
            DownloadQueue.jobs.collectLatest { jobs ->
                val active = jobs.filterNot { it.state.isTerminal }

                // The service retires itself rather than being stopped by the
                // queue. The queue used to do it from the pump's `finally`,
                // which raced the next submit starting it again; here there is
                // one writer and the state it reacts to is the queue itself.
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(
                        this@DownloadService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                    return@collectLatest
                }

                val current = active.firstOrNull { it.state == JobState.DOWNLOADING } ?: active.first()
                val remaining = active.size
                val text = buildString {
                    append(current.title.take(60))
                    if (remaining > 1) append("  (+${remaining - 1} queued)")
                }
                val percent = (current.progress * 100).toInt().takeIf { current.progress >= 0f }

                notificationManager().notify(NOTIFICATION_ID, buildNotification(text, percent))
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String, percent: Int?): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("slurp")
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (percent != null) setProgress(100, percent, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001

        /**
         * Call this from the foreground — see `DownloadQueue.wakeService`. It
         * throws ForegroundServiceStartNotAllowedException on Android 12+ if the
         * app is in the background, so the caller is responsible for catching.
         *
         * There is no matching stop(): the service retires itself when the queue
         * empties.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_downloads),
                    // Low: a progress bar that dings on every update is unusable.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
    }
}
