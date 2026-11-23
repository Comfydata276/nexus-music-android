package org.medianexus.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
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
import kotlinx.coroutines.flow.stateIn
import org.medianexus.music.Accounts
import org.medianexus.music.net.Song

/** What's in the queue, as the screens show it: the songs to come, the first `queuedAhead`
 *  of which are "Next in queue". */
data class UpNext(val songs: List<Song> = emptyList(), val queuedAhead: Int = 0, val context: String = "")

/**
 * Everything the screens need to show and drive playback, and nothing else: it connects to
 * the playback service's session the way the car does, with a `MediaController`, and gives
 * the player's state as `StateFlow`s and its commands as plain calls. There is no other way
 * for a screen to reach the player.
 *
 * Connect with [connect] when the app comes to the front and [release] when it goes; the
 * service carries on playing either way.
 */
@OptIn(UnstableApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerConnection(private val context: Context, accounts: Accounts, scope: CoroutineScope) {
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
    /** Why the song isn't playing, in words, when something went wrong. */
    val error: StateFlow<String?> = errorNow.asStateFlow()

    /** Whether the song playing is liked. */
    val liked: StateFlow<Boolean> = accounts.state
        .flatMapLatest { account -> account?.likes?.liked ?: flowOf(emptySet()) }
        .let { likes -> combine(likes, currentSong) { set, song -> song != null && song.id in set } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val accounts = accounts

    private val listener = object : Player.Listener, MediaController.Listener {
        override fun onEvents(player: Player, events: Player.Events) = read()

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = read()
    }

    private val ticker = object : Runnable {
        override fun run() {
            controller?.let { positionNow.value = it.currentPosition.coerceAtLeast(0) }
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).setListener(listener).buildAsync()
        future = pending
        pending.addListener({
            val connected = runCatching { pending.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            read()
            main.post(ticker)
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        main.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private fun read() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val index = c.currentMediaItemIndex
        val songs = items.map(::songOf)
        currentSong.value = songs.getOrNull(index)
        playingNow.value = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        bufferingNow.value = c.playbackState == Player.STATE_BUFFERING
        positionNow.value = c.currentPosition.coerceAtLeast(0)
        durationNow.value = c.duration.takeIf { it != C.TIME_UNSET } ?: ((currentSong.value?.duration ?: 0) * 1000L)
        val ahead = c.sessionExtras.getInt(PlaybackService.EXTRA_QUEUED_AHEAD)
        queueNow.value = UpNext(songs.drop(index + 1), ahead, c.playlistMetadata.title?.toString().orEmpty())
        shuffleNow.value = c.shuffleModeEnabled
        repeatNow.value = QueuePlayer.ourRepeat(c.repeatMode)
        errorNow.value = c.playerError?.message
    }

    private fun songOf(item: MediaItem): Song =
        (item.localConfiguration?.tag as? QueuePlayer.SongTag)?.song
            ?: Song(id = item.mediaId, title = item.mediaMetadata.title?.toString().orEmpty(), artist = item.mediaMetadata.artist?.toString().orEmpty())

    // ---- Commands -------------------------------------------------------------------------

    /**
     * Plays a list from one of its songs. `context` is what it is, in words ("Slow Rivers");
     * `source` the route key of the page it came from ("album:<id>"), so the list's shuffle
     * is remembered; `shuffle` null plays it as it was last played.
     */
    fun play(songs: List<Song>, start: Int, context: String, source: String = "", shuffle: Boolean? = null, anyStart: Boolean = false) =
        send(AppCommands.PLAY, Bundle().apply {
            putString("songs", AppCommands.songsArg(songs))
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

    /** Moves the song `from` places after the current one to `to` places after it, into
     *  "Next in queue" (`queued`) or out of it. */
    fun move(from: Int, to: Int, queued: Boolean) =
        send(AppCommands.MOVE, Bundle().apply { putInt("from", from); putInt("to", to); putBoolean("queued", queued) })

    /** Takes out the song `offset` places after the current one. */
    fun remove(offset: Int) = send(AppCommands.REMOVE, Bundle().apply { putInt("offset", offset) })

    /** Empties "Next in queue" (`queued`), or the rest of the list after it. */
    fun clear(queued: Boolean) = send(AppCommands.CLEAR, Bundle().apply { putBoolean("queued", queued) })

    /** Plays the song `offset` places after the current one now. */
    fun jump(offset: Int) = send(AppCommands.JUMP, Bundle().apply { putInt("offset", offset) })

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) c.pause() else c.play()
    }

    fun next() = controller?.seekToNext()

    fun previous() = controller?.seekToPrevious()

    fun seek(positionMs: Long) = controller?.seekTo(positionMs)

    fun setShuffle(on: Boolean) = controller?.setShuffleModeEnabled(on)

    fun setRepeat(mode: Int) = controller?.setRepeatMode(QueuePlayer.exoRepeat(mode))

    /** Likes or unlikes the song playing. */
    fun toggleLike() {
        val song = currentSong.value ?: return
        accounts.state.value?.likes?.toggle(song)
    }

    private fun send(command: SessionCommand, args: Bundle) {
        val c = controller
        if (c != null) {
            c.sendCustomCommand(command, args)
        } else {
            // Not connected yet (the app has just opened): connect, then send.
            connect()
            future?.addListener({ controller?.sendCustomCommand(command, args) }, ContextCompat.getMainExecutor(context))
        }
    }

    private companion object {
        const val TICK_MILLIS = 250L
    }
}
