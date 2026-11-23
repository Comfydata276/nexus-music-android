package org.medianexus.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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

    private var account: AccountGraph? = null
    private var tree: CarTree? = null
    private var kept: KeptSongs? = null
    private var accountJobs: Job? = null
    private val database by lazy { StandaloneDatabaseProvider(this) }

    /** Every song in the queue, by id, for the data source (which runs on the player's
     *  loading threads) to know a song's file type. */
    private val songsById = ConcurrentHashMap<String, Song>()
    private val needsConverting = NeedsConverting()

    private val playCount = PlayCount()
    /** The queue entry last reported as "now playing", so each play is reported once. */
    private var reportedUid: Long? = null
    private var failures = 0
    private var waitingForServer = false

    /** Queues to write, each with the account it belongs to; only the latest is written. */
    private val saves = Channel<Pair<QueueFiles, SavedQueue>>(Channel.CONFLATED)
    /** The player is being emptied because the account is going: nothing to save. */
    private var leaving = false
    private var lastSaveAt = 0L

    /** What the car's search found, by query, until it asks for the results. */
    private val searches = ConcurrentHashMap<String, List<CarEntry>>()

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
            songCount = { resources.getQuantityString(R.plurals.song_count, it, it) },
            albumCount = { resources.getQuantityString(R.plurals.album_count, it, it) },
        )

        // One HTTP client for everything, so audio shares the warm connections.
        val http = OkHttpDataSource.Factory(graph.http)
        val sources = SongDataSource.Factory(SongResolver(::resolve), http) {
            if (graph.settings.value.cacheLimitMb > 0) kept?.cache() else null
        }
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

        // The queue is written off the main thread, the latest of a burst only.
        scope.launch(Dispatchers.IO) {
            for ((files, saved) in saves) files.save(saved)
        }
        // Who is signed in decides everything else.
        scope.launch {
            combine(graph.session.state, graph.accounts.state) { state, account -> state to account }
                .distinctUntilChanged()
                .collect { (state, account) -> signedIn(state, account) }
        }
        main.postDelayed(ticker, TICK_MILLIS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        saveNow()
        scope.cancel()
        session.release()
        player.release()
        exo.release()
        kept?.close()
        super.onDestroy()
    }

    /**
     * Where the audio processors for crossfade, levelling, the equaliser and mono will go:
     * `DefaultRenderersFactory.buildAudioSink` with them in its chain.
     */
    private fun renderers() = DefaultRenderersFactory(this)

    // ---- Who is signed in ---------------------------------------------------------------------

    private fun signedIn(state: SessionState, now: AccountGraph?) {
        if (state == SessionState.Restoring) return
        if (now === account) return
        if (now == null && state is SessionState.SignedIn) return // its objects are on their way
        // Someone else, or nobody: what was playing was theirs.
        accountJobs?.cancel()
        if (account != null) {
            saveNow()
            // Emptying the player isn't a change to the queue that was theirs: it stays saved
            // for when they sign in again.
            leaving = true
            player.clear()
            leaving = false
            reportedUid = null
            kept?.let { old -> scope.launch(Dispatchers.IO) { old.close() } }
            kept = null
        }
        account = now
        tree = now?.let { CarTree(it.library, words) }
        if (now == null) {
            session.setPlaybackException(signInNeeded())
            return
        }
        session.setPlaybackException(null)
        kept = KeptSongs(now.keptSongsDir, database) { graph.settings.value.cacheLimitMb * 1024L * 1024L }
        accountJobs = scope.launch {
            // The like button follows the likes, wherever they're changed.
            launch { now.likes.liked.collect { updateButtons() } }
            // Back to where things were, paused, ready for "play" from anywhere.
            val saved = withContext(Dispatchers.IO) { now.queueFiles.load() }
            if (saved != null && account === now && player.queue.isEmpty) player.restore(saved)
        }
    }

    private fun signInNeeded(): PlaybackException =
        PlaybackException(getString(R.string.car_sign_in), null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, signInButton())

    /** A "Sign in" button for the car's error screen, which opens the app on the phone. */
    private fun signInButton() = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.sign_in))
        putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp())
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- Where songs come from ------------------------------------------------------------------

    /** Called on the player's loading threads. */
    private fun resolve(songId: String): SongWhere {
        val api = graph.accounts.state.value?.api ?: throw IOException("Nobody is signed in")
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

    private fun queueChanged() {
        val queue = player.queue
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
        // How long "Next in queue" is, for the app's queue sheet.
        session.setSessionExtras(Bundle().apply { putInt(EXTRA_QUEUED_AHEAD, queue.queuedAhead()) })
        player.refresh()
        updateButtons()
        save()
    }

    private fun save() {
        if (leaving) return
        val files = account?.queueFiles ?: return
        val queue = player.queue
        saves.trySend(files to queue.saved(exo.currentPosition / 1000.0, System.currentTimeMillis() / 1000))
        lastSaveAt = System.currentTimeMillis()
    }

    /** Writes the queue now, on this thread: the service is going. */
    private fun saveNow() {
        val files = account?.queueFiles ?: return
        files.save(player.queue.saved(exo.currentPosition / 1000.0, System.currentTimeMillis() / 1000))
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
                reportNowPlaying()
            } else {
                save()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Repeating one song is a new listen of it.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) reportedUid = null
            if (exo.isPlaying) reportNowPlaying()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) playCount.jumped(newPosition.positionMs / 1000.0)
        }

        override fun onPlayerError(error: PlaybackException) {
            val song = player.queue.current() ?: return
            when {
                undecodable(error) && needsConverting.mark(song.id) -> {
                    // The phone can't play this file as it is: have the server convert it.
                    exo.prepare()
                }
                network(error) -> {
                    // Not the song's fault: the player has already tried a few times. Wait
                    // where it is for the server to come back, trying now and then.
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
        network(error) -> PlaybackException(getString(R.string.lost_connection), error, error.errorCode)
        song != null && !undecodable(error) -> PlaybackException(getString(R.string.couldnt_play, song.title), error, error.errorCode)
        else -> null
    }

    /** Once a second: counts listening, and notes where playback has got to now and then. */
    private val ticker = object : Runnable {
        override fun run() {
            if (exo.isPlaying) {
                val seconds = exo.currentPosition / 1000.0
                if (playCount.at(seconds)) scrobble(playCount.songId)
                if (System.currentTimeMillis() - lastSaveAt >= CHECKPOINT_MILLIS) save()
            }
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun reportNowPlaying() {
        val uid = player.queue.currentUid() ?: return
        if (uid == reportedUid) return
        reportedUid = uid
        val song = player.queue.current() ?: return
        playCount.start(song.id, song.duration.toDouble(), exo.currentPosition / 1000.0)
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
            // read notifications, which is what Android Auto is) and the rest: the rest may
            // look but not touch.
            val accept = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (!controller.isTrusted) return accept.build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE).add(SHUFFLE).add(REPEAT)
            if (controller.packageName == packageName) AppCommands.all.forEach { commands.add(it) }
            return accept.setAvailableSessionCommands(commands.build()).setMediaButtonPreferences(buttons()).build()
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

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future {
                val tree = treeOrNull() ?: return@future LibraryResult.ofError(signedOut())
                val node = CarIds.parse(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                try {
                    val entries = inTime { tree.children(node) { session.notifyChildrenChanged(parentId, Int.MAX_VALUE, params) } }
                        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    LibraryResult.ofItemList(ImmutableList.copyOf(slice(entries, page, pageSize).map(CarMedia::item)), params)
                } catch (e: ApiError) {
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future {
                val tree = treeOrNull() ?: return@future LibraryResult.ofError(signedOut())
                val node = CarIds.parse(mediaId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                try {
                    inTime { tree.item(node) }?.let { LibraryResult.ofItem(CarMedia.item(it), null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } catch (e: ApiError) {
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            scope.future {
                val tree = treeOrNull() ?: return@future LibraryResult.ofError(signedOut())
                try {
                    val found = inTime { tree.search(query) }
                    searches[query] = found
                    session.notifySearchResultChanged(browser, query, found.size, params)
                    LibraryResult.ofVoid()
                } catch (e: ApiError) {
                    // A car asking the old way gets its answer only through this: say there's
                    // nothing, rather than leave it waiting, and say why.
                    searches[query] = emptyList()
                    session.notifySearchResultChanged(browser, query, 0, params)
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future {
                val tree = treeOrNull() ?: return@future LibraryResult.ofError(signedOut())
                try {
                    val found = searches[query] ?: inTime { tree.search(query) }.also { searches[query] = it }
                    LibraryResult.ofItemList(ImmutableList.copyOf(slice(found, page, pageSize).map(CarMedia::item)), params)
                } catch (e: ApiError) {
                    LibraryResult.ofError(trouble(browser, e))
                }
            }

        /**
         * One item asked for: a song chosen in the car, or a spoken request. Either way a
         * whole list is queued: the song's album or playlist from it, or what was asked for.
         */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> =
            scope.future {
                if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.tag is QueuePlayer.SongTag }) {
                    // Our own items, handed back: play them as they are.
                    return@future MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                }
                val tree = treeOrNull() ?: throw refuse(controller, signedOut())
                val asked = mediaItems.singleOrNull() ?: throw refuse(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play)))
                val words = asked.requestMetadata.searchQuery
                try {
                    if (words != null) return@future inTime { voice(tree, controller, words, asked.requestMetadata.extras) }
                    val track = CarIds.parse(asked.mediaId) as? CarNode.Track
                        ?: throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.nothing_to_play)))
                    val list = inTime { tree.play(track) } ?: throw refuse(controller, SessionError(SessionError.ERROR_BAD_VALUE, getString(R.string.couldnt_play, asked.mediaMetadata.title ?: "")))
                    staged(list)
                } catch (e: ApiError) {
                    throw refuse(controller, trouble(controller, e))
                }
            }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            scope.future {
                val tree = treeOrNull() ?: throw refuse(controller, signedOut())
                mediaItems.mapNotNull { item ->
                    if (item.localConfiguration?.tag is QueuePlayer.SongTag) return@mapNotNull item
                    val track = CarIds.parse(item.mediaId) as? CarNode.Track
                    val song = try {
                        track?.let { tree.play(it) }?.let { it.songs[it.start] } ?: account?.library?.song(item.mediaId)
                    } catch (_: ApiError) {
                        null
                    } ?: return@mapNotNull null
                    MediaItem.Builder().setMediaId(song.id).setUri(songUri(song.id)).setTag(QueuePlayer.SongTag(song, 0)).setMediaMetadata(CarMedia.metadata(song)).build()
                }
            }

        /** "Play", with nothing in the player: the queue as it was left; failing that, Liked
         *  songs shuffled. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaItemsWithStartPosition> =
            scope.future {
                val tree = treeOrNull() ?: throw refuse(controller, signedOut())
                resume(tree, controller)
            }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
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
                else -> AppCommands.handle(player, customCommand, args, ::shuffleFor)
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                SessionResult(if (done) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED),
            )
        }
    }

    /** The car's tree for the account signed in, once the saved sign-in has been read. */
    private suspend fun treeOrNull(): CarTree? {
        val now = graph.accounts.restored() ?: return null
        // The collector that sets `tree` may not have run yet.
        if (account !== now) signedIn(graph.session.state.value, now)
        return tree
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
        val message = if (CarTree.unreachable(error)) getString(R.string.car_unreachable) else error.message ?: getString(R.string.car_unreachable)
        val sessionError = SessionError(SessionError.ERROR_IO, message)
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
        val (items, start) = player.stage(list.songs, list.start, list.context, list.source, list.songs.map { it.id }, shuffle, list.anyStart)
        return MediaItemsWithStartPosition(items, start, 0)
    }

    private suspend fun resume(tree: CarTree, controller: MediaSession.ControllerInfo): MediaItemsWithStartPosition {
        player.stageCurrent()?.let { (items, start) -> return MediaItemsWithStartPosition(items, start, exo.currentPosition) }
        val saved = account?.let { a -> withContext(Dispatchers.IO) { a.queueFiles.load() } }
        if (saved != null) {
            val (items, start) = player.stageSaved(saved)
            return MediaItemsWithStartPosition(items, start, (saved.position * 1000).toLong())
        }
        val liked = try {
            inTime { tree.shuffledLiked() }
        } catch (e: ApiError) {
            throw refuse(controller, trouble(controller, e))
        }
        return staged(liked ?: throw refuse(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, getString(R.string.nothing_to_play))))
    }

    /** A spoken request: "play X on Nexus Music". */
    private suspend fun voice(tree: CarTree, controller: MediaSession.ControllerInfo, words: String, extras: Bundle?): MediaItemsWithStartPosition {
        val request = VoiceRequest(
            query = words,
            focus = VoiceRequest.focusOf(extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)),
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST).orEmpty(),
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM).orEmpty(),
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE).orEmpty(),
            playlist = extras?.getString(EXTRA_MEDIA_PLAYLIST).orEmpty(),
            genre = extras?.getString(EXTRA_MEDIA_GENRE).orEmpty(),
        )
        if (request.unnamed) return resume(tree, controller)
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

        /** In the session's extras: how many songs straight after the current one are in
         *  "Next in queue". */
        const val EXTRA_QUEUED_AHEAD = "org.medianexus.music.QUEUED_AHEAD"

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
        private const val CHECKPOINT_MILLIS = 20_000L
    }
}
