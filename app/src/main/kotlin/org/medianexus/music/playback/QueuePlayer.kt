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
 * ExoPlayer's playlist always holds the queue's songs in exactly the order they will play,
 * and its own shuffle is never turned on: shuffling reorders what is to come. Everything
 * that changes the queue, from whichever side, goes through here and is put to ExoPlayer by
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
    private val items = HashMap<Long, MediaItem>()
    private val main = Handler(Looper.getMainLooper())

    /** A queue made ready for the session to hand to the player (see [stage]). */
    private var staged: Queue? = null

    init {
        exo.shuffleModeEnabled = false
        exo.repeatMode = exoRepeat(queue.repeat)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = followPlayer()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && queue.repeat == Repeat.OFF && !queue.isEmpty) {
                    // The end of the queue: park on the first song, ready to go again, as
                    // Windows does.
                    exo.pause()
                    exo.seekTo(0, 0)
                    followPlayer()
                }
            }
        })
    }

    // ---- What the session and the car see --------------------------------------------------

    override fun getState(): State {
        val state = super.getState()
        val builder = state.buildUpon()
            // Ours, not ExoPlayer's (which stays off).
            .setShuffleModeEnabled(queue.shuffle)
            .setPlaylistMetadata(MediaMetadata.Builder().setTitle(queue.context.ifEmpty { null }).build())
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
        staged = null
        if (ready != null && mediaItems.map(::uidOf) == ready.playingUids()) {
            // A queue this player made ready itself (the car chose a song, or asked for one
            // aloud): take it with everything known about it.
            queue.adopt(ready)
        } else {
            // Someone else's list of songs, which plays as it is.
            val songs = mediaItems.map { songOf(it) }
            queue.replace(songs, if (startIndex == C.INDEX_UNSET) 0 else startIndex, context = "", shuffle = false)
        }
        load(if (startIndex == C.INDEX_UNSET || startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        // Added by hand, as far as this app is concerned: into "Next in queue", at the top
        // if put straight after the song playing.
        enqueue(mediaItems.map { songOf(it) }, playNext = index == queue.cursor + 1)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        for (place in (fromIndex until toIndex.coerceAtMost(queue.order.size)).reversed()) queue.dropAt(place)
        sync()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        queue.move(fromIndex, toIndex, newIndex)
        sync()
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        // Not something this app's screens or a car asks for; played as a list of its own.
        val songs = queue.playingOrder().toMutableList()
        val replaced = mediaItems.map { songOf(it) }
        repeat(toIndex - fromIndex) { songs.removeAt(fromIndex) }
        songs.addAll(fromIndex, replaced)
        queue.replace(songs, queue.cursor.coerceAtMost(songs.size - 1), queue.context, shuffle = false)
        load(exo.currentPosition)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (seekCommand == COMMAND_SEEK_TO_MEDIA_ITEM && mediaItemIndex > queue.cursor + 1 && queue.queuedAhead() > 0) {
            // Jumping down the queue past songs queued by hand: they stay next, after it.
            jumpAhead(mediaItemIndex - queue.cursor - 1, positionMs)
            return Futures.immediateVoidFuture()
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    // ---- Playing --------------------------------------------------------------------------

    /**
     * Plays a list from one of its songs. `source` is the route key of the page it came from
     * ("album:<id>"), `shuffle` whether it plays shuffled (each list remembers that).
     */
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
        queue.replace(songs, start, context, source, origins, sourceList, shuffle, anyStart, random)
        load(startPositionMs)
        if (playNow) {
            exo.prepare()
            exo.play()
        }
    }

    /**
     * Makes a queue ready for the session to start, for the times the session asks for media
     * items rather than letting this player be told directly (a car choosing a song). The
     * items come back to [handleSetMediaItems], which recognises them.
     */
    fun stage(
        songs: List<Song>,
        start: Int,
        context: String,
        source: String = "",
        sourceList: List<String> = emptyList(),
        shuffle: Boolean = queue.shuffle,
        anyStart: Boolean = false,
        origins: List<Int?>? = null,
    ): Pair<List<MediaItem>, Int> {
        val next = Queue()
        next.repeat = queue.repeat
        next.replace(songs, start, context, source, origins, sourceList, shuffle, anyStart, random)
        staged = next
        val items = next.order.map { itemOf(next.songs[it], next.uids[it]) }
        return items to next.cursor
    }

    /** The queue as it stands, for the session to start again where it is. */
    fun stageCurrent(): Pair<List<MediaItem>, Int>? {
        if (queue.isEmpty) return null
        val next = Queue()
        next.adopt(queue)
        staged = next
        return next.order.map { itemOf(next.songs[it], next.uids[it]) } to next.cursor
    }

    /** The song at the cursor, as the session shows it; null with an empty queue. Changes
     *  nothing. */
    fun currentItem(): MediaItem? {
        val uid = queue.currentUid() ?: return null
        return queue.current()?.let { itemOf(it, uid) }
    }

    /** The saved queue, from the last run: paused where it stopped. */
    fun restore(saved: SavedQueue) {
        if (!queue.isEmpty) return
        queue.restore(saved)
        load((saved.position * 1000).toLong())
    }

    /** Nothing to play any more (signed out). */
    fun clear() {
        queue.clear()
        items.clear()
        exo.stop()
        exo.clearMediaItems()
        changed(this)
    }

    /** Puts the whole queue to ExoPlayer afresh, at the current song. */
    private fun load(positionMs: Long) {
        items.keys.retainAll(queue.uids.toSet())
        if (queue.isEmpty) {
            exo.clearMediaItems()
        } else {
            exo.setMediaItems(queue.order.map { itemOf(queue.songs[it], queue.uids[it]) }, queue.cursor, positionMs.coerceAtLeast(0))
        }
        queue.trackChanged()
        changed(this)
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
        exo.repeatMode = exoRepeat(queue.repeat)
        changed(this)
    }

    /** Off, the whole queue, one song, and round again: the repeat button. */
    fun cycleRepeat() = setRepeat((queue.repeat + 1) % 3)

    // ---- Editing the queue ----------------------------------------------------------------

    /** Songs queued by hand: into "Next in queue", at the top with `playNext`. */
    fun enqueue(songs: List<Song>, playNext: Boolean) {
        if (songs.isEmpty()) return
        val wasEmpty = queue.isEmpty
        queue.queueUp(songs, playNext)
        if (wasEmpty) load(0) else sync()
    }

    /** Removes the entry `offset` places after the current song. */
    fun removeAhead(offset: Int) {
        val at = queue.cursor + 1 + offset
        if (offset < 0 || at >= queue.order.size) return
        queue.dropAt(at)
        sync()
    }

    /** Moves the entry `from` places after the current song to `to` places after it, into
     *  "Next in queue" or out of it. */
    fun moveAhead(from: Int, to: Int, queued: Boolean) {
        queue.moveAhead(from, to, queued)
        sync()
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

    /**
     * Makes ExoPlayer's playlist match the queue's playing order without disturbing the song
     * that's playing: what comes before it and after it is replaced around it, in as few
     * changes as possible, so the next song stays loaded when it hasn't changed. If the song
     * that's playing has left the queue, or the queue has moved to another song (a jump), the
     * player goes there.
     */
    private fun sync(positionMs: Long = 0) {
        val target = queue.playingUids()
        items.keys.retainAll(target.toSet())
        val now = (0 until exo.mediaItemCount).map { uidOf(exo.getMediaItemAt(it)) }
        val playing = exo.currentMediaItem?.let(::uidOf)
        val k = if (playing != null) target.indexOf(playing) else -1
        if (k < 0) {
            if (target.isEmpty()) exo.clearMediaItems() else exo.setMediaItems(target.map(::itemFor), queue.cursor, positionMs)
            queue.trackChanged()
            changed(this)
            return
        }
        val c = exo.currentMediaItemIndex
        val wantBefore = target.subList(0, k)
        if (now.subList(0, c) != wantBefore) replace(0, c, wantBefore)
        val haveAfter = now.subList(c + 1, now.size)
        val wantAfter = target.subList(k + 1, target.size)
        if (haveAfter != wantAfter) {
            var head = 0
            while (head < haveAfter.size && head < wantAfter.size && haveAfter[head] == wantAfter[head]) head++
            var tail = 0
            while (tail < haveAfter.size - head && tail < wantAfter.size - head &&
                haveAfter[haveAfter.size - 1 - tail] == wantAfter[wantAfter.size - 1 - tail]) tail++
            replace(k + 1 + head, k + 1 + haveAfter.size - tail, wantAfter.subList(head, wantAfter.size - tail))
        }
        if (queue.cursor != k) exo.seekTo(queue.cursor, positionMs)
        queue.trackChanged()
        changed(this)
    }

    private fun replace(from: Int, to: Int, uids: List<Long>) {
        val list = uids.map(::itemFor)
        when {
            list.isEmpty() -> exo.removeMediaItems(from, to)
            from == to -> exo.addMediaItems(from, list)
            else -> exo.replaceMediaItems(from, to, list)
        }
    }

    /** ExoPlayer moved on by itself, or was told to by next, previous or a queue entry. */
    private fun followPlayer() {
        val uid = exo.currentMediaItem?.let(::uidOf) ?: return
        val place = queue.placeOf(uid)
        if (place >= 0 && place != queue.cursor) {
            queue.moveTo(place)
            changed(this)
        } else if (place >= 0) {
            changed(this)
        }
    }

    // ---- Media items ----------------------------------------------------------------------

    private fun itemFor(uid: Long): MediaItem {
        items[uid]?.let { return it }
        val entry = queue.uids.indexOf(uid)
        return itemOf(queue.songs[entry], uid)
    }

    private fun itemOf(song: Song, uid: Long): MediaItem = items.getOrPut(uid) {
        MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(songUri(song.id))
            .setTag(SongTag(song, uid))
            .setMediaMetadata(metadataFor(song))
            .build()
    }

    /** The song a media item stands for: ours carry it; anyone else's is read from its
     *  details. */
    private fun songOf(item: MediaItem): Song {
        uidOf(item)?.let { uid ->
            val entry = queue.uids.indexOf(uid)
            if (entry >= 0) return queue.songs[entry]
            staged?.let { s -> s.uids.indexOf(uid).takeIf { it >= 0 }?.let { return s.songs[it] } }
        }
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
}
