package org.medianexus.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.media3.datasource.HttpDataSource
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
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** Every song in the queue, by id, for the data source (which runs on the player's
     *  loading threads) to know a song's file type. */
    private val songsById = ConcurrentHashMap<String, Song>()
    private val needsConverting = NeedsConverting()

    /** What the server is told about listening: "now playing", and each play that counts. */
    private val plays = PlayReporter(nowPlaying = ::reportNowPlaying, played = ::scrobble)
    private var failures = 0
    private var waitingForServer = false

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

        // One HTTP client for everything, so audio shares the warm connections.
        val http = OkHttpDataSource.Factory(graph.http)
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
        player = QueuePlayer(exo, queue, CarMedia::metadata, changed = { queueChanged() }, explain = ::explain)
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
        main.postDelayed(ticker, TICK_MILLIS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

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
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && !isPlaybackOngoing) {
            promised = true
            // Whatever happens to the request (a reply that never comes, a command Media3
            // drops), the promise is looked at again well inside the system's time limit.
            main.removeCallbacks(promiseCheck)
            main.postDelayed(promiseCheck, PROMISE_MILLIS)
        }
        return super.onStartCommand(intent, flags, startId)
    }

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
            ServiceCompat.startForeground(this, STAND_DOWN_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: IllegalStateException) {
            // Not allowed into the foreground at all: then nothing was promised either.
        }
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
     * Back to where things were, paused, ready for "play" from anywhere. Until this has
     * finished nothing is saved (see [KeptQueue]): the player's queue is empty while the
     * saved one is being read, and an account that goes in that moment must not have "empty"
     * written over its queue.
     */
    private suspend fun restore(era: Era) {
        val saved = era.account.queue.restore()
        if (saved != null && player.queue.isEmpty) player.restore(saved)
        era.restoring.complete(Unit)
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
        val song = songsById[songId]
        val suffix = song?.suffix?.lowercase().orEmpty()
        val native = (suffix.isEmpty() || suffix in NATIVE_SUFFIXES) && songId !in needsConverting
        val form = streamForm(native, graph.settings.value.bitrate, suffix)
        // Here, in later versions, a downloaded copy is chosen first, then one kept from
        // fetching ahead; only then the server.
        return SongWhere(form, cacheKey(songId, form), song?.duration ?: 0) { offset -> api.streamUrl(songId, form.bitrate, offset) }
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
            if (error is HttpDataSource.InvalidResponseCodeException && error.responseCode in 400..499) return C.TIME_UNSET
            if (error !is IOException) return super.getRetryDelayMsFor(loadErrorInfo)
            return RETRY_WAITS.getOrElse(loadErrorInfo.errorCount - 1) { RETRY_WAITS.last() }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int) = RETRY_WAITS.size
    }

    // ---- The queue changing -----------------------------------------------------------------

    /** The queue's revision as last seen, and when its order last changed (for a tap on a
     *  queue that has not caught up: see [Access.staleQueueTap]). */
    private var seenRevision = -1L
    private var reorderedAt = 0L

    /**
     * Counts the times something was asked to start playing. A start that has to look
     * something up first (the car choosing a song, a spoken request) takes the number as it
     * begins and is dropped if another start has begun by the time it is ready: a slow answer
     * to the car must not replace what was chosen on the phone meanwhile.
     */
    private var starts = 0L

    private fun queueChanged() {
        val queue = player.queue
        if (queue.revision != seenRevision) {
            seenRevision = queue.revision
            reorderedAt = android.os.SystemClock.elapsedRealtime()
        }
        // Changed in place, never emptied: the player may be opening a song right now.
        for (song in queue.songs) songsById[song.id] = song
        if (songsById.size > queue.songs.size) songsById.keys.retainAll(queue.songs.mapTo(HashSet()) { it.id })
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
     * queue" is (for the queue sheet), the list the queue came from and the place in it of
     * the song playing (to mark its row, and no other row of the same song), and whether the
     * server is converting the song (for the format line).
     */
    private fun publishExtras() {
        val queue = player.queue
        val extras = Bundle().apply {
            putInt(EXTRA_QUEUED_AHEAD, queue.queuedAhead())
            putString(EXTRA_SOURCE, queue.source)
            putInt(EXTRA_ORIGIN, queue.currentOrigin() ?: -1)
            putBoolean(EXTRA_CONVERTED, queue.current()?.id?.let { it in needsConverting } == true)
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
        val kept = account?.queue ?: return
        kept.save { player.queue.saved(exo.currentPosition / 1000.0, System.currentTimeMillis() / 1000) }
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
                waitingForServer = false
                countPlaying()
            } else {
                // A last sample: what was heard up to the pause counts.
                plays.paused(exo.currentPosition / 1000.0, clock())
                save()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
            val song = player.queue.current() ?: return
            when {
                undecodable(error) && needsConverting.mark(song.id) -> {
                    // The phone can't play this file as it is: have the server convert it.
                    publishExtras()
                    exo.prepare()
                }
                network(error) -> {
                    // Not the song's fault: the player has already tried a few times. Wait
                    // where it is for the server to come back, trying now and then (with no
                    // permission to reach the home network, until it is given).
                    waitingForServer = true
                    retryLater(SERVER_WAIT_MILLIS)
                }
                else -> passOver()
            }
        }
    }

    /** Asks for the song again later, from where it stopped. If that fails too, the player's
     *  error brings this round again. */
    private fun retryLater(delayMs: Long) {
        main.postDelayed({ if (waitingForServer && exo.playerError != null) exo.prepare() }, delayMs)
    }

    /** Gives up on the song that's playing and goes to the next, unless three in a row have
     *  failed or there is no next, as Windows does. */
    private fun passOver() {
        failures++
        if (failures < 3 && exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
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
    ) || (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode?.let { it >= 500 } == true

    /** The words the car, the notification and the app show for a playback error. */
    private fun explain(error: PlaybackException, song: Song?): PlaybackException? = when {
        // Android 17, a server on the home network, and no permission to reach it: waiting
        // for it to come back would be waiting for ever. Say what will put it right.
        network(error) && graph.localAccess.blocked.value -> PlaybackException(getString(R.string.car_local_network), error, error.errorCode, openAppButton())
        network(error) -> PlaybackException(getString(R.string.lost_connection), error, error.errorCode)
        song != null && !undecodable(error) -> PlaybackException(getString(R.string.couldnt_play, song.title), error, error.errorCode)
        else -> null
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
            // read notifications or control media, which is what Android Auto, the Assistant
            // and a car's own screens are) and the rest. The rest get no connection: a private
            // library is not for any app on the phone to browse. See [Access].
            val caller = callerOf(session, controller)
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
            return when {
                !Access.mayPlayer(caller, playerCommand) -> SessionError.ERROR_PERMISSION_DENIED
                // "Play that entry of the queue", by its place, from someone whose screen may
                // still show the order from before a reorder a moment ago (see Access).
                Access.staleQueueTap(caller, playerCommand, android.os.SystemClock.elapsedRealtime() - reorderedAt) -> SessionError.ERROR_INVALID_STATE
                else -> SessionResult.RESULT_SUCCESS
            }
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            // Unless a folder says otherwise: folders in a grid, songs in a list.
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            val root = CarMedia.item(CarEntry(CarIds.ROOT, getString(R.string.app_name), browsable = true))
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
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
                    LibraryResult.ofError(trouble(browser, e))
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
                try {
                    val found = inTime { era.tree.search(query) }
                    era.searches.put(query, found)
                    session.notifySearchResultChanged(browser, query, found.size, params)
                    LibraryResult.ofVoid()
                } catch (e: ApiError) {
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
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        /**
         * One item asked for: a song chosen in the car, or a spoken request. Either way a
         * whole list is queued: the song's album or playlist from it, or what was asked for.
         */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> {
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
                else -> {
                    // A choice made on the phone: anything still being looked up for the car
                    // has been overtaken.
                    if (customCommand.customAction == AppActions.PLAY) starts++
                    AppCommands.handle(player, customCommand, args, ::shuffleFor)
                }
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                SessionResult(if (done) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED),
            )
        }
    }

    /** Who a controller is (see [Access]). */
    private fun callerOf(session: MediaSession, controller: MediaSession.ControllerInfo): Caller = Access.caller(
        trusted = controller.isTrusted,
        sameUid = controller.uid == android.os.Process.myUid(),
        ownPackage = controller.isPackageNameVerified && controller.packageName == packageName,
        legacy = controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
        notification = session.isMediaNotificationController(controller),
    )

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

    private fun staged(list: PlayList): MediaItemsWithStartPosition {
        val shuffle = list.shuffle ?: shuffleFor(list.source)
        // A list with a source is that page's whole list, so each song's place is its origin.
        val origins = if (list.source.isNotEmpty()) list.songs.indices.toList() else null
        val (items, start) = player.stage(list.songs, list.start, list.context, list.source, list.songs.map { it.id }, shuffle, list.anyStart, origins)
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
         *  current one are in "Next in queue"; the list the queue came from; the place in it
         *  of the song playing (-1 for none); whether the server is converting that song. */
        const val EXTRA_QUEUED_AHEAD = "org.medianexus.music.QUEUED_AHEAD"
        const val EXTRA_SOURCE = "org.medianexus.music.SOURCE"
        const val EXTRA_ORIGIN = "org.medianexus.music.ORIGIN"
        const val EXTRA_CONVERTED = "org.medianexus.music.CONVERTED"

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
        private const val CHECKPOINT_MILLIS = 20_000L
    }
}
