package org.medianexus.music.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.medianexus.music.MainActivity
import org.medianexus.music.R
import org.medianexus.music.graph

/**
 * What keeps the app running while downloads are under way, and brings it back to finish
 * them after it has been stopped, killed, or the phone restarted, on every version of Android
 * from 9. (The downloading itself is [Downloads]'.) Android has tightened what an app may do
 * in the background with nearly every version, so this is two things:
 *
 * - **A job** ([DownloadsJob]), kept by Android's job scheduler through a restart of the phone,
 *   that runs whenever there are downloads waiting and the network they may use (Wi-Fi only,
 *   or any) is there. Android itself starts the app for it, stops it when the network goes,
 *   and runs it again when the network is back. On Android 14 and later, when the person has
 *   just asked for the downloads in the app, it is a **user-initiated data transfer**: no time
 *   limit, its own notification, and not held back by the app's standby bucket. Asked for
 *   while the app isn't on screen (a downloaded playlist topping itself up), Android allows
 *   only an ordinary job, which runs for minutes at a time and is run again until the work is
 *   done.
 * - **Before Android 14**, which has no such jobs, a **foreground service** of the data-sync
 *   kind ([DownloadsService]), started from the app when the person asks (an app on screen may
 *   start one on every version), holds the app up for as long as downloads are arriving. The
 *   job stands behind it, for after a restart: from Android 9 to 11 it starts the service
 *   again, and on 12 and 13, which don't let an app in the background start one, the job does
 *   the downloading itself, a few minutes at a time.
 *
 * Media3's own download service was the obvious frame, and was not used: in 1.11.1 it asks
 * for no restart at all on Android 12 and later (`DownloadService.onCreate`: a scheduler only
 * below API 31), so downloads stopped there stayed stopped until the app was next opened, and
 * it keeps one download manager for the whole app, where each account has its own.
 */
object DownloadKeeper {
    const val JOB_ID = 4_201
    const val NOTIFICATION_ID = 4_202
    const val CHANNEL = "downloads"

    /** Whether the app's screens are showing (only then may a user-initiated job be asked
     *  for, or a foreground service started). Set by [MainActivity]. */
    @Volatile var visible = false

    private var wanted: Triple<Boolean, Boolean, Boolean>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var retry: Runnable? = null

    /**
     * There are downloads to do (`busy`), and they may use Wi-Fi only (`wifiOnly`): the job is
     * scheduled for them, and before Android 14 the service started if the app is on screen.
     * No work: nothing is held up, and the job is let go.
     */
    fun update(context: Context, busy: Boolean, wifiOnly: Boolean) {
        val app = context.applicationContext
        // Called whenever the downloads' state is published (twice a second while one is
        // arriving): only a change is acted on.
        val now = Triple(busy, wifiOnly, visible)
        if (wanted == now) return
        val again = wanted?.let { it.first != busy || it.second != wifiOnly } ?: true
        retry?.let(main::removeCallbacks)
        retry = null
        val jobs = app.getSystemService(JobScheduler::class.java) ?: return
        if (!busy) {
            wanted = now
            // A job running now finishes itself when the manager is idle (see DownloadsJob).
            if (again) runCatching { jobs.getPendingJob(JOB_ID)?.let { if (!running) jobs.cancel(JOB_ID) } }
            DownloadsService.stop(app)
            return
        }
        val pending = runCatching { jobs.getPendingJob(JOB_ID) }.getOrNull()
        val userInitiated = Build.VERSION.SDK_INT >= 34 && visible
        // Already asked for, the same way, or better (a user-initiated job is never swapped
        // for an ordinary one): left as it is. Asking again would stop one that is running.
        val fine = pending != null && wantsUnmetered(pending) == wifiOnly && (!userInitiated || isUserInitiated(pending))
        var scheduled = fine
        if (!fine) {
            scheduled = runCatching { jobs.schedule(job(app, wifiOnly, userInitiated)) }.getOrDefault(JobScheduler.RESULT_FAILURE) == JobScheduler.RESULT_SUCCESS
            if (!scheduled && userInitiated) {
                // Refused (the app had just left the screen): an ordinary job, then.
                scheduled = runCatching { jobs.schedule(job(app, wifiOnly, userInitiated = false)) }.getOrDefault(JobScheduler.RESULT_FAILURE) == JobScheduler.RESULT_SUCCESS
            }
        }
        if (scheduled) {
            wanted = now
        } else {
            // Android would not take it. Not remembered as asked for: it is asked for again
            // at the next look, and in a while whether or not anything changes meanwhile
            // (downloads that are only waiting publish nothing).
            wanted = null
            runCatching { app.graph.diagnostics.note("downloads: Android would not schedule the job that carries them on; asked again in a while") }
            retry = Runnable { update(app, busy, wifiOnly) }.also { main.postDelayed(it, RETRY_MILLIS) }
        }
        if (Build.VERSION.SDK_INT < 34 && visible) DownloadsService.start(app)
    }

    /**
     * The job: kept through a restart, and run when there is a network of the kind the
     * downloads may use. **Not** `setRequiredNetworkType`, which also asks that Android has
     * validated the network as reaching the internet: a music server on a home Wi-Fi with no
     * internet beyond it would never see the job run. The network is asked for by what it
     * is (one that carries ordinary traffic; unmetered, for Wi-Fi only), and whether our own
     * server answers on it is our own question (`ServerWatch`).
     */
    internal fun job(context: Context, wifiOnly: Boolean, userInitiated: Boolean): JobInfo {
        val network = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // A VPN counts (a server reached over one is as good as any), as it does for
            // JobScheduler's own network types.
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .apply { if (wifiOnly) addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()
        return JobInfo.Builder(JOB_ID, ComponentName(context, DownloadsJob::class.java))
            .setRequiredNetwork(network)
            .setPersisted(true)
            .apply {
                if (userInitiated && Build.VERSION.SDK_INT >= 34) {
                    setUserInitiated(true)
                    setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                }
            }
            .build()
    }

    private fun wantsUnmetered(info: JobInfo): Boolean =
        info.requiredNetwork?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    /**
     * Whoever was signed in has signed out: nothing of theirs is to be carried on. The job is
     * cancelled, running or not (Android then stops it, and it lets go of the account: see
     * [DownloadsJob]), the service stopped, and nothing remembered as asked for.
     */
    fun letGo(context: Context) {
        val app = context.applicationContext
        retry?.let(main::removeCallbacks)
        retry = null
        wanted = null
        runCatching { app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        DownloadsService.stop(app)
    }

    /** How long after Android rejects a schedule it is asked for again. */
    private const val RETRY_MILLIS = 30_000L

    private fun isUserInitiated(info: JobInfo): Boolean = Build.VERSION.SDK_INT >= 34 && info.isUserInitiated

    /** Whether the job is running now. */
    @Volatile var running = false

    fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
        }
    }

    /**
     * The progress notification: how far the batch has got, in the app's own words, and never
     * a song's name (song names go to the player's notification and the car as what is
     * playing, and nowhere else outside the app). Tapping it opens the app.
     */
    fun notification(context: Context, state: DownloadsState): Notification {
        channel(context)
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (state.busy) context.getString(R.string.downloading_n_of, state.at, state.batchTotal) else context.getString(R.string.downloads)
        val text = when (state.held) {
            Held.WIFI -> context.getString(R.string.waiting_for_wifi)
            Held.NETWORK -> context.getString(R.string.waiting_for_network)
            Held.OFFLINE -> context.getString(R.string.offline_mode)
            Held.SERVER -> context.getString(R.string.waiting_for_server)
            Held.ANDROID -> context.getString(R.string.waiting_for_android)
            Held.NOTHING -> null
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(1000, (state.progress * 1000).toInt(), state.held == Held.NOTHING && state.active.none { it.state is SongState.Fetching } && state.busy)
            .build()
    }
}

/**
 * The job that downloads are done under when the app's screens aren't what holds it up (see
 * [DownloadKeeper]). It waits for the signed-in account's downloads to settle, keeping the
 * notification up to date, and ends when nothing is left to do. Stopped by Android (the
 * network went, or its time ran out), it asks to be run again if there is still work.
 */
class DownloadsJob : JobService() {
    private val scope = MainScope()
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        DownloadKeeper.running = true
        val graph = applicationContext.graph
        graph.diagnostics.note(
            "downloads: carried on by Android's job scheduler" +
                if (Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob) " (a user-initiated transfer)" else "",
        )
        if (Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob) {
            // Asked of a user-initiated job straight away; shown when notifications are allowed.
            setNotification(params, DownloadKeeper.NOTIFICATION_ID, DownloadKeeper.notification(this, org.medianexus.music.downloads.DownloadsState()), JOB_END_NOTIFICATION_POLICY_REMOVE)
        } else if (Build.VERSION.SDK_INT < 31) {
            // Android 9 to 11 let a job start a foreground service, which outlasts a job's
            // few minutes. Android 12 and 13 don't: the job carries on itself.
            DownloadsService.start(this, fromJob = true)
        }
        work = scope.launch {
            graph.accounts.restored()
            // Whoever is signed in, as that changes: a job must not go on holding the
            // downloads of an account that has signed out.
            graph.accounts.state.collectLatest { account ->
                val downloads = account?.downloads
                if (downloads == null) {
                    finish(params)
                    return@collectLatest
                }
                // The job is running (again): the downloads may, on the network it was given.
                downloads.useNetwork(params.network)
                downloads.stoppedByAndroid(false)
                downloads.state.collect { state ->
                    if (Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob) {
                        setNotification(params, DownloadKeeper.NOTIFICATION_ID, DownloadKeeper.notification(this@DownloadsJob, state), JOB_END_NOTIFICATION_POLICY_REMOVE)
                    }
                    // (Not before the manager has read what was waiting: a cold start would find
                    // nothing yet.)
                    if (state.settled && (!state.busy || state.held == Held.OFFLINE)) {
                        downloads.useNetwork(null)
                        finish(params)
                    }
                }
            }
        }
        return true
    }

    /**
     * Android has given the job another network (the one it had has gone, a better one has
     * come). With Wi-Fi only off, transfers started from now go out on it; with Wi-Fi only
     * on they are bound to the unmetered network the app asks for itself, whatever the job
     * has. (It used to be ignored: the job went on with a network it no longer had.)
     */
    override fun onNetworkChanged(params: JobParameters) {
        val graph = applicationContext.graph
        graph.accounts.state.value?.downloads?.useNetwork(params.network)
        graph.diagnostics.note("downloads: Android gave the job another network")
    }

    private fun finish(params: JobParameters) {
        DownloadKeeper.running = false
        work?.cancel()
        jobFinished(params, false)
    }

    /**
     * Android says stop (the network the job was given has gone, its time is up, the phone
     * wants the memory, or the job was cancelled). The transfers stop with it, now: they are
     * paused until the job is run again, or the app is opened. Left running, they would carry
     * on in a process Android has told to stop, on whatever network is the default.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        DownloadKeeper.running = false
        work?.cancel()
        val reason = if (Build.VERSION.SDK_INT >= 31) params.stopReason else -1
        val graph = applicationContext.graph
        val downloads = graph.accounts.state.value?.downloads
        downloads?.stoppedByAndroid(true)
        downloads?.useNetwork(null)
        graph.diagnostics.note("downloads: the job was stopped by Android (reason $reason); they wait until it runs again")
        // Run again later if there is still something to fetch.
        return downloads?.state?.value?.busy == true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Before Android 14: a foreground service of the data-sync kind, holding the app up while
 * downloads arrive, with the progress notification (see [DownloadKeeper]). Stops itself when
 * there's nothing left to do, or when Android says its time is up (Android 15's six hours a
 * day for data-sync services; not met here, as 15 and later use the job).
 */
class DownloadsService : Service() {
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = applicationContext.graph
        val first = DownloadKeeper.notification(this, graph.accounts.state.value?.downloads?.state?.value ?: DownloadsState())
        try {
            ServiceCompat.startForeground(this, DownloadKeeper.NOTIFICATION_ID, first, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: RuntimeException) {
            // Not allowed from here (the background, on Android 12 and 13): the job does it.
            graph.diagnostics.note("downloads: Android would not let the service into the foreground (${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        scope.launch {
            graph.accounts.restored()
            // Whoever is signed in, as that changes: signed out, there is nothing to hold up.
            graph.accounts.state.collectLatest { account ->
                val downloads = account?.downloads
                if (downloads == null) {
                    stopSelf()
                    return@collectLatest
                }
                // Held up by the service: whatever Android stopped a job for no longer stands.
                downloads.stoppedByAndroid(false)
                downloads.state.collect { state ->
                    if (state.settled && (!state.busy || state.held == Held.OFFLINE)) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)?.notify(DownloadKeeper.NOTIFICATION_ID, DownloadKeeper.notification(this@DownloadsService, state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 and later, after six hours of data sync in a day. Not reached in
        // practice (15 and later download under the job), but it must not go unanswered.
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile private var started = false

        fun start(context: Context, fromJob: Boolean = false) {
            if (Build.VERSION.SDK_INT >= 34) return
            if (started && !fromJob) return
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, DownloadsService::class.java))
                started = true
            } catch (_: RuntimeException) {
                // Not allowed now; the job carries the work.
            }
        }

        fun stop(context: Context) {
            if (!started) return
            started = false
            context.stopService(Intent(context, DownloadsService::class.java))
        }
    }
}
