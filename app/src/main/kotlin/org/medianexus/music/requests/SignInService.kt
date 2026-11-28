package org.medianexus.music.requests

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
 * A foreground service of the data-sync kind gives the app its standing for the whole of
 * the wait (five minutes at the most), with a quiet notification that says so. (The short
 * kind would have needed no permission of its own, but Android gives it three minutes, and
 * a sign-in with a second factor can take longer; the data-sync permission is held for
 * downloads already. Downloads use this kind only before Android 14, so they never share
 * the six hours a day Android 15 and later allow it.) It holds nothing itself: the wait is
 * [Requests]'s, in the app's process. Started when a sign-in begins, which is a tap on the
 * app's own screen; stopped when the last sign-in under way has ended.
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, ID, notification(this, For.entries.getOrElse(intent?.getIntExtra(FOR, 0) ?: 0) { For.REQUESTS }), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    /** Android 15 and later: the app's six hours a day of data-sync are used up. */
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

    /** Whose sign-in is held, and so the notification's words and channel: Lidarr's in the
     *  browser for requests, or the Nexus server's in the app. */
    enum class For(val channel: String, val channelName: Int, val words: Int) {
        REQUESTS("sign-in", R.string.requests_sign_in_channel, R.string.requests_sign_in_waiting),
        NEXUS("nexus-sign-in", R.string.nexus_sign_in_channel, R.string.nexus_sign_in_waiting),
    }

    companion object {
        private const val ID = 0x7369676e
        private const val FOR = "for"

        private val lock = Any()

        /** How many sign-ins are under way, in every account. */
        private var held = 0
        private var running: SignInService? = null

        /**
         * Keeps the app running for a sign-in that has begun (from a tap: Android lets only
         * an app in front start this). What it returns says whether the service is running
         * now, and lets it go, once, when that sign-in has ended.
         *
         * **An ordinary start, not a foreground one**: the app's screen is in front, where
         * that is allowed, and the service makes itself foreground when it runs. A start
         * promised as foreground would let Android end the app if the main thread were late
         * to keep the promise, which 0.6.0 could not do; this way the worst is a sign-in
         * that goes on as it did in 0.6.0.
         */
        fun hold(context: Context, holding: For = For.REQUESTS): Requests.Hold {
            val app = context.applicationContext
            synchronized(lock) { held++ }
            try {
                app.startService(Intent(app, SignInService::class.java).putExtra(FOR, holding.ordinal))
            } catch (_: RuntimeException) {
                // (Started from the background after all: Android's rule. The sign-in goes on
                // without it.)
            }
            val once = AtomicBoolean(false)
            return Requests.Hold(running = { synchronized(lock) { running != null } }) {
                if (once.compareAndSet(false, true)) {
                    synchronized(lock) {
                        held--
                        if (held == 0) running?.quit()
                    }
                }
            }
        }

        private fun notification(context: Context, holding: For): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(holding.channel) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(holding.channel, context.getString(holding.channelName), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
            // (A request code of its own: the player's notification opens the same screen with
            // code 0, and the first made of two with the same code lends the other its flags.)
            val open = PendingIntent.getActivity(
                context, ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, holding.channel)
                .setSmallIcon(R.drawable.ic_bars)
                .setContentTitle(context.getString(holding.words))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
