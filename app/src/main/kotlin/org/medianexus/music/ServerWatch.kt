package org.medianexus.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.medianexus.music.net.ApiError

/** Where the app stands with the server: offline mode switched on by hand, and whether the
 *  server could be reached when it was last asked. */
data class Reach(
    val manual: Boolean = false,
    val unreachable: Boolean = false,
    /** Whether it is known which: false only for the moment after an account is taken on,
     *  before the server has answered or failed to. Then what is on the phone is shown
     *  first, and nothing waits on the server that has an answer here (the car, started
     *  cold in a dead spot, must not sit waiting to find out there is no signal). */
    val sure: Boolean = true,
) {
    /** Nothing should be expected of the server (Windows' `is_offline`). */
    val offline: Boolean get() = manual || unreachable
}

/**
 * Notices that the server has stopped answering, and that it is back, as Windows does
 * (`set_unreachable`, `check_connection` and `check_server` in src/offline.rs).
 *
 * A request that couldn't reach the server is a reason to ask it straight away whether it is
 * there (a ping), not proof that it isn't: one cover timing out is not the server gone. If
 * the ping fails too, the server is out of reach: everything that asks "offline?" says yes
 * (only what is on the phone plays, changes are held, fetching ahead stops), and the server
 * is asked again, quietly, until it answers.
 *
 * **It is back only when a check has returned a real, successful answer**, exactly as
 * Windows has it. Not when something answered a request (a proxy's error page in front of a
 * server that is down is an answer to the request and not from the server: counting it used
 * to announce "Back online", start the downloads and the sender, and then drop back to
 * offline when the check behind it was found not to be a real answer); such an answer is
 * only a reason to ask now rather than at the next turn. And not when the check itself comes
 * back as an error, whatever kind: Windows' `check_connection` takes any error but a refused
 * sign-in as "still out of reach". When it is back, `cameOnline` runs: held changes are
 * sent, downloads carry on, failed pages and covers are asked for again.
 *
 * Only a sign-in the server refuses signs anyone out (`signInGone`), as on Windows, which
 * then asks for the password again.
 *
 * **Asked only while there is a reason to know** (`wanted`: the app in front, something
 * playing or meant to be, downloads or held changes waiting), **and less often the longer it
 * has been gone** ([againAfter]): a phone left in a drawer out of reach of its server does
 * not ping it four times a minute for ever.
 *
 * **Each check belongs to the account it was made for**: an answer that arrives after that
 * account has gone ([reset]) changes nothing.
 *
 * Offline mode switched on by hand asks nothing at all. Switched off, the server is asked
 * whether it is really there before it is assumed to be, **and until it has answered the
 * app is still offline** (out of reach, and not sure of it: see [manual]); if it is there,
 * `caughtUp` runs (held changes are sent, downloads carry on), as Windows' `check_connection`
 * catches up after any check that finds the server, but nothing says "Back online": the
 * switch was in the person's hand. If it isn't, it is out of reach, and its coming back is
 * the usual one.
 */
class ServerWatch(
    private val scope: CoroutineScope,
    /** Asks the server whether it is there; throws the request's error if not. */
    private val ping: suspend () -> Unit,
    private val cameOnline: () -> Unit,
    private val signInGone: () -> Unit,
    /** Whether asking can do any good (Android 17, a server on the home network and no
     *  permission to reach it: it can't; asking resumes when it is allowed). */
    private val mayAsk: () -> Boolean = { true },
    private val againMillis: Long = AGAIN_MILLIS,
    private val caughtUp: () -> Unit = {},
    /** The first ask after an account is taken on: the same question, given up on sooner
     *  (see [begin]). */
    private val firstPing: suspend () -> Unit = ping,
    /** Whether there is a reason to know now whether the server is back (see the class). */
    private val wanted: () -> Boolean = { true },
    /** The clock the time out of reach is counted on (milliseconds). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val now = MutableStateFlow(Reach())
    val state: StateFlow<Reach> = now.asStateFlow()

    val offline: Boolean get() = now.value.offline

    private var asking: Job? = null
    private var loop: Job? = null
    private val lock = Any()

    /**
     * **One ask at a time, and the newest answer wins.** Each ask takes the next number as it
     * begins; an answer is acted on only if no ask begun after it has already been. The
     * loop's periodic ask used to go out beside whatever [check] had in flight: a slow one
     * that failed after a quicker, later one had succeeded put the app back out of reach.
     *
     * **Asking whether it is the newest, and acting on it, are one step** under [lock], for
     * every way an ask can end (also "may not ask", which used to skip the question): asked
     * first and acted on after, an ask that had been given up for a newer one could still put
     * its answer over the newer one's. Everything else that changes [state] takes the same
     * lock; what is to be told to the rest of the app is told after it is let go.
     */
    private val turns = java.util.concurrent.atomic.AtomicLong()
    private var applied = 0L

    /** When the ask now in flight began (by [clock]). */
    @Volatile private var askedAt = 0L

    /**
     * Asks, unless an ask is already in flight. `afresh`: something has just happened that
     * makes an answer worth having now (the app came to the front, Play, a network came
     * back); an ask that has been out a while may be hanging on a connection that will never
     * answer, and is then given up for a new one.
     */
    private fun ask(first: Boolean = false, afresh: Boolean = false) {
        synchronized(lock) {
            val out = asking
            if (out?.isActive == true) {
                if (!afresh || clock() - askedAt < STALE_ASK_MILLIS) return
                out.cancel()
            }
            askedAt = clock()
            asking = scope.launch { askOnce(first) }
        }
    }

    /** Moves on whenever the account does: a check made for an earlier one changes nothing. */
    private val generation = java.util.concurrent.atomic.AtomicLong()

    /** When the server was found out of reach (by [clock]). */
    @Volatile private var goneAt = 0L

    /** Offline mode has just been switched off, and the server not yet heard from. */
    @Volatile private var switchedOff = false

    /** The server was taken to be out of reach without being asked (no network at
     *  start-up): its first answer is not a return worth announcing. */
    @Volatile private var assumed = false

    /**
     * An account has just been taken on (the app has started, or someone signed in). If the
     * phone has no network at all, the server is out of reach **from this instant**: nothing
     * waits to find that out. Otherwise it is asked at once, and given less time than usual to
     * answer; until it has answered or failed to, the state says it isn't [Reach.sure].
     */
    fun begin(networkThere: Boolean) {
        synchronized(lock) {
            if (now.value.manual) return
            if (networkThere) {
                now.value = now.value.copy(sure = false)
            } else {
                assumed = true
                gone()
            }
        }
        // (Asked all the same with no network: a server on the phone itself, or over a link
        // Android doesn't count as a network, answers.)
        if (networkThere) ask(first = true) else check()
    }

    /** The phone has lost its network: the server is out of reach until it answers. */
    fun noNetwork() = synchronized(lock) {
        if (!now.value.manual && !now.value.unreachable) gone()
    }

    /**
     * Offline mode, by hand, as Settings has it. **Switched off, the app is not online yet**:
     * it is out of reach, and not sure of it, until a check of the server has succeeded
     * (given the few seconds the first check after start-up gets). Until then nothing that
     * waits on "no longer offline" is let go: the sender of held changes, the downloads. It
     * used to become "online" the instant the switch moved, on no evidence, and the sender
     * and the downloads started against a server nobody had heard from.
     */
    fun manual(on: Boolean) {
        synchronized(lock) {
            val was = now.value
            if (was.manual == on) return
            if (on) {
                switchedOff = false
                now.value = was.copy(manual = true)
                return
            }
            // (Its first answer after this is no return worth announcing: see [answered].)
            switchedOff = true
            goneAt = clock()
            now.value = was.copy(manual = false, unreachable = true, sure = false)
        }
        ask(first = true, afresh = true)
    }

    /**
     * A request got an answer of some kind (true), or couldn't reach the server at all
     * (false). Neither settles anything by itself: an answer while the server is taken to be
     * out of reach is a reason to ask it now whether it is really back (the answer may be a
     * proxy's page), and a failure while it is taken to be there, a reason to ask whether it
     * has gone.
     */
    fun reached(ok: Boolean) {
        if (ok == now.value.unreachable) check()
    }

    /** Asks the server now whether it is there (once at a time). */
    fun check() {
        if (now.value.manual) return
        ask()
    }

    /** Something now wants to know (the app has come to the front, Play was pressed): if the
     *  server is out of reach it is asked now, not at the loop's next turn. */
    fun nudge() {
        if (now.value.unreachable && !now.value.manual) ask(afresh = true)
    }

    /** The phone has a network again, or may now reach the home network: worth asking. */
    fun networkBack() {
        if (now.value.unreachable && !now.value.manual) ask(afresh = true)
    }

    /** Whoever was signed in has gone: what was found out about their server goes too. */
    fun reset() {
        generation.incrementAndGet()
        synchronized(lock) {
            loop?.cancel()
            loop = null
            asking?.cancel()
            asking = null
            assumed = false
            now.value = now.value.copy(unreachable = false, sure = true)
        }
    }

    private suspend fun askOnce(first: Boolean = false) {
        val asked = generation.get()
        val turn = turns.incrementAndGet()
        // (May not be asked: the same as no answer.)
        val error: ApiError? = if (!mayAsk()) {
            NOT_ASKED
        } else {
            try {
                if (first) firstPing() else ping()
                null
            } catch (e: ApiError) {
                e
            }
        }
        val tell: (() -> Unit)? = synchronized(lock) {
            // The account this was asked for has gone meanwhile: its answer is nobody's.
            if (generation.get() != asked) return
            // An ask begun after this one has already been answered: this answer is the older
            // news, and changes nothing. Asked and acted on in one step (see [turns]).
            if (turn < applied) return
            applied = turn
            when {
                error == null -> answered()
                error.signInRefused -> {
                    now.value = now.value.copy(sure = true)
                    switchedOff = false
                    signInGone
                }
                // No answer, an error page from something in front of the server, or an
                // error from the server itself: none of those is the server back, and none a
                // reason to sign out (Windows' check_connection). Carry on with what's on the
                // phone and keep asking.
                else -> {
                    switchedOff = false
                    gone()
                    null
                }
            }
        }
        tell?.invoke()
    }

    /** The server answered a ping: back, if it was out of reach; caught up with, if offline
     *  mode has just been switched off. Under [lock]; returns what is to be told after it. */
    private fun answered(): (() -> Unit)? {
        if (now.value.manual) return null
        // Found there straight after offline mode was switched off: caught up with, and
        // nothing said (the switch was in the person's hand).
        val quiet = switchedOff
        switchedOff = false
        if (now.value.unreachable) return back(quiet)
        if (!now.value.sure) now.value = now.value.copy(sure = true)
        return if (quiet) caughtUp else null
    }

    /** Under [lock]. */
    private fun gone() {
        if (now.value.manual) return
        if (!now.value.unreachable) goneAt = clock()
        now.value = now.value.copy(unreachable = true, sure = true)
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                // Less often the longer it has been gone.
                delay(againAfter(clock() - goneAt, againMillis))
                if (!now.value.unreachable) break
                // And only while something wants to know: with the app out of sight, nothing
                // playing and nothing waiting, the server is left alone (and asked at once
                // when that changes: see [nudge]).
                // (Through the one way of asking: never beside an ask already out.)
                if (!now.value.manual && mayAsk() && wanted()) ask()
            }
        }
    }

    /** Under [lock]; returns what is to be told after it. */
    private fun back(quiet: Boolean): (() -> Unit)? {
        val was = now.value
        if (!was.unreachable) return null
        now.value = was.copy(unreachable = false, sure = true)
        loop?.cancel()
        loop = null
        // Taken to be out of reach only because the phone had no network when the app
        // started (or `quiet`: only until this check, offline mode having just been switched
        // off): caught up with, and nothing said.
        if (assumed || quiet) {
            assumed = false
            return caughtUp
        }
        return cameOnline
    }

    companion object {
        /** An ask that may not be made (see `mayAsk`). */
        private val NOT_ASKED = ApiError.Network("Not asked.")

        /** How often the server is asked again when it has just gone out of reach, as on
         *  Windows. */
        const val AGAIN_MILLIS = 15_000L

        /** How long an ask may have been out before something wanting an answer now gives
         *  it up for a new one. */
        const val STALE_ASK_MILLIS = 4_000L

        /**
         * How long to wait before asking again, the server having been out of reach for
         * `goneFor` milliseconds: Windows' fifteen seconds for the first two minutes (a
         * tunnel, a lift, a router restarting), every half minute up to ten, every minute up
         * to an hour, and every five minutes after that.
         */
        fun againAfter(goneFor: Long, first: Long = AGAIN_MILLIS): Long = when {
            goneFor < 2 * 60_000L -> first
            goneFor < 10 * 60_000L -> 2 * first
            goneFor < 60 * 60_000L -> 4 * first
            else -> 20 * first
        }
    }
}
