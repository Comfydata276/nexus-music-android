package org.medianexus.music.jam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.medianexus.music.MainActivity
import org.medianexus.music.R
import org.medianexus.music.graph

/**
 * Keeps the phone's connection to the relay while it is in a jam, hosting one, or steering
 * another of the person's devices (rule 1): a foreground service of the connected-device kind,
 * with a notification that says so and offers to stop. Android then lets the app hold the
 * relay's requests open with its screens closed, and the person can see it and end it. It
 * holds nothing itself: the waits on the relay are [Jams]'s, in the app's process; this only
 * gives that process its standing. Started from the app's screens (joining, starting a jam,
 * choosing a device are taps), stopped the moment none of the three is so.
 *
 * Not used while the phone is only in the room where the person's devices find each other:
 * that is held only while the app is in front or music plays (the playback service is then in
 * the foreground), and otherwise let go.
 */
class JamService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                // The notification's button: leave, stop steering, or end the jam.
                graph.jamsIfMade()?.stopFromNotification()
                return START_NOT_STICKY
            }
        }
        val note = intent?.getStringExtra(TITLE).orEmpty() to intent?.getStringExtra(TEXT).orEmpty()
        val leave = intent?.getStringExtra(BUTTON).orEmpty()
        val shown = notification(this, note.first, note.second, leave)
        try {
            ServiceCompat.startForeground(this, ID, shown, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: RuntimeException) {
            // Android wouldn't have it (started from the background after all): the jam goes
            // on while the app is in front, and the notification isn't there to say so.
            graph.diagnostics.note("jam: Android refused the foreground for the relay's connection")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 0x6a616d
        private const val CHANNEL = "jam"
        private const val STOP = "org.medianexus.music.jam.STOP"
        private const val TITLE = "title"
        private const val TEXT = "text"
        private const val BUTTON = "button"

        /** Shows the notification (starting the service if it isn't running), or with
         *  `title` null stops it. */
        fun update(context: Context, title: String?, text: String, button: String) {
            val app = context.applicationContext
            val intent = Intent(app, JamService::class.java)
            if (title == null) {
                app.stopService(intent)
                return
            }
            intent.putExtra(TITLE, title).putExtra(TEXT, text).putExtra(BUTTON, button)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (_: RuntimeException) {
                // (Started from the background: Android's rule. Only a tap starts one.)
            }
        }

        private fun notification(context: Context, title: String, text: String, button: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.jam_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.jam_channel_detail)
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, JamService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_bars)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, button, stop)
                .build()
        }
    }
}
