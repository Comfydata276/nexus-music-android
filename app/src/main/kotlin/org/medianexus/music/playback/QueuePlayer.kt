package org.medianexus.music.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.random.Random
import org.medianexus.music.net.Song

/**
 * The player the session (and so the car, the notification and the app's own screens)
 * controls: ExoPlayer, with the queue model ([Queue]) as the truth about what plays.
 *
 * ExoPlayer's playlist holds the queue's songs in exactly the order they will play, and its
 * own shuffle is never turned on: shuffling reorders what is to come. It holds a window of
 * the queue around the song playing, not all of it (see [QueueSync]); the session, the car
 * and the app's screens see that window, and the model holds the rest. Everything that
 * changes the queue, from whichever side, goes through here and is put to ExoPlayer by
 * [sync]; everything ExoPlayer does by itself (moving on to the next song) is put back to
 * the model by [followPlayer]. So "next", gapless joins, the car's queue and the app's queue
 * can't disagree.
 *
 * Main thread only, as the player is.
 */
@OptIn(UnstableApi::class)
class QueuePlayer(
    private val exo: ExoPlayer,
    val queue: Queue,
    /** The details shown for a song: in the notification, the car, the app's screens. */
    private val metadataFor: (Song) -> MediaMetadata,
    /** Told after anything that changes the queue or the place in it. */
    private val changed: (QueuePlayer) -> Unit,
    /** The message to show for a playback error, or null to show the player's own. */
    private val explain: (PlaybackException, Song?) -> PlaybackException? = { _, _ -> null },
    private val random: Random = Random.Default,
) : ForwardingSimpleBasePlayer(exo) {
    /** The media items of the entries the player holds, by entry number. */
    private val items = HashMap<Long, MediaItem>()

    /** Media items made ahead of need, off the main thread (see [ready]): taken from here
     *  the first time each is wanted, and whatever is left is dropped at the next change. */
    private val made = java.util.concurrent.ConcurrentHashMap<Long, MediaItem>()

    /** The songs of those entries, by song id, for the player's loading threads (which are
     *  told a song's id and need to know what kind of file it is). */
    private val held = java.util.concurrent.ConcurrentHashMap<String, Song>()

    /** The song with this id, if the player holds it. Safe from any thread. */
    fun songFor(id: String): Song? = held[id]
    private val main = Handler(Looper.getMainLooper())

    /** A queue made ready for the session to hand to the player (see [stage]), and the
     *  entry numbers of the items that were handed over for it. */
    private var staged: Queue? = null
    private var stagedUids: List<Long> = emptyList()

    /** ExoPlayer's playlist, as [QueueSync] sees it. */
    private val playlist = object : QueueSync.Playlist {
        override val count: Int get() = exo.mediaItemCount
        override fun uidAt(index: Int): Long? = if (index in 0 until exo.mediaItemCount) uidOf(exo.getMediaItemAt(index)) else null
        override val currentIndex: Int get() = exo.currentMediaItemIndex
        override fun set(slots: List<QueueSync.Slot>, index: Int, positionMs: Long) = exo.setMediaItems(slots.map(::itemFor), index, positionMs)
        override fun clear() = exo.clearMediaItems()
        override fun remove(from: Int, to: Int) = exo.removeMediaItems(from, to)
        override fun add(at: Int, slots: List<QueueSync.Slot>) = exo.addMediaItems(at, slots.map(::itemFor))
        override fun seekTo(index: Int, positionMs: Long) = exo.seekTo(index, positionMs)
    }

    init {
        exo.shuffleModeEnabled = false
        applyRepeat()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = followPlayer()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED || queue.isEmpty) return
                // The end of the queue (the window moves along with the song playing, so the
                // player only runs out when the queue has).
                if (queue.repeat == Repeat.ALL) {
                    // Too long for the player to hold whole, so its own "repeat all" was
                    // off: round to the top by hand.
                    queue.moveTo(0)
                    sync(0)
                    exo.prepare()
                    exo.play()
                } else if (queue.repeat == Repeat.OFF) {
                    // Park on the first song, ready to go again, as Windows does.
                    exo.pause()
                    queue.moveTo(0)
                    sync(0)
                }
            }
        })
    }

    // ---- What the session and the car see --------------------------------------------------

    override fun getState(): State {
        val state = super.getState()
        val builder = state.buildUpon()
            // Ours, not ExoPlayer's (whose shuffle stays off, and whose repeat is off when it
            // holds only part of the queue).
            .setShuffleModeEnabled(queue.shuffle)
            .setRepeatMode(exoRepeat(queue.repeat))
            .setPlaylistMetadata(MediaMetadata.Builder().setTitle(queue.context.ifEmpty { null }).build())
        // Next is always there to press while there is a queue: on the last song ExoPlayer
        // withdraws it, and Windows goes round to the top (see handleSeek).
        if (!queue.isEmpty) {
            builder.setAvailableCommands(state.availableCommands.buildUpon().add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build())
        }
        if (exo.isCommandAvailable(COMMAND_GET_TIMELINE)) {
            // The playlist is already in playing order, whatever shuffle says: nobody should
            // look for a shuffled order underneath it.
            builder.setPlaylist(InOrderTimeline(exo.currentTimeline), exo.currentTracks, exo.mediaMetadata)
        }
        exo.playerError?.let { error -> explain(error, queue.current())?.let { builder.setPlayerError(it) } }
        return builder.build()
    }

    /** The state may have changed in a way ExoPlayer didn't announce (our shuffle). */
    fun refresh() = invalidateState()

    /** How many songs come after the one playing, in the whole queue (the player and the
     *  session hold only the nearest of them). */
    val ahead: Int get() = (queue.order.size - queue.cursor - 1).coerceAtLeast(0)

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        setRepeat(ourRepeat(repeatMode))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val ready = staged
        val handed = stagedUids
        staged = null
        stagedUids = emptyList()
        val given = mediaItems.map(::uidOf)
        if (ready != null && given == handed) {
            // A queue this player made ready itself (the car chose a song, or asked for one
            // aloud): take it with everything known about it. (Unless it is this very queue,
            // handed over to be started again where it is.)
            if (ready !== queue) queue.adopt(ready)
        } else if (given.isNotEmpty() && given.all { it != null }) {
            // Items this player made ready, but not the ones ready now: a start that was
            // overtaken by a newer choice while it was on its way here. Dropped.
            return Futures.immediateVoidFuture()
        } else {
            // Someone else's list of songs, which plays as it is.
            val songs = mediaItems.map { songOf(it) }
            queue.replace(songs, if (startIndex == C.INDEX_UNSET) 0 else startIndex, context = "", shuffle = false)
        }
        load(if (startIndex == C.INDEX_UNSET || startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    // The indexes a controller sends are places in the player's playlist, which is a window
    // of the queue: `base` is the place in the queue of the window's first item.
    private val base: Int get() = queue.cursor - exo.currentMediaItemIndex

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        // Added by hand, as far as this app is concerned: into "Next in queue", at the top
        // if put straight after the song playing.
        enqueue(mediaItems.map { songOf(it) }, playNext = index == exo.currentMediaItemIndex + 1)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val gone = (fromIndex until toIndex.coerceAtMost(exo.mediaItemCount)).mapNotNullTo(HashSet()) { playlist.uidAt(it) }
        queue.dropUids(gone)
        sync()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        queue.move(base + fromIndex, base + toIndex, base + newIndex)
        sync()
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        // Not something this app's screens or a car asks for; played as a list of its own.
        val songs = queue.playingOrder().toMutableList()
        val replaced = mediaItems.map { songOf(it) }
        val from = (base + fromIndex).coerceIn(0, songs.size)
        repeat((toIndex - fromIndex).coerceAtMost(songs.size - from)) { songs.removeAt(from) }
        songs.addAll(from, replaced)
        queue.replace(songs, queue.cursor.coerceAtMost(songs.size - 1), queue.context, shuffle = false)
        load(exo.currentPosition)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val here = exo.currentMediaItemIndex
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                // Where Next goes is the queue's to say, not the playlist's: the playlist may
                // hold only part of the queue, and has no next at all after its last song
                // (Media3 passes that on as a seek to nowhere). On the last song, round to the
                // top, as Windows does, playing on only if repeat is on.
                val step = queue.next() ?: return Futures.immediateVoidFuture()
                if (step.place == queue.cursor + 1) return super.handleSeek(here + 1, 0, COMMAND_SEEK_TO_MEDIA_ITEM)
                if (!step.carriesOn) exo.pause()
                queue.moveTo(step.place)
                sync(0)
                return Futures.immediateVoidFuture()
            }
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> return when {
                // Nothing before this one, and not asked to start it again.
                mediaItemIndex == C.INDEX_UNSET -> Futures.immediateVoidFuture()
                // The start of this song: asked for, or because it is the first in the queue
                // (the playlist, asked with "repeat all", would say its own last item; on
                // Windows "previous" on the first song starts it again).
                mediaItemIndex == here || queue.cursor == 0 -> super.handleSeek(here, 0, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                else -> super.handleSeek(here - 1, 0, COMMAND_SEEK_TO_MEDIA_ITEM)
            }
            COMMAND_SEEK_TO_MEDIA_ITEM -> if (mediaItemIndex > here + 1 && queue.queuedAhead() > 0) {
                // Jumping down the queue past songs queued by hand: they stay next, after it.
                jumpAhead(mediaItemIndex - here - 1, positionMs)
                return Futures.immediateVoidFuture()
            }
            // Every other seek is the player's own to do.
            else -> Unit
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    // ---- Playing --------------------------------------------------------------------------

    /**
     * A queue of these songs, to play from `start`, made ready to be played ([play]) or handed
     * to the session ([stage]). `source` is the route key of the page they came from
     * ("album:<id>"), `shuffle` whether they play shuffled (each list remembers that).
     *
     * Building it is all the work there is in starting a long list (a copy of 20,000 songs,
     * a number for each, a shuffle), and it touches nothing of the player: so this may be
     * called on any thread, and for a long list should be called off the main one.
     */
    fun prepare(
        songs: List<Song>,
        start: Int,
        context: String,
        source: String = "",
        origins: List<Int?>? = null,
        sourceList: List<String> = emptyList(),
        shuffle: Boolean,
        anyStart: Boolean = false,
    ): Queue = Queue().apply { replace(songs, start, context, source, origins, sourceList, shuffle, anyStart, random) }.also(::ready)

    /**
     * Makes the media items the player will want first for a queue that isn't its own yet
     * (the window around its current song), so that they are there, already made, when the
     * queue is taken on. Safe from any thread: it only adds to maps that are.
     */
    private fun ready(next: Queue) {
        for (slot in QueueSync.slots(next)) made.getOrPut(slot.uid) { build(next.songs[slot.entry], slot.uid) }
    }

    /** Plays a list from one of its songs (see [prepare] for the rest). */
    fun play(
        songs: List<Song>,
        start: Int,
        context: String,
        source: String = "",
        origins: List<Int?>? = null,
        sourceList: List<String> = emptyList(),
        shuffle: Boolean = queue.shuffle,
        anyStart: Boolean = false,
        startPositionMs: Long = 0,
        playNow: Boolean = true,
    ) {
        if (songs.isEmpty()) return
        play(prepare(songs, start, context, source, origins, sourceList, shuffle, anyStart), startPositionMs, playNow)
    }

    /** Plays a queue made ready by [prepare]. */
    fun play(next: Queue, startPositionMs: Long = 0, playNow: Boolean = true) {
        if (next.isEmpty) return
        // A queue made ready earlier and not yet handed over has been overtaken.
        staged = null
        stagedUids = emptyList()
        queue.adopt(next)
        load(startPositionMs)
        if (playNow) {
            exo.prepare()
            exo.play()
        }
    }

    /**
     * Hands a queue made ready by [prepare] to the session to start, for the times the
     * session asks for media items rather than letting this player be told directly (a car
     * choosing a song). What is handed back is the part of it the player will hold (see
     * [QueueSync]), and which of those to start at; the items come back to
     * [handleSetMediaItems], which recognises them.
     */
    fun stage(next: Queue): Pair<List<MediaItem>, Int> = hand(next)

    /** The queue as it stands, for the session to start again where it is. */
    fun stageCurrent(): Pair<List<MediaItem>, Int>? = if (queue.isEmpty) null else hand(queue)

    private fun hand(next: Queue): Pair<List<MediaItem>, Int> {
        val slots = QueueSync.slots(next)
        staged = next
        stagedUids = slots.map { it.uid }
        return slots.map { itemOf(next.songs[it.entry], it.uid) } to next.cursor - QueueSync.window(next.cursor, next.order.size).first
    }

    /** The song at the cursor, as the session shows it; null with an empty queue. Changes
     *  nothing. */
    fun currentItem(): MediaItem? {
        val uid = queue.currentUid() ?: return null
        return queue.current()?.let { itemOf(it, uid) }
    }

    /** The saved queue, from the last run, as a queue ready to be put back with [restore].
     *  Touches nothing of the player: for a long queue, call it off the main thread. */
    fun revive(saved: SavedQueue): Queue = Queue().apply { restore(saved) }.also(::ready)

    /** Puts back the queue from the last run: paused where it stopped. */
    fun restore(revived: Queue, positionMs: Long) {
        if (!queue.isEmpty) return
        // Shuffle is as it was set when the service started (from the settings); a saved
        // queue doesn't say.
        revived.shuffle = queue.shuffle
        queue.adopt(revived)
        load(positionMs)
    }

    /** Nothing to play any more (signed out). */
    fun clear() {
        queue.clear()
        items.clear()
        held.clear()
        staged = null
        stagedUids = emptyList()
        exo.stop()
        exo.clearMediaItems()
        changed(this)
    }

    /** Puts the queue to ExoPlayer afresh, at the current song. */
    private fun load(positionMs: Long) {
        QueueSync.load(queue, playlist, positionMs)
        settled()
    }

    // ---- Shuffle and repeat ---------------------------------------------------------------

    fun setShuffle(on: Boolean) {
        if (queue.shuffle == on) return
        queue.setShuffle(on, random)
        sync()
        invalidateState()
    }

    fun setRepeat(mode: Int) {
        queue.repeat = mode.coerceIn(Repeat.OFF, Repeat.ONE)
        applyRepeat()
        changed(this)
    }

    /** Off, the whole queue, one song, and round again: the repeat button. */
    fun cycleRepeat() = setRepeat((queue.repeat + 1) % 3)

    /** ExoPlayer repeats one song by itself, and the whole queue only when it holds the
     *  whole queue: otherwise going round is done here, when it reaches the end. */
    private fun applyRepeat() {
        val mode = when {
            queue.repeat == Repeat.ONE -> Player.REPEAT_MODE_ONE
            queue.repeat == Repeat.ALL && QueueSync.whole(queue) -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        if (exo.repeatMode != mode) exo.repeatMode = mode
    }

    // ---- Editing the queue ----------------------------------------------------------------

    /** Songs queued by hand: into "Next in queue", at the top with `playNext`. */
    fun enqueue(songs: List<Song>, playNext: Boolean) {
        if (songs.isEmpty()) return
        val wasEmpty = queue.isEmpty
        queue.queueUp(songs, playNext)
        if (wasEmpty) load(0) else sync()
    }

    /** Takes the entry with this number out of what's to come. */
    fun removeEntry(uid: Long) {
        if (queue.dropUid(uid)) sync()
    }

    /** Moves the entry with this number to straight after the entry `after` (null: straight
     *  after the song playing), into "Next in queue" or out of it. */
    fun moveEntry(uid: Long, after: Long?, queued: Boolean) {
        if (queue.moveUid(uid, after, queued)) sync()
    }

    /** Plays the entry with this number now, if it is still to come. */
    fun jumpTo(uid: Long) {
        if (!queue.jumpToUid(uid)) return
        sync(0)
        exo.prepare()
        exo.play()
    }

    fun clearAhead(queued: Boolean) {
        if (queue.isEmpty) return
        queue.clearAhead(queued)
        sync()
    }

    /**
     * The list the queue came from (`source`, a route key) has changed, here or on the
     * server: the queue follows it (see [Queue.follow]). The song playing carries on.
     */
    fun follow(source: String, songs: List<Song>) {
        if (source.isEmpty() || source != queue.source) return
        if (queue.follow(songs.map { it.id }, songs::getOrNull, random)) sync()
    }

    /** Plays the entry `offset` places after the current song now. */
    fun jumpAhead(offset: Int, positionMs: Long = 0) {
        if (!queue.jumpAhead(offset)) return
        sync(positionMs)
        exo.prepare()
        exo.play()
    }

    // ---- Keeping ExoPlayer in step ----------------------------------------------------------

    /** Makes ExoPlayer's playlist match the queue (see [QueueSync.sync]), after a change to
     *  the queue. */
    private fun sync(positionMs: Long = 0) {
        QueueSync.sync(queue, playlist, positionMs)
        settled()
    }

    /** After the playlist has been made to match the queue. */
    private fun settled() {
        // Only the items the player holds, and any made ready and not yet handed over.
        val keep = HashSet<Long>(stagedUids)
        for (index in 0 until exo.mediaItemCount) playlist.uidAt(index)?.let { keep += it }
        items.keys.retainAll(keep)
        // Changed in place, never emptied: the player may be opening a song right now.
        val ids = items.values.mapTo(HashSet()) { it.mediaId }
        held.keys.retainAll(ids)
        // Whatever was made ahead and not taken belongs to a queue that never arrived.
        if (staged == null) made.clear()
        applyRepeat()
        queue.trackChanged()
        changed(this)
    }

    /** ExoPlayer moved on by itself, or was told to by next, previous or a queue entry. */
    private fun followPlayer() {
        val uid = exo.currentMediaItem?.let(::uidOf) ?: return
        val place = queue.placeOf(uid)
        if (place < 0) return
        if (place != queue.cursor) queue.moveTo(place)
        // The window the player holds moves along with the song playing.
        if (QueueSync.sync(queue, playlist)) applyRepeat()
        changed(this)
    }

    // ---- Media items ----------------------------------------------------------------------

    private fun itemFor(slot: QueueSync.Slot): MediaItem = itemOf(queue.songs[slot.entry], slot.uid)

    private fun itemOf(song: Song, uid: Long): MediaItem = items.getOrPut(uid) {
        held[song.id] = song
        made.remove(uid) ?: build(song, uid)
    }

    private fun build(song: Song, uid: Long): MediaItem = MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(songUri(song.id))
        .setTag(SongTag(song, uid))
        .setMediaMetadata(metadataFor(song))
        .build()

    /** The song a media item stands for: ours carry it; anyone else's is read from its
     *  details. */
    private fun songOf(item: MediaItem): Song {
        (item.localConfiguration?.tag as? SongTag)?.let { return it.song }
        val m = item.mediaMetadata
        return Song(
            id = item.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString().orEmpty(),
            duration = ((m.durationMs ?: 0L) / 1000).toInt(),
        )
    }

    /** What our media items carry: the song, and which entry of the queue they are. */
    data class SongTag(val song: Song, val uid: Long)

    private fun uidOf(item: MediaItem): Long? = (item.localConfiguration?.tag as? SongTag)?.uid

    /** Waits on the main thread, without holding it. */
    fun later(delayMs: Long, block: () -> Unit) {
        main.postDelayed(block, delayMs)
    }

    companion object {
        fun exoRepeat(ours: Int): Int = when (ours) {
            Repeat.ALL -> Player.REPEAT_MODE_ALL
            Repeat.ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

        fun ourRepeat(exo: Int): Int = when (exo) {
            Player.REPEAT_MODE_ALL -> Repeat.ALL
            Player.REPEAT_MODE_ONE -> Repeat.ONE
            else -> Repeat.OFF
        }
    }
}

/** A timeline that is the same in shuffle mode as out of it: ours is always in playing order. */
@OptIn(UnstableApi::class)
private class InOrderTimeline(timeline: Timeline) : ForwardingTimeline(timeline) {
    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) =
        timeline.getNextWindowIndex(windowIndex, repeatMode, false)

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) =
        timeline.getPreviousWindowIndex(windowIndex, repeatMode, false)

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) = timeline.getFirstWindowIndex(false)

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean) = timeline.getLastWindowIndex(false)

    /**
     * A song whose length the player doesn't know is given the library's. That is a song the
     * server converts: its stream comes with no length, which the player takes for a live
     * broadcast with no end and nowhere to seek to, and the car and the phone would show it
     * that way. It can be sought in all the same (see [SongReader]), it is as long as the
     * library says to within a second, and it ends when the server ends it.
     */
    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        if (window.durationUs == C.TIME_UNSET) {
            val seconds = (window.mediaItem.localConfiguration?.tag as? QueuePlayer.SongTag)?.song?.duration ?: 0
            if (seconds > 0) {
                window.durationUs = seconds * 1_000_000L
                window.liveConfiguration = null
                window.isDynamic = false
            }
        }
        return window
    }
}
