package org.medianexus.music.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
import org.medianexus.music.core.Here
import org.medianexus.music.core.QueueSaved
import org.medianexus.music.core.QueueSong
import org.medianexus.music.core.SaveVerdict
import org.medianexus.music.core.Theirs
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.PlayQueue
import org.medianexus.music.net.Song
import org.medianexus.music.net.Timed
import org.medianexus.music.playback.Choices

/** What another device has been playing: its saved queue, and the song it had got to, in
 *  words, for "Continue here". `read` is that queue as the core takes it, with the clocks
 *  as the answer that brought it had them. */
data class Elsewhere(val queue: PlayQueue, val title: String, val artist: String, internal val read: Theirs = Theirs()) {
    /**
     * The name of whatever saved that queue, where it is a name worth showing: another
     * music app's own name for itself. Null for a copy of this app, which saves under a
     * made-up number ("NexusMusic-0badc0de") that would mean nothing to anybody: a device
     * has no name of its own that other devices can read.
     */
    val by: String? get() = queue.changedBy.trim().takeIf { it.isNotEmpty() && !it.startsWith("NexusMusic-") && it.length <= 40 && it.all { c -> c >= ' ' } }
}

/**
 * What a look is told is in the queue here: what a save of it would carry, in order; and
 * where a save would carry nothing though something is at the cursor (a radio station),
 * that one thing. Empty only with nothing in the queue. That is the library's contract
 * (`handover.rs`): it compares the lists and not only the song, and takes an empty list
 * for an empty queue.
 */
internal fun idsHere(current: String?, queue: QueueNow?): List<String> =
    queue?.let { Core.queueWindow(it.songs, it.cursor, it.positionSeconds) }?.ids ?: listOfNotNull(current)

/** The queue in the player as it stands: its songs in the order they play, which one is
 *  playing, and how far into it (seconds). */
class QueueNow(val songs: List<QueueSong>, val cursor: Int, val positionSeconds: Double)

/**
 * The player's side of sharing the queue: the playback service, while there is one. Every
 * call is made on the main thread.
 */
interface QueueHere {
    /** The queue as it stands; null with nothing in it. */
    fun queue(): QueueNow?

    /** The id of the song at the cursor, if there is one. */
    fun currentId(): String?

    /** Something is playing, or meant to be. */
    fun playing(): Boolean

    /** When the queue was last changed here by the person, or played on, **by this phone's
     *  own clock** (seconds since 1970; 0 for never). For a queue taken up from another
     *  device, the time that came with it ([replace]). Kept with the queue between runs. */
    fun stamp(): Long

    /** Set while the queue is one taken up from another device with nothing done to it or
     *  played of it here since: when that device saved it, as the server said. Kept with
     *  the queue between runs. */
    fun taken(): String?

    /**
     * A number that moves on whenever what a save of the queue would carry has changed:
     * the list, the current song or the place in it by a jump of any kind (a seek within
     * the same second too), play and pause, another device's queue put in its place. It
     * does not move as a song simply plays on. A save is for the queue as it was at one
     * such number, and is sent only if it is still that number ([QueueStep]): the time
     * alone ([stamp]) is in whole seconds, and says nothing of a pause.
     */
    fun revision(): Long

    /**
     * Puts another device's queue in place of this one, at `cursor` and `positionMs` into
     * that song; `stamp` and `taken` are the queue's own from then on ([stamp], [taken]).
     * Putting it there is not a change to pass on.
     *
     * `choice` is the place "Continue here" took in the order of choices
     * ([org.medianexus.music.playback.Choices]) when it was tapped: the queue is played,
     * if nothing has taken a later place by the time it is ready. Null for a queue taken up
     * without being asked: left paused, and it takes no place of its own; then `asOf` is
     * the newest place there was, and `stampThen` this phone's [stamp], when it was decided
     * to take it up: **if the person has done anything to the queue since, it is not
     * replaced.**
     *
     * `still` is asked at the last moment, when the queue is ready to go in: false, and it
     * isn't done (sharing has been switched off meanwhile).
     *
     * False if it wasn't done (also: the account has gone, or something is playing here that
     * a queue taken up unasked must not replace).
     */
    suspend fun replace(songs: List<Song>, cursor: Int, positionMs: Long, stamp: Long, taken: String, choice: Long?, asOf: Long, stampThen: Long, still: () -> Boolean): Boolean
}

/**
 * Sharing the play queue and the place in it through the server's own saved-queue feature:
 * whichever device changed it last has the say; a device that's playing keeps its own; and
 * **a queue the person has made their own is never replaced by itself**: another device's
 * newer queue is put in this phone's queue's place only while that is empty, or is one taken
 * up from another device and left alone. Otherwise it is offered ("Continue here").
 *
 * **Whose queue is the newest is the shared core's to say** ([Core.queueSave],
 * [Core.queueLook]; the Windows repository's `core/src/handover.rs`, whose numbered steps
 * for a save and for a look are followed below). This is the phone's part: the timers, the
 * requests, and putting a queue in the player. It never starts playing by itself, and with
 * the setting off it offers nothing and puts nothing in the player, whatever was already on
 * its way.
 *
 * The account's; the player's side ([here]) is the playback service's while it has one.
 */
class QueueStep(
    private val save: suspend (device: String, upload: QueueSaved) -> Unit,
    private val saved: suspend () -> Timed<PlayQueue>,
    /** This device's made-up id (eight hex digits), made the first time it is wanted. */
    private val deviceId: () -> String,
    /** The account's key on the phone: what "saves take turns" goes by. */
    private val account: String,
    /** Sharing is switched on, this is still the account signed in, and the server may be
     *  asked now. */
    private val may: () -> Boolean,
    /** The setting itself is on, whether or not the server can be reached just now. With it
     *  off nothing is offered, and nothing of another device's is put in the player. */
    private val on: () -> Boolean = may,
    /** A number that moves on whenever sharing is switched off (the settings count it): a
     *  look, or "Continue here", that was getting a queue ready when it moved puts nothing
     *  in the player, though sharing be on again by then. */
    private val standing: () -> Long = { 0 },
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Where the player is spoken to: the main thread, unless a test says otherwise. (Asked
     *  for when first needed: there is no main thread to name where there is no Android.) */
    onMain: CoroutineDispatcher? = null,
    private val note: (String) -> Unit = {},
) {
    private val main: CoroutineDispatcher by lazy { onMain ?: Dispatchers.Main.immediate }

    /** The player, while the playback service has this account. */
    @Volatile var here: QueueHere? = null

    private val offer = MutableStateFlow<Elsewhere?>(null)

    /** Another device's queue, offered as "Continue here"; null when there is nothing to
     *  offer. With nothing playing here: whatever another device saved that is newer than
     *  the queue here, for as long as it is. While a song plays here: what another device
     *  was playing in the last half hour. (The shared core's rule: `handover::look`.) */
    val elsewhere: StateFlow<Elsewhere?> = offer.asStateFlow()

    private var soon: Job? = null
    @Volatile private var uploadedAt = 0L

    /** Looks take turns too, for the account: one that began later reads the server later,
     *  and what it finds is never put in the player before an earlier look's. */
    private val looking = lookOf(account)

    /** Saves take turns, in the order they were asked for: **for the account**, whichever
     *  of its objects asks (signing out and straight back in makes a second set while a
     *  save of the first's may still be on its way). */
    private val saving = turnOf(account)

    /** The name this device saves the queue under, so it can tell its own from others'. */
    private fun device(): String = Core.deviceName(deviceId())

    /** The queue changed here, by the person's doing: save it on the server once things have
     *  settled. */
    fun changedHere() {
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
        if (clock() - uploadedAt >= PROGRESS_SECONDS) changedHere()
    }

    private fun hereOf(player: QueueHere, name: String) = Here(name, player.currentId(), player.playing(), player.stamp(), player.taken())

    private fun theirsOf(answer: Timed<PlayQueue>, received: Long): Theirs {
        val queue = answer.value
        return Theirs(
            songs = queue.songs.map { QueueSong(it.id, it.duration.toInt().coerceAtLeast(0), it.radioUrl.isNotEmpty()) },
            current = queue.current, position = queue.position.coerceAtLeast(0), changed = queue.changed, changedBy = queue.changedBy,
            date = answer.serverTime, received = received,
        )
    }

    /**
     * A save of the queue as it stands, **made only if it is still the newest thing that
     * happened** (the core's steps for a save). One at a time, in the order asked.
     */
    private suspend fun upload() = saving.withLock {
        if (!may()) return@withLock
        try {
            // (With no player there is nothing to save, and no main thread need be asked.)
            if (here == null) return@withLock
            val name = device()
            // The queue this save is for: as the player has it at this moment, by number.
            val (player, prepared) = withContext(main) { here?.let { it to it.revision() } } ?: return@withLock
            val (now, mine) = withContext(main) { player.queue() to hereOf(player, name) }
            if (withContext(main) { player.revision() } != prepared) return@withLock
            val upload = now?.let { Core.queueWindow(it.songs, it.cursor, it.positionSeconds) } ?: return@withLock
            // 1 and 2. While a song is playing here the queue is this phone's to keep, and is
            // saved without a look. Otherwise the server's saved queue is read first, and the
            // clocks are compared as that very read has them.
            if (Core.queueReadsFirst(mine)) {
                val theirs = theirsOf(saved(), clock())
                // (A save that can't be made now is still owed: the next look finds this
                // phone's queue the newer, and comes back here.)
                if (Core.queueSave(theirs, mine) != SaveVerdict.SAVE) return@withLock
            }
            // 3. Immediately before sending: the queue is still the one this save was for
            // (nothing has been done to it, or to playing it, since: the same player, at
            // the same number), and it is still this account with sharing on. Otherwise
            // nothing is sent: the change that came after has a save of its own, or the next
            // look finds one owed.
            val same = withContext(main) { here === player && player.revision() == prepared }
            if (!same || !may()) return@withLock
            uploadedAt = clock()
            // (Not abandoned half-way: see InStep.)
            withContext(NonCancellable) { save(name, upload) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // Still owed: the next look finds it.
        } catch (e: CoreException) {
            note("in step: ${e.message}")
        }
    }

    /**
     * A look at the server's saved queue (the core's steps for a look): another device's
     * newer queue is taken up, paused, if this phone has nothing of the person's to lose by
     * it (its queue is empty, or is one taken up and left alone); otherwise it is offered as
     * "Continue here"; and this phone's own save, if it is owed, is made.
     */
    suspend fun check() = looking.withLock {
        if (!may()) return@withLock
        // (The "on" this look has: not one that comes after a switching off.)
        val began = standing()
        try {
            val answer = saved()
            val theirs = theirsOf(answer, clock())
            // The setting was switched off while the answer was on its way: it is not used.
            if (!may()) return@withLock
            val name = device()
            // With no player (the playback service isn't up) there is nothing to take the
            // queue up or to offer it to: it is looked at again when there is.
            if (here == null) return@withLock forget()
            val (seen, asOf) = withContext(main) { here?.let { (hereOf(it, name) to it.queue()) to Choices.latest } } ?: return@withLock forget()
            // (What is in the queue here, as it would be saved: the same song current in
            // another list is another queue.)
            val mine = seen.first.copy(ids = idsHere(seen.first.current, seen.second))
            val looked = Core.queueLook(theirs, mine, clock())
            withContext(main) {
                val player = here ?: return@withContext
                offered(looked.offer?.let { answer.value.songs.getOrNull(it) }?.let { Elsewhere(answer.value, it.title, it.artistLabel, theirs) })
                when (looked.then) {
                    "take_up" -> looked.place?.let { place ->
                        // Taken up: there's nothing left to offer; and it is that device's
                        // still, not this phone's to save, until something is done to it here.
                        if (player.replace(answer.value.songs, place.cursor, (place.position * 1000).toLong(), place.stamp, place.taken, choice = null, asOf = asOf, stampThen = mine.stamp, still = { on() && standing() == began })) offered(null)
                    }
                    // This phone's queue is newer than what the server holds: its save is
                    // owed. (Unless one is already on its way.)
                    "save" -> if (soon?.isActive != true) scope.launch { upload() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // Looked at again at the next look. And an offer made from an earlier answer is
            // no longer known to stand: it is not left there to be tapped.
            forget()
        } catch (e: CoreException) {
            note("in step: ${e.message}")
        }
    }

    /** What is offered as "Continue here", or nothing. */
    private fun offered(what: Elsewhere?) = synchronized(offering) {
        // (Asked under the same lock as [forget], which is called once the setting is off:
        // whichever of the two comes second, nothing is left on offer. And an offer that
        // was taken, or that the person acted past, is not made again.)
        offer.value = if (on()) what?.takeIf { idOf(it.queue) != used } else null
    }

    private val offering = Any()

    /** The offer that was last taken, or acted past: the time of that save and who made it.
     *  The same one is never made again; what that device saves next is another. */
    private var used: Pair<String, String>? = null

    private fun idOf(queue: PlayQueue) = queue.changed to queue.changedBy

    /** The person has done something to the queue, or to playing it, here: whatever was on
     *  offer is no longer the newer of the two, and that offer is done with. */
    fun actedPast() = synchronized(offering) {
        offer.value?.let { used = idOf(it.queue) }
        offer.value = null
    }

    /**
     * Takes up what another device was playing, from where it had got to, and plays it: the
     * person asked for it. It is a choice like any other: it takes its place in the order of
     * choices now, at the tap, and by the time its queue is ready it is played only if
     * nothing has taken a later place (a pause, something else chosen, a change to the queue).
     */
    fun continueHere() {
        val theirs = synchronized(offering) {
            // (Taken: that offer is done with.)
            offer.value?.also { used = idOf(it.queue) }.also { offer.value = null }
        } ?: return
        if (!on()) return
        val began = standing()
        val asked = Choices.take()
        scope.launch {
            val place = try {
                Core.queuePlace(theirs.read)
            } catch (e: CoreException) {
                return@launch note("in step: ${e.message}")
            }
            withContext(main) {
                val player = here ?: return@withContext
                player.replace(theirs.queue.songs, place.cursor, (place.position * 1000).toLong(), place.stamp, place.taken, choice = asked, asOf = asked, stampThen = player.stamp(), still = { on() && standing() == began })
            }
        }
    }

    /** Whether something is playing here, or meant to be: asked of the player, on its own
     *  thread. False with no player. */
    suspend fun playingHere(): Boolean {
        val player = here ?: return false
        return withContext(main) { player.playing() }
    }

    /** Nothing is offered any more: sharing was switched off, or there is no player, or the
     *  person has just done something to the queue here while nothing was playing (what
     *  another device saved is offered only while it is the newer, and it no longer is). */
    fun forget() = synchronized(offering) {
        offer.value = null
    }

    private companion object {
        private val turns = HashMap<String, Mutex>()

        /** One save at a time for an account (see [saving]). */
        private fun turnOf(account: String): Mutex = synchronized(turns) { turns.getOrPut(account) { Mutex() } }

        private val looks = HashMap<String, Mutex>()

        /** One look at a time for an account (see [looking]). */
        private fun lookOf(account: String): Mutex = synchronized(looks) { looks.getOrPut(account) { Mutex() } }

        /** How often the place in a song that is simply playing is saved (Windows: every
         *  twenty seconds; a phone may be on a data plan). */
        const val PROGRESS_SECONDS = 60L
    }
}
