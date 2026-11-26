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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /** "Singles and EPs": whether an artist's page lists them, and a search offers a song
     *  that only came out as one. On by default, as on Windows. */
    val singles: Boolean = true,
) {
    val door: SignInDoor? get() = if (service.isEmpty()) null else SignInDoor(service, client)
}

/** Sentences that are the phone's own (Settings'), for what the library is never asked. */
object RequestsWords {
    /** An address with a name and password written into it (`http://me:secret@host`). */
    const val ADDRESS_WITH_NAME = "Leave the name and password out of the address. Lidarr is given its key instead."

    /** An address the app's client can make nothing of. */
    const val ADDRESS_UNUSABLE = "That address isn't one the app can use."

    /** What answered at Lidarr's address pointed at a sign-in service the app can't use. */
    const val SERVICE_UNUSABLE = "The sign-in in front of Lidarr gave an address the app can't use."

    /** Something on the screen was shown under a connection to Lidarr that has since been
     *  changed (another address, another key, another sign-in), and was pressed. */
    const val CONNECTION_CHANGED = "The connection to Lidarr has changed since this was shown. Open it again."
}

/** Where the connection to Lidarr stands. */
sealed interface RequestsState {
    /** Requests are switched off. Nothing is asked of anyone. */
    data object Off : RequestsState

    /** Switched on, with no address or no key yet. */
    data object NotConnected : RequestsState

    /** The key that was kept was for a different place from the address now given, so it
     *  was forgotten and none has been given since: **a key is only ever sent to the place
     *  it was typed in for** (the same scheme, host and port). Also what a start comes to
     *  that finds a key kept for another place than the address it has, however that came
     *  about. */
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
    /** How long the app's own screen is waited for, to trade a code for its token a second
     *  time, when the first try was made with the app out of sight (see [waitFor]). */
    private val secondChanceMillis: Long = 60_000,
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

    /** Lidarr's key and the place it is for, as last read or given. */
    private var key: RequestsSecrets.Bound? = null

    /** A key was found kept for another place than Lidarr's address, and was forgotten;
     *  until one is given, that is what there is to say. */
    private var forgotten = false

    /** The sign-in that is open in the library: which, the library's number for it, the
     *  place its token is for, and this sign-in's own number (see [live]). */
    private class Open(val door: SignInDoor, val session: Long, val place: String, val number: Long)

    private var open: Open? = null

    /**
     * **One sign-in owns the token that is kept, at a time.** Every sign-in that is opened
     * here has a number, and [live] is the number of the one whose token the file holds (0
     * for none). Only that one may write the file or delete it: a renewal that comes back
     * for a sign-in that has since been signed out of writes nothing, and an old sign-in's
     * goodbye takes away only the token it was handing back. Without this, signing out and
     * in again at once could end with the old sign-in's late renewal written over the new
     * token, or its clean-up deleting it.
     *
     * [owner] guards the number and every write and delete of the token file together. It
     * is a plain lock, not [turn], because the library calls [tokenChanged] on a thread of
     * its own, in the middle of a request.
     */
    private val owner = Any()
    private var live = 0L
    private var numbers = 0L

    /** Only the newest check counts. */
    private var checks = 0

    /**
     * **Which connection this is**: a number that moves whenever the address, the key or
     * the sign-in changes ([moved]). Everything a page hands to a screen carries the number
     * it was found under, and something pressed on a row from another connection is refused
     * (see [RequestsPages]): Lidarr's own numbers for things mean something else on another
     * Lidarr. It starts somewhere no earlier run of the app can have been, so a number kept
     * across a restart matches nothing.
     */
    private var connection = (System.currentTimeMillis() shl 16) or java.security.SecureRandom().nextInt(1 shl 16).toLong()

    /** The connection has changed. Under [turn]. */
    private fun moved() {
        connection++
    }

    /** A sign-in under way: the door the browser comes back through, and how many times
     *  the app's own screen has come to the front since it began ([cameBack]). */
    private class Attempt(val door: BrowserReturn, val host: String) {
        val fronts = MutableStateFlow(0)
    }

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

    /** Where Lidarr is, as the client that connects takes the address (see
     *  [Core.lidarrPlace]); null for no address, or one it can't use. */
    private fun placeOf(settings: RequestsSettings): String? = if (settings.address.isBlank()) null else Core.lidarrPlace(settings.address)

    /**
     * The key, **if the one that is kept is for where Lidarr now is.** One kept for any
     * other place is forgotten here and now, whatever the settings say and however the two
     * came to disagree (the app stopped between writing one and the other, a write that
     * failed): it is never sent. Under [turn].
     */
    private fun keyFor(place: String): String? {
        val kept = key ?: return null
        if (kept.origin == place) return kept.secret
        secrets.forgetKey()
        key = null
        forgotten = true
        moved()
        note("requests: the key that was kept was for another address than Lidarr's, so it was forgotten")
        return null
    }

    /** The sign-in's token, if the one that is kept is for this service's own place; one
     *  kept for another is forgotten, and never sent. Under [turn]. */
    private fun tokenFor(door: SignInDoor): RequestsSecrets.Bound? {
        val place = Core.servicePlace(door.service) ?: return null
        return synchronized(owner) {
            val kept = secrets.token() ?: return null
            if (kept.origin == place) return kept
            // (Nobody's: no sign-in that is open here was given it.)
            secrets.forgetToken()
            null
        }
    }

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
        if (unreadable || !settings.on) return null
        val place = placeOf(settings) ?: return null
        val key = keyFor(place) ?: return null
        val session = sessionFor(settings)
        return LidarrAt(settings.address, key, session, place, connection)
    }

    /** The sign-in in front of Lidarr, opened from what is kept if it isn't open yet. */
    private suspend fun sessionFor(settings: RequestsSettings): Long? {
        val door = settings.door
        open?.let { now ->
            if (now.door == door) return now.session
            // (Another sign-in's, or none any more: this one is let go of, and owns nothing.)
            open = null
            moved()
            synchronized(owner) { if (live == now.number) live = 0 }
            afterwards { Core.gateClose(now.session) }
        }
        if (door == null) return null
        val token = tokenFor(door) ?: return null
        val number = synchronized(owner) { (++numbers).also { live = it } }
        val session = Core.gateOpen(door, token.secret, bound = token.origin) { fresh -> tokenChanged(number, token.origin, fresh) }
        open = Open(door, session, token.origin, number)
        return session
    }

    /**
     * The service replaced the token of sign-in `number` (every renewal does), or stopped
     * honouring it (""): called by the library, on one of its threads, before the request
     * that caused it is answered. **Only the sign-in that owns the kept token changes it**
     * (see [live]): one that has been signed out of, or replaced, writes and deletes nothing.
     */
    private fun tokenChanged(number: Long, place: String, fresh: String) {
        val kept = synchronized(owner) {
            if (number != live) return
            if (fresh.isEmpty()) secrets.forgetToken() else secrets.keepToken(place, fresh)
        }
        if (!kept && fresh.isNotEmpty()) note("requests: the sign-in's new token couldn't be kept on the phone, so the sign-in lasts only until the app is closed")
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
            forgotten = false
            moved()
            checks++
        }
        check()
    }

    /**
     * Saves where Lidarr is and its key, then connects: asks whether it answers and, if a
     * sign-in answers in its place, finds which. `key` blank leaves the key that is kept,
     * **unless the address is now a different place** (another host, another port, `https`
     * for `http`): a key is only ever sent to the place it was typed in for, so then it is
     * forgotten ([RequestsState.KeyForgotten]). Ends in [RequestsState.Connected],
     * [RequestsState.NeedsSignIn] (the caller then offers [signIn], or goes straight to
     * it), or why not.
     *
     * An address with a name and password in it, or one the app can make nothing of, is
     * refused ([RequestsState.Unreachable] with one of [RequestsWords]) and nothing of it
     * is kept.
     */
    suspend fun connect(address: String, key: String): RequestsState = withContext(io) {
        val forgotten = turn.withLock {
            read()
            if (unreadable) return@withContext stateNow.value
            val typed = address.trim()
            val place = if (typed.isEmpty()) null else Core.lidarrPlace(typed)
            val refused = when {
                typed.isEmpty() -> null
                Core.namesSomeone(typed) -> RequestsWords.ADDRESS_WITH_NAME
                place == null -> RequestsWords.ADDRESS_UNUSABLE
                else -> null
            }
            if (refused != null) {
                checks++
                stateNow.value = RequestsState.Unreachable(refused)
                return@withContext stateNow.value
            }
            val before = settingsNow.value
            val moved = Core.lidarrTidy(before.address) != Core.lidarrTidy(typed)
            var next = before.copy(address = typed)
            if (moved) {
                // A sign-in belongs to the address it was found in front of.
                dropSignIn(before)
                next = next.copy(service = "", client = "", signedIn = "", signingIn = false)
            }
            // (The order of these two writes does not matter, and neither does the app being
            // stopped between them: the key is sealed with its place, and one found beside
            // an address that is another place is never used. See [keyFor].)
            write(next)
            val kept = this@Requests.key ?: secrets.key()
            val given = key.trim()
            val forgotten = given.isEmpty() && kept != null && kept.origin != place
            if (given.isNotEmpty() && place != null) {
                if (!secrets.keepKey(place, given)) note("requests: Lidarr's key couldn't be kept on the phone, so it lasts only until the app is closed")
                this@Requests.key = RequestsSecrets.Bound(place, given)
            } else if (kept != null && kept.origin != place) {
                secrets.forgetKey()
                this@Requests.key = null
            } else {
                this@Requests.key = kept
            }
            this@Requests.forgotten = forgotten
            // (Whatever was typed, what is on the screens was shown before it was.)
            moved()
            checks++
            forgotten
        }
        if (forgotten) {
            stateNow.value = RequestsState.KeyForgotten
            return@withContext RequestsState.KeyForgotten
        }
        check()
    }

    /** Switches "singles and EPs" on or off (see [RequestsSettings.singles]). What is being
     *  found when it changes takes the new setting from the next thing it asks. */
    suspend fun setSingles(on: Boolean) = withContext(io) {
        turn.withLock {
            read()
            if (!unreadable && settingsNow.value.singles != on) write(settingsNow.value.copy(singles = on))
        }
    }

    /** Lidarr's key as it may be shown: its last few letters (`lidarr::mask`). Empty for none. */
    suspend fun keyHint(): String = withContext(io) {
        turn.withLock {
            read()
            // (Only the key for where Lidarr now is: one for another place is not this
            // address's, and is not hinted at.)
            val place = placeOf(settingsNow.value)
            (key ?: secrets.key())?.takeIf { it.origin == place }?.let { Core.lidarrMask(it.secret) }.orEmpty()
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
                stateNow.value = when {
                    !settings.on -> RequestsState.Off
                    forgotten -> RequestsState.KeyForgotten
                    else -> RequestsState.NotConnected
                }
                return@withContext stateNow.value
            }
            stateNow.value = RequestsState.Connecting
            ++checks to at
        }
        val checked = Core.lidarrCheck(at)
        turn.withLock {
            if (checks != mine) return@withContext stateNow.value
            val answered = (checked as? Outcome.Got)?.value?.door
            // (A sign-in service whose address the client can make nothing of, or with a
            // name and password written into it, is not one to keep or to sign in to.)
            val unusable = answered != null && (Core.servicePlace(answered.service) == null || Core.namesSomeone(answered.service))
            val found = answered?.takeIf { !unusable }
            if (found != null && found != settingsNow.value.door) {
                // A sign-in nobody here has signed in to: whatever was kept for another goes.
                dropSignIn(settingsNow.value)
                write(settingsNow.value.copy(service = found.service, client = found.client, signedIn = ""))
            }
            val door = settingsNow.value.door
            val signedOut = door != null && tokenFor(door) == null
            // (Nobody is signed in: whoever is written down as signed in was signed in with
            // a token that is no longer kept, and is written down no longer.)
            if (signedOut && settingsNow.value.signedIn.isNotEmpty()) write(settingsNow.value.copy(signedIn = ""))
            val settings = settingsNow.value
            stateNow.value = when (checked) {
                is Outcome.Stopped -> if (door != null && signedOut) RequestsState.NeedsSignIn(hostOf(door)) else RequestsState.NotConnected
                is Outcome.Failed -> RequestsState.Unreachable(checked.why)
                is Outcome.Got -> {
                    val got = checked.value
                    when {
                        got.version != null -> RequestsState.Connected(got.version, signedInAs = if (door == null) null else settings.signedIn)
                        unusable -> RequestsState.Unreachable(RequestsWords.SERVICE_UNUSABLE)
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
        try {
            if (came is BrowserReturn.Came.GivenUp) return
            val code = came as? BrowserReturn.Came.Code
            // **The app comes forward first, and trades the code for its token then.** An
            // app nobody is looking at has its network taken away after a few seconds
            // (Android 15 and later), and it has been behind the browser for as long as
            // signing in took: a trade begun there never reaches the service. In front, it
            // does. (The tab is closed by coming forward; a browser that kept a window of
            // its own is shown its page when the trade is done, below.)
            val fronted = mine.fronts.value
            if (code != null || came is BrowserReturn.Came.TurnedDown) comeBack()
            // Collecting the token is not to be cut off part-way, whatever becomes of the
            // account meanwhile: a token collected for nobody is handed straight back. (It
            // is not left to take for ever either: see `Core.gateFinish`.)
            var done = if (code != null) withContext(NonCancellable) { Core.gateFinish(found, code.code, mine.door.back, secret) } else null
            if (code != null && done is Outcome.Failed && mine.fronts.value == fronted && secondChanceMillis > 0) {
                // It failed, and the app's screen has not come to the front since the
                // browser came back: Android would not let it come forward by itself, so
                // the trade was tried out of sight. When the person comes back to the app
                // it is tried once more. (A code is good for a minute or so, and one the
                // service never saw has not been used.)
                val back = withTimeoutOrNull(secondChanceMillis) { mine.fronts.first { it > fronted } }
                if (back != null && turn.withLock { attempt === mine }) {
                    done = withContext(NonCancellable) { Core.gateFinish(found, code.code, mine.door.back, secret) }
                }
            }
            val token = (done as? Outcome.Got)?.value
            // 0: this sign-in has been retired meanwhile; 1: it didn't go through; 2: signed in.
            val ended = withContext(NonCancellable) {
                turn.withLock {
                    if (attempt !== mine || !scope.isActive) return@withLock 0
                    attempt = null
                    val settings = settingsNow.value
                    val place = Core.servicePlace(found.service)
                    if (token != null && place != null) {
                        // This sign-in owns the kept token from here: whatever an earlier one
                        // still has on its way writes nothing.
                        val (number, kept) = synchronized(owner) {
                            live = ++numbers
                            live to secrets.keepToken(place, token.token)
                        }
                        if (!kept) note("requests: the sign-in's token couldn't be kept on the phone, so the sign-in lasts only until the app is closed")
                        write(settings.copy(signedIn = shown(token.name), signingIn = false))
                        open?.let { old -> afterwards { Core.gateClose(old.session) } }
                        open = Open(found, Core.gateOpen(found, token.token, bound = place) { fresh -> tokenChanged(number, place, fresh) }, place, number)
                        moved()
                        2
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
                        1
                    }
                }
            }
            // **The browser is shown how it ended, now that it is known**: "You're signed
            // in" only if the token is in this app's keeping. (A sign-in the service turned
            // down was shown so as it arrived.)
            code?.say(signedIn = ended == 2)
            mine.door.close()
            if (ended == 0) {
                if (token != null) afterwards { Core.gateGiveUp(found, token.token, Core.servicePlace(found.service)) }
                return
            }
            if (ended == 2) check()
        } finally {
            // (Whatever happened: the door is shut, and nobody is left waiting at it.)
            mine.door.close()
        }
    }

    /**
     * The app's own screen is in front again. If a sign-in was waiting for the browser and
     * the browser has not come back, the tab was closed without signing in: after a moment
     * (in which a browser that was on its way back still gets through) the sign-in is given
     * up. Call it whenever the screen comes to the front; it does nothing otherwise.
     */
    fun cameBack() {
        val waiting = attempt ?: return
        waiting.fronts.update { it + 1 }
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
        moved()
        // From here no sign-in owns the kept token: a renewal that is on its way for the one
        // being dropped writes nothing when it comes back.
        val onFile = synchronized(owner) {
            live = 0
            secrets.token().also { secrets.forgetToken() }
        }
        val door = was?.door ?: settings.door
        if (door == null || (was == null && onFile == null)) return
        // (Handed back to the service it was given for, and to no other: what the client is
        // told is the place that was kept with the token.)
        val place = was?.place ?: onFile?.origin
        afterwards {
            // Closing waits out a renewal that is under way, so the token handed back is
            // the one that is good now.
            val token = was?.let { Core.gateClose(it.session) }?.takeIf { it.isNotEmpty() } ?: onFile?.secret
            if (token != null) Core.gateGiveUp(door, token, place)
            // Tidied away only if what is kept is still the very token that was handed
            // back (its first deletion failed, say). Anything else there by now is a later
            // sign-in's, and is not this one's to take.
            synchronized(owner) { if (token != null && secrets.token()?.secret == token) secrets.forgetToken() }
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

        /** The longest name a sign-in service may give whoever signed in, as it is kept. */
        const val NAME_LENGTH = 80

        /**
         * A name as a sign-in service gave it, **made fit to keep and show**: it is the
         * service's to choose, and is kept in a plain settings file. Nothing that isn't
         * printable, on one line, and no longer than a name is.
         */
        fun shown(name: String): String =
            name.map { if (it.isWhitespace()) ' ' else it }.filter { !it.isISOControl() && it.category != CharCategory.FORMAT && it.category != CharCategory.PRIVATE_USE && it.category != CharCategory.SURROGATE && it.category != CharCategory.UNASSIGNED }
                .joinToString("").trim().replace(Regex(" {2,}"), " ").take(NAME_LENGTH).trim()
    }
}

/**
 * Where the connection stands, **in the library's own sentence**, for the screens (which call
 * nothing in `core/`): "Connected to Lidarr 2.12.4.", "Lidarr is behind the sign-in at …",
 * "Finish signing in at … in your browser." and the rest. Empty when requests are off, and
 * where the library has no sentence yet: what is kept can't be read. A sign-in the app was
 * stopped in the middle of is said as any sign-in still to be made, until the library has a
 * sentence of its own for it. (Added for the screens, M7b.)
 */
fun Requests.says(state: RequestsState): String = when (state) {
    RequestsState.Off -> ""
    RequestsState.NotConnected -> Words.notConnected
    RequestsState.KeyForgotten -> Words.keyForgotten
    RequestsState.Connecting -> Words.checking
    is RequestsState.Connected -> Words.connected(state.version)
    is RequestsState.NeedsSignIn -> when {
        state.signedOut -> Words.signedOut(state.host)
        state.why != null -> state.why
        else -> Words.behindSignIn(state.host)
    }
    is RequestsState.SigningIn -> Words.finishInBrowser(state.host)
    is RequestsState.Unreachable -> state.why
    RequestsState.CantRead -> ""
}
