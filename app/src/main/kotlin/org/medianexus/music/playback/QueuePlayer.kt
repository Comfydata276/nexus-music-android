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
    /**
     * Whether whoever is asking right now names a queue entry by its place on a screen that
     * may be a moment behind: a car, or any controller but the app's own screens (which name
     * an entry by its own number). See [placeIsSure].
     */
    private val byPlace: () -> Boolean = { false },
    /** A clock that only has to run steadily, in milliseconds. */
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    /**
     * Asked just before the player is asked to play, whoever asked: the session (a car, a
     * headset, the notification, the app's screens) or this class itself. The service uses it
     * to be in the foreground before the player asks Android for the sound (see the
     * service's `holdForeground`), and says no when Android will not have it there: then the
     * player is not asked to play at all (see [refuseStart]).
     */
    private val mayPlay: () -> Boolean = { true },
    /**
     * Something to say in the player's error line while nothing is wrong with the player
     * itself: that playing could not be started from where it was asked for (see
     * [refuseStart]). Null for nothing.
     */
    private val notice: () -> PlaybackException? = { null },
    /**
     * Told when a queue handed to the session came back to a player that no longer wants it:
     * its start was overtaken on the way (or, for "carry on", the queue it was to carry on
     * with has been replaced). Media3 goes on to send "make ready" and "play" for it all the
     * same; the service notes whose they are, and answers [mayPrepare] and [mayPlay] with
     * no until that sender is done (see `PlayRequests`).
     */
    private val overtaken: () -> Unit = {},
    /** Asked before the player is made ready at someone's request (see [overtaken]). */
    private val mayPrepare: () -> Boolean = { true },
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

    // ---- Starts, and which of them still stands -----------------------------------------------
    //
    // Something asked to start playing may have to look things up first (the car choosing a
    // song, a spoken request, a long page named by a screen), and by the time it is ready
    // something else may have been chosen. Each start takes a number as it begins
    // ([beginStart]); only the newest stands. Whether one still stands is asked twice: when
    // its queue is handed to the session ([stage]), and again when the session's items reach
    // this player ([handleSetMediaItems]), because the session delivers them later and a
    // choice can be made in between. A change of account overtakes every start ([clear]).

    private var starts = 0L

    /** Something has been asked to start playing: its number. Every start before it has
     *  been overtaken. */
    fun beginStart(): Long = ++starts

    /** Whether the start with this number is still the newest. */
    fun stands(ticket: Long): Boolean = ticket == starts

    /** A queue handed to the session and not yet come back as media items: the entry numbers
     *  of the items handed over, which start it was for (null: not a start, but the queue as
     *  it stood, handed over to be carried on with), the queue's revision then, and the entry
     *  at its cursor then (whose place in the song goes with a "carry on"). */
    private class Staged(val queue: Queue, val uids: List<Long>, val ticket: Long?, val revision: Long, val current: Long?)

    /** Those still on their way, oldest first. Each is told from the others by its items;
     *  none can spoil another by being handed over after it. */
    private val staged = ArrayList<Staged>()

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
                    startPlaying()
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
            builder.setPlaylist(InOrderTimeline(exo.currentTimeline, sent), exo.currentTracks, exo.mediaMetadata)
        }
        val failed = exo.playerError
        if (failed != null) {
            explain(failed, queue.current())?.let { builder.setPlayerError(it) }
        } else if (exo.playbackState == STATE_IDLE) {
            // (An error is only ever shown by a player that is stopped: Media3's rule.)
            notice()?.let { builder.setPlayerError(it) }
        }
        return builder.build()
    }

    /** The state may have changed in a way ExoPlayer didn't announce (our shuffle). */
    fun refresh() = invalidateState()

    /** How many songs come after the one playing, in the whole queue (the player and the
     *  session hold only the nearest of them). */
    val ahead: Int get() = (queue.order.size - queue.cursor - 1).coerceAtLeast(0)

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (!mayPlay()) return Futures.immediateVoidFuture()
            // A start that was refused left the player stopped (see refuseStart): a Play that
            // is allowed makes it ready again, whether or not whoever pressed it thought to.
            if (refused) {
                refused = false
                if (exo.playbackState == STATE_IDLE) exo.prepare()
            }
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    /** Starts the player playing what it holds, from this class's own doing. */
    private fun startPlaying() {
        if (!mayPlay()) return
        refused = false
        exo.prepare()
        exo.play()
    }

    /** Whether the player was stopped by [refuseStart] and has not been started since. */
    private var refused = false

    /**
     * Playing can't be started (Android would not let the service into the foreground, or
     * would not give it the sound): the queue stays as it was chosen, at its place, and the
     * player is stopped rather than left looking as if it were about to play. Stopped, it can
     * carry the service's word on why ([notice]) where a car shows a player's error, with its
     * Play button still there: the next Play that is allowed carries on from here.
     */
    fun refuseStart() {
        refused = true
        if (exo.playWhenReady) exo.pause()
        // (Stopping keeps the queue and the place in the song.)
        if (exo.playbackState != STATE_IDLE) exo.stop()
        invalidateState()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        setRepeat(ourRepeat(repeatMode))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val given = mediaItems.map(::uidOf)
        val at = staged.indexOfLast { it.uids == given }
        if (at >= 0) {
            // A queue this player handed to the session itself. It, and anything handed
            // over before it, is no longer on its way.
            val ready = staged[at]
            repeat(at + 1) { staged.removeAt(0) }
            if (ready.ticket == null) {
                // This very queue, handed over to be carried on with where it is: only if it
                // is still the queue it was. (A newer choice has since replaced it otherwise,
                // and starting that at the old one's place is nobody's wish: nor is starting
                // it at all, which the "play" that follows this would do.)
                if (ready.queue !== queue || ready.revision != queue.revision) return dropped()
                // The same queue, but another song is at its cursor now (Next was pressed on
                // the way): the place that came with this was taken for the song before.
                // Nothing is loaded again; the queue carries on from where it is.
                if (ready.current != queue.currentUid()) return Futures.immediateVoidFuture()
            } else {
                // A start (the car chose a song, or asked for one aloud): asked once more, now
                // that it is here, whether it is still the newest. The session delivers these
                // a moment after they were handed over, and a choice made on the phone in that
                // moment stands.
                if (!stands(ready.ticket)) return dropped()
                queue.adopt(ready.queue)
            }
        } else if (given.isNotEmpty() && given.all { it != null }) {
            // Items this player made ready, but for nothing that is still on its way: a start
            // that was overtaken by a newer choice. Dropped.
            return dropped()
        } else {
            // Someone else's list of songs, which plays as it is: a start like any other.
            beginStart()
            staged.clear()
            val songs = mediaItems.map { songOf(it) }
            queue.replace(songs, if (startIndex == C.INDEX_UNSET) 0 else startIndex, context = "", shuffle = false)
        }
        load(if (startIndex == C.INDEX_UNSET || startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    /**
     * What came back from the session is not wanted any more. Nothing is done with it; and
     * the service is told ([overtaken]), because Media3 follows a hand-over that it took for
     * a success with "make ready" and "play" (a car's in the same breath, in
     * `MediaSessionLegacyStub.handleMediaRequest`), and those would start whatever is in the
     * player now: the newer choice, which the person may have paused.
     */
    private fun dropped(): ListenableFuture<*> {
        overtaken()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!mayPrepare()) return Futures.immediateVoidFuture()
        return super.handlePrepare()
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
        songs.subList(from, from + (toIndex - fromIndex).coerceIn(0, songs.size - from)).clear()
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
                if (step.place == queue.cursor) {
                    // A queue of one song: "the top" is the song that's playing, which there
                    // is nothing to move to. It is started again (and, without repeat, left
                    // paused there), as any queue is at its end.
                    return super.handleSeek(here, 0, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }
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
            COMMAND_SEEK_TO_MEDIA_ITEM -> {
                // "Play that entry of the queue", by its place. If the places have just
                // moved, the place may mean one song to the sender and another here: unless
                // it means the same either way, nothing is played, and the queue is sent
                // again (see placeIsSure).
                if (byPlace() && !placeIsSure(mediaItemIndex)) {
                    sendAgain()
                    return Futures.immediateVoidFuture()
                }
                if (mediaItemIndex > here + 1 && queue.queuedAhead() > 0) {
                    // Jumping down the queue past songs queued by hand: they stay next, after it.
                    jumpAhead(mediaItemIndex - here - 1, positionMs)
                    return Futures.immediateVoidFuture()
                }
            }
            // Every other seek is the player's own to do.
            else -> Unit
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    // ---- A tap on a queue that has just moved ---------------------------------------------
    //
    // Media3 gives a car's queue items their place in the player's playlist as an id, and a
    // tap comes back as that place and nothing more (`MediaSessionLegacyStub.onSkipToQueueItem`).
    // Whenever the places move (the window of the queue slides along, the queue is reordered,
    // something is added or removed, another queue takes its place), every place the car is
    // still showing means another song for a moment, until its screen has caught up. Nothing
    // in a tap says which queue its sender was looking at.
    //
    // It used to be guessed: a place sent within a second of a change was read as it was
    // before the change. That is right for a row the car has not redrawn and wrong for one
    // it has, and with two changes in the second there are three queues the tap could have
    // been made on and only the oldest was considered. Either way a song could play that
    // the driver had not tapped.
    //
    // So nothing is guessed. For a second ([PLACES_SETTLE_MILLIS]) after the places move, a
    // place is acted on only if it means the same entry in every arrangement of that second
    // and now: then it does not matter which the sender saw. Otherwise the tap is ignored,
    // and the queue is sent again, so the car draws it as it is and the next tap is on that.
    // What this costs is a tap that does nothing, in the second after a change, on a row
    // that moved.

    /** The entry numbers of the playlist's items, in order, as last put to the player. */
    private var shown: List<Long> = emptyList()

    /** The same as it was before each change of the last second, oldest first, with when it
     *  stopped being so. */
    private val shownBefore = ArrayDeque<Pair<List<Long>, Long>>()

    /** Counts the times the queue has been sent again unchanged (see [sendAgain]). */
    private var sent = 0

    /** Notes the playlist's places, after anything that may have changed them. */
    private fun placesMayHaveMoved() {
        val now = ArrayList<Long>(exo.mediaItemCount)
        for (index in 0 until exo.mediaItemCount) now += playlist.uidAt(index) ?: NO_ENTRY
        if (now == shown) return
        val at = clock()
        forgetSettled(at)
        if (shown.isNotEmpty()) shownBefore.addLast(shown to at)
        shown = now
    }

    private fun forgetSettled(at: Long) {
        while (shownBefore.isNotEmpty() && at - shownBefore.first().second >= PLACES_SETTLE_MILLIS) shownBefore.removeFirst()
    }

    /**
     * Whether a place in the playlist, sent by someone who names an entry by its place, means
     * one entry whichever queue the sender was looking at: the places have not moved in the
     * last second, or that place means the same entry now as in every arrangement of it.
     */
    private fun placeIsSure(index: Int): Boolean {
        forgetSettled(clock())
        val now = shown.getOrNull(index)
        return shownBefore.all { (before, _) -> before.getOrNull(index).let { it != null && it != NO_ENTRY && it == now } }
    }

    /**
     * Has the session send its queue again, as it stands. The session sends the queue (to a
     * car, by `MediaSessionCompat.setQueue`) when the player says its timeline has changed;
     * the timeline carries a count that only this moves, so that it is "changed" with every
     * song where it was.
     */
    private fun sendAgain() {
        sent++
        invalidateState()
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
        filter: String = "",
    ): Queue = Queue().apply { replace(songs, start, context, source, origins, sourceList, shuffle, anyStart, random, filter) }.also(::ready)

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

    /** Plays a queue made ready by [prepare], now: a start of its own, the newest. */
    fun play(next: Queue, startPositionMs: Long = 0, playNow: Boolean = true) {
        if (next.isEmpty) return
        playFor(beginStart(), next, startPositionMs, playNow)
    }

    /**
     * Plays a queue made ready by [prepare] for the start numbered `ticket`, if that start
     * still stands; if something newer has been chosen since it began, nothing. Returns
     * whether it was played.
     */
    fun playFor(ticket: Long, next: Queue, startPositionMs: Long = 0, playNow: Boolean = true): Boolean {
        if (next.isEmpty || !stands(ticket)) return false
        // Whatever was handed to the session earlier and has not come back has been overtaken.
        staged.clear()
        queue.adopt(next)
        load(startPositionMs)
        if (playNow) startPlaying()
        return true
    }

    /**
     * Hands a queue made ready by [prepare] to the session to start, for the times the
     * session asks for media items rather than letting this player be told directly (a car
     * choosing a song), on behalf of the start numbered `ticket`. Null, and nothing handed
     * over or disturbed, if that start has been overtaken while the queue was being made.
     * What is handed back is the part of it the player will hold (see [QueueSync]), and
     * which of those to start at; the items come back to [handleSetMediaItems], which
     * recognises them and asks again whether the start still stands.
     */
    fun stage(next: Queue, ticket: Long): Pair<List<MediaItem>, Int>? = if (stands(ticket)) hand(next, ticket) else null

    /** The queue as it stands, for the session to start again where it is. Not a start: it
     *  overtakes nothing, and gives way to any start. */
    fun stageCurrent(): Pair<List<MediaItem>, Int>? = if (queue.isEmpty) null else hand(queue, null)

    private fun hand(next: Queue, ticket: Long?): Pair<List<MediaItem>, Int> {
        val slots = QueueSync.slots(next)
        staged += Staged(next, slots.map { it.uid }, ticket, next.revision, next.currentUid())
        // Never many: each is used, overtaken or forgotten within a moment.
        while (staged.size > MOST_STAGED) staged.removeAt(0)
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

    /** Nothing to play any more (signed out). Whatever was being started for the account
     *  that has gone is overtaken. */
    fun clear() {
        beginStart()
        queue.clear()
        items.clear()
        held.clear()
        staged.clear()
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
        // What the playlist holds after the queue's last song depends on it (see
        // QueueSync.round); the song playing is not disturbed.
        if (queue.isEmpty) {
            applyRepeat()
            changed(this)
        } else {
            sync()
        }
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
        if (wasEmpty) {
            load(0)
        } else {
            sync()
        }
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

    /**
     * Moves to this place in the playing order, leaving the order as it is (nothing is
     * taken out or brought forward), and makes the player's playlist follow: for passing
     * over what can't be played to the next that can, wherever in the queue that is.
     * Whether it then plays is as it was.
     */
    fun passTo(place: Int) {
        if (place !in queue.order.indices || place == queue.cursor) return
        queue.moveTo(place)
        sync(0)
    }

    /** Plays the entry with this number now, if it is still to come. */
    fun jumpTo(uid: Long) {
        if (!queue.jumpToUid(uid)) return
        sync(0)
        startPlaying()
    }

    fun clearAhead(queued: Boolean) {
        if (queue.isEmpty) return
        queue.clearAhead(queued)
        sync()
    }

    /**
     * The list the queue came from (`source`, a route key) has changed, here or on the
     * server: the queue follows it (see [Queue.follow]). The song playing carries on. `view`
     * is the list in the order its page shows it, as places in `songs` (see [followList]);
     * a song that joined the list joins the queue only if it `joins` (offline, as on Windows:
     * only what plays with no server).
     */
    fun follow(source: String, songs: List<Song>, view: List<Int> = songs.indices.toList(), joins: (Song) -> Boolean = { true }) {
        if (source.isEmpty() || source != queue.source) return
        if (queue.follow(songs.map { it.id }, { place -> songs.getOrNull(place)?.takeIf(joins) }, random, view)) sync()
    }

    /** Plays the entry `offset` places after the current song now. */
    fun jumpAhead(offset: Int, positionMs: Long = 0) {
        if (!queue.jumpAhead(offset)) return
        sync(positionMs)
        startPlaying()
    }

    // ---- Keeping ExoPlayer in step ----------------------------------------------------------

    /** Makes ExoPlayer's playlist match the queue (see [QueueSync.sync]), after a change to
     *  the queue. */
    private fun sync(positionMs: Long = 0) {
        QueueSync.sync(queue, playlist, positionMs)
        settled()
    }

    /** Lets go of the media items (and the songs, for the loading threads) of everything the
     *  player no longer holds. */
    private fun prune() {
        // Only the items the player holds, and any handed to the session and not yet back.
        val keep = HashSet<Long>()
        for (waiting in staged) keep += waiting.uids
        for (index in 0 until exo.mediaItemCount) playlist.uidAt(index)?.let { keep += it }
        items.keys.retainAll(keep)
        // Changed in place, never emptied: the player may be opening a song right now.
        val ids = items.values.mapTo(HashSet()) { it.mediaId }
        held.keys.retainAll(ids)
        // Whatever was made ahead and not taken belongs to a queue that never arrived.
        if (staged.isEmpty()) made.clear()
    }

    /** How many media items are being held on to: the playlist's, and no more. */
    internal val heldItems: Int get() = items.size

    /** After the playlist has been made to match the queue. */
    private fun settled() {
        prune()
        placesMayHaveMoved()
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
        // The window the player holds moves along with the song playing; and what it has
        // left behind is let go (a long session used to keep every song it had passed).
        if (QueueSync.sync(queue, playlist)) {
            prune()
            placesMayHaveMoved()
            applyRepeat()
        }
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
        /** How long after the playlist's places move a place is only acted on if it means
         *  the same entry as before. */
        const val PLACES_SETTLE_MILLIS = 1_000L

        /** Queues handed to the session and not yet back that are remembered at once. */
        private const val MOST_STAGED = 4

        /** In [shown], an item that isn't one of the queue's entries. Entry numbers start at 1. */
        private const val NO_ENTRY = 0L

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
private class InOrderTimeline(
    private val timeline: Timeline,
    /** Part of what makes one of these equal to another, and nothing else: a timeline with
     *  another count is a changed timeline to Media3, which then sends the queue again
     *  (see `QueuePlayer.sendAgain`). */
    private val sent: Int = 0,
) : Timeline() {
    override fun equals(other: Any?): Boolean = other is InOrderTimeline && other.sent == sent && super.equals(other)

    override fun hashCode(): Int = 31 * super.hashCode() + sent

    override fun getWindowCount() = timeline.windowCount

    override fun getPeriodCount() = timeline.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = timeline.getPeriod(periodIndex, period, setIds)

    override fun getIndexOfPeriod(uid: Any) = timeline.getIndexOfPeriod(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = timeline.getUidOfPeriod(periodIndex)

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
        timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
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
