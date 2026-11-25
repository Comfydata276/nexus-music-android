package org.medianexus.music.sync

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import org.medianexus.music.core.Answers
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
import org.medianexus.music.core.Device
import org.medianexus.music.core.Found
import org.medianexus.music.core.Plan
import org.medianexus.music.core.Play
import org.medianexus.music.core.PlayFrom
import org.medianexus.music.core.Stated
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.StatedPlaylist
import org.medianexus.music.net.Timed

/** What an exchange asks of the server: the account's playlists as the server states them
 *  (the sync playlist among them), and the one write, of the record. */
interface StepServer {
    /** The account's playlists, and the server's clock as it answered. */
    suspend fun playlists(): Timed<List<StatedPlaylist>>

    /** One playlist, and the server's clock as it answered. */
    suspend fun playlist(id: String): Timed<StatedPlaylist>

    /** A new, empty playlist of this name: its id. */
    suspend fun create(name: String): String

    /** Replaces the playlist's comment (and keeps the playlist private). The whole value:
     *  sent again, it comes to the same. */
    suspend fun setComment(id: String, comment: String)
}

/**
 * The phone's side of an exchange: its own stores, at the steps the shared core's contract
 * names (the top of the Windows repository's `core/src/step.rs`; [InStep] follows it step
 * by step). Whatever of these needs the disk says whether the disk has it.
 */
interface StepPhone {
    /**
     * Step 3: what this phone holds **now**, the server's answers being in hand. Null while
     * any part of it has not been read from the phone for this account: an exchange with
     * part of the phone's record missing would say "none" for what is only "not read yet",
     * so there is none.
     */
    suspend fun device(): Device?

    /**
     * A number that moves on whenever sharing is switched off (the settings count it). An
     * exchange has leave to go on only while it is the number it began with: switched off
     * and on again while one is on its way is not the same "on". Each of the three things
     * below that change the phone's settings is given the number the exchange began with,
     * **and checks it where the change is committed**: they can wait their turn at the
     * settings' file, and what was true when the exchange last asked may not be by then.
     */
    fun standing(): Long

    /**
     * Step 5: the plays other devices have told of go into the phone's log, **on the
     * disk**, with everything else the log holds in memory (whether or not any is new).
     * Throws `IOException` if the phone couldn't: then the exchange stops, nothing is taken
     * in and nothing written, and the same plays are offered again next time.
     */
    suspend fun keep(plays: List<Play>, sources: List<PlayFrom>)

    /**
     * Step 6: what other devices shared is taken in: the choices (kept, on the disk, before
     * they show), the notes of when songs were added, and Recently played, which is what
     * the phone has at this moment put together with what the plan brought. False if the
     * phone couldn't keep it: then the exchange has failed, and nothing is written.
     * `still` is asked before each of the three is taken in (songs may have to be looked
     * up on the way): what an exchange that has lost its leave brought is not applied.
     */
    suspend fun take(plan: Plan, began: Long, still: () -> Boolean = { true }): Boolean

    /**
     * Step 7.1: the record's text as it was found is kept on the phone, once for this
     * account and this version of the app, before the first write this version makes: a
     * file that is its own proof, made only if it isn't there, never written again. It is
     * written whole under another name beside its place and only then given its own, so a
     * kill part-way leaves nothing at its name (what it leaves is cleared, and never counts
     * as the copy). False if it isn't there and couldn't be made: then nothing is sent.
     */
    fun keepFirst(found: String): Boolean

    /**
     * Step 7.2: whether the write still fits the choices it was made from (the person may
     * have taken a change back meanwhile), which then note, on the disk, that it is being
     * tried. Null if that couldn't be kept: then nothing is sent.
     */
    suspend fun sending(write: JsonElement, began: Long): Boolean?

    /** Step 7.4: the server has answered that it took the write: what it carried is agreed,
     *  and those changes are done with. Kept on the disk. False if it couldn't be kept: the
     *  changes then stay as tried, and the exchange has not finished. */
    suspend fun written(write: JsonElement, began: Long): Boolean
}

/**
 * Keeping the phone in step with the person's other devices: the exchange of the shared
 * record with the server. (The play queue goes another way: see [QueueStep].)
 *
 * **What is decided is the shared core's** ([Core.find], [Core.settle], and the choices'
 * own operations): which playlist holds the record, what the server's answers mean, what
 * to keep, take in and write. This is the phone's part, and it is the core's contract for
 * a caller, step by step ([once]): asking the server, the phone's stores, and the order
 * things are taken, checked and saved in.
 *
 * **One exchange at a time for an account**, in the whole process, from its first request
 * to the last thing it saves: one asked for meanwhile does nothing but note that another
 * is wanted when this one is back. An exchange that has been let go (the account signed
 * out, sharing switched off) is still on its way until its requests have answered, and the
 * next waits for that: a request that changes the server is never abandoned half-way, so
 * that a second exchange can't read the record from before a write that then lands.
 */
class InStep(
    private val server: StepServer,
    private val phone: StepPhone,
    /** The account's name on the server: the sync playlist is the one it owns. */
    private val username: String,
    /** The account's key on the phone: what "one at a time" goes by. */
    private val account: String,
    /** Sharing is switched on, this is still the account signed in, and the server may be
     *  asked now. Asked again immediately before each request that changes the server. */
    private val may: () -> Boolean,
    private val scope: CoroutineScope,
    /** Seconds since 1970. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Something for the Diagnostics page (no names, no addresses, nothing of the record). */
    private val note: (String) -> Unit = {},
) {
    /** How an exchange ended. */
    enum class Outcome {
        /** The record on the server and on the phone are in step. */
        DONE,
        /** Sharing is off, or the server may not be asked: nothing was read or written. */
        NOT_NOW,
        /** Part of what the phone shares hasn't been read from the phone yet: nothing was sent. */
        NOT_READ_YET,
        /** One is already under way; another follows it. */
        BUSY,
        /** The server's record is in a later layout: taken in as far as understood, not written. */
        NEWER_LAYOUT,
        /** The server's record holds, inside a list's choices or a note, something this version
         *  doesn't know and couldn't write back: taken in as far as understood, not written. */
        MORE_THAN_KNOWN,
        /** What this phone has to add would make a record no device would read back: what
         *  the server has was taken in, and nothing was written. */
        WOULD_NOT_BE_READ,
        /** The comment on the server isn't a record this app can read: left exactly as it is. */
        UNREADABLE,
        /** The server didn't say whose the playlist is, or its answers disagree about what
         *  is in it: nothing is read into that, and nothing written on the strength of it. */
        NOT_STATED,
        /** This phone's clock and the server's disagree: what the server has was taken in, and
         *  nothing was written. */
        CLOCK_WRONG,
        /** The server couldn't be asked, or refused, or the phone couldn't keep what came:
         *  tried again at the next look. */
        FAILED,
    }

    private val wrongClock = MutableStateFlow(false)

    /** What is known of this phone's clock beside the server's, since the app started. */
    enum class ClockIs {
        /** No answer has come from the server yet in this run: nothing is known. **Not the
         *  same as right**: nothing that depends on comparing times may be decided by it. */
        UNMEASURED,
        /** Within five minutes of the server's by its last answers; or the server gives no
         *  date, and there is nothing to compare with (the core takes the phone's as right). */
        RIGHT,
        /** More than five minutes from the server's. */
        WRONG,
    }

    private val clockNow = MutableStateFlow(ClockIs.UNMEASURED)

    /** See [ClockIs]. What the queue goes by is the clock **as its own read of the server
     *  measured it** (`QueueStep`): a save that compares times reads the server first, and
     *  that read is the measurement; a save made while a song plays compares none. This is
     *  for what has no read of its own: no queue is saved, or taken up, while it is WRONG. */
    val clockIs: StateFlow<ClockIs> = clockNow.asStateFlow()

    /**
     * This phone's clock disagrees with the server's by more than five minutes, as last
     * compared (the core's `Clock`). While it does the phone takes in what other devices
     * share and shares nothing itself: a time put on a choice by a clock that is ahead is
     * one no other device can ever beat, and a clock far enough ahead would take word of a
     * song's removal for long forgotten and drop it. What changed here waits (it is kept)
     * until the clocks agree.
     */
    val clockWrong: StateFlow<Boolean> = wrongClock.asStateFlow()

    private val again = AtomicBoolean(false)
    private var soon: Job? = null

    /** Something that's shared changed here: pass it on once things have settled, so a burst
     *  of changes goes as one. */
    fun shareSoon() {
        if (!may()) return
        synchronized(this) {
            soon?.cancel()
            // The wait can be called off by a newer change; an exchange once begun is not.
            soon = scope.launch {
                pause(SETTLE_MILLIS)
                scope.launch { exchange() }
            }
        }
    }

    /**
     * Reads what's on the server, has the core decide, and carries out what it decided.
     */
    suspend fun exchange(): Outcome {
        if (!may()) return Outcome.NOT_NOW
        val turn = turnOf(account)
        if (!turn.tryLock()) {
            // One exchange at a time; go again when this one is back.
            again.set(true)
            return Outcome.BUSY
        }
        try {
            // The leave this exchange has, from now to its last step: this account, sharing
            // on, **the same "on" as now**, and whoever asked still wanting it. Asked again
            // before everything it applies here and every request that changes the server.
            val began = phone.standing()
            val asker = kotlin.coroutines.coroutineContext[Job]
            val still = { may() && phone.standing() == began && asker?.isActive != false }
            // (The playlist that holds the record, made by this exchange: what goes into
            // it is decided again, from the top, with it there.)
            return once(began, still, made = null)
                // (Its leave asked again before the read that follows making the playlist.)
                ?: (if (still()) once(began, still, made = true) else Outcome.NOT_NOW)
                ?: Outcome.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            return Outcome.FAILED
        } catch (_: java.io.IOException) {
            // The phone couldn't keep what it was given: nothing was written to the server on
            // the strength of it, and it is offered again at the next exchange.
            note("in step: what other devices shared couldn't be kept on the phone; nothing was written, and it will be asked for again")
            return Outcome.FAILED
        } catch (e: CoreException) {
            // The shared library gave up on something: nothing more of this exchange is done.
            note("in step: ${e.message}")
            return Outcome.FAILED
        } finally {
            turn.unlock()
            if (again.getAndSet(false)) shareSoon()
        }
    }

    /**
     * The core's numbered steps (the top of its `step.rs`), in order. Null when it has just
     * made the playlist that is to hold the record: nothing is written into it on the
     * strength of what was decided before it existed (the person may have taken a change
     * back while the server was making it); the steps are gone through again, once
     * (`made`), and that time nothing is made.
     */
    private suspend fun once(began: Long, still: () -> Boolean, made: Boolean?): Outcome? {
        // 1. Ask the server. Nothing of the phone's state is taken yet: what the person
        // does while these answers are on their way is in what is decided.
        val listed = server.playlists()
        val stated = listed.value.map { Stated(it.id, it.name, it.owner, it.comment) }
        val read = (Core.find(stated, username) as? Found.Ours)?.let { server.playlist(it.id) }
        // 2. With both answers in hand: still this account, with sharing on. The clock is
        // the server's by the last answer, beside the phone's as it arrived.
        val received = clock()
        if (!still()) return Outcome.NOT_NOW
        // 3. Decide, from what the phone holds now.
        val device = phone.device() ?: return Outcome.NOT_READ_YET
        // (Reading the phone's own stores took a moment: asked again before anything of
        // this exchange shows, the clock it measured included.)
        if (!still()) return Outcome.NOT_NOW
        val playlist = read?.value?.let { Stated(it.id, it.name, it.owner, it.comment) }
        val settled = Core.settle(device, Answers(stated, playlist, read?.serverTime ?: listed.serverTime, received))
        if (settled.clock.wrong && !wrongClock.value) note("in step: this phone's clock and the server's disagree by more than five minutes; nothing is shared from here until they agree")
        wrongClock.value = settled.clock.wrong
        clockNow.value = if (settled.clock.wrong) ClockIs.WRONG else ClockIs.RIGHT
        // 4. Stop: say why, and do nothing else.
        settled.stop?.let { stop ->
            note("in step: ${stop.words}")
            return when (stop.kind) {
                "NotARecord" -> Outcome.UNREADABLE
                "OwnerNotStated", "Disagree" -> Outcome.NOT_STATED
                else -> Outcome.FAILED
            }
        }
        val plan = settled.plan ?: return Outcome.FAILED
        // (From here on things are applied on the phone: nothing is, from an exchange that
        // has been let go or has lost its leave. Asked before each.)
        if (!still()) return Outcome.NOT_NOW
        // 5. Keep: on the disk, or the exchange stops here (it throws).
        phone.keep(plan.keepPlays, plan.keepSources)
        if (!still()) return Outcome.NOT_NOW
        // 6. Take in.
        if (!phone.take(plan, began, still)) {
            note("in step: what other devices shared couldn't be kept on the phone; nothing was written, and it will be asked for again")
            return Outcome.FAILED
        }
        // (Taking in waits its turn at the settings' file, and checks its leave there: if
        // it had gone by then, nothing was taken in, and nothing more is done.)
        if (!still()) return Outcome.NOT_NOW
        val held = plan.held?.let { held ->
            if (held.kind != "ClockWrong") note("in step: ${held.words}")
            when (held.kind) {
                "ClockWrong" -> Outcome.CLOCK_WRONG
                "LaterLayout" -> Outcome.NEWER_LAYOUT
                "MoreThanKnown" -> Outcome.MORE_THAN_KNOWN
                else -> Outcome.WOULD_NOT_BE_READ
            }
        }
        // 7. Write, if there is something to write.
        val write = plan.write ?: return held ?: Outcome.DONE
        // No playlist holds the record yet: one is made, and that is all this pass does.
        // **Making it sends nothing of the person's**, so nothing is marked as tried for
        // it and nothing is kept as "the record as found": the decision made once it
        // exists is the only one. (Marked now, a change taken back while the server was
        // making the playlist would look like one sent and then undone, and the undoing
        // would be sent over whatever another device had put there meanwhile.)
        val into = plan.writeInto ?: return withContext(NonCancellable) {
            // Never a second one beside one this exchange has just made.
            if (made == true) return@withContext Outcome.FAILED
            if (!still()) return@withContext Outcome.NOT_NOW
            server.create(PLAYLIST_NAME).ifEmpty { throw ApiError.NotSubsonic("The new playlist came back without an id.") }
            null
        }
        // 7.1. The record as this version first found it is kept, or nothing is sent: the
        // text this very decision read, kept whole before this decision's write.
        if (!phone.keepFirst(plan.writeFound)) {
            note("in step: a copy of the shared record couldn't be kept on the phone first, so nothing was written")
            return Outcome.FAILED
        }
        // 7.2. The plan must still fit the choices, which are saved, as tried, before the
        // request is made.
        if (!still()) return Outcome.NOT_NOW
        val sent = phone.sending(write, began)
        if (!still()) return Outcome.NOT_NOW
        when (sent) {
            null -> return Outcome.FAILED
            false -> {
                // A change the write carries was taken back meanwhile: from step 1 again.
                again.set(true)
                return Outcome.BUSY
            }
            true -> {}
        }
        // 7.3 and 7.4. Not abandoned half-way: once a request that changes the server may
        // have been made, this exchange is on its way until the server has answered,
        // whatever has become of whoever asked for it.
        return withContext(NonCancellable) {
            // Still this account, with sharing on as it was when this began, and not let
            // go: asked immediately before each such request.
            if (!still()) return@withContext Outcome.NOT_NOW
            server.setComment(into, plan.writeText)
            // (Where the test rig can have the process killed: the server has the write, and
            // the phone has not yet kept that it has. In a release build, a null field read.)
            org.medianexus.music.downloads.CrashPoints.reached("step-sent")
            // Only now is what the write carried agreed, and its changes done with: unless
            // this exchange lost its leave while the server had the write. Then nothing of
            // it is noted here; the changes stay as tried, and the next exchange there is
            // makes them again over their own result, which alters nothing.
            if (!still()) return@withContext Outcome.NOT_NOW
            // (Not kept: the exchange is not done, and the same holds.)
            if (!phone.written(write, began)) return@withContext Outcome.FAILED
            // (The note waited its turn at the settings' file, and checked the leave there:
            // if it had gone by then, nothing was noted, and this is not "done".)
            if (still()) Outcome.DONE else Outcome.NOT_NOW
        }
    }

    companion object {
        /** How long after a change before it's passed on, so a burst of changes goes as one. */
        const val SETTLE_MILLIS = 3_000L

        /** How often to look for changes made elsewhere. */
        const val LOOK_MILLIS = 60_000L

        private val turns = HashMap<String, Mutex>()

        /** One exchange at a time for an account, whichever of its objects asks: signing out
         *  and straight back in makes a second set while the first may still be writing. */
        private fun turnOf(account: String): Mutex = synchronized(turns) { turns.getOrPut(account) { Mutex() } }
    }
}
