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
 * is asked again every 15 seconds, quietly, until it answers. Any answer from it meanwhile
 * settles it too. When it is back, `cameOnline` runs: held changes are sent, downloads carry
 * on, failed pages and covers are asked for again.
 *
 * A server that answers with an error is there: that is not "out of reach", and is no reason
 * to sign anyone out. Only a sign-in the server refuses signs out (`signInGone`), as on
 * Windows, which then asks for the password again.
 *
 * Offline mode switched on by hand asks nothing at all. Switched off, the server is asked
 * whether it is really there before it is assumed to be; if it is, `caughtUp` runs (held
 * changes are sent, downloads carry on), as Windows' `check_connection` catches up after any
 * check that finds the server, but nothing says "Back online": the switch was in the
 * person's hand. If it isn't, it is out of reach, and its coming back is the usual one.
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
) {
    private val now = MutableStateFlow(Reach())
    val state: StateFlow<Reach> = now.asStateFlow()

    val offline: Boolean get() = now.value.offline

    private var asking: Job? = null
    private var loop: Job? = null
    private val lock = Any()

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
        if (now.value.manual) return
        if (!networkThere) {
            assumed = true
            gone()
            // (Asked all the same: a server on the phone itself, or over a link Android
            // doesn't count as a network, answers.)
            check()
            return
        }
        now.value = now.value.copy(sure = false)
        synchronized(lock) {
            if (asking?.isActive == true) return
            asking = scope.launch { askOnce(first = true) }
        }
    }

    /** The phone has lost its network: the server is out of reach until it answers. */
    fun noNetwork() {
        if (!now.value.manual && !now.value.unreachable) gone()
    }

    /** Offline mode, by hand, as Settings has it. */
    fun manual(on: Boolean) {
        val was = now.value
        if (was.manual == on) return
        now.value = was.copy(manual = on)
        switchedOff = !on
        if (!on) check()
    }

    /** A request to the server went through (true) or couldn't reach it at all (false). */
    fun reached(ok: Boolean) {
        if (ok) {
            if (now.value.unreachable) back()
        } else if (!now.value.unreachable) {
            check()
        }
    }

    /** Asks the server now whether it is there (once at a time). */
    fun check() {
        if (now.value.manual) return
        synchronized(lock) {
            if (asking?.isActive == true) return
            asking = scope.launch { askOnce() }
        }
    }

    /** The phone has a network again, or may now reach the home network: worth asking. */
    fun networkBack() {
        if (now.value.unreachable) check()
    }

    /** Whoever was signed in has gone: what was found out about their server goes too. */
    fun reset() {
        synchronized(lock) {
            loop?.cancel()
            loop = null
            asking?.cancel()
            asking = null
        }
        assumed = false
        now.value = now.value.copy(unreachable = false, sure = true)
    }

    private suspend fun askOnce(first: Boolean = false) {
        if (!mayAsk()) {
            gone()
            return
        }
        try {
            if (first) firstPing() else ping()
            answered()
        } catch (e: ApiError) {
            when {
                e.signInRefused -> {
                    now.value = now.value.copy(sure = true)
                    signInGone()
                }
                // No answer, an error page from something in front of the server, or the
                // server unable to answer properly: carry on with what's on the phone and
                // keep trying. (An error the server itself gives is an answer: it is there.)
                e is ApiError.Server -> answered()
                else -> {
                    switchedOff = false
                    gone()
                }
            }
        }
    }

    /** The server answered a ping: back, if it was out of reach; caught up with, if offline
     *  mode has just been switched off. */
    private fun answered() {
        if (!now.value.sure) now.value = now.value.copy(sure = true)
        if (now.value.unreachable) {
            switchedOff = false
            back()
        } else if (switchedOff && !now.value.manual) {
            switchedOff = false
            caughtUp()
        }
    }

    private fun gone() {
        if (now.value.manual) return
        now.value = now.value.copy(unreachable = true, sure = true)
        synchronized(lock) {
            if (loop?.isActive == true) return
            loop = scope.launch {
                while (true) {
                    delay(againMillis)
                    if (!now.value.unreachable) break
                    if (!now.value.manual && mayAsk()) askOnce()
                }
            }
        }
    }

    private fun back() {
        val was = now.value
        if (!was.unreachable) return
        now.value = was.copy(unreachable = false, sure = true)
        synchronized(lock) {
            loop?.cancel()
            loop = null
        }
        // Taken to be out of reach only because the phone had no network when the app
        // started: caught up with, and nothing said.
        if (assumed) {
            assumed = false
            caughtUp()
        } else {
            cameOnline()
        }
    }

    companion object {
        /** How often the server is asked again while it is out of reach, as on Windows. */
        const val AGAIN_MILLIS = 15_000L
    }
}
