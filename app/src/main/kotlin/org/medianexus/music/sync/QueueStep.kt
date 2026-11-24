package org.medianexus.music.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.medianexus.music.library.Arrange
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.PlayQueue
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Choices

/** What of the queue is saved on the server: the songs around the one playing, which one
 *  that is, and how far into it (milliseconds). */
data class QueueUpload(val ids: List<String>, val current: String, val positionMs: Long)

/** What another device has been playing: its saved queue, and the song it had got to, in
 *  words, for "Continue here". */
data class Elsewhere(val queue: PlayQueue, val title: String, val artist: String)

/**
 * The player's side of sharing the queue: the playback service, while there is one. Every
 * call is made on the main thread.
 */
interface QueueHere {
    /** What to save of the queue as it stands; null with nothing in it, or a radio station
     *  playing (not something another device could pick up by id). */
    fun upload(): QueueUpload?

    /** The id of the song at the cursor, if there is one. */
    fun currentId(): String?

    /** Something is playing, or meant to be. */
    fun playing(): Boolean

    /** When the queue was last changed here by the person, or played on (seconds since
     *  1970): a queue saved by another device is taken up only if it is newer than this. */
    fun stamp(): Long

    /**
     * Puts another device's queue in place of this one, at `cursor` and `positionMs` into
     * that song, as changed at `stamp`. Putting it there is not a change to pass on.
     *
     * `choice` is the place "Continue here" took in the order of choices
     * ([org.medianexus.music.playback.Choices]) when it was tapped: the queue is played,
     * if nothing has taken a later place by the time it is ready. Null for a queue taken up
     * without being asked: left paused, and it takes no place of its own.
     *
     * `still` is asked at the last moment, when the queue is ready to go in: false, and it
     * isn't done (sharing has been switched off meanwhile).
     *
     * False if it wasn't done (also: the account has gone, or something is playing here that
     * a queue taken up unasked must not replace).
     */
    suspend fun replace(songs: List<Song>, cursor: Int, positionMs: Long, stamp: Long, choice: Long?, still: () -> Boolean): Boolean
}

/** The rules for sharing the queue, as plain functions: Windows' `upload_queue`,
 *  `note_elsewhere`, `adopt_queue` and `continue_here` in its src/sync.rs. */
object QueueShare {
    /** The name this device gives when it saves the queue, so it can tell its own from
     *  others' (Windows' `device_name`). */
    fun deviceName(id: String): String = "NexusMusic-$id"

    /** A made-up id for this device: eight hex digits, as Windows makes its own. */
    fun newDeviceId(random: java.util.Random = java.security.SecureRandom()): String = "%08x".format(random.nextInt())

    /**
     * What is passed on of a queue of `size` songs in playing order, at `cursor`: at most
     * [QUEUE_BEHIND] songs before the current one and [QUEUE_AHEAD] from it onwards, radio
     * stations left out. Null with nothing playing, or a station.
     */
    fun window(size: Int, cursor: Int, song: (Int) -> Song, positionSeconds: Double): QueueUpload? {
        if (cursor !in 0 until size) return null
        val current = song(cursor)
        // A radio station isn't something another device could pick up by id.
        if (current.radioUrl.isNotEmpty() || current.id.isEmpty()) return null
        val from = (cursor - QUEUE_BEHIND).coerceAtLeast(0)
        val to = minOf(size, cursor + QUEUE_AHEAD)
        val ids = ArrayList<String>(to - from)
        for (i in from until to) song(i).let { if (it.radioUrl.isEmpty() && it.id.isNotEmpty()) ids += it.id }
        return QueueUpload(ids, current.id, (positionSeconds.coerceAtLeast(0.0) * 1000.0).toLong())
    }

    /** When a saved queue was last changed, in seconds since 1970; 0 if the server's text
     *  isn't a time. */
    fun changedAt(theirs: PlayQueue): Long = Arrange.parseTime(theirs.changed)

    /**
     * Whether another device has been playing in the last half hour something other than
     * what is at the cursor here: then it is offered as "Continue here".
     */
    fun offered(theirs: PlayQueue, device: String, currentId: String?, now: Long): Boolean {
        val age = now - changedAt(theirs)
        return theirs.songs.isNotEmpty() && theirs.changedBy != device && age in 0 until HALF_HOUR && currentId != theirs.current
    }

    /** Whether a saved queue is taken up without being asked: another device changed it
     *  after anything was last done here, and nothing is playing here. */
    fun adopted(theirs: PlayQueue, device: String, stamp: Long, playing: Boolean): Boolean =
        theirs.songs.isNotEmpty() && theirs.changedBy != device && changedAt(theirs) > stamp && !playing

    /** Where to pick a saved queue up: which of its songs, and how far in (milliseconds):
     *  from the start of one that had all but ended. */
    fun place(theirs: PlayQueue): Pair<Int, Long> {
        val cursor = theirs.songs.indexOfFirst { it.id == theirs.current }.coerceAtLeast(0)
        val duration = theirs.songs[cursor].duration.toDouble()
        val position = theirs.position / 1000.0
        return cursor to if (duration > 0.0 && position >= duration - 1.0) 0L else theirs.position.coerceAtLeast(0)
    }

    const val HALF_HOUR = 30 * 60L
}

/**
 * Sharing the play queue and the place in it through the server's own saved-queue feature, as
 * the Windows app does: whichever device changed it last has the say; a device that's playing
 * keeps its own. The phone saves its queue under its own device name a few seconds after the
 * person changes it, if nothing newer has happened meanwhile (see [upload]); asks the server for the saved queue at each look; takes it up, paused,
 * if another device saved it after anything was last done here and nothing is playing; and
 * otherwise, if another device has been playing in the last half hour, offers "Continue
 * here" (see [continueHere]). It never starts playing by itself, and with the setting off it
 * offers nothing and puts nothing in the player, whatever was already on its way.
 *
 * The account's; the player's side ([here]) is the playback service's while it has one.
 */
class QueueStep(
    private val save: suspend (device: String, upload: QueueUpload) -> Unit,
    private val saved: suspend () -> PlayQueue,
    /** This device's name on the server (see [QueueShare.deviceName]). */
    private val device: () -> String,
    /** Sharing is switched on and the server may be asked now. */
    private val may: () -> Boolean,
    /** The setting itself is on, whether or not the server can be reached just now. With it
     *  off nothing is offered, and nothing of another device's is put in the player. */
    private val on: () -> Boolean = may,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Where the player is spoken to: the main thread, unless a test says otherwise. (Asked
     *  for when first needed: there is no main thread to name where there is no Android.) */
    onMain: CoroutineDispatcher? = null,
) {
    private val main: CoroutineDispatcher by lazy { onMain ?: Dispatchers.Main.immediate }

    /** The player, while the playback service has this account. */
    @Volatile var here: QueueHere? = null

    private val offer = MutableStateFlow<Elsewhere?>(null)

    /** What another device has been playing in the last half hour, for "Continue here";
     *  null when there is nothing to offer. */
    val elsewhere: StateFlow<Elsewhere?> = offer.asStateFlow()

    private var soon: Job? = null
    @Volatile private var uploadedAt = 0L

    /** The queue here is one taken up from another device, and nothing has been done to it
     *  or played of it here since: it is that device's still, and is not this phone's to save. */
    @Volatile private var tookUp = false

    /** Saves take turns, in the order they were asked for. */
    private val saving = Mutex()

    /** The queue changed here, by the person's doing: save it on the server once things have
     *  settled (Windows' `queue_changed_here`). */
    fun changedHere() {
        tookUp = false
        if (!may()) return
        synchronized(this) {
            soon?.cancel()
            soon = scope.launch {
                pause(InStep.SETTLE_MILLIS)
                scope.launch { upload() }
            }
        }
    }

    /** Playback has moved on within the same song: saved now and then, not at every note
     *  of it (each save sends the whole of the queue's ids again). */
    fun progressed() {
        tookUp = false
        if (clock() - uploadedAt >= PROGRESS_SECONDS) changedHere()
    }

    /**
     * Saves the queue as it stands, **if it is still the newest thing that happened**. One
     * save at a time, in the order asked: two can't finish the wrong way round. And not:
     *
     * - a queue taken up from another device with nothing done to it here since (a save asked
     *   for before it was taken up would otherwise pass that device's queue on as this
     *   phone's own, changed now);
     * - when another device has saved its queue since the change this save is for. The server
     *   is asked immediately before, and its queue is left alone if it is another device's
     *   and was saved after this phone's queue last changed (three seconds pass between a
     *   change here and its save, and the save takes the server's clock for its time: it
     *   would land on top of anything saved in between).
     *
     * Between that question and the save another device can still save, and be overwritten:
     * the server has no way to make a save conditional (as for a playlist's edits).
     */
    private suspend fun upload() = saving.withLock {
        if (!may() || tookUp) return@withLock
        val (upload, changed) = withContext(main) { here?.let { it.upload() to it.stamp() } } ?: return@withLock
        upload ?: return@withLock
        try {
            val theirs = saved()
            if (!may() || tookUp) return@withLock
            if (theirs.songs.isNotEmpty() && theirs.changedBy != device() && QueueShare.changedAt(theirs) > changed) return@withLock
            uploadedAt = clock()
            save(device(), upload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // The next change saves it again.
        }
    }

    /**
     * Asks the server for the saved queue, and takes it up if another device changed it
     * after anything was last done here (Windows' `check_server_queue`). A device that's
     * playing keeps its own queue.
     */
    suspend fun check() {
        if (!may()) return
        val theirs = try {
            saved()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            return
        }
        // The setting was switched off while the answer was on its way: it is not used.
        if (!may()) return
        val name = device()
        // With no player (the playback service isn't up) there is nothing to take it up, and
        // nothing playing here: it is only noted, for when there is.
        if (here == null) return note(theirs, name, null)
        withContext(main) {
            val player = here
            note(theirs, name, player?.currentId())
            if (player == null || !QueueShare.adopted(theirs, name, player.stamp(), player.playing())) return@withContext
            val (cursor, position) = QueueShare.place(theirs)
            // Taken up: there's nothing left to offer, and it is not this phone's to save.
            if (player.replace(theirs.songs, cursor, position, QueueShare.changedAt(theirs), choice = null, still = on)) {
                tookUp = true
                offer.value = null
            }
        }
    }

    /** Remembers what another device has been playing in the last half hour, so it can be
     *  offered as "Continue here" (Windows' `note_elsewhere`). */
    private fun note(theirs: PlayQueue, name: String, currentId: String?) = synchronized(offering) {
        // (Asked under the same lock as [forget], which is called once the setting is off:
        // whichever of the two comes second, nothing is left on offer.)
        offer.value = if (on() && QueueShare.offered(theirs, name, currentId, clock())) {
            val song = theirs.songs.firstOrNull { it.id == theirs.current } ?: theirs.songs.first()
            Elsewhere(theirs, song.title, song.artistLabel)
        } else {
            null
        }
    }

    private val offering = Any()

    /**
     * Takes up what another device was playing, from where it had got to, and plays it: the
     * person asked for it (Windows' `continue_here`). It is a choice like any other: it takes
     * its place in the order of choices now, at the tap, and by the time its queue is ready
     * it is played only if nothing has taken a later place (a pause, something else chosen).
     */
    fun continueHere() {
        val theirs = offer.value?.queue ?: return
        offer.value = null
        if (!on()) return
        val place = Choices.take()
        scope.launch(main) {
            val player = here ?: return@launch
            val (cursor, position) = QueueShare.place(theirs)
            player.replace(theirs.songs, cursor, position, QueueShare.changedAt(theirs), choice = place, still = on)
        }
    }

    /** Nothing is offered any more (sharing switched off). */
    fun forget() = synchronized(offering) {
        offer.value = null
    }

    private companion object {
        /** How often the place in a song that is simply playing is saved (Windows: every
         *  twenty seconds; a phone may be on a data plan). */
        const val PROGRESS_SECONDS = 60L
    }
}
