package org.medianexus.music.requests

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.medianexus.music.core.Core
import org.medianexus.music.core.LidarrAt
import org.medianexus.music.core.Outcome
import org.medianexus.music.core.SignInDoor
import org.medianexus.music.core.Words
import org.medianexus.music.store.Account

/**
 * What an account has set up for requests, as it is kept on the phone (`requests.json` in
 * the account's own folder). No secret is ever here: the key and the sign-in's token are
 * sealed, elsewhere ([RequestsSecrets]).
 */
@Serializable
data class RequestsSettings(
    /** Requests are switched on. Off by default, as on Windows. */
    val on: Boolean = false,
    /** Where Lidarr is, as the person typed it. */
    val address: String = "",
    /** The sign-in found in front of Lidarr, if one was: where its service is, and the name
     *  it knows the sign-in in front of Lidarr by. Both empty for none. */
    val service: String = "",
    val client: String = "",
    /** Who signed in there, for showing; may be empty. */
    val signedIn: String = "",
    /** A sign-in was started and has neither finished nor been given up: if the app starts
     *  and finds this, it was stopped behind the browser. */
    val signingIn: Boolean = false,
) {
    val door: SignInDoor? get() = if (service.isEmpty()) null else SignInDoor(service, client)
}

/** Where the connection to Lidarr stands. */
sealed interface RequestsState {
    /** Requests are switched off. Nothing is asked of anyone. */
    data object Off : RequestsState

    /** Switched on, with no address or no key yet. */
    data object NotConnected : RequestsState

    /** The address now given is on a different host, so the key that was kept was forgotten
     *  (a key is only ever sent to the host it was typed in for) and none was given. */
    data object KeyForgotten : RequestsState

    /** Asking Lidarr whether it is there. */
    data object Connecting : RequestsState

    /** Lidarr answered. `signedInAs` is null when there is no sign-in in front of it, and
     *  otherwise who signed in (which the service may not have said: then it is empty). */
    data class Connected(val version: String, val signedInAs: String?) : RequestsState

    /**
     * Lidarr is behind a sign-in at `host`, and nobody is signed in. `why` is the library's
     * own sentence when something has just gone wrong with it (a sign-in turned down, one
     * that has ended); `signedOut` just after signing out; `interrupted` when the app was
     * stopped behind the browser part-way through a sign-in, which is simply started again.
     */
    data class NeedsSignIn(val host: String, val why: String? = null, val signedOut: Boolean = false, val interrupted: Boolean = false) : RequestsState

    /** The browser is open for someone to sign in at `host`. */
    data class SigningIn(val host: String) : RequestsState

    /** It couldn't be reached, or answered as something else: `why`, in the library's own
     *  sentence, as the Windows app shows it. */
    data class Unreachable(val why: String) : RequestsState

    /** What is kept on the phone about the connection is there and couldn't be read. It is
     *  left as it is, and nothing can be changed until a start that can read it. */
    data object CantRead : RequestsState
}

/**
 * An account's connection to Lidarr: what the screens use to set it up, and what everything
 * else of requests asks for the way to Lidarr ([lidarr]).
 *
 * As on Windows: requests are off until switched on; the person gives Lidarr's address and
 * its key; and if a sign-in service answers at that address instead of Lidarr, they sign in
 * to it **in the browser** and the app keeps only a token. What is asked of Lidarr and the
 * service, what is made of the answers and every sentence shown are the shared core's
 * ([Core]); here is only the order things happen in on a phone, and what is kept.
 *
 * Everything here may be called from the main thread: nothing blocks it.
 */
class Requests internal constructor(
    private val account: Account,
    /** The account's own work: cancelled when it signs out of the music server. */
    private val scope: CoroutineScope,
    private val secrets: RequestsSecrets,
    /** Runs something that must finish though the account has gone: handing a token back. */
    private val afterwards: (suspend () -> Unit) -> Unit,
    /** Brings the app's own screen back in front of the browser. */
    private val comeBack: () -> Unit = {},
    private val note: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long the browser is waited for (a test's own). */
    private val waitMillis: Long = Words.signInWaitMillis,
) {
    private val kept = Kept(File(account.dirs.data, "requests.json"), RequestsSettings.serializer(), RequestsSettings(), note)
    private val settingsNow = MutableStateFlow(RequestsSettings())
    private val stateNow = MutableStateFlow<RequestsState>(RequestsState.Off)

    /** What is set up, without its secrets. */
    val settings: StateFlow<RequestsSettings> = settingsNow.asStateFlow()

    val state: StateFlow<RequestsState> = stateNow.asStateFlow()

    /** One change to the connection at a time. Never held across a request. */
    private val turn = Mutex()
    private var opened = false
    private var unreadable = false

    /** Lidarr's key, as last read or given. */
    private var key: String? = null

    /** The sign-in that is open in the library: which, and the library's number for it. */
    private class Open(val door: SignInDoor, val session: Long)

    private var open: Open? = null

    /** Only the newest check counts. */
    private var checks = 0

    /** A sign-in under way: the door the browser comes back through. */
    private class Attempt(val door: BrowserReturn, val host: String)

    private var attempt: Attempt? = null

    // ---- what is kept ----------------------------------------------------------------------------

    /** Reads what is kept. Under [turn], off the main thread. */
    private fun read() {
        if (opened) return
        opened = true
        val read = kept.read()
        if (read == null) {
            unreadable = true
            stateNow.value = RequestsState.CantRead
            return
        }
        settingsNow.value = read
        if (read.on) key = secrets.key()
    }

    private fun write(next: RequestsSettings): Boolean {
        if (unreadable) return false
        settingsNow.value = next
        return kept.write(next)
    }

    private fun hostOf(door: SignInDoor): String = Core.gateHost(door.service)

    // ---- the way to Lidarr -----------------------------------------------------------------------

    /**
     * What a call to Lidarr is made with now, or null while there is nothing to call
     * (requests off, no address, no key). The sign-in in front of it, if there is one and
     * someone is signed in, is opened in the library the first time this is asked.
     */
    suspend fun lidarr(): LidarrAt? = withContext(io) { turn.withLock { lidarrNow() } }

    private suspend fun lidarrNow(): LidarrAt? {
        read()
        val settings = settingsNow.value
        if (unreadable || !settings.on || settings.address.isBlank()) return null
        val key = key ?: return null
        return LidarrAt(settings.address, key, sessionFor(settings))
    }

    /** The sign-in in front of Lidarr, opened from what is kept if it isn't open yet. */
    private suspend fun sessionFor(settings: RequestsSettings): Long? {
        val door = settings.door
        open?.let { now ->
            if (now.door == door) return now.session
            // (Another sign-in's, or none any more: this one is let go of.)
            open = null
            afterwards { Core.gateClose(now.session) }
        }
        if (door == null) return null
        val token = secrets.token() ?: return null
        val session = Core.gateOpen(door, token) { fresh -> tokenChanged(fresh) }
        open = Open(door, session)
        return session
    }

    /** The service replaced the token (every renewal does), or stopped honouring it (""):
     *  called by the library, on one of its threads, before the request that caused it is
     *  answered. */
    private fun tokenChanged(fresh: String) {
        if (fresh.isEmpty()) {
            secrets.forgetToken()
        } else if (!secrets.keepToken(fresh)) {
            note("requests: the sign-in's new token couldn't be kept on the phone, so the sign-in lasts only until the app is closed")
        }
    }

    // ---- setting it up -----------------------------------------------------------------------------

    /** Reads what is kept and, if requests are on and set up, asks Lidarr whether it is
     *  there. As the account starts; asks nothing of anyone while requests are off. */
    fun start() {
        scope.launch(io) {
            val interrupted = turn.withLock {
                read()
                val settings = settingsNow.value
                (settings.signingIn && write(settings.copy(signingIn = false)))
            }
            check(interrupted = interrupted)
        }
    }

    /** Switches requests on or off (off by default). Off, nothing is asked of anyone; what
     *  is set up is kept for when they are switched on again. */
    suspend fun switch(on: Boolean): RequestsState = withContext(io) {
        turn.withLock {
            read()
            if (unreadable) return@withContext stateNow.value
            if (!on) giveUpAttempt()
            write(settingsNow.value.copy(on = on, signingIn = false))
            key = if (on) secrets.key() else null
            checks++
        }
        check()
    }

    /**
     * Saves where Lidarr is and its key, then connects: asks whether it answers and, if a
     * sign-in answers in its place, finds which. `key` blank leaves the key that is kept,
     * unless the address is now on a different host: a key is only ever sent to the host it
     * was typed in for. Ends in [RequestsState.Connected], [RequestsState.NeedsSignIn] (the
     * caller then offers [signIn], or goes straight to it), or why not.
     */
    suspend fun connect(address: String, key: String): RequestsState = withContext(io) {
        val forgotten = turn.withLock {
            read()
            if (unreadable) return@withContext stateNow.value
            val before = settingsNow.value
            val moved = Core.lidarrTidy(before.address) != Core.lidarrTidy(address)
            val elsewhere = Core.lidarrHost(before.address) != Core.lidarrHost(address)
            var next = before.copy(address = address.trim())
            if (moved) {
                // A sign-in belongs to the address it was found in front of.
                dropSignIn(before)
                next = next.copy(service = "", client = "", signedIn = "", signingIn = false)
            }
            write(next)
            val forgotten = key.isBlank() && elsewhere && secrets.key() != null
            if (key.isNotBlank()) {
                if (!secrets.keepKey(key.trim())) note("requests: Lidarr's key couldn't be kept on the phone, so it lasts only until the app is closed")
                this@Requests.key = key.trim()
            } else if (elsewhere) {
                secrets.forgetKey()
                this@Requests.key = null
            } else {
                this@Requests.key = secrets.key() ?: this@Requests.key
            }
            checks++
            forgotten
        }
        if (forgotten) {
            stateNow.value = RequestsState.KeyForgotten
            return@withContext RequestsState.KeyForgotten
        }
        check()
    }

    /** Lidarr's key as it may be shown: its last few letters (`lidarr::mask`). Empty for none. */
    suspend fun keyHint(): String = withContext(io) {
        turn.withLock {
            read()
            (key ?: secrets.key())?.let { Core.lidarrMask(it) }.orEmpty()
        }
    }

    /**
     * Asks Lidarr whether it is there and, if a sign-in answers in its place, which one.
     * Only the newest check counts: one overtaken by another changes nothing.
     */
    suspend fun check(): RequestsState = check(interrupted = false)

    private suspend fun check(interrupted: Boolean): RequestsState = withContext(io) {
        val (mine, at) = turn.withLock {
            read()
            if (unreadable) return@withContext stateNow.value
            val settings = settingsNow.value
            val at = lidarrNow()
            if (at == null) {
                stateNow.value = if (settings.on) RequestsState.NotConnected else RequestsState.Off
                return@withContext stateNow.value
            }
            stateNow.value = RequestsState.Connecting
            ++checks to at
        }
        val checked = Core.lidarrCheck(at)
        turn.withLock {
            if (checks != mine) return@withContext stateNow.value
            val found = (checked as? Outcome.Got)?.value?.door
            if (found != null && found != settingsNow.value.door) {
                // A sign-in nobody here has signed in to: whatever was kept for another goes.
                dropSignIn(settingsNow.value)
                write(settingsNow.value.copy(service = found.service, client = found.client, signedIn = ""))
            }
            val settings = settingsNow.value
            val door = settings.door
            val signedOut = door != null && secrets.token() == null
            stateNow.value = when (checked) {
                is Outcome.Stopped -> if (door != null && signedOut) RequestsState.NeedsSignIn(hostOf(door)) else RequestsState.NotConnected
                is Outcome.Failed -> RequestsState.Unreachable(checked.why)
                is Outcome.Got -> {
                    val got = checked.value
                    when {
                        got.version != null -> RequestsState.Connected(got.version, signedInAs = if (door == null) null else settings.signedIn)
                        found != null -> RequestsState.NeedsSignIn(hostOf(found), interrupted = interrupted)
                        got.doorWhy != null -> RequestsState.Unreachable(got.doorWhy)
                        signedOut && door != null -> RequestsState.NeedsSignIn(hostOf(door), why = got.why, interrupted = interrupted)
                        else -> RequestsState.Unreachable(got.why)
                    }
                }
            }
            stateNow.value
        }
    }

    // ---- signing in with the browser ---------------------------------------------------------------

    /**
     * Starts a sign-in to what is in front of Lidarr. Opens the door the browser will come
     * back through and returns **the page to open in the browser** (a Custom Tab: see
     * [BrowserTab]); null if there is nothing to sign in to, or the door couldn't be opened
     * (then [state] says why). Starting another retires this one.
     */
    suspend fun signIn(): String? = withContext(io) {
        turn.withLock {
            read()
            val settings = settingsNow.value
            val found = settings.door ?: return@withContext null
            if (unreadable || !settings.on) return@withContext null
            giveUpAttempt()
            checks++
            val host = hostOf(found)
            val secret = BrowserReturn.unguessable(48)
            val state = BrowserReturn.unguessable(18)
            val door = try {
                BrowserReturn.open(state)
            } catch (e: IOException) {
                stateNow.value = RequestsState.NeedsSignIn(host, why = Words.notReady(e.javaClass.simpleName))
                return@withContext null
            }
            val link = Core.gateLink(found, door.back, state, secret)
            val mine = Attempt(door, host)
            attempt = mine
            write(settings.copy(signingIn = true))
            stateNow.value = RequestsState.SigningIn(host)
            scope.launch(io) { waitFor(mine, found, secret) }
            link
        }
    }

    private suspend fun waitFor(mine: Attempt, found: SignInDoor, secret: String) {
        val came = mine.door.await(waitMillis)
        mine.door.close()
        if (came is BrowserReturn.Came.GivenUp) return
        // Whoever signed in is looking at the browser: the app comes back in front of it.
        if (came is BrowserReturn.Came.Code || came is BrowserReturn.Came.TurnedDown) comeBack()
        // Collecting the token is not to be cut off part-way, whatever becomes of the
        // account meanwhile: a token collected for nobody is handed straight back.
        val done = if (came is BrowserReturn.Came.Code) withContext(NonCancellable) { Core.gateFinish(found, came.code, mine.door.back, secret) } else null
        val token = (done as? Outcome.Got)?.value
        val current = withContext(NonCancellable) {
            turn.withLock {
                if (attempt !== mine || !scope.isActive) return@withLock false
                attempt = null
                val settings = settingsNow.value
                if (token != null) {
                    if (!secrets.keepToken(token.token)) note("requests: the sign-in's token couldn't be kept on the phone, so the sign-in lasts only until the app is closed")
                    write(settings.copy(signedIn = token.name, signingIn = false))
                    open?.let { old -> afterwards { Core.gateClose(old.session) } }
                    open = Open(found, Core.gateOpen(found, token.token) { fresh -> tokenChanged(fresh) })
                } else {
                    write(settings.copy(signingIn = false))
                    val why = when {
                        done is Outcome.Failed -> done.why
                        came is BrowserReturn.Came.TurnedDown -> Words.turnedDown(came.why)
                        came is BrowserReturn.Came.Nobody -> Words.nobodySignedIn
                        came is BrowserReturn.Came.Failed -> Words.notHeard(came.why)
                        else -> null
                    }
                    stateNow.value = RequestsState.NeedsSignIn(mine.host, why = why)
                }
                true
            }
        }
        if (!current) {
            if (token != null) afterwards { Core.gateGiveUp(found, token.token) }
            return
        }
        if (token != null) check()
    }

    /**
     * The app's own screen is in front again. If a sign-in was waiting for the browser and
     * the browser has not come back, the tab was closed without signing in: after a moment
     * (in which a browser that was on its way back still gets through) the sign-in is given
     * up. Call it whenever the screen comes to the front; it does nothing otherwise.
     */
    fun cameBack() {
        val waiting = attempt ?: return
        scope.launch(io) {
            delay(LATE_MILLIS)
            turn.withLock {
                if (attempt !== waiting || waiting.door.arrived) return@withLock
                giveUpAttempt()
                write(settingsNow.value.copy(signingIn = false))
                stateNow.value = RequestsState.NeedsSignIn(waiting.host)
            }
        }
    }

    /** Stops waiting for a sign-in that was started. */
    suspend fun cancelSignIn() = withContext(io) {
        turn.withLock {
            val waiting = attempt ?: return@withLock
            giveUpAttempt()
            write(settingsNow.value.copy(signingIn = false))
            stateNow.value = RequestsState.NeedsSignIn(waiting.host)
        }
    }

    private fun giveUpAttempt() {
        attempt?.door?.close()
        attempt = null
    }

    /** Forgets the sign-in in front of Lidarr and hands its token back to the service.
     *  Under [turn]. */
    private fun dropSignIn(settings: RequestsSettings) {
        giveUpAttempt()
        val was = open
        open = null
        val onFile = secrets.token()
        secrets.forgetToken()
        val door = was?.door ?: settings.door
        if (door == null || (was == null && onFile == null)) return
        afterwards {
            // Closing waits out a renewal that is under way, so the token handed back is
            // the one that is good now.
            val token = was?.let { Core.gateClose(it.session) }?.takeIf { it.isNotEmpty() } ?: onFile
            if (token != null) Core.gateGiveUp(door, token)
            secrets.forgetToken()
        }
    }

    /** Signs out of the sign-in in front of Lidarr: the token is handed back to the service
     *  and nothing of it is kept. */
    suspend fun signOut(): RequestsState = withContext(io) {
        turn.withLock {
            read()
            if (unreadable) return@withContext stateNow.value
            val settings = settingsNow.value
            val door = settings.door ?: return@withContext stateNow.value
            dropSignIn(settings)
            write(settings.copy(signedIn = "", signingIn = false))
            checks++
            stateNow.value = RequestsState.NeedsSignIn(hostOf(door), signedOut = true)
            stateNow.value
        }
    }

    /**
     * The account is signing out of the music server: its secrets go with its password. The
     * sign-in's token is handed back to the service and the key is forgotten; where Lidarr
     * is stays in the account's folder with its other things.
     */
    suspend fun leave() = withContext(NonCancellable + io) {
        turn.withLock {
            if (!kept.exists() && !opened) return@withLock
            read()
            dropSignIn(settingsNow.value)
            secrets.forgetKey()
            key = null
            if (!unreadable) write(settingsNow.value.copy(signedIn = "", signingIn = false))
            stateNow.value = RequestsState.Off
        }
    }

    private companion object {
        /** How long after the screen comes back a browser on its way is still let through. */
        const val LATE_MILLIS = 1_500L
    }
}
