package org.medianexus.music.requests

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
import java.util.concurrent.atomic.AtomicBoolean
import org.medianexus.music.MainActivity
import org.medianexus.music.R

/**
 * **Keeps the app running while a sign-in waits for the browser to come back.** The browser
 * comes back to a door on the phone's loopback address ([BrowserReturn]), which only a
 * running app can answer. With the browser in front the app is out of sight, and after a
 * few seconds to a minute Android freezes an app that is out of sight and has nothing in
 * the foreground: the door is still open, the browser's knock is let in by the system and
 * nobody answers it, so the browser waits on a blank page until it gives up. That is what a
 * real sign-in, which takes longer than the stand-ins' did, ran into in 0.6.0.
 *
 * A foreground service of the short kind (Android 14 and later: three minutes at the most,
 * then Android asks for it to stop, and it does) gives the app its standing while the
 * sign-in waits, with a quiet notification that says so. It holds nothing itself: the wait
 * is [Requests]'s, in the app's process. Started when a sign-in begins, which is a tap on
 * the app's own screen; stopped when the last sign-in under way has ended.
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            ServiceCompat.startForeground(this, ID, notification(this), type)
        } catch (_: RuntimeException) {
            // Android wouldn't have it: the sign-in goes on as it did before, and is
            // answered only while the app is in front.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        synchronized(lock) {
            latest = startId
            // (Every sign-in ended before this came in: nothing to wait for.)
            if (held == 0) quit() else running = this
        }
        return START_NOT_STICKY
    }

    /** Android's three minutes for a short service are up (Android 14). */
    override fun onTimeout(startId: Int) {
        synchronized(lock) { quit() }
    }

    /** The same from Android 15. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        synchronized(lock) { quit() }
    }

    override fun onDestroy() {
        synchronized(lock) { if (running === this) running = null }
        super.onDestroy()
    }

    /** Under [lock]. Stops unless a newer start has come in meanwhile. */
    private fun quit() {
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(latest)
    }

    private var latest = 0

    companion object {
        private const val ID = 0x7369676e
        private const val CHANNEL = "sign-in"

        private val lock = Any()

        /** How many sign-ins are under way, in every account. */
        private var held = 0
        private var running: SignInService? = null

        /**
         * Keeps the app running for a sign-in that has begun (from a tap: Android lets only
         * an app in front start this), and returns what lets it go, once, when that sign-in
         * has ended.
         */
        fun hold(context: Context): () -> Unit {
            val app = context.applicationContext
            synchronized(lock) { held++ }
            try {
                ContextCompat.startForegroundService(app, Intent(app, SignInService::class.java))
            } catch (_: RuntimeException) {
                // (Started from the background after all: Android's rule.)
            }
            val once = AtomicBoolean(false)
            return {
                if (once.compareAndSet(false, true)) {
                    synchronized(lock) {
                        held--
                        if (held == 0) running?.quit()
                    }
                }
            }
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.requests_sign_in_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_bars)
                .setContentTitle(context.getString(R.string.requests_sign_in_waiting))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
