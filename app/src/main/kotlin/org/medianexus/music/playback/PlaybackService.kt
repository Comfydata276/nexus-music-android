package org.medianexus.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.AccountGraph
import org.medianexus.music.BuildConfig
import org.medianexus.music.Graph
import org.medianexus.music.MainActivity
import org.medianexus.music.R
import org.medianexus.music.SessionState
import org.medianexus.music.graph
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Http
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptSongs
import org.medianexus.music.store.ListPrefs

/**
 * The playback service: one ExoPlayer in a Media3 `MediaLibraryService`. The app's screens,
 * the notification and lock screen, headset buttons and the car all control the same player
 * through its session; the car also browses the library through it.
 *
 * It must work with no screen: a car starts it cold. So everything it needs comes from the
 * app's [Graph], it waits for the saved sign-in to be read before deciding that nobody is
 * signed in, and it brings back the saved queue by itself.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var graph: Graph
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    private lateinit var session: MediaLibrarySession
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())

    /**
     * One account's turn at the player: everything that is theirs and must not outlive them.
     * Each sign-in is a new generation (`number`). The car's requests, voice lookups and
     * anything else begun for this account run in `scope` and are dropped when it goes;
     * search results are kept here, so they go too; and the player's reads carry the number
     * and are refused once it is no longer current (see [Leases]).
     */
    private inner class Era(val number: Long, val account: AccountGraph) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tree = CarTree(account.library, words)
        val kept = KeptSongs(account.keptSongsDir, database) { graph.settings.value.cacheLimitMb * 1024L * 1024L }
        val searches = SearchResults()
        /** Who made each search lately: told when one is to be made again. */
        val searchedBy = java.util.concurrent.ConcurrentHashMap<String, MediaSession.ControllerInfo>()
        /** The account's saved queue, for this service's turn at it: nothing is written to
         *  it until what was read from it is in the player (see [restore]). */
        val queue = KeptQueue(account.queueFiles, graph.queueSaver)
        /** Done once the saved queue has been read and put back (see [restore]). */
        val restoring = CompletableDeferred<Unit>()
    }

    /** The account signed in, on the main thread. */
    private var era: Era? = null
    /** The same, for the player's loading threads. */
    @Volatile private var current: Era? = null
    private var eras = 0L
    private val account: AccountGraph? get() = era?.account
    private val database by lazy { StandaloneDatabaseProvider(this) }

    private val needsConverting = NeedsConverting()

    /** What the server is told about listening: "now playing", and each play that counts. */
    private val plays = PlayReporter(nowPlaying = ::reportNowPlaying, played = ::scrobble)
    private var failures = 0

    /** The try at the server that has been put off (see [Recovery.Retry]); null when the
     *  player isn't waiting for the server. */
    private var waitingFor: Recovery.Retry? = null
    private var retry: Job? = null

    /** The player is being emptied because the account is going: nothing to save. */
    private var leaving = false
    private var lastSaveAt = 0L

    private lateinit var words: CarWords

    override fun onCreate() {
        super.onCreate()
        graph = applicationContext.graph
        words = CarWords(
            home = getString(R.string.tab_home),
            playlists = getString(R.string.playlists),
            library = getString(R.string.tab_library),
            albums = getString(R.string.albums),
            artists = getString(R.string.artists),
            genres = getString(R.string.genres),
            likedSongs = getString(R.string.liked_songs),
            recentlyPlayed = getString(R.string.recently_played),
            recentlyAdded = getString(R.string.recently_added),
            songs = getString(R.string.songs),
            others = getString(R.string.others),
            shuffle = getString(R.string.shuffle),
            mostPlayed = getString(R.string.most_played),
            aToZ = getString(R.string.sort_a_to_z),
            songCount = { resources.getQuantityString(R.plurals.song_count, it, it) },
            albumCount = { resources.getQuantityString(R.plurals.album_count, it, it) },
        )

        // One HTTP client for everything, so audio shares the warm connections; for audio,
        // with a shorter limit on a read that has stalled.
        val http = OkHttpDataSource.Factory(Http.forStreams(graph.http))
        val sources = SongDataSource.Factory(Leases(), http)
        // Conversions are MP3 at a constant bit rate with no index: allow seeking in them by
        // bit rate, even before their length is known (see SongDataSource).
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val mediaSources = DefaultMediaSourceFactory(sources, extractors).setLoadErrorHandlingPolicy(RideOut())
        exo = ExoPlayer.Builder(this, renderers(), mediaSources)
            // Handling audio focus is what makes it give way to a navigation prompt or a call.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val queue = Queue().apply {
            shuffle = graph.settings.value.shuffle
            repeat = graph.settings.value.repeat.coerceIn(Repeat.OFF, Repeat.ONE)
        }
        player = QueuePlayer(
            exo, queue, CarMedia::metadata, changed = { queueChanged() }, explain = ::explain,
            // Everyone but the app's own screens names a queue entry by its place.
            byPlace = { session.controllerForCurrentRequest?.let { callerOf(session, it) != Caller.OWN_INTERFACE } ?: false },
        )
        exo.addListener(Watcher())
        // Debug builds log what the player does (track joins, underruns, errors): how
        // gapless joins and seeking are checked on the emulator, which can't record sound.
        if (BuildConfig.DEBUG) exo.addAnalyticsListener(EventLogger("NexusPlayer"))

        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(openApp())
            .setMediaButtonPreferences(buttons())
            // An error answering the car (nobody signed in) stays on its screen until put
            // right, rather than flashing up and going.
            .setLibraryErrorReplicationMode(MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_FATAL)
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_bars) })

        // Who is signed in decides everything else.
        scope.launch {
            combine(graph.session.state, graph.accounts.state) { state, account -> state to account }
                .distinctUntilChanged()
                .collect { (state, account) -> signedIn(state, account) }
        }
        // The limit on kept songs applies the moment it is changed in Settings.
        scope.launch {
            graph.settings.state.map { it.cacheLimitMb }.distinctUntilChanged().drop(1).collect {
                val kept = era?.kept ?: return@collect
                graph.scope.launch(Dispatchers.IO) { kept.enforce() }
            }
        }
        main.postDelayed(ticker, TICK_MILLIS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    // ---- What started the player, and whether Android let it into the foreground ------------
    //
    // Noted for Settings' Diagnostics page (see org.medianexus.music.Diagnostics): on a real
    // phone, started cold by a car or a headset, this is all there is to go on afterwards.

    /** When this service was made, and what first asked for it. */
    private val madeAt = android.os.SystemClock.elapsedRealtime()
    private var startedBy: String? = null
    private var foregroundNoted = false

    private fun noteStartedBy(what: String) {
        if (startedBy != null) return
        startedBy = what
        val cold = madeAt - android.os.Process.getStartElapsedRealtime() < COLD_START_MILLIS
        graph.diagnostics.note("player started by $what, with the app ${if (cold) "not running" else "already running"}", now = true)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        noteStartedBy(
            when (intent?.action) {
                SERVICE_INTERFACE, MediaSessionService.SERVICE_INTERFACE -> "a Media3 controller connecting"
                "android.media.browse.MediaBrowserService" -> "a media browser connecting (a car, or the system)"
                else -> "something binding to it"
            },
        )
        return super.onBind(intent)
    }

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        val updated = super.onUpdateNotificationAsync(session, startInForegroundRequired)
        if (startInForegroundRequired) {
            updated.addListener({
                val failure = runCatching { updated.get() }.exceptionOrNull()?.let { it.cause ?: it }
                if (failure != null) {
                    // (Media3 has caught it; if it is Android refusing the foreground, its
                    // listener is told too: see onCreate.)
                    graph.diagnostics.note("foreground: Android refused it (${failure.javaClass.simpleName})", now = true)
                } else if (!foregroundNoted) {
                    foregroundNoted = true
                    graph.diagnostics.note("foreground: entered ${android.os.SystemClock.elapsedRealtime() - madeAt} ms after the player was made")
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(this))
        }
        return updated
    }

    /**
     * The app was swiped away from the recent apps. Media3 stops the service unless a song
     * is sounding at that very moment, which ends the music for good if it was only
     * buffering, or had given way to a navigation prompt. Only a pause by the person ends it
     * here (see [Recovery.carriesOnWhenSwipedAway]).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Recovery.carriesOnWhenSwipedAway(exo.playWhenReady, exo.playbackState, waitingFor != null)) return
        pauseAllPlayersAndStopSelf()
    }

    // ---- Started by a button, with the app not running --------------------------------------

    /**
     * Set when the service was started "in the foreground" for a media button and has not
     * yet shown the notification that keeps that promise. Media3 shows it when playback
     * starts. If there turns out to be nothing to play, the promise is kept deliberately and
     * the service stopped (see [standDown]); left alone, the system kills the whole app with
     * ForegroundServiceDidNotStartInTimeException.
     */
    private var promised = false
    private val promiseCheck = Runnable { if (promised) standDown() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        noteStartedBy(
            when {
                intent?.action == Intent.ACTION_MEDIA_BUTTON -> "a media button" + (keyOf(intent)?.let { " ($it)" } ?: "")
                intent?.action == null -> "a start request"
                else -> "a start request (${intent.action?.substringAfterLast('.')?.take(40)})"
            },
        )
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && !isPlaybackOngoing) {
            promised = true
            // Whatever happens to the request (a reply that never comes, a command Media3
            // drops), the promise is looked at again well inside the system's time limit.
            main.removeCallbacks(promiseCheck)
            main.postDelayed(promiseCheck, PROMISE_MILLIS)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** The button a media button intent carries, by its key's name. */
    private fun keyOf(intent: Intent): String? = runCatching {
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT))?.keyCode?.let { android.view.KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
    }.getOrNull()

    /**
     * Ends a start that has nothing to play, the way Media3's own service does when it has
     * no session to give (`stopSelfSafely`): enter the foreground with a notification that
     * is never shown (the system holds a new foreground notification back for ten seconds),
     * leave it at once, and stop. Does nothing if playback did start.
     */
    private fun standDown() {
        promised = false
        main.removeCallbacks(promiseCheck)
        if (isPlaybackOngoing) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
            if (manager.getNotificationChannel(channel) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(channel, getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val notification = NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_bars)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .build()
            // Before Android 10 a foreground service has no type.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, STAND_DOWN_NOTIFICATION, notification, type)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: IllegalStateException) {
            // Not allowed into the foreground at all: then nothing was promised either.
        }
        graph.diagnostics.note("started by a button with nothing to play: stopped cleanly", now = true)
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "started by a button with nothing to play: stopped cleanly")
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        // Handed to the app's writer, which finishes it whatever becomes of this service.
        save()
        scope.cancel()
        session.release()
        player.release()
        exo.release()
        leave()
        super.onDestroy()
    }

    /**
     * Where the audio processors for crossfade, levelling, the equaliser and mono will go:
     * `DefaultRenderersFactory.buildAudioSink` with them in its chain.
     */
    private fun renderers() = object : DefaultRenderersFactory(this) {
        override fun buildAudioRenderers(
            context: android.content.Context,
            extensionRendererMode: Int,
            mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: androidx.media3.exoplayer.audio.AudioSink,
            eventHandler: Handler,
            eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
            out: ArrayList<androidx.media3.exoplayer.Renderer>,
        ) {
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
            // Media3's renderer, with the trim for MP3 put right (see Mp3Trim).
            val at = out.indexOfFirst { it is androidx.media3.exoplayer.audio.MediaCodecAudioRenderer }
            if (at >= 0) out[at] = GaplessAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) { graph.diagnostics.note(it) }
        }

        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Debug builds only: a tap that can write what goes to the speaker to a file
                // (see DebugAudio); none in a release build.
                .setAudioProcessors(DebugAudio.processors(context))
                .build()
    }

    // ---- Who is signed in ---------------------------------------------------------------------

    private fun signedIn(state: SessionState, now: AccountGraph?) {
        if (state == SessionState.Restoring) return
        if (now == null && state is SessionState.SignedIn) return // its objects are on their way
        // Nothing has changed, once the session has been told the first time: a cold start
        // with nobody signed in is "no change" from the start, and must still be said.
        if (now === era?.account && told) return
        told = true
        // Someone else, or nobody: what was playing was theirs.
        if (era != null) {
            save()
            leave()
            // Emptying the player isn't a change to the queue that was theirs: it stays saved
            // for when they sign in again.
            leaving = true
            player.clear()
            leaving = false
            plays.reset()
        }
        if (now == null) {
            // A standing error in words, there before anything is asked: a car that only
            // shows the player, and never opens a folder, still says why nothing plays.
            session.setPlaybackException(signInNeeded())
            reload()
            return
        }
        session.setPlaybackException(null)
        // And the same error as a folder gave it to a car: Media3 keeps that one standing
        // until a folder next opens, which a car showing only the player never asks for.
        session.clearReplicatedLibraryError()
        val next = Era(++eras, now)
        era = next
        current = next
        reload()
        // The like button follows the likes, wherever they're changed.
        next.scope.launch { now.likes.liked.collect { updateButtons() } }
        // The list the queue came from changed (a fresh copy from the server, a song unliked
        // from Liked songs): the queue follows it.
        next.scope.launch {
            now.pages.changes.collect { (key, value) ->
                if (key == player.queue.source) songsIn(value)?.let { player.follow(key, it) }
            }
        }
        next.scope.launch { restore(next) }
    }

    /**
     * Back to where things were, paused, ready for "play" from anywhere. Until the saved
     * queue is in the player nothing is saved (see [KeptQueue]): the player's queue is empty
     * while the saved one is being read and then made ready, and an account that goes, or a
     * service destroyed, in that time must not have "empty" written over its queue. (This
     * runs in the account's scope: if the account goes meanwhile it stops where it is, and
     * the queue is never marked as put back.)
     */
    private suspend fun restore(era: Era) {
        val saved = era.queue.read()
        if (saved != null) {
            // Made ready off the main thread: it can be thousands of songs.
            val revived = withContext(Dispatchers.Default) { player.revive(saved) }
            player.restore(revived, (saved.position * 1000).toLong())
        }
        // Only now: the saved queue is the player's, or there was none.
        era.queue.settle()
        era.restoring.complete(Unit)
        // The screens are told the queue is as it will be: until now, "nothing playing" only
        // meant "not read yet".
        publishExtras()
        // Something started while the saved queue was being read is the queue now.
        if (!player.queue.isEmpty) save()
    }

    /**
     * Has the car look again at every folder it has asked for lately, and the tabs: after a
     * sign-in they have something in them, after a sign-out they don't, and a car sitting on
     * "sign in" (or on someone else's albums) would otherwise sit there until its screen was
     * closed and opened again.
     */
    private fun reload() {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "who is signed in changed: the car is asked to look again at ${asked.size + 1} folders")
        for (id in asked + CarIds.ROOT) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
    }

    /** The folders the car has asked for lately, most recent last. */
    private val asked = LinkedHashSet<String>()

    private fun askedFor(parentId: String) {
        asked.remove(parentId)
        asked.add(parentId)
        if (asked.size > MOST_ASKED) asked.remove(asked.first())
    }

    /** Whether the session has been told, at least once, who is signed in (or that nobody is). */
    private var told = false

    /**
     * The account's turn is over: its generation is no longer current (so a read that opens
     * from now on is refused), whatever was running for it is dropped, and its kept songs
     * close as soon as the reads still using them have.
     */
    private fun leave() {
        val old = era ?: return
        stopWaiting()
        current = null
        era = null
        old.scope.cancel()
        // Off the main thread: closing writes the cache's index.
        graph.scope.launch(Dispatchers.IO) { old.kept.close() }
    }

    /** Gives the player's reads what they need from the account they were made for. */
    private inner class Leases : SongLeases {
        override fun generation(): Long = current?.number ?: 0

        override fun lease(generation: Long): SongLease {
            val era = current
            if (era == null || era.number != generation) throw IOException("The account this song was for has signed out")
            val cache = if (graph.settings.value.cacheLimitMb > 0) era.kept.acquire() else null
            return object : SongLease {
                override val cache = cache
                // The same switch the API client obeys: offline mode stops the player
                // fetching too, not only the pages.
                override val offline = graph.offline.on
                override fun resolve(songId: String): SongWhere = resolve(era, songId)
                override fun release() {
                    if (cache != null) era.kept.release()
                }
            }
        }
    }

    /** The songs of a kept page that a queue can come from: an album, a playlist, Liked songs. */
    private fun songsIn(page: Any?): List<Song>? = when (page) {
        is org.medianexus.music.net.Album -> page.songs
        is org.medianexus.music.net.Playlist -> page.songs
        is org.medianexus.music.net.Found -> page.songs
        else -> null
    }

    private fun signInNeeded(): PlaybackException =
        PlaybackException(getString(R.string.car_sign_in), null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, signInButton())

    /** A "Sign in" button for the car's error screen, which opens the app on the phone. */
    private fun signInButton() = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.sign_in))
        putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp())
    }

    /** An "Open Nexus Music" button for the car's error screen. */
    private fun openAppButton() = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.open_app))
        putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp())
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- Where songs come from ------------------------------------------------------------------

    /** Called on the player's loading threads, for a read that belongs to `era`. */
    private fun resolve(era: Era, songId: String): SongWhere {
        val api = era.account.api
        val song = player.songFor(songId)
        val suffix = song?.suffix?.lowercase().orEmpty()
        val native = (suffix.isEmpty() || suffix in NATIVE_SUFFIXES) && songId !in needsConverting
        val form = streamForm(native, graph.settings.value.bitrate, suffix)
        // Here, in later versions, a downloaded copy is chosen first, then one kept from
        // fetching ahead; only then the server.
        val least = if (form.converted && song != null) leastBytes(song.duration, form.bitrate, song.bitRate) else 0
        return SongWhere(form, cacheKey(songId, form), least) { offset -> api.streamUrl(songId, form.bitrate, offset) }
    }

    /**
     * Rides out a bad connection: a stream that fails is asked for again, a little further
     * apart each time, for about fifteen seconds, before the player stops to wait for the
     * server (see `onPlayerError`). The server
     * saying no (a 4xx) is not a connection problem and isn't retried.
     */
    private class RideOut : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val error = loadErrorInfo.exception
            if (error is StreamFailure && (error.refused || error.offline)) return C.TIME_UNSET
            if (error !is IOException) return super.getRetryDelayMsFor(loadErrorInfo)
            return RETRY_WAITS.getOrElse(loadErrorInfo.errorCount - 1) { RETRY_WAITS.last() }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int) = RETRY_WAITS.size
    }

    // ---- The queue changing -----------------------------------------------------------------

    /**
     * Counts the times something was asked to start playing. A start that has to look
     * something up first (the car choosing a song, a spoken request) takes the number as it
     * begins and is dropped if another start has begun by the time it is ready: a slow answer
     * to the car must not replace what was chosen on the phone meanwhile.
     */
    private var starts = 0L

    private fun queueChanged() {
        val queue = player.queue
        // Shuffle and repeat are settings, and the list being played remembers its shuffle.
        val settings = graph.settings.value
        if (settings.shuffle != queue.shuffle || settings.repeat != queue.repeat) {
            val source = queue.source
            graph.settings.update { s ->
                val lists = if (source.isNotEmpty() && s.shuffle != queue.shuffle) {
                    s.lists + (source to (s.lists[source] ?: ListPrefs()).copy(shuffle = queue.shuffle, at = System.currentTimeMillis() / 1000))
                } else {
                    s.lists
                }
                s.copy(shuffle = queue.shuffle, repeat = queue.repeat, lists = lists)
            }
        }
        publishExtras()
        player.refresh()
        updateButtons()
        save()
    }

    /**
     * What only the app's own screens read, in the session's extras: how long "Next in
     * queue" is and how many songs are to come in all (for the queue sheet, which is shown
     * the nearest few hundred), the list the queue came from and the place in it of
     * the song playing (to mark its row, and no other row of the same song), and whether the
     * server is converting the song (for the format line).
     */
    private fun publishExtras() {
        val queue = player.queue
        val extras = Bundle().apply {
            putInt(EXTRA_QUEUED_AHEAD, queue.queuedAhead())
            putInt(EXTRA_AHEAD, player.ahead)
            putString(EXTRA_SOURCE, queue.source)
            putInt(EXTRA_ORIGIN, queue.currentOrigin() ?: -1)
            putBoolean(EXTRA_CONVERTED, queue.current()?.id?.let { it in needsConverting } == true)
            putBoolean(EXTRA_SETTLED, era?.restoring?.isCompleted == true)
            // The song these extras are about: the player's own word on what's playing can
            // reach a controller before or after them.
            putString(EXTRA_SONG, queue.current()?.id.orEmpty())
        }
        val before = shownExtras
        if (before != null && before.keySet() == extras.keySet() && extras.keySet().all { before.get(it) == extras.get(it) }) return
        shownExtras = extras
        session.setSessionExtras(extras)
    }

    private var shownExtras: Bundle? = null

    /** Hands the queue as it stands to the app's one writer of saved queues, which writes
     *  the newest it has been given, off the main thread, in order. */
    private fun save() {
        if (leaving) return
        val kept = era?.queue ?: return
        val queue = player.queue
        // Something the person started while the saved queue was still being put back is the
        // queue now (the saved one is not put over it: see QueuePlayer.restore).
        if (!kept.settled && !queue.isEmpty) kept.settle()
        val position = exo.currentPosition / 1000.0
        val stamp = System.currentTimeMillis() / 1000
        // The queue is copied for the writer only when it has changed; while it only plays,
        // where it has got to is all that's handed over.
        kept.save(queue.revision, queue.cursor, position, stamp) { queue.saved(position, stamp) }
        lastSaveAt = System.currentTimeMillis()
    }

    // ---- The car's buttons ------------------------------------------------------------------

    private fun buttons(): List<CommandButton> {
        val queue = if (::player.isInitialized) player.queue else null
        val liked = queue?.current()?.let { song -> account?.likes?.isLiked(song.id) } == true
        val shuffle = queue?.shuffle == true
        val repeat = queue?.repeat ?: Repeat.OFF
        return listOf(
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (liked) R.string.remove_from_liked else R.string.add_to_liked))
                .setSessionCommand(LIKE)
                .build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(if (shuffle) R.string.shuffled else R.string.shuffle))
                .setSessionCommand(SHUFFLE)
                .build(),
            CommandButton.Builder(
                when (repeat) {
                    Repeat.ALL -> CommandButton.ICON_REPEAT_ALL
                    Repeat.ONE -> CommandButton.ICON_REPEAT_ONE
                    else -> CommandButton.ICON_REPEAT_OFF
                },
            )
                .setDisplayName(getString(when (repeat) { Repeat.ALL -> R.string.repeat_all; Repeat.ONE -> R.string.repeat_one; else -> R.string.repeat }))
                .setSessionCommand(REPEAT)
                .build(),
        )
    }

    private var shownButtons: List<CommandButton>? = null

    private fun updateButtons() {
        if (!::session.isInitialized) return
        val now = buttons()
        if (now != shownButtons) {
            shownButtons = now
            session.setMediaButtonPreferences(now)
        }
    }

    // ---- What the player does -----------------------------------------------------------------

    private inner class Watcher : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                failures = 0
                stopWaiting()
                countPlaying()
            } else {
                // A last sample: what was heard up to the pause counts.
                plays.paused(exo.currentPosition / 1000.0, clock())
                save()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Another song: a try at the server put off for the last one is no longer wanted.
            if (waitingFor?.uid != player.queue.currentUid()) stopWaiting()
            // Rolled into the next song while playing (the song before it has had its last
            // sample, in onPositionDiscontinuity, which comes first).
            if (exo.isPlaying) countPlaying()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val from = oldPosition.positionMs / 1000.0
            val sameEntry = entryOf(oldPosition.mediaItem) != null && entryOf(oldPosition.mediaItem) == entryOf(newPosition.mediaItem)
            when {
                // Repeating one song: the play that just finished is counted, and the next
                // time round is a new play of it.
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> plays.left(from, clock(), again = sameEntry)
                sameEntry -> plays.sought(from, newPosition.positionMs / 1000.0, clock())
                else -> plays.left(from, clock())
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The end of the queue. The same song started again from the top is a new play.
            if (playbackState == Player.STATE_ENDED) plays.ended(clock())
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // "Play" with nothing to play is not playing: Media3 presses play all the same
            // when there was nothing to resume, and left like that the next queue to arrive
            // would start by itself.
            if (playWhenReady && player.queue.isEmpty) {
                exo.pause()
                if (promised) standDown()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // By kind only: an error's own message can name the server.
            val status = (error.cause as? StreamFailure)?.status?.takeIf { it > 0 }
            graph.diagnostics.note("playback error: ${error.errorCodeName}" + (status?.let { " (the server answered $it)" } ?: ""))
            val song = player.queue.current() ?: return
            when {
                undecodable(error) && needsConverting.mark(song.id) -> {
                    // The phone can't play this file as it is: have the server convert it.
                    publishExtras()
                    exo.prepare()
                }
                offline(error) -> passOver()
                network(error) -> {
                    // Not the song's fault: the player has already tried a few times. Wait
                    // where it is for the server to come back, trying now and then (with no
                    // permission to reach the home network, until it is given).
                    retryLater(SERVER_WAIT_MILLIS)
                }
                else -> passOver()
            }
        }
    }

    /**
     * Asks for the song again later, from where it stopped. If that fails too, the player's
     * error brings this round again. The try belongs to this account's turn and this entry
     * of the queue: if either has changed by the time it is due, it is dropped.
     */
    private fun retryLater(delayMs: Long) {
        val era = era ?: return
        val uid = player.queue.currentUid() ?: return
        val wanted = Recovery.Retry(era.number, uid)
        retry?.cancel()
        waitingFor = wanted
        retry = era.scope.launch {
            delay(delayMs)
            if (waitingFor != wanted) return@launch
            waitingFor = null
            if (wanted.stillStands(this@PlaybackService.era?.number, player.queue.currentUid(), exo.playerError != null)) exo.prepare()
        }
    }

    private fun stopWaiting() {
        retry?.cancel()
        retry = null
        waitingFor = null
    }

    /**
     * Gives up on the song that's playing and goes to the next, unless three in a row have
     * failed or there is no next, as Windows does. Whether it then plays is as it was: a
     * song that failed while paused, or while only being made ready, leaves the next one
     * paused too.
     */
    private fun passOver() {
        failures++
        if (failures < 3 && exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.prepare()
        }
    }

    private fun undecodable(error: PlaybackException) = error.errorCode in listOf(
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private fun network(error: PlaybackException) = error.errorCode in listOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
    ) || (error.cause as? StreamFailure)?.let { it.status >= 500 } == true

    /** Offline mode is on, and the song isn't kept on the phone. */
    private fun offline(error: PlaybackException) = (error.cause as? StreamFailure)?.offline == true

    /** The words the car, the notification and the app show for a playback error. */
    private fun explain(error: PlaybackException, song: Song?): PlaybackException? = when {
        // Android 17, a server on the home network, and no permission to reach it: waiting
        // for it to come back would be waiting for ever. Say what will put it right.
        // The player's own error is not passed on as the cause: a playback error goes to
        // every app with a controller, cause and all.
        offline(error) -> PlaybackException(getString(R.string.cant_play_offline), null, error.errorCode)
        network(error) && graph.localAccess.blocked.value -> PlaybackException(getString(R.string.car_local_network), null, error.errorCode, openAppButton())
        // Short, as Windows says it ("Reconnecting"): a car's player has a line for it under
        // the title. The phone's own Now Playing has room for the whole sentence, and is told
        // by the extra that this is the lost connection.
        network(error) -> PlaybackException(getString(R.string.lost_connection_short), null, error.errorCode, Bundle().apply { putBoolean(EXTRA_LOST, true) })
        song != null && !undecodable(error) -> PlaybackException(getString(R.string.couldnt_play, song.title), null, error.errorCode)
        // (A file the phone can't decode is about to be asked for again, converted.)
        else -> PlaybackException(null, null, error.errorCode)
    }

    /** Once a second: counts listening, and notes where playback has got to now and then. */
    private val ticker = object : Runnable {
        override fun run() {
            if (exo.isPlaying) {
                plays.tick(exo.currentPosition / 1000.0, clock())
                if (System.currentTimeMillis() - lastSaveAt >= CHECKPOINT_MILLIS) save()
            }
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    /** The clock listening is timed on: it only has to run steadily while a song plays. */
    private fun clock(): Long = android.os.SystemClock.elapsedRealtime()

    private fun entryOf(item: MediaItem?): Long? = (item?.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid

    /** The song at the cursor is playing: count it (see [PlayReporter]). */
    private fun countPlaying() {
        val uid = player.queue.currentUid() ?: return
        val song = player.queue.current() ?: return
        plays.playing(uid, song, exo.currentPosition / 1000.0, clock())
    }

    private fun reportNowPlaying(song: Song) {
        val graph = account ?: return
        if (!this.graph.settings.value.scrobble) return
        graph.scope.launch { runCatching { graph.api.scrobble(song.id, submission = false) } }
    }

    /** A play counts: tell the server, if the person wants plays reported. */
    private fun scrobble(songId: String) {
        val graph = account ?: return
        if (!this.graph.settings.value.scrobble || songId.isEmpty()) return
        // Sent now; holding plays made offline until the server is back comes with offline mode.
        graph.scope.launch { runCatching { graph.api.scrobbleAt(songId, System.currentTimeMillis()) } }
    }

    // ---- The session's callback: the car, the notification, the app's screens ----------------

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Media3 sorts callers into trusted (this app, the system, and anything allowed to
            // read notifications or control media, which is what a car's own screens are, and
            // on most phones Android Auto and the Assistant) and the rest. Of the rest, the
            // known hosts of media apps are let in by package name and signing certificate, so
            // that Android Auto is never turned away for what one phone didn't grant it. Any
            // other app gets no connection: a private library is not for it to browse. See
            // [Access] and [KnownHosts].
            val caller = callerOf(session, controller)
            noteConnection(controller, caller)
            if (caller == Caller.REFUSED) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE).add(SHUFFLE).add(REPEAT)
            // The app's own commands go to the app's own screens and nobody else: not to
            // Media3's notification controller, though it carries this app's name. (The
            // platform's session, which is what a car sees, takes its buttons and commands
            // from the notification controller's, so those it keeps in full.)
            if (caller == Caller.OWN_INTERFACE) AppCommands.all.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setMediaButtonPreferences(buttons())
                .build()
        }

        /**
         * Asked before every player command is carried out. What arrives under the
         * notification controller's name may be a tap on the notification or a headset
         * button, or may be an intent any app sent to the exported service, which Media3
         * can't tell apart: so under that name only transport is carried out, whatever the
         * intent asked for (Media3 will turn one into "play this item" if allowed).
         */
        @Deprecated("Media3 offers nothing else that is asked per command and per controller")
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            val caller = callerOf(session, controller)
            // ("Play that entry of the queue", by its place, from someone whose screen may
            // still show the places as they were a moment ago, is the player's to read
            // rightly: see QueuePlayer.entryMeant.)
            return if (Access.mayPlayer(caller, playerCommand)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_PERMISSION_DENIED
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            // Unless a folder says otherwise: folders in a grid, songs in a list.
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            graph.diagnostics.note("library opened by ${browser.packageName}" + (params?.let { " (recent: ${yes(it.isRecent)}; offline: ${yes(it.isOffline)}; suggested: ${yes(it.isSuggested)})" } ?: ""))
            val root = CarMedia.item(CarEntry(CarIds.ROOT, getString(R.string.app_name), browsable = true))
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
        }

        /**
         * A subscription to one of our folders stands, whoever is signed in. Media3's own
         * answer asks for the folder's item first and drops the subscription if that fails,
         * which with nobody signed in is always: and a car that isn't subscribed is never
         * told to look again, so it sat on "Sign in" after the person had signed in.
         */
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            if (CarIds.parse(parentId) == null) return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            // The system hands a car of the older kind the folder as it subscribes; a Media3
            // browser waits to be told there is something to fetch.
            if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // "Tap to try again", opened: the folder (or search) it stands in for, fetched
            // again; and the car is told that folder has changed, so going back finds it too.
            when (val again = CarIds.retryOf(parentId)) {
                is CarRetry.Folder -> {
                    session.notifyChildrenChanged(again.parentId, Int.MAX_VALUE, params)
                    return children(session, browser, again.parentId, page, pageSize, params)
                }
                is CarRetry.Search -> return answer({ LibraryResult.ofError(signedOut()) }) { era ->
                    try {
                        val found = inTime { era.tree.search(again.query) }
                        era.searches.put(again.query, found)
                        era.searchedBy[again.query]?.let { session.notifySearchResultChanged(it, again.query, found.size, null) }
                        LibraryResult.ofItemList(ImmutableList.copyOf(slice(found, page, pageSize).map(CarMedia::item)), params)
                    } catch (e: ApiError) {
                        if (CarTree.unreachable(e)) {
                            LibraryResult.ofItemList(ImmutableList.of(CarMedia.item(tryAgain(CarIds.retrySearch(again.query, retriesMade.incrementAndGet())))), params)
                        } else {
                            LibraryResult.ofError(trouble(browser, e))
                        }
                    }
                }
                null -> {}
            }
            askedFor(parentId)
            return children(session, browser, parentId, page, pageSize, params)
        }

        private fun children(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            answer({ LibraryResult.ofError(signedOut()) }) { era ->
                val node = CarIds.parse(parentId) ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                try {
                    val entries = inTime { era.tree.children(node) { if (era === this@PlaybackService.era) session.notifyChildrenChanged(parentId, Int.MAX_VALUE, params) } }
                        ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    LibraryResult.ofItemList(ImmutableList.copyOf(slice(entries, page, pageSize).map(CarMedia::item)), params)
                } catch (e: ApiError) {
                    // A folder never fetched, and the server out of reach: one entry that says
                    // so and offers to try again, not the car's own "Something went wrong".
                    // (Not when the phone isn't allowed to reach it: trying again can't help,
                    // and the error's button opens the app, where it can be allowed.)
                    if (CarTree.unreachable(e) && !graph.localAccess.blocked.value) {
                        LibraryResult.ofItemList(ImmutableList.of(CarMedia.item(tryAgain(CarIds.retryFolder(parentId, retriesMade.incrementAndGet())))), params)
                    } else {
                        LibraryResult.ofError(trouble(browser, e))
                    }
                }
            }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            answer({ LibraryResult.ofError(signedOut()) }) { era ->
                val node = CarIds.parse(mediaId) ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                try {
                    inTime { era.tree.item(node) }?.let { LibraryResult.ofItem(CarMedia.item(it), null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } catch (e: ApiError) {
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            answer({
                // A car asking the old way is only ever answered through this (Media3 sends
                // its search result when it is told the result has changed, and at no other
                // time): without it, a search made while signed out is never answered, and
                // the car waits out its own time limit.
                session.notifySearchResultChanged(browser, query, 0, params)
                LibraryResult.ofError(signedOut())
            }) { era ->
                era.searchedBy[query] = browser
                try {
                    val found = inTime { era.tree.search(query) }
                    era.searches.put(query, found)
                    session.notifySearchResultChanged(browser, query, found.size, params)
                    LibraryResult.ofVoid()
                } catch (e: ApiError) {
                    if (CarTree.unreachable(e) && !graph.localAccess.blocked.value) {
                        // Out of reach: the one entry that says so, and tries again when tapped.
                        val retry = listOf(tryAgain(CarIds.retrySearch(query, retriesMade.incrementAndGet())))
                        era.searches.put(query, retry)
                        session.notifySearchResultChanged(browser, query, retry.size, params)
                        return@answer LibraryResult.ofVoid()
                    }
                    // A car asking the old way gets its answer only through this: say there's
                    // nothing, rather than leave it waiting, and say why.
                    era.searches.put(query, emptyList())
                    session.notifySearchResultChanged(browser, query, 0, params)
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            answer({ LibraryResult.ofError(signedOut()) }) { era ->
                try {
                    val found = era.searches.get(query) ?: inTime { era.tree.search(query) }.also { era.searches.put(query, it) }
                    LibraryResult.ofItemList(ImmutableList.copyOf(slice(found, page, pageSize).map(CarMedia::item)), params)
                } catch (e: ApiError) {
                    if (CarTree.unreachable(e) && !graph.localAccess.blocked.value) {
                        LibraryResult.ofItemList(ImmutableList.of(CarMedia.item(tryAgain(CarIds.retrySearch(query, retriesMade.incrementAndGet())))), params)
                    } else {
                        LibraryResult.ofError(trouble(browser, e))
                    }
                }
            }

        /**
         * One item asked for: a song chosen in the car, or a spoken request. Either way a
         * whole list is queued: the song's album or playlist from it, or what was asked for.
         */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> {
            // "Tap to try again": the car is asked to load that folder (or search) again, and
            // nothing about playback changes. Before anything else, so it can't overtake a
            // choice still being looked up.
            mediaItems.singleOrNull()?.mediaId?.let(CarIds::retryOf)?.let { target ->
                tryAgainNow(target)
                return com.google.common.util.concurrent.Futures.immediateFailedFuture(UnsupportedOperationException("Not something to play"))
            }
            val mine = ++starts
            return setMediaItems(mine, controller, mediaItems, startIndex, startPositionMs)
        }

        private fun setMediaItems(mine: Long, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> =
            answer({ throw refuse(controller, signedOut()) }) { era ->
                if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.tag is QueuePlayer.SongTag }) {
                    // Our own items, handed back: play them as they are.
                    return@answer MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                }
                val tree = era.tree
                val asked = mediaItems.singleOrNull() ?: throw refuse(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play)))
                val words = asked.requestMetadata.searchQuery
                try {
                    if (words != null) {
                        val spoken = inTime { voice(era, controller, words, asked.requestMetadata.extras) }
                        if (mine != starts) throw UnsupportedOperationException("Overtaken by a newer choice")
                        return@answer spoken
                    }
                    val list = when (val chosen = CarIds.parse(asked.mediaId)) {
                        is CarNode.Track -> inTime { tree.play(chosen) }
                        // "Shuffle", at the top of a list: the whole list, shuffled.
                        is CarNode.Shuffle -> inTime { tree.shuffled(chosen.list) }
                        else -> throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.nothing_to_play)))
                    } ?: throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.couldnt_play, asked.mediaMetadata.title ?: "")))
                    // Something else was chosen while this was being looked up: that stands.
                    if (mine != starts) throw UnsupportedOperationException("Overtaken by a newer choice")
                    staged(list)
                } catch (e: ApiError) {
                    throw refuse(controller, trouble(controller, e))
                }
            }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            answer<List<MediaItem>>({ throw refuse(controller, signedOut()) }) { era ->
                val tree = era.tree
                mediaItems.mapNotNull { item ->
                    if (item.localConfiguration?.tag is QueuePlayer.SongTag) return@mapNotNull item
                    val track = CarIds.parse(item.mediaId) as? CarNode.Track
                    val song = try {
                        track?.let { tree.play(it) }?.let { it.songs[it.start] } ?: era.account.library.song(item.mediaId)
                    } catch (_: ApiError) {
                        null
                    } ?: return@mapNotNull null
                    MediaItem.Builder().setMediaId(song.id).setUri(songUri(song.id)).setTag(QueuePlayer.SongTag(song, 0)).setMediaMetadata(CarMedia.metadata(song)).build()
                }
            }

        /**
         * "Play", with nothing in the player: a headset or car button, or the system asking
         * what it would resume. The queue as it was left, and nothing else: read from the
         * phone, waited for only briefly, with no word to the server. (Looking for something
         * to play when there is nothing to carry on with is for a spoken "play music", which
         * can wait; a button that started the app cold can't.)
         *
         * With `isForPlayback` false the system only wants to know what would play, to draw
         * its "resume" card: it is told, and nothing is loaded or started.
         */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaItemsWithStartPosition> =
            scope.future {
                val era = withTimeoutOrNull(RESUME_WAIT_MILLIS) { eraOrNull() }
                val ready = era != null && withTimeoutOrNull(RESUME_WAIT_MILLIS) { era.restoring.await() } != null && era === this@PlaybackService.era
                if (!isForPlayback) {
                    val item = (if (ready) player.currentItem() else null) ?: throw UnsupportedOperationException("Nothing to resume")
                    return@future MediaItemsWithStartPosition(listOf(item), 0, exo.currentPosition)
                }
                val (items, start) = (if (ready) player.stageCurrent() else null) ?: run {
                    // Said in words to whoever asked, unless what's wrong is that nobody is
                    // signed in, which the car is already being told.
                    if (era != null) session.sendError(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play)))
                    main.post { if (promised) standDown() }
                    throw UnsupportedOperationException("Nothing to resume")
                }
                MediaItemsWithStartPosition(items, start, exo.currentPosition)
            }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            // Authorised here, where it is carried out, and not only by what the controller
            // was granted when it connected.
            if (!Access.mayCustom(callerOf(session, controller), customCommand.customAction)) {
                return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val done = when (customCommand.customAction) {
                LIKE.customAction -> {
                    player.queue.current()?.let { song -> account?.likes?.toggle(song) }
                    updateButtons()
                    true
                }
                SHUFFLE.customAction -> {
                    player.setShuffle(!player.queue.shuffle)
                    true
                }
                REPEAT.customAction -> {
                    player.cycleRepeat()
                    true
                }
                AppActions.PLAY -> {
                    // A choice made on the phone: anything still being looked up for the car
                    // has been overtaken.
                    val mine = ++starts
                    if (AppCommands.byPage(args)) playPage(mine, args) else AppCommands.handle(player, customCommand, args, ::shuffleFor)
                }
                else -> AppCommands.handle(player, customCommand, args, ::shuffleFor)
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                SessionResult(if (done) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED),
            )
        }
    }

    /**
     * Starts a list the app's screens named rather than sent (a long album, playlist or Liked
     * songs): its songs are read from the library's kept copy, where the screen read them,
     * off the main thread. Dropped if something else is chosen meanwhile.
     */
    private fun playPage(mine: Long, args: Bundle): Boolean {
        val era = era ?: return false
        val source = args.getString("source").orEmpty()
        era.scope.launch {
            val shuffle = AppCommands.shuffleOf(args, ::shuffleFor)
            // Read, and made into a queue, off the main thread: it is thousands of songs.
            val next = withContext(Dispatchers.Default) {
                era.account.library.keptSongs(source)?.takeIf { it.isNotEmpty() }?.let { AppCommands.queueFor(player, args, it, shuffle) }
            }
            if (mine != starts || era !== this@PlaybackService.era) return@launch
            if (next == null) {
                // The phone has cleared its copy away since the screen drew it. Nothing to
                // start with; the screen's next refresh fetches it again.
                if (BuildConfig.DEBUG) android.util.Log.w(TAG, "nothing kept to start $source from")
                return@launch
            }
            player.play(next)
        }
        return true
    }

    /** Who a controller is (see [Access]). */
    private fun callerOf(session: MediaSession, controller: MediaSession.ControllerInfo): Caller {
        val own = controller.uid == android.os.Process.myUid()
        return Access.caller(
            trusted = controller.isTrusted,
            // Asked of the system only for a caller that has no other standing: its package
            // name and its signing certificate, against Google's list (see KnownHosts).
            knownHost = !own && !controller.isTrusted && graph.callers.knownHost(controller.packageName, controller.uid),
            sameUid = own,
            ownPackage = controller.isPackageNameVerified && controller.packageName == packageName,
            legacy = controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            notification = session.isMediaNotificationController(controller),
        )
    }

    /** Notes who asked to connect and what became of it (see [org.medianexus.music.Diagnostics]):
     *  on a real phone in a real car this is the only account there is of whether Android
     *  Auto was let in, and why. */
    private fun noteConnection(controller: MediaSession.ControllerInfo, caller: Caller) {
        val legacy = controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
        val own = controller.uid == android.os.Process.myUid()
        val known = !own && graph.callers.knownHost(controller.packageName, controller.uid)
        val how = if (legacy) "Android's own media session and browser" else "Media3 (version ${controller.controllerVersion})"
        val outcome = when (caller) {
            Caller.REFUSED -> "refused"
            Caller.TRUSTED -> "let in"
            Caller.NOTIFICATION -> "let in as the notification's own"
            Caller.OWN_INTERFACE -> "let in as the app's own screens"
        }
        graph.diagnostics.note(
            "connection from ${controller.packageName} by $how: $outcome. Android trusts it: ${yes(controller.isTrusted)}; known host: ${yes(known)}",
            now = caller == Caller.REFUSED,
        )
    }

    private fun yes(on: Boolean) = if (on) "yes" else "no"

    /** The account signed in, once the saved sign-in has been read; null if nobody is. */
    private suspend fun eraOrNull(): Era? {
        val now = graph.accounts.restored() ?: return null
        // The collector that takes the account on may not have run yet.
        if (era?.account !== now) signedIn(graph.session.state.value, now)
        return era?.takeIf { it.account === now }
    }

    /**
     * Answers something asked of the session, for the account signed in. The work belongs
     * to that account's generation: if the account goes while it is being done, the work is
     * dropped and the answer is `signedOut`, never something of theirs handed on afterwards.
     * `signedOut` is also the answer when nobody is signed in.
     */
    private fun <T> answer(signedOut: () -> T, work: suspend (Era) -> T): ListenableFuture<T> = scope.future {
        val era = eraOrNull() ?: return@future signedOut()
        val doing = era.scope.async { work(era) }
        val done = try {
            doing.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The account went (its scope was cancelled), not this request.
            if (era !== this@PlaybackService.era) return@future signedOut()
            throw e
        }
        if (era !== this@PlaybackService.era) signedOut() else done
    }

    /**
     * Runs something the car is waiting on, giving up after a few seconds as if the server
     * couldn't be reached: a car waits about ten seconds before showing an error of its own,
     * and one in our words is better.
     */
    private suspend fun <T> inTime(block: suspend () -> T): T = try {
        withTimeout(CAR_WAIT_MILLIS) { block() }
    } catch (_: TimeoutCancellationException) {
        throw ApiError.Network("(It didn't answer in time.)")
    }

    /** "Tap to try again" entries made so far: each has an id of its own. */
    private val retriesMade = java.util.concurrent.atomic.AtomicLong()

    /**
     * The entry a folder or search shows while the server is out of reach. A folder, not
     * something to play: a car shows its player when something playable is chosen, which here
     * would be an empty one. Opened, it is the folder fetched again (see onGetChildren). Drawn
     * as a row, whatever its list is.
     */
    private fun tryAgain(id: String) = CarEntry(id, getString(R.string.cant_reach_server), getString(R.string.tap_to_try_again), icon = R.drawable.ic_refresh_cw, browsable = true, style = CarStyle.LIST)

    /** A "Tap to try again" was tapped: the car is told the folder, or the search, has
     *  changed, and asks for it again (fetching it afresh, now). */
    private fun tryAgainNow(target: CarRetry) {
        val era = era ?: return
        when (target) {
            is CarRetry.Folder -> session.notifyChildrenChanged(target.parentId, Int.MAX_VALUE, null)
            is CarRetry.Search -> {
                era.searches.forget(target.query)
                era.searchedBy[target.query]?.let { session.notifySearchResultChanged(it, target.query, 1, null) }
            }
        }
    }

    private fun signedOut() = SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, getString(R.string.car_sign_in), signInButton())

    /** What to tell the car when the server couldn't help. */
    private fun trouble(controller: MediaSession.ControllerInfo, error: ApiError): SessionError {
        val blocked = CarTree.unreachable(error) && graph.localAccess.blocked.value
        val message = when {
            blocked -> getString(R.string.car_local_network)
            CarTree.unreachable(error) -> getString(R.string.car_unreachable)
            else -> error.message ?: getString(R.string.car_unreachable)
        }
        val sessionError = if (blocked) SessionError(SessionError.ERROR_IO, message, openAppButton()) else SessionError(SessionError.ERROR_IO, message)
        // Said on the car's screen too, not only in the list that failed.
        session.sendError(controller, sessionError)
        return sessionError
    }

    private fun refuse(controller: MediaSession.ControllerInfo, error: SessionError): Exception {
        if (error.code != SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED) session.sendError(controller, error)
        return UnsupportedOperationException(error.message)
    }

    /**
     * Whether a list plays shuffled, as on Windows: each list remembers (off until it's been
     * shuffled); songs that aren't from a list leave shuffle as it is.
     */
    private fun shuffleFor(source: String): Boolean =
        if (source.isEmpty()) player.queue.shuffle else graph.settings.value.lists[source]?.shuffle ?: false

    private suspend fun staged(list: PlayList): MediaItemsWithStartPosition {
        val shuffle = list.shuffle ?: shuffleFor(list.source)
        // Made into a queue off the main thread: a list can be thousands of songs.
        val next = withContext(Dispatchers.Default) {
            // A list with a source is that page's whole list, so each song's place is its origin.
            val origins = if (list.source.isNotEmpty()) list.songs.indices.toList() else null
            player.prepare(list.songs, list.start, list.context, list.source, origins, list.songs.map { it.id }, shuffle, list.anyStart)
        }
        val (items, start) = player.stage(next)
        return MediaItemsWithStartPosition(items, start, 0)
    }

    private suspend fun resume(era: Era, controller: MediaSession.ControllerInfo): MediaItemsWithStartPosition {
        val tree = era.tree
        // The saved queue is put back when the account is taken on; wait for that rather
        // than read the file a second time.
        era.restoring.await()
        player.stageCurrent()?.let { (items, start) -> return MediaItemsWithStartPosition(items, start, exo.currentPosition) }
        val liked = try {
            inTime { tree.shuffledLiked() }
        } catch (e: ApiError) {
            throw refuse(controller, trouble(controller, e))
        }
        return staged(liked ?: throw refuse(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play))))
    }

    /** A spoken request: "play X on Nexus Music". */
    private suspend fun voice(era: Era, controller: MediaSession.ControllerInfo, words: String, extras: Bundle?): MediaItemsWithStartPosition {
        val tree = era.tree
        // Each is what a person said, however it got here: text, and no longer than a name.
        fun said(key: String) = runCatching { extras?.getString(key) }.getOrNull()?.let(VoiceAsk::tidy).orEmpty()
        val request = VoiceRequest(
            query = VoiceAsk.tidy(words),
            focus = VoiceRequest.focusOf(said(MediaStore.EXTRA_MEDIA_FOCUS).ifEmpty { null }),
            artist = said(MediaStore.EXTRA_MEDIA_ARTIST),
            album = said(MediaStore.EXTRA_MEDIA_ALBUM),
            title = said(MediaStore.EXTRA_MEDIA_TITLE),
            playlist = said(EXTRA_MEDIA_PLAYLIST),
            genre = said(EXTRA_MEDIA_GENRE),
        )
        if (request.unnamed) return resume(era, controller)
        val list = tree.forVoice(request)
            ?: throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.couldnt_find, request.searchWords())))
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "voice: $request -> ${list.context} (${list.songs.size} songs)")
        return staged(list)
    }

    private fun <T> slice(all: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return all
        val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
        return all.subList(from, to)
    }

    companion object {
        private const val TAG = "NexusPlayback"

        val LIKE = SessionCommand("org.medianexus.music.LIKE", Bundle.EMPTY)
        val SHUFFLE = SessionCommand("org.medianexus.music.SHUFFLE", Bundle.EMPTY)
        val REPEAT = SessionCommand("org.medianexus.music.REPEAT", Bundle.EMPTY)

        /** In the session's extras (see `publishExtras`): how many songs straight after the
         *  current one are in "Next in queue"; how many are to come in all; the list the queue came from; the place in it
         *  of the song playing (-1 for none); whether the server is converting that song. */
        const val EXTRA_QUEUED_AHEAD = "org.medianexus.music.QUEUED_AHEAD"
        const val EXTRA_AHEAD = "org.medianexus.music.AHEAD"
        const val EXTRA_SOURCE = "org.medianexus.music.SOURCE"
        const val EXTRA_ORIGIN = "org.medianexus.music.ORIGIN"
        const val EXTRA_CONVERTED = "org.medianexus.music.CONVERTED"
        /** In the session's extras: the saved queue has been read and put back, so what the
         *  player says is playing (nothing, perhaps) is so, not merely not known yet. */
        const val EXTRA_SETTLED = "org.medianexus.music.SETTLED"
        const val EXTRA_SONG = "org.medianexus.music.SONG"
        /** On a playback error: it is the lost connection to the server. */
        const val EXTRA_LOST = "org.medianexus.music.LOST"

        /** The extras the Assistant names a playlist and a genre with (MediaStore has no
         *  constants for these two). */
        private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
        private const val EXTRA_MEDIA_GENRE = "android.intent.extra.genre"

        /** Waits between tries at a stream that keeps failing, as Windows waits: after these,
         *  the song waits for the server, tried again every [SERVER_WAIT_MILLIS]. */
        private val RETRY_WAITS = listOf(1_000L, 2_000L, 4_000L, 8_000L)

        /** How often to try again once the player has given up waiting for the server. */
        private const val SERVER_WAIT_MILLIS = 15_000L
        private const val TICK_MILLIS = 1_000L
        private const val CAR_WAIT_MILLIS = 7_000L

        /** Folders the car asked for that are remembered, to tell it to look again. */
        private const val MOST_ASKED = 32

        /** How long "play" with nothing loaded waits for the saved sign-in, and then for the
         *  saved queue, to be read from the phone. */
        private const val RESUME_WAIT_MILLIS = 3_000L

        /** How long after a button started the service the promise to enter the foreground
         *  is looked at again. The system allows 30 seconds on Android 17 and 10 on older
         *  versions; this is well inside both. */
        private const val PROMISE_MILLIS = 7_000L
        private const val STAND_DOWN_NOTIFICATION = 20_001

        /** A service made this soon after its process began was what the process began for. */
        private const val COLD_START_MILLIS = 3_000L
        private const val CHECKPOINT_MILLIS = 20_000L
    }
}
