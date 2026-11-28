package org.medianexus.music.requests

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.core.Core
import org.medianexus.music.core.LibraryLists
import org.medianexus.music.nexus.FeatureEndpoint
import org.medianexus.music.store.Account
import org.medianexus.music.store.writeAtomically

/** What an account's music gives its requests while the account is signed in: the phone's
 *  copy of the library each time it changes, the songs as far as choosing favourites needs
 *  them, and who the music server says an artist is like. */
class RequestsSources(
    val library: Flow<LibraryLists>,
    val played: () -> List<Core.Played>,
    val similar: suspend (String) -> List<String>,
)

/** Where the signed-in account's requests stand, for the screens (see [RequestsOwners]). */
sealed interface RequestsNow {
    /** The person's toggle is off: **nothing of requests exists** for the account. */
    data object Off : RequestsNow

    /** On, and not yet to be used: what owns this account's requests is finishing what the
     *  last sign-in left (a token being handed back, a setting being put back on Lidarr). */
    data object NotYet : RequestsNow

    data class Unavailable(val words: String) : RequestsNow

    /** On: the account's connection to Lidarr, and everything its pages ask for. */
    class On(val requests: Requests, val pages: RequestsPages, val server: Boolean = false) : RequestsNow
}

/**
 * **What owns each account's requests, for the life of the process.**
 *
 * An account's requests are its files (`requests.json`, what was asked for, what is to be
 * put back on Lidarr), its key and its sign-in's token, the library's record of them for
 * the account (its place, with the job that keeps the way back) and whatever of that is
 * under way: a token being handed back, an ask being seen through, a setting being put
 * back. **One [Requests] and one [RequestsPages] hold all of it, made once for the account
 * and kept**: signing out of the music server tells them so, and signing in again finds
 * the same two. So two owners of one account's files cannot exist, whatever order
 * accounts come and go in, and nothing ever has to be cut off from a file.
 *
 * **The account's music never waits for any of it.** The account's own objects are made at
 * once at a sign-in; what they are handed here is a [Seat], and where its requests stand
 * ([Seat.now]) is `NotYet` for as long as the last sign-out (of this account or of any
 * other) is still being finished. Arrivals and departures are taken strictly in the order
 * they happened, one at a time.
 *
 * **With the person's toggle off there is nothing**: no object, no collector, no file read,
 * no copy of the library. Whether it is on is a file that is there or not (`requests.on`
 * in the account's folder: looked for, never read), made when the toggle is turned on and
 * removed when it is turned off; what is running then stops, and what was set up stays in
 * the account's folder for the next time.
 */
class RequestsOwners internal constructor(
    /** The app's own work: never an account's, which ends at sign-out. */
    private val scope: CoroutineScope,
    /** Makes an account's two objects, on work that lasts as long as the process. `here`
     *  is whether the account is signed in with its requests on, and `sources` what its
     *  music gives them then; `switched` is to be told when the person's toggle is. */
    private val make: (account: Account, sources: StateFlow<RequestsSources?>, here: StateFlow<Boolean>, switched: (Boolean) -> Unit) -> Pair<Requests, RequestsPages>,
    private val note: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Whether anything of an account's Lidarr key or sign-in is kept on the phone: a look
     *  for its files, no more. */
    private val holds: (Account) -> Boolean = { false },
    private val router: RequestsRouter? = null,
) {
    private class Owner(val requests: Requests, val pages: RequestsPages, private val sources: MutableStateFlow<RequestsSources?>, private val here: MutableStateFlow<Boolean>) {
        /** The sign-in that has it now; null between sign-ins, and while switched off. */
        var seat: Seat? = null

        /** The account is here (signed in, its requests on), with what its music gives
         *  them; or, with none, it has gone. */
        fun present(with: RequestsSources?) {
            if (with != null) {
                sources.value = with
                here.value = true
            } else {
                here.value = false
                sources.value = null
            }
        }
    }

    /** By the account's key. Only ever touched by [order]'s one worker. */
    private val owners = HashMap<String, Owner>()

    /** How many owners have been made (a test's to count). */
    @Volatile var made = 0
        private set

    /** What is to be done, in the order it was asked: one thing at a time, for everybody. */
    private val order = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(io) {
            for (next in order) {
                try {
                    next()
                } catch (e: Exception) {
                    note("requests: something of an account's requests couldn't be seen to (${e.javaClass.simpleName})")
                }
            }
        }
    }

    private fun marker(account: Account) = File(account.dirs.data, MARKER)

    private fun owner(account: Account): Owner = owners.getOrPut(account.key) {
        val sources = MutableStateFlow<RequestsSources?>(null)
        val here = MutableStateFlow(false)
        // (Switched off from the screen: the registry is told, and lets go.)
        val (requests, pages) = make(account, sources, here) { on -> if (!on) order.trySend { switchedOff(account) } }
        made++
        Owner(requests, pages, sources, here)
    }

    /**
     * Somebody has signed in to `account`: their seat at its requests. With the toggle off
     * (the one thing looked at here: whether a file is there) that is all that happens.
     */
    fun signedIn(account: Account, sources: RequestsSources): Seat {
        if (router != null) {
            val seat = Seat(account, sources, RequestsNow.NotYet)
            seat.follow = scope.launch(io) {
                router.route.collect { route -> order.trySend { routeChanged(seat, route) } }
            }
            return seat
        }
        val on = marker(account).exists()
        val seat = Seat(account, sources, if (on) RequestsNow.NotYet else RequestsNow.Off)
        if (on) order.trySend { arrive(seat) }
        return seat
    }

    /** One sign-in's hold on its account's requests. */
    inner class Seat internal constructor(internal val account: Account, internal val sources: RequestsSources, first: RequestsNow) {
        internal val state = MutableStateFlow(first)
        @Volatile internal var left = false
        internal var follow: Job? = null
        internal var directForDeparture: FeatureEndpoint? = null

        /** Where the account's requests stand for this sign-in. */
        val now: StateFlow<RequestsNow> = state.asStateFlow()

        /** The person has turned the toggle on: the account's requests come into being (or
         *  are found again), switched on. Returns when they are there, or the sign-in has
         *  ended meanwhile. */
        suspend fun turnOn() {
            if (left || (router != null && router.forAccount(account)?.direct != true)) return
            if (state.value == RequestsNow.Off) state.value = RequestsNow.NotYet
            val done = CompletableDeferred<Unit>()
            order.trySend {
                try {
                    if (!left) {
                        keepMarker(account, true)
                        arrive(this, switchOn = true)
                    }
                } finally {
                    done.complete(Unit)
                }
            }
            done.await()
        }

        /** The sign-in has ended. What it left is finished behind it, in its turn. */
        fun leave() {
            left = true
            follow?.cancel()
            state.value = RequestsNow.Off
            order.trySend { depart(this) }
        }
    }

    private suspend fun routeChanged(seat: Seat, route: RequestsRoute) {
        if (seat.left || route !== router?.route?.value) return
        val choice = router.forAccount(seat.account)
        val existing = owners[seat.account.key]
        if (choice == null) {
            // The app scope closes before the account's departure reaches this worker.
            // Keep its direct selection for that departure's token revocation only.
            val unavailable = route as? RequestsRoute.Unavailable
            if (unavailable != null && !unavailable.sessionEnded) {
                seat.directForDeparture = null
                if (existing?.seat === seat) existing.requests.select(null)
            }
            if (existing?.seat === seat) existing.present(null)
            seat.state.value = RequestsNow.Unavailable((route as? RequestsRoute.Unavailable)?.words.orEmpty())
            return
        }
        seat.directForDeparture = choice.status.takeIf { choice.direct }
        if (choice.direct && !marker(seat.account).exists()) {
            if (existing?.seat === seat) {
                existing.requests.select(choice)
                existing.present(null)
            }
            seat.state.value = RequestsNow.Off
            return
        }
        val owner = owner(seat.account)
        owner.requests.select(choice)
        if (choice.direct) {
            arrive(seat)
        } else {
            owner.seat = seat
            owner.present(seat.sources)
            seat.state.value = RequestsNow.On(owner.requests, owner.pages, server = true)
        }
    }

    private suspend fun arrive(seat: Seat, switchOn: Boolean = false) {
        // (Everything that was to be finished before this has been: it is this one's turn.)
        if (seat.left) return
        val owner = withContext(io) { owner(seat.account) }
        if (router != null) {
            val choice = router.forAccount(seat.account)?.takeIf { it.direct } ?: return
            owner.requests.select(choice)
        }
        if (switchOn) owner.requests.switch(true)
        // (The toggle was turned off and the app stopped before that was all written down.)
        if (owner.requests.isOff()) {
            keepMarker(seat.account, false)
            seat.state.value = RequestsNow.Off
            return
        }
        owner.seat = seat
        owner.present(seat.sources)
        // (Switched on just now: that has read what is kept and asked Lidarr already.)
        if (!switchOn) owner.requests.start()
        if (!seat.left) seat.state.value = RequestsNow.On(owner.requests, owner.pages)
    }

    /**
     * The sign-in is over: what it had under way is finished, in this order. Whatever was
     * only for someone looking stops at once; what Lidarr was asked is seen through and
     * written down, which needs the sign-in that leaving then lets go of.
     *
     * **Signing out of the music account takes the account's Lidarr key and its sign-in
     * with it, whether or not requests were on**: the same leaving either way. Turned off
     * earlier in this run, their owner is still here, and leaves. Turned off since before
     * the app started, there is no owner; if anything of the key or the sign-in is still
     * kept (a look for its files), one is made now, to leave. With nothing kept and the
     * toggle off, nothing is made and nothing is done.
     */
    private suspend fun depart(seat: Seat) {
        val owner = owners[seat.account.key] ?: if (holds(seat.account)) owner(seat.account) else return
        // (Another sign-in has them now: not this one's to end. The line is taken in
        // order and a sign-in is over before the next begins, so this is never so; with
        // no seat at all, they were switched off, or the sign-in ended before its turn.)
        if (owner.seat != null && owner.seat !== seat) return
        owner.seat = null
        owner.present(null)
        // (Whatever is thrown on the way, the account leaves: and the line moves on, see
        // the worker above.)
        try {
            owner.pages.settled()
        } finally {
            owner.requests.select(null, leaving = true)
            owner.requests.leave(seat.directForDeparture)
        }
    }

    private suspend fun switchedOff(account: Account) {
        val owner = owners[account.key] ?: return
        // (Switched on again before this had its turn.)
        if (!owner.requests.isOff()) return
        keepMarker(account, false)
        owner.present(null)
        owner.seat?.state?.value = RequestsNow.Off
        owner.seat = null
        owner.pages.settled()
    }

    private suspend fun keepMarker(account: Account, on: Boolean) = withContext(io) {
        val file = marker(account)
        try {
            if (on) writeAtomically(file, ByteArray(0), durable = true) else java.nio.file.Files.deleteIfExists(file.toPath())
        } catch (_: IOException) {
            note("requests: whether requests are switched on couldn't be written down")
        }
    }

    companion object {
        /** There, and empty, while the person's toggle is on. */
        const val MARKER = "requests.on"
    }
}
