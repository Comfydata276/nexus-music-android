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
 * not a queue of thousands; `total` is how many are to come in all.
 */
@Immutable
data class UpNext(val songs: List<Song> = emptyList(), val queuedAhead: Int = 0, val context: String = "", val total: Int = songs.size)

/**
 * Where what's playing came from: the list's route key ("album:<id>"), and the place in that
 * list of the song playing (-1 if it isn't from a list, or isn't known). A row is marked as
 * playing only if it is that place in that list, so the same song elsewhere on the page, or
 * in another list, isn't.
 */
@Immutable
data class PlayingFrom(val source: String = "", val origin: Int = -1, val songId: String = "", val paused: Boolean = false) {
    fun isRow(key: String, index: Int): Boolean = source.isNotEmpty() && source == key && origin == index

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

    /** The queue's own number for each song in `queue`, and for the song playing: what a
     *  command names its song by. Read together with `queue`, so the two always match. */
    private var aheadUids: List<Long> = emptyList()

    /** The song playing (or paused on); null with an empty queue. */
    val song: StateFlow<Song?> = currentSong.asStateFlow()
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

    /** Whether the song playing is liked. */
    val liked: StateFlow<Boolean> = accounts.state
        .flatMapLatest { account -> account?.likes?.liked ?: flowOf(emptySet()) }
        .let { likes -> combine(likes, currentSong) { set, song -> song != null && song.id in set } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** What's being played, as the line under the song says it. */
    val format: StateFlow<FormatLine> = combine(currentSong, convertedNow, settings.state.map { it.bitrate }.distinctUntilChanged()) { song, converted, cap ->
        if (song == null) FormatLine("", false) else formatLine(song, cap, converted, liveStream)
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
            main.removeCallbacks(ticker)
            this@PlayerConnection.controller = null
            future = null
            playingNow.value = false
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
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).setListener(listener).buildAsync()
        future = pending
        pending.addListener({
            val connected = runCatching { pending.get() }.getOrNull()
            if (connected == null) {
                if (future === pending) future = null
                return@addListener
            }
            if (future !== pending) {
                // Released while connecting.
                connected.release()
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            read(queueToo = true)
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        main.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private fun read(queueToo: Boolean) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val song = c.currentMediaItem?.let(::songOf)
        currentSong.value = song
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
            aheadUids = items.map { (it.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid ?: AppCommands.NONE }
            queueNow.value = UpNext(
                songs, ahead.coerceAtMost(songs.size), c.playlistMetadata.title?.toString().orEmpty(),
                total = extras.getInt(PlaybackService.EXTRA_AHEAD, songs.size).coerceAtLeast(songs.size),
            )
        }
        shuffleNow.value = c.shuffleModeEnabled
        repeatNow.value = QueuePlayer.ourRepeat(c.repeatMode)
        errorNow.value = c.playerError?.message
        fromNow.value = PlayingFrom(
            source = extras.getString(PlaybackService.EXTRA_SOURCE).orEmpty(),
            origin = extras.getInt(PlaybackService.EXTRA_ORIGIN, -1),
            songId = song?.id.orEmpty(),
            paused = !playing,
        )
        convertedNow.value = extras.getBoolean(PlaybackService.EXTRA_CONVERTED)
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
     * list, in its order. `shuffle` null plays it as it was last played.
     */
    fun play(songs: List<Song>, start: Int, context: String, source: String = "", shuffle: Boolean? = null, anyStart: Boolean = false) =
        send(AppCommands.PLAY, Bundle().apply {
            // A long list that is a page the phone keeps (an album, a playlist, Liked songs)
            // is named, and the service reads it from where the screen read it: writing
            // 20,000 songs out here and reading them back there would hold the main thread
            // for both. A short list, or one put together on the spot, is sent as it is.
            if (songs.size > AppCommands.MOST_SENT && keptPage(source)) {
                putBoolean("byPage", true)
                songs.getOrNull(start)?.let { putString("startId", it.id) }
            } else {
                putString("songs", AppCommands.songsArg(songs))
            }
            putInt("start", start)
            putString("context", context)
            putString("source", source)
            putInt("shuffle", when (shuffle) { null -> -1; true -> 1; false -> 0 })
            putBoolean("anyStart", anyStart)
        })

    /** Plays these straight after the song playing, ahead of anything else queued. */
    fun playNext(songs: List<Song>) = enqueue(songs, playNext = true)

    /** Adds these to the end of "Next in queue". */
    fun addToQueue(songs: List<Song>) = enqueue(songs, playNext = false)

    private fun enqueue(songs: List<Song>, playNext: Boolean) =
        send(AppCommands.ENQUEUE, Bundle().apply { putString("songs", AppCommands.songsArg(songs)); putBoolean("playNext", playNext) })

    // The places below are places in `queue` as it was last published, which is what the
    // screen that calls them is showing. They are turned here into the queue's own number
    // for that entry, and the command carries the number: if a song ends while the command
    // is on its way, it still means the song that was tapped.

    /** Moves the song `from` places after the current one to `to` places after it, into
     *  "Next in queue" (`queued`) or out of it. */
    fun move(from: Int, to: Int, queued: Boolean) {
        val uids = aheadUids
        val uid = uids.getOrNull(from) ?: return
        // The song it should follow, once it has been lifted out: none for the top.
        val rest = uids.filterIndexed { place, _ -> place != from }
        val after = if (to <= 0) AppCommands.NONE else rest.getOrNull(to - 1) ?: rest.lastOrNull() ?: AppCommands.NONE
        send(AppCommands.MOVE, Bundle().apply { putLong("uid", uid); putLong("after", after); putBoolean("queued", queued) })
    }

    /** Takes out the song `offset` places after the current one. */
    fun remove(offset: Int) {
        val uid = aheadUids.getOrNull(offset) ?: return
        send(AppCommands.REMOVE, Bundle().apply { putLong("uid", uid) })
    }

    /** Empties "Next in queue" (`queued`), or the rest of the list after it. */
    fun clear(queued: Boolean) = send(AppCommands.CLEAR, Bundle().apply { putBoolean("queued", queued) })

    /** Plays the song `offset` places after the current one now. */
    fun jump(offset: Int) {
        val uid = aheadUids.getOrNull(offset) ?: return
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
    private fun keptPage(source: String): Boolean = source == "liked" || source.startsWith("album:") || source.startsWith("playlist:")

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
