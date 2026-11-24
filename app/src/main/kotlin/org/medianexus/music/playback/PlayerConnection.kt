package org.medianexus.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import org.medianexus.music.Accounts
import org.medianexus.music.net.Song
import org.medianexus.music.store.SettingsStore

/**
 * What's in the queue, as the screens show it: the songs to come, the first `queuedAhead` of
 * which are "Next in queue". `songs` is the next few hundred (as Windows shows 400 rows),
 * not a queue of thousands; `total` is how many are to come in all. `uids` is each song's
 * entry in the queue, by its own number: what a command about that row names it by, so a
 * song ending between the tap and the command can't shift it onto another (and the same
 * song twice is two entries).
 */
@Immutable
data class UpNext(
    val songs: List<Song> = emptyList(),
    val queuedAhead: Int = 0,
    val context: String = "",
    val total: Int = songs.size,
    val uids: List<Long> = List(songs.size) { AppCommands.NONE },
)

/**
 * Where what's playing came from: the list's route key ("album:<id>"), and the place in that
 * list of the song playing (-1 if it isn't from a list, or isn't known). A row is marked as
 * playing only if it is that place in that list, so the same song elsewhere on the page, or
 * in another list, isn't.
 */
@Immutable
data class PlayingFrom(val source: String = "", val origin: Int = -1, val songId: String = "", val paused: Boolean = false) {
    /** That row of that list is what's playing: its place, and the song in it too. (A list
     *  refreshed with a song added above can shift the places before the player has heard;
     *  the song keeps the mark from falling on its neighbour meanwhile.) */
    fun isRow(key: String, index: Int, id: String): Boolean = source.isNotEmpty() && source == key && origin == index && songId == id

    /** This list is what's playing (its play button pauses and resumes). */
    fun isList(key: String): Boolean = songId.isNotEmpty() && source.isNotEmpty() && source == key
}

/**
 * Everything the screens need to show and drive playback, and nothing else: it connects to
 * the playback service's session the way the car does, with a `MediaController`, and gives
 * the player's state as `StateFlow`s and its commands as plain calls. There is no other way
 * for a screen to reach the player.
 *
 * Connect with [connect] when the app comes to the front and [release] when it goes; the
 * service carries on playing either way. Commands sent while not connected connect first.
 *
 * The position is a flow of its own, moved four times a second only while playing, so the
 * few things that show it are all that redraw; the rest change only when the player does.
 */
@OptIn(UnstableApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerConnection(
    private val context: Context,
    private val accounts: Accounts,
    settings: SettingsStore,
    scope: CoroutineScope,
    /** The words for a radio station's format line. */
    private val liveStream: String = "Live stream",
    /** The words for a lost connection: the player says it shortly, for a car's screen. */
    private val lostConnection: String = "Lost the connection to the server.",
) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val currentSong = MutableStateFlow<Song?>(null)
    private val playingNow = MutableStateFlow(false)
    private val bufferingNow = MutableStateFlow(false)
    private val positionNow = MutableStateFlow(0L)
    private val durationNow = MutableStateFlow(0L)
    private val queueNow = MutableStateFlow(UpNext())
    private val shuffleNow = MutableStateFlow(false)
    private val repeatNow = MutableStateFlow(Repeat.OFF)
    private val errorNow = MutableStateFlow<String?>(null)
    private val fromNow = MutableStateFlow(PlayingFrom())
    private val convertedNow = MutableStateFlow(false)

    /** The copy the player is playing a song from: the bit rate it was converted to (0: the
     *  file as it is; -1: not opened yet). */
    private data class Copy(val songId: String = "", val kbps: Int = -1)

    private val copyNow = MutableStateFlow(Copy())

    private val reportedNow = MutableStateFlow(false)
    private val entryNow = MutableStateFlow(AppCommands.NONE)

    /** A failed or dropped connection is tried again by itself, with a pause between tries,
     *  for as long as the screens want the player. */
    private val again = Reconnect(
        post = { run, delay -> main.postDelayed(run, delay) },
        cancel = { run -> main.removeCallbacks(run) },
        connect = { connect() },
    )

    /** The song playing (or paused on); null with an empty queue. */
    val song: StateFlow<Song?> = currentSong.asStateFlow()
    /** The queue entry playing, by its own number: the same song twice in a row is two. */
    val entry: StateFlow<Long> = entryNow.asStateFlow()
    val playing: StateFlow<Boolean> = playingNow.asStateFlow()
    /** Waiting for the stream. */
    val buffering: StateFlow<Boolean> = bufferingNow.asStateFlow()
    /** Milliseconds into the song; moves about four times a second while it plays. */
    val position: StateFlow<Long> = positionNow.asStateFlow()
    /** The song's length in milliseconds, 0 if not known yet. */
    val duration: StateFlow<Long> = durationNow.asStateFlow()
    val queue: StateFlow<UpNext> = queueNow.asStateFlow()
    val shuffle: StateFlow<Boolean> = shuffleNow.asStateFlow()
    /** [Repeat.OFF], [Repeat.ALL] or [Repeat.ONE]. */
    val repeat: StateFlow<Int> = repeatNow.asStateFlow()
    /** Why the song isn't playing, in words, when something went wrong ("Lost the
     *  connection to the server…", in the service's words). */
    val error: StateFlow<String?> = errorNow.asStateFlow()
    /** The list what's playing came from, and its place in it. */
    val from: StateFlow<PlayingFrom> = fromNow.asStateFlow()

    /** The player has said what it's doing since the screens connected. Until then "nothing
     *  playing" means only "not heard yet" (as when the app has just come back after Android
     *  stopped it, and the service hasn't answered). */
    val reported: StateFlow<Boolean> = reportedNow.asStateFlow()

    /** Whether the song playing is liked. */
    val liked: StateFlow<Boolean> = accounts.state
        .flatMapLatest { account -> account?.likes?.liked ?: flowOf(emptySet()) }
        .let { likes -> combine(likes, currentSong) { set, song -> song != null && song.id in set } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** What's being played, as the line under the song says it. */
    val format: StateFlow<FormatLine> = combine(
        currentSong, convertedNow, settings.state.map { it.bitrate }.distinctUntilChanged(),
        // A song played from its download plays as it was saved, whatever the streaming
        // quality is (unless the download needs a fresh copy: then the server's is preferred).
        accounts.state.flatMapLatest { account -> account?.catalogue?.state ?: flowOf(null) },
        copyNow,
    ) { song, converted, cap, downloads, playing ->
        if (song == null) return@combine FormatLine("", false)
        // What the player says it is playing this song from, once it has opened it: a song
        // goes on from the copy it started on, whatever has been downloaded or changed since.
        if (playing.songId == song.id && playing.kbps >= 0) return@combine formatLineOf(song, playing.kbps, liveStream)
        // Until then (it is still being made ready), what it would be opened from now.
        val saved = downloads?.songs?.get(song.id)?.takeIf { !it.stale }
        when {
            saved == null -> formatLine(song, cap, converted, liveStream)
            saved.bitrate > 0 -> FormatLine("MP3 · ${saved.bitrate} kbps", false)
            else -> formatLine(song, 0, false, liveStream)
        }
    }.stateIn(scope, SharingStarted.Eagerly, FormatLine("", false))

    private val listener = object : Player.Listener, MediaController.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // The list of what's to come is rebuilt only when it can have changed.
            val queueToo = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYLIST_METADATA_CHANGED)
            read(queueToo)
        }

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = read(queueToo = true)

        override fun onDisconnected(controller: MediaController) {
            // The service went (the system stopped it): the next command connects again.
            // Everything it said goes with it: a song, a queue or an error left on show
            // would be about a player that isn't there.
            main.removeCallbacks(ticker)
            this@PlayerConnection.controller = null
            future = null
            forget()
            again.lost()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller ?: return
            positionNow.value = c.currentPosition.coerceAtLeast(0)
            if (c.isPlaying) main.postDelayed(this, TICK_MILLIS)
        }
    }

    fun connect() {
        again.wanted()
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).setListener(listener).buildAsync()
        future = pending
        pending.addListener({
            val connected = runCatching { pending.get() }.getOrNull()
            if (connected == null) {
                if (future === pending) {
                    future = null
                    again.lost()
                }
                return@addListener
            }
            if (future !== pending) {
                // Released while connecting.
                connected.release()
                return@addListener
            }
            controller = connected
            again.connected()
            connected.addListener(listener)
            read(queueToo = true)
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        again.stop()
        main.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        // Kept on show (it is what the player was doing a moment ago, and will be read again
        // on connecting), but no longer the player's word.
        reportedNow.value = false
    }

    /** Clears everything the player reported. */
    private fun forget() {
        currentSong.value = null
        entryNow.value = AppCommands.NONE
        playingNow.value = false
        bufferingNow.value = false
        positionNow.value = 0
        durationNow.value = 0
        queueNow.value = UpNext()
        errorNow.value = null
        fromNow.value = PlayingFrom()
        convertedNow.value = false
        copyNow.value = Copy()
        reportedNow.value = false
    }

    private fun read(queueToo: Boolean) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val song = c.currentMediaItem?.let(::songOf)
        currentSong.value = song
        entryNow.value = (c.currentMediaItem?.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid ?: AppCommands.NONE
        val playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        playingNow.value = playing
        bufferingNow.value = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        positionNow.value = c.currentPosition.coerceAtLeast(0)
        durationNow.value = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((song?.duration ?: 0) * 1000L)
        val extras = c.sessionExtras
        if (queueToo) {
            val ahead = extras.getInt(PlaybackService.EXTRA_QUEUED_AHEAD)
            val items = (index + 1 until c.mediaItemCount).map { c.getMediaItemAt(it) }
            val songs = items.map(::songOf)
            queueNow.value = UpNext(
                songs, ahead.coerceAtMost(songs.size), c.playlistMetadata.title?.toString().orEmpty(),
                total = extras.getInt(PlaybackService.EXTRA_AHEAD, songs.size).coerceAtLeast(songs.size),
                uids = items.map { (it.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid ?: AppCommands.NONE },
            )
        }
        shuffleNow.value = c.shuffleModeEnabled
        repeatNow.value = QueuePlayer.ourRepeat(c.repeatMode)
        errorNow.value = c.playerError?.let { error -> if (error.extras.getBoolean(PlaybackService.EXTRA_LOST)) lostConnection else error.message }
        fromNow.value = PlayingFrom(
            source = extras.getString(PlaybackService.EXTRA_SOURCE).orEmpty(),
            origin = extras.getInt(PlaybackService.EXTRA_ORIGIN, -1),
            songId = song?.id.orEmpty(),
            paused = !playing,
        )
        convertedNow.value = extras.getBoolean(PlaybackService.EXTRA_CONVERTED)
        copyNow.value = Copy(extras.getString(PlaybackService.EXTRA_SONG).orEmpty(), extras.getInt(PlaybackService.EXTRA_PLAYING_KBPS, -1))
        // Only once the service has put back the saved queue: before that, after Android has
        // stopped the app and it is starting again, it says "nothing" for a moment.
        // And only when the player's word on the song and the extras' agree: the two travel
        // separately, and "settled" can arrive a moment before the song it was settled on.
        reportedNow.value = extras.getBoolean(PlaybackService.EXTRA_SETTLED) && extras.getString(PlaybackService.EXTRA_SONG).orEmpty() == song?.id.orEmpty()
        main.removeCallbacks(ticker)
        if (c.isPlaying) main.postDelayed(ticker, TICK_MILLIS)
    }

    private fun songOf(item: MediaItem): Song =
        (item.localConfiguration?.tag as? QueuePlayer.SongTag)?.song
            ?: Song(id = item.mediaId, title = item.mediaMetadata.title?.toString().orEmpty(), artist = item.mediaMetadata.artist?.toString().orEmpty())

    // ---- Commands -------------------------------------------------------------------------

    /**
     * Plays a list from one of its songs. `context` is what it is, in words ("Slow Rivers");
     * `source` the route key of the page it came from ("album:<id>"), so the list's shuffle
     * is remembered and its playing row marked; a list with a source is the page's whole
     * list, in its order. `shuffle` null plays it as it was last played. `view` is how the
     * list was arranged on the screen the tap was made on (sorted, narrowed to what was
     * typed): the player plays that arrangement, from the song at `start`, or nothing if it
     * isn't there any more. `anyStart`: no song was chosen (the play button).
     */
    fun play(
        songs: List<Song>,
        start: Int,
        context: String,
        source: String = "",
        shuffle: Boolean? = null,
        anyStart: Boolean = false,
        view: org.medianexus.music.library.ListView? = null,
    ) {
        // A long list that is a page the phone keeps (an album, a playlist, Liked songs)
        // is named, and the service reads it from where the screen read it: writing
        // 20,000 songs out here and reading them back there would hold the main thread
        // for both. A short list, or one put together on the spot, is sent as it is.
        if (songs.size > AppCommands.MOST_SENT && keptPage(source)) {
            playPage(source, start, songs.getOrNull(start)?.id, context, shuffle, anyStart, view)
            return
        }
        send(AppCommands.PLAY, Bundle().apply {
            putString("songs", AppCommands.songsArg(songs))
            putInt("start", start)
            songs.getOrNull(start)?.id?.let { putString("startId", it) }
            putString("context", context)
            putString("source", source)
            putInt("shuffle", when (shuffle) { null -> -1; true -> 1; false -> 0 })
            putBoolean("anyStart", anyStart)
            AppCommands.putView(this, view)
        })
    }

    /**
     * Plays a list the phone keeps, by naming it (`source`) rather than sending its songs:
     * the service reads it where the screen read it. `start` is the place of the song tapped
     * in the list's own order and `startId` that song; `view` how the screen arranged the list
     * (see [play]). For the Songs page, whose 50,000 songs the screen never holds as songs,
     * this is the only way.
     */
    fun playPage(
        source: String,
        start: Int,
        startId: String?,
        context: String,
        shuffle: Boolean? = null,
        anyStart: Boolean = false,
        view: org.medianexus.music.library.ListView? = null,
    ) = send(AppCommands.PLAY, Bundle().apply {
        putBoolean("byPage", true)
        startId?.let { putString("startId", it) }
        putInt("start", start)
        putString("context", context)
        putString("source", source)
        putInt("shuffle", when (shuffle) { null -> -1; true -> 1; false -> 0 })
        putBoolean("anyStart", anyStart)
        AppCommands.putView(this, view)
    })

    /** Plays these straight after the song playing, ahead of anything else queued. */
    fun playNext(songs: List<Song>) = enqueue(songs, playNext = true)

    /** Adds these to the end of "Next in queue". */
    fun addToQueue(songs: List<Song>) = enqueue(songs, playNext = false)

    private fun enqueue(songs: List<Song>, playNext: Boolean) =
        send(AppCommands.ENQUEUE, Bundle().apply { putString("songs", AppCommands.songsArg(songs)); putBoolean("playNext", playNext) })

    // Each of these names the queue entry it means by the entry's own number (`UpNext.uids`),
    // as the screen that calls them had it when the person tapped: if a song ends while the
    // command is on its way, it still means the song that was tapped.

    /** Moves the entry `uid` to straight after the entry `after` (null: straight after the
     *  song playing), into "Next in queue" (`queued`) or out of it. */
    fun move(uid: Long, after: Long?, queued: Boolean) {
        if (uid == AppCommands.NONE) return
        send(AppCommands.MOVE, Bundle().apply { putLong("uid", uid); putLong("after", after ?: AppCommands.NONE); putBoolean("queued", queued) })
    }

    /** Takes the entry `uid` out of the queue. */
    fun remove(uid: Long) {
        if (uid == AppCommands.NONE) return
        send(AppCommands.REMOVE, Bundle().apply { putLong("uid", uid) })
    }

    /** Empties "Next in queue" (`queued`), or the rest of the list after it. */
    fun clear(queued: Boolean) = send(AppCommands.CLEAR, Bundle().apply { putBoolean("queued", queued) })

    /** Plays the entry `uid` now. */
    fun jump(uid: Long) {
        if (uid == AppCommands.NONE) return
        send(AppCommands.JUMP, Bundle().apply { putLong("uid", uid) })
    }

    fun playPause() = withController { c ->
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) {
            c.pause()
        } else {
            // A song that failed (the server was out of reach) is asked for again.
            if (c.playerError != null || c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seek(positionMs: Long) = withController {
        it.seekTo(positionMs)
        positionNow.value = positionMs
    }

    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }

    fun setRepeat(mode: Int) = withController { it.repeatMode = QueuePlayer.exoRepeat(mode) }

    /** Off, the whole queue, one song, and round again: the repeat button. */
    fun cycleRepeat() = setRepeat((repeatNow.value + 1) % 3)

    /** Likes or unlikes the song playing. */
    fun toggleLike() {
        val song = currentSong.value ?: return
        accounts.state.value?.likes?.toggle(song)
    }

    /** Whether a list's route key names a page the library keeps whole (see
     *  `Library.keptSongs`). */
    private fun keptPage(source: String): Boolean =
        source == "liked" || source.startsWith("album:") || source.startsWith("playlist:") || source == org.medianexus.music.library.Library.SONGS ||
            source == org.medianexus.music.downloads.DownloadedViews.SOURCE || source.startsWith("${org.medianexus.music.downloads.DownloadedViews.SOURCE}:")

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            // Not connected yet (the app has just opened, or the service was stopped):
            // connect, then do it.
            connect()
            future?.addListener({ controller?.let(block) }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun send(command: SessionCommand, args: Bundle) = withController { it.sendCustomCommand(command, args) }

    private companion object {
        const val TICK_MILLIS = 250L
    }
}

/**
 * When to try connecting to the player again: after a failure or a drop, once after a pause
 * that doubles each time (1, 2, 4, 8, 16 seconds, then every 30), for as long as the screens
 * want the player; a connection that holds starts the count again. Plain Kotlin, with the
 * waiting handed in, so it can be tested on its own.
 */
internal class Reconnect(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val connect: () -> Unit,
) {
    private var wanted = false
    private var tries = 0
    private val attempt = Runnable { if (wanted) connect() }

    /** The screens want the player (they are in front). */
    fun wanted() {
        wanted = true
    }

    fun connected() {
        tries = 0
        cancel(attempt)
    }

    /** It failed to connect, or the connection dropped. */
    fun lost() {
        if (!wanted) return
        cancel(attempt)
        post(attempt, pauseBefore(tries++))
    }

    /** The screens have let the player go: no more tries. */
    fun stop() {
        wanted = false
        tries = 0
        cancel(attempt)
    }

    companion object {
        fun pauseBefore(attempt: Int): Long = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
    }
}
