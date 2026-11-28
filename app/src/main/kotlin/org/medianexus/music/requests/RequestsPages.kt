package org.medianexus.music.requests

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.medianexus.music.core.AlbumPage
import org.medianexus.music.core.Asked
import org.medianexus.music.core.Asking
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.core.KeptSuggestions
import org.medianexus.music.core.LibraryLists
import org.medianexus.music.core.LidarrAt
import org.medianexus.music.core.Outcome
import org.medianexus.music.core.Outside
import org.medianexus.music.core.OutsideArtist
import org.medianexus.music.core.Popular
import org.medianexus.music.core.Suggestion
import org.medianexus.music.core.Waiting
import org.medianexus.music.core.Words

/** What a page came to: found, or why not, in the library's own sentence, ready to show. */
sealed interface Found<out T> {
    /**
     * `connection` is **which connection to Lidarr this was found under** (a number that
     * moves whenever the address, the key or the sign-in changes). Keep it with whatever of
     * `value` is put on a screen, and hand it back with anything pressed there
     * ([RequestsPages.request], [RequestsPages.addArtist], [RequestsPages.cancel], which
     * will not be called without it): what was shown under one Lidarr is refused, in one
     * sentence, once the app is pointed at another.
     */
    data class Got<T>(val value: T, val connection: Long = 0) : Found<T>

    data class Not(val why: String) : Found<Nothing>
}

/**
 * An album that was asked for and has turned up, **with whether that has been said**: kept
 * on the phone (`arrivals.json`) from the moment it is found until the note of the album is
 * done with. `note` is the note as it was when the arrival was found, which is what the
 * sentence is made from.
 */
@Serializable
internal data class Arrived(val note: Asked, val said: Boolean = false) {
    /** Which asking this is the arrival of: an album asked for again later is another. */
    fun isOf(other: Asked): Boolean = note.album == other.album && note.at == other.at
}

/**
 * How something the person asked to be done ended: what to say, and whether it was done.
 *
 * `kept` is false when it **was done and this phone could not write it down**: an album
 * asked for whose note couldn't be kept (its arrival won't be announced), or one taken off
 * the list whose note is still on the phone (its arrival may be). `words` then says exactly
 * that, in the library's words ([Words.askedUnnoted], [Words.offTheListUnnoted]), never the plain
 * sentence for having done it.
 */
data class Said(
    val done: Boolean, val words: String, val kept: Boolean = true,
    /** Nothing was asked, because **what was pressed was shown under a connection to Lidarr
     *  that has since changed** (see [Found.Got]): the page it was on is to be asked for
     *  again. `words` says so ([RequestsWords.CONNECTION_CHANGED]). */
    val stale: Boolean = false,
)

/**
 * **Everything the screens of requests ask for**, for one account: the pages (what is not
 * in the library, an artist, an album, what is on its way, artists the person might like)
 * and the actions (asking for an album, adding an artist, taking one off the list). The
 * screens need nothing but this and [Requests] (the connection): no call of `Core`, nothing
 * of JNI.
 *
 * What is asked of Lidarr, MusicBrainz and ListenBrainz, in what order, and every caption
 * and sentence that comes back are the shared library's, the same as the Windows app shows.
 * Here is what is the phone's own: what is kept, when arrivals are looked for, and what
 * happens when the screen that asked goes away.
 *
 * - **Every function may be called from the main thread** and suspends; none blocks it.
 * - **Leaving a screen stops what it asked for**: cancel the coroutine that called. The
 *   requests in flight are cut off and nothing is delivered. The exceptions are the three
 *   that change Lidarr, once they have begun to: asking for an album, adding an artist,
 *   taking one off the list. Those are seen through behind the screen's back, **and what
 *   Lidarr accepted is written down here whatever has become of the screen or of the
 *   account meanwhile** (signed out of, even): into that account's own files.
 * - **Nothing is retried in a loop.** A page that could not be had says why, once.
 * - **Kept on the phone**, per account, written whole and never over a file that couldn't be
 *   read: `asked.json` (what was asked for through this phone, so that its arrival can be
 *   said) and `suggestions.json` (artists the person might like, for three days).
 */
class RequestsPages internal constructor(
    private val requests: Requests,
    /** The work of what owns this account's requests: **as long as the process**, in the
     *  app (see [RequestsOwners]). Signing out does not end it: [here] says so. */
    private val scope: CoroutineScope,
    dir: File,
    /** The phone's copy of the library, each time it changes, as the library's rules ask it. */
    private val library: Flow<LibraryLists>,
    /** The library's songs, as far as choosing favourites needs them: asked when suggestions
     *  are looked for. */
    private val played: () -> List<Core.Played>,
    /** Who the music server says an artist (by its id for them) is like, that the library
     *  doesn't have. */
    private val similar: suspend (String) -> List<String>,
    private val note: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Whether **the account is signed in with its requests on**. While it is not, nothing
     * here looks at the library, puts anything back on Lidarr or has news for anyone, and a
     * page asked for is refused; what Lidarr was already asked is still seen through and
     * written down.
     */
    private val here: StateFlow<Boolean> = MutableStateFlow(true),
    /**
     * Runs something that must finish **though the screen has gone and though the account
     * has**: writing down what Lidarr accepted. The app's own work, never the account's
     * (which is cancelled at sign-out) and never a screen's.
     */
    private val afterwards: (suspend () -> Unit) -> Unit,
) {
    companion object {
        /**
         * As the app makes it for an account: what the account's music gives its requests
         * (`sources`) is whoever's is signed in, and nothing while nobody is.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        internal fun of(
            requests: Requests, scope: CoroutineScope, dir: File, sources: StateFlow<RequestsSources?>, here: StateFlow<Boolean>,
            note: (String) -> Unit, afterwards: (suspend () -> Unit) -> Unit,
        ): RequestsPages = RequestsPages(
            requests, scope, dir,
            library = sources.flatMapLatest { it?.library ?: kotlinx.coroutines.flow.emptyFlow() },
            played = { sources.value?.played?.invoke() ?: emptyList() },
            similar = { id -> sources.value?.similar?.invoke(id) ?: emptyList() },
            note = note, here = here, afterwards = afterwards,
        )
    }

    private val askedFile = Kept(File(dir, "asked.json"), ListSerializer(Asked.serializer()), emptyList(), note)
    private val suggestionsFile = Kept(File(dir, "suggestions.json"), KeptSuggestions.serializer(), KeptSuggestions(), note)

    private val arrivalsFile = Kept(File(dir, "arrivals.json"), ListSerializer(Arrived.serializer()), emptyList(), note)

    /**
     * **What must be put back on Lidarr**, as the library writes it down (its batch job's
     * own record, the same file as the Windows app's): an artist's "new releases" setting,
     * turned off while their singles are read in, with the Lidarr it was turned off on. The
     * library hands it over to be written before it changes the setting, and changes
     * nothing if it is told it wasn't written; what a run that got no further left here is
     * put back by the next ([putBack]).
     */
    private val askingFile = KeptAsWritten(File(dir, "asking.json"), note)

    private val askedNow = MutableStateFlow<List<Asked>>(emptyList())

    /** Moves when there may be news to say, and when the account goes. */
    private val wake = MutableStateFlow(0L)

    /** One listener at a time is handed the news. */
    private val saying = Mutex()

    /** What has arrived, said or not, as kept; read when first wanted. Under [turn]. */
    private var held: List<Arrived>? = null
    private val suggestionsNow = MutableStateFlow<List<Suggestion>>(emptyList())

    /** What was asked for through this phone and is still followed up, newest note last. */
    val asked: StateFlow<List<Asked>> = askedNow.asStateFlow()

    /** An arrival to tell the person of: its sentence, and (for [shown]) which it is. */
    class Arrival internal constructor(val words: String, internal val of: List<Arrived>)

    /**
     * **Albums that were asked for and have turned up in the library, to be told of**
     * ("“Yellow” has arrived, on “Parachutes”."), for one listener at a time: the one place
     * the app shows its passing notes. The screens add where to see it.
     *
     * **An arrival is said when it has been shown, and not before**: collecting marks
     * nothing; [shown] does, and is to be called when the note is actually on the screen.
     * Whether it has been said is kept with the arrival on the phone. So:
     *
     * - each new collection is offered everything not yet shown, once (a listener that
     *   starts again, as when the activity is made again, is offered what the last one took
     *   and never showed);
     * - one never shown at all (the app stopped or hidden, the account changed) is offered
     *   again by the next run;
     * - one that was shown is not offered again by any run, whatever else failed to be
     *   written meanwhile.
     *
     * Nothing is offered while requests are switched off in Settings. When the account
     * goes (signed out of the music server) the flow ends.
     */
    val arrivals: Flow<Arrival> = flow {
        val offered = ArrayList<Arrived>()
        if (requests.serverMode) return@flow
        combine(wake, requests.settings.map { it.on }.distinctUntilChanged(), here) { _, on, here -> on to here }.takeWhile { (_, here) -> here }.collect { (on, _) ->
            if (!on) return@collect
            saying.withLock {
                val unsaid = turn.withLock { arrivalsKept().filter { kept -> !kept.said && offered.none { it.isOf(kept.note) } } }
                if (unsaid.isEmpty() || isGone()) return@withLock
                val sentence = withContext(io) { Core.arrivedNews(unsaid.map { it.note }) }
                offered += unsaid
                if (sentence.isNotEmpty()) emit(Arrival(sentence, unsaid))
            }
        }
    }

    /**
     * The note for `arrival` is on the screen: **it has been said**, and is kept as said.
     * May be called from the main thread; the writing is done behind.
     */
    fun shown(arrival: Arrival) {
        afterwards {
            withContext(NonCancellable + io) {
                turn.withLock {
                    val all = arrivalsKept().map { kept -> if (!kept.said && arrival.of.any { it.isOf(kept.note) }) kept.copy(said = true) else kept }
                    if (all != arrivalsKept()) {
                        held = all
                        arrivalsFile.write(all)
                    }
                }
            }
        }
    }

    /** [arrivals] as sentences, each said as soon as its listener has taken it: for a
     *  listener that shows what it is handed there and then. (The app's screens use
     *  [arrivals] and [shown]; this goes when they have moved over.) */
    val news: Flow<String> = flow {
        arrivals.collect {
            emit(it.words)
            shown(it)
        }
    }

    /** Whether the account has gone (signed out of the music server, or its requests
     *  switched off). */
    private fun isGone(): Boolean = !here.value

    private fun arrivalsKept(): List<Arrived> = held ?: (arrivalsFile.read() ?: emptyList()).also { held = it }

    /** The list is on the phone and can't be read just now: nothing is written over it. */
    private val askedUnreadableNow = MutableStateFlow(false)

    /** What the Requests page is told ([askedUnreadable]). */
    private val askedTroubleNow = MutableStateFlow(false)

    /**
     * True when **the list of what this phone asked for is short of what was asked**, and
     * the Requests page should say so, in the library's sentence for it
     * ([Words.askedUnreadable]):
     *
     * - while the file is on the phone and can't be read. Asking still works; what is asked
     *   meanwhile is kept in memory, and put together with what the file holds as soon as
     *   it can be read again, and then this is false again;
     * - for the rest of this run of the app, once the file was found to be something the
     *   app can make nothing of and was moved aside (`Kept`): the list starts afresh, and
     *   what was asked for before will not be said to have arrived.
     */
    val askedUnreadable: StateFlow<Boolean> = askedTroubleNow.asStateFlow()

    private val wayBackTroubleNow = MutableStateFlow("")

    /**
     * What the Requests page says, for this run of the app, when **what was written down
     * of a change to Lidarr that had to be put back** (`asking.json`) **could not be taken
     * up**; empty when there is nothing to say.
     *
     * - The library could make nothing of it, and it has been moved aside
     *   ([RequestsWords.wayBackLost]): if a run of the app was stopped part-way through
     *   asking for a single or an EP, that artist may have been left not taking new
     *   releases, and the app no longer knows which.
     * - It couldn't be read, or couldn't be moved aside ([RequestsWords.WAY_BACK_HELD]):
     *   it is left as it is, nothing is written over it, and until the app is started
     *   again nothing that would have to be put back on Lidarr is changed.
     */
    val wayBackTrouble: StateFlow<String> = wayBackTroubleNow.asStateFlow()

    /** Says whether the list can be read just now, and tells the page accordingly. */
    private fun askedHeld(held: Boolean) {
        askedUnreadableNow.value = held
        askedTroubleNow.value = held || askedFile.asideAs != null
    }

    /** What has been done to the list since it couldn't be read, in order: done again on
     *  top of what the file holds once it can be. Each answers with the whole list. */
    private val sinceUnread = ArrayList<(Long) -> List<Asked>>()

    /** Artists the person might like, as last found; empty until [suggest] has been asked. */
    val suggestions: StateFlow<List<Suggestion>> = suggestionsNow.asStateFlow()

    /** One change to what is kept at a time. Never held across a request. */
    private val turn = Mutex()
    private var place: Long? = null
    private var loadedDirect = false

    /**
     * The newest connection anything has been asked under here, and **what is on its way
     * as that connection's list last showed it**: Lidarr's own number for each entry, and
     * the entry's title (see [cancel]). Under `synchronized(this)`.
     */
    private var shownUnder = 0L
    private val shownPending = HashMap<Long, org.medianexus.music.core.Pending>()

    /** Changes to Lidarr under way whose outcome is still to be written down: the place
     *  outlasts the account for as long as there are any (see [outlasting]). */
    private var owed = 0
    private val owedNow = MutableStateFlow(0)
    private var suggested: KeptSuggestions? = null
    @Volatile private var haveLibrary = false
    private val looking = Mutex()

    /** Whether a setting is being put back on Lidarr just now ([putBack]). */
    private val recovering = MutableStateFlow(false)

    /** Held for the one step in which a putting back sees the account is still here and
     *  begins; and passed through by [settled]. */
    private val starting = Any()

    init {
        // The phone's copy of the library, handed to the library's rules each time it
        // changes while the account is here; and each time, a look for what has arrived
        // (see [checkArrivals]). When the account goes, the copy is let go of.
        scope.launch(io) {
            here.collectLatest { present ->
                unlessGone {
                    if (!present) {
                        haveLibrary = false
                        synchronized(this@RequestsPages) { place }?.let { Core.placeLibrary(it, LibraryLists()) }
                        return@unlessGone
                    }
                    library.collectLatest { lists ->
                        val place = ready()
                        Core.placeLibrary(place, lists)
                        haveLibrary = lists.albums.isNotEmpty() || lists.songs.isNotEmpty()
                        checkArrivals()
                    }
                }
            }
        }
        scope.launch(io) {
            combine(requests.settings.map { it.singles }.distinctUntilChanged(), here) { singles, present -> singles to present }
                .collectLatest { (singles, present) -> if (present) unlessGone { Core.placeSingles(ready(), singles) } }
        }
        // **What an earlier run left changed on Lidarr is put back**, by this, the one owner
        // of the account's requests, one at a time: each time Lidarr answers as itself on
        // the connection (`Requests.verified`: the very connection it answered on), for as
        // long as the account is here and that connection stands. `collectLatest` does not
        // begin the next until the one before has ended, and that one does not end until
        // the library's worker has (see [putBack]).
        scope.launch(io) {
            combine(requests.verified, here) { verified, present -> verified?.takeIf { present } }
                .collectLatest { verified -> if (verified != null) unlessGone { putBack(verified.at) } }
        }
        // (Switched on, or on again: what arrived meanwhile is looked for.)
        scope.launch(io) {
            combine(requests.settings.map { it.on }.distinctUntilChanged(), here) { on, present -> on && present }
                .distinctUntilChanged()
                .collect { on -> if (on) unlessGone { runCatching { checkArrivals() } } }
        }
        // (Whoever is listening for this account's news is let go when it goes.)
        scope.launch { here.collect { wake.update { it + 1 } } }
    }

    /** Runs `act`; if it ends because the account went meanwhile ([ready] says so by
     *  cancelling), that is the end of `act` and not of whoever called. */
    private suspend fun unlessGone(act: suspend () -> Unit) {
        try {
            act()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * Something begun that changes Lidarr, **seen to its end and written down whatever
     * happens meanwhile**: `finish` runs on the app's own work once `begun` has ended, with
     * how it ended, and its answer is what the caller is told if the caller is still there.
     */
    private fun <T, R> outlasting(begun: CoreHttp.Begun<Outcome<T>>, finish: suspend (Outcome<T>) -> R): Deferred<R?> {
        synchronized(this) { owedNow.value = ++owed }
        val done = CompletableDeferred<R?>()
        afterwards {
            val ended = runCatching {
                val outcome = try {
                    begun.done.await()
                } catch (_: CoreHttp.Busy) {
                    // (No room for it: it was never begun, and is said as any failure.)
                    Outcome.Failed(CoreHttp.BUSY)
                } catch (_: Exception) {
                    null
                }
                when (outcome) {
                    null, Outcome.Stopped -> null
                    else -> withContext(NonCancellable) { finish(outcome) }
                }
            }
            synchronized(this) { owedNow.value = --owed }
            done.completeWith(ended)
        }
        return done
    }

    /** Returns when nothing that changes Lidarr is still under way here: what was asked of
     *  it seen through and written down, and a putting back (which stops when the account
     *  goes) ended. What an account's leaving waits for before its sign-in is let go of. */
    suspend fun settled() {
        // (Asked once the account has gone. Past this, no putting back can begin: see
        // [putBack]. One that did is under way, and is waited for.)
        synchronized(starting) {}
        combine(owedNow, recovering) { owed, busy -> owed == 0 && !busy }.first { it }
    }

    /** Waits for something begun with [outlasting], as its caller. A caller that goes away
     *  stops it, which Lidarr having begun to be changed is no longer honoured. */
    private suspend fun <R> awaiting(through: Deferred<R?>, begun: CoreHttp.Begun<*>): R = try {
        through.await() ?: throw CancellationException("stopped")
    } catch (e: CancellationException) {
        begun.stop()
        throw e
    }

    /** The place the library keeps for this account's connection (see `Core.placeOpen`),
     *  opened the first time anything is asked, with what was asked for read from the phone. */
    private suspend fun ready(): Long = withContext(io) {
        turn.withLock {
            // (The account has gone: nothing more is asked for it.)
            if (isGone()) throw CancellationException("the account has gone")
            val opened = synchronized(this@RequestsPages) {
                place ?: Core.placeOpen { batch, surely -> askingFile.write(batch, surely) }.also { place = it }
            }
            if (requests.serverMode || loadedDirect) return@withLock opened
            loadedDirect = true
            askingFile.takeUp { written -> Core.placeWays(opened, written.decodeToString()) }
            wayBackTroubleNow.value = when {
                askingFile.held -> RequestsWords.WAY_BACK_HELD
                else -> askingFile.asideAs?.let(RequestsWords::wayBackLost).orEmpty()
            }
            val kept = askedFile.read()
            askedHeld(kept == null)
            if (kept != null) {
                Core.placeAsked(opened, kept)
                // (As the account starts, as on Windows: what has been listed long enough,
                // or waited for too long, is dropped.)
                val tidied = Core.askedTidy(opened, clock())
                if (tidied.changed) askedFile.write(tidied.asked)
                askedNow.value = tidied.asked
                arrivalsFor(tidied.asked, found = emptyList())
            }
            // (An arrival the last run of the app found and never got to say.)
            if (arrivalsKept().any { !it.said }) wake.update { it + 1 }
            opened
        }
    }

    /**
     * Writes down what has been asked for (`all`, the whole list as it now is; `again` does
     * to a list what was just done to this one). **If the list couldn't be read when it was
     * wanted**, it is tried again first: still unreadable, nothing is written (never over a
     * file that can't be read) and what was done is remembered; readable now, what was done
     * since is done again on top of what the file holds, and that is what is kept. False if
     * it is not on the phone at the end of this. Under [turn].
     */
    private fun keepAsked(place: Long, all: List<Asked>, again: ((Long) -> List<Asked>)? = null): Boolean {
        if (!askedUnreadableNow.value) {
            askedNow.value = all
            return askedFile.write(all)
        }
        if (again != null) sinceUnread += again
        val kept = askedFile.read()
        if (kept == null) {
            askedNow.value = all
            return false
        }
        Core.placeAsked(place, kept)
        var merged = Core.askedTidy(place, clock()).asked
        for (done in sinceUnread) merged = done(place)
        sinceUnread.clear()
        askedHeld(false)
        askedNow.value = merged
        note("requests: asked.json can be read again; what was asked for meanwhile was added to it")
        return askedFile.write(merged)
    }

    /** The way to Lidarr, or why there is none: the library's sentence for where the
     *  connection stands. */
    private suspend fun way(): Pair<LidarrAt?, String> {
        // (Certainly on the home network without the permission: nothing is sent, and the
        // pages say what Settings, Requests says, without asking anything themselves. Only
        // maybe there, they ask as before.)
        if ((requests.state.value as? RequestsState.LocalNetwork)?.certain == true) return null to RequestsWords.HOME_NETWORK
        requests.lidarr()?.let { return it to "" }
        return null to when (val state = requests.state.value) {
            is RequestsState.NeedsSignIn, is RequestsState.SigningIn -> Words.signInNeeded
            is RequestsState.Unreachable -> state.why
            RequestsState.KeyForgotten -> Words.keyForgotten
            else -> Words.notConnected
        }
    }

    /**
     * Notes the connection something is about to be asked under. **If it is a newer one
     * than anything was asked under before, Lidarr's numbers that the old one's list showed
     * are no longer good for anything.** False if `at` is itself from before a change that
     * has been seen here already: then whoever asks, asks again.
     *
     * (What a connection has been offered needs nothing done here. Each operation takes
     * its offers on the Rust side by its own connection's number, together with its own
     * Lidarr: see `pages::Place::offers`. So it cannot matter in what order this, the
     * change of connection and an operation that was waiting its turn happen.)
     */
    private fun under(at: LidarrAt): Boolean = synchronized(this) {
        when {
            at.connection == shownUnder -> true
            at.connection < shownUnder -> false
            else -> {
                shownUnder = at.connection
                shownPending.clear()
                true
            }
        }
    }

    /** The way to Lidarr with the place made its own (see [under]), or why there is none. */
    private suspend fun connected(): Triple<LidarrAt?, Long, String> {
        repeat(3) {
            val (at, why) = way()
            if (at == null) return Triple(null, 0, why)
            val place = ready()
            if (under(at)) return Triple(at, place, "")
        }
        return Triple(null, 0, RequestsWords.CONNECTION_CHANGED)
    }

    /** Whether something found under `connection` may still be acted on, the connection
     *  being `at` now. */
    private fun still(connection: Long, at: LidarrAt): Boolean = connection == at.connection

    /** What is said of something pressed that was shown under another connection. */
    private val changed = Said(false, RequestsWords.CONNECTION_CHANGED, stale = true)

    private suspend fun <T> page(shown: (T) -> Unit = {}, ask: suspend (LidarrAt, Long) -> Outcome<T>): Found<T> = withContext(io) {
        val (at, place, why) = connected()
        if (at == null) return@withContext Found.Not(why)
        val outcome = ask(at, place)
        if (at.feature != null && !requests.current(at)) throw CancellationException("stopped")
        when (outcome) {
            is Outcome.Got -> {
                shown(outcome.value)
                Found.Got(outcome.value, at.connection)
            }
            is Outcome.Failed -> Found.Not(outcome.why)
            // (Only ever with the caller cancelled, who is told nothing.)
            Outcome.Stopped -> throw CancellationException("stopped")
        }
    }

    // ---- the pages ---------------------------------------------------------------------------------

    /**
     * Songs, artists and albums matching `query` that aren't in the library ("Not in your
     * library"). `soFar` is handed what has been found each time more of it is known, on a
     * background thread, so the page fills as the answers arrive; all of it is returned.
     * Its `note` is the line to show above the results at each stage.
     *
     * (What `soFar` hands over does not say which connection it was found under; the answer
     * does. A screen that lets rows be pressed before the search has ended uses
     * [searchShowing].)
     */
    suspend fun search(query: String, soFar: (Outside) -> Unit = {}): Found<Outside> = searchShowing(query) { soFar(it.value) }

    /** [search], with what is found so far handed over **as it will be answered**: with the
     *  connection it is found under, to keep with its rows. */
    suspend fun searchShowing(query: String, soFar: (Found.Got<Outside>) -> Unit): Found<Outside> =
        page { at, place -> Core.pageSearch(at, place, query) { if (at.feature == null || requests.current(at)) soFar(Found.Got(it, at.connection)) } }

    /** An artist as the wider world knows them: what they have made, with what the library
     *  has shown as its own and the rest there to be asked for. `id` is an
     *  [org.medianexus.music.core.ArtistOffer]'s, a song's `artistId`, a [Suggestion]'s. */
    suspend fun artist(id: String): Found<OutsideArtist> = page { at, place -> Core.pageArtist(at, place, id) }

    /** The songs that artist is most played for that the library doesn't have, for the top
     *  of their page: asked after [artist] (it takes a few more questions), with the name
     *  their page shows. With no songs in it there is nothing to show. */
    suspend fun popular(id: String, name: String): Found<Popular> = page { at, place -> Core.pageArtistPopular(at, place, id, name) }

    /** An album that isn't in the library: what is on it and whether it can be asked for.
     *  `id` is an offer's `album`. */
    suspend fun album(id: String): Found<AlbumPage> = page { at, place -> Core.pageAlbum(at, place, id) }

    /** What has arrived lately and what Lidarr has still to get (the Requests page). Opening
     *  it is also when arrivals are looked for. */
    suspend fun onTheirWay(): Found<Waiting> {
        if (!requests.serverMode) checkArrivals()
        var asked: LidarrAt? = null
        return page(shown = { waiting ->
            // (What can be taken off the list is what this very connection has just listed.)
            val entries = waiting.pending.map { it.first }.filter(waiting::canRemove).associateBy { it.id }
            synchronized(this) {
                if (asked?.connection == shownUnder) {
                    shownPending.clear()
                    shownPending += entries
                }
            }
        }) { at, place ->
            asked = at
            Core.pageOnTheirWay(at, place)
        }
    }

    // ---- asking ------------------------------------------------------------------------------------

    /**
     * Whether to ask for an album, and how the question is put, in the library's words: its
     * heading and its text, or that it has been asked for already. `album` is Lidarr's name
     * for it; `song` the song that led here, if it was one; `kind` "EP" or "Single" if the
     * caller knows. Asks nobody.
     *
     * `connection` is the one the row or page that was pressed was found under. **That is
     * looked at before anything else can answer**: if the connection has changed since,
     * the answer is [Asking.Refused] (stale), whatever the Lidarr there is now would have
     * said of an album by that name.
     */
    suspend fun asking(album: String, title: String, by: String = "", song: String = "", kind: String = "", connection: Long): Asking = withContext(io) {
        val (at, place, why) = connected()
        when {
            at == null -> Asking.Refused(why, stale = false)
            !still(connection, at) -> Asking.Refused(RequestsWords.CONNECTION_CHANGED, stale = true)
            else -> Core.asking(place, album, title, by, song, kind, connection)
        }
    }

    /** What to say while an album is being asked for ("Asking for “Parachutes”…"). */
    fun askingFor(title: String): String = Words.askingFor(title)

    /**
     * Asks Lidarr for an album. Says how it went: "Asked for “…”. It'll be in your library
     * once it arrives.", or why it couldn't be asked for.
     *
     * Once Lidarr has begun to be changed this runs to its end though the caller is
     * cancelled, and the note of what was asked for is kept all the same.
     *
     * `connection` is the one the row that was pressed was found under ([Found.Got]). If
     * the connection has changed since (another address, key or sign-in) nothing is asked,
     * [RequestsWords.CONNECTION_CHANGED] is said, and the answer is [Said.stale]: the page
     * is to be asked for again.
     */
    suspend fun request(album: String, title: String, song: String = "", connection: Long): Said = withContext(io) {
        val (at, place, why) = connected()
        if (at == null) return@withContext Said(false, why)
        if (!still(connection, at)) return@withContext changed
        val begun = Core.request(at, place, album)
        if (at.feature != null) return@withContext serverChange(begun, at, Words.askedFor(title)) {
            Core.marked(place, album, true, at.connection)
        }
        val through = outlasting(begun) { outcome ->
            when (outcome) {
                is Outcome.Got -> {
                    val kept = turn.withLock {
                        // The order the library asks of every app: the note is kept
                        // first, and only then is the offer marked as asked for.
                        val by = outcome.value
                        val time = clock()
                        val all = Core.noted(place, album, title, by, song, time)
                        val kept = keepAsked(place, all) { where -> Core.noted(where, album, title, by, song, time) }
                        if (!kept) note("requests: what was asked for couldn't be kept on the phone, so its arrival won't be said")
                        // (Marked as asked for among what the connection it was asked under
                        // has been offered: if the app has been pointed at another Lidarr
                        // meanwhile, that one's offers are not this album's to mark.)
                        Core.marked(place, album, true, at.connection)
                        kept
                    }
                    // (Lidarr has it either way; a note that couldn't be written is said.)
                    Said(true, if (kept) Words.askedFor(title) else Words.askedUnnoted(title), kept = kept)
                }
                is Outcome.Failed -> Said(false, outcome.why)
                Outcome.Stopped -> null
            }
        }
        awaiting(through, begun)
    }

    /** The question before Lidarr is set to keep track of an artist: its heading and its
     *  text. `open` is [OutsideArtist.open]. */
    suspend fun adding(name: String, open: Int): Pair<String, String> = withContext(io) { Core.adding(name, open) }

    private suspend fun serverChange(
        begun: CoreHttp.Begun<out Outcome<*>>,
        at: LidarrAt,
        words: String,
        done: suspend () -> Unit,
    ): Said = try {
        val answer = begun.done.await()
        if (!requests.current(at)) throw CancellationException("stopped")
        when (answer) {
            is Outcome.Got -> { done(); Said(true, words) }
            is Outcome.Failed -> Said(false, answer.why)
            Outcome.Stopped -> throw CancellationException("stopped")
        }
    } catch (e: CancellationException) {
        begun.stop()
        throw e
    }

    /** What to say while an artist is being added ("Adding …"). */
    fun addingNow(name: String): String = Words.addingNow(name)

    /**
     * Has Lidarr get everything by an artist. Says "Added …. Their albums are on Lidarr's
     * list" (the screens add where to see them), or why not. Seen through like [request];
     * and they are no longer suggested. `connection` as for [request].
     */
    suspend fun addArtist(id: String, name: String, connection: Long): Said = withContext(io) {
        val (at, place, why) = connected()
        if (at == null) return@withContext Said(false, why)
        if (!still(connection, at)) return@withContext changed
        val begun = Core.addArtist(at, place, id, name)
        if (at.feature != null) return@withContext serverChange(begun, at, Words.added(name)) {
            turn.withLock { forgetSuggestion(place, id) }
        }
        val through = outlasting(begun) { outcome ->
            when (outcome) {
                is Outcome.Got -> {
                    turn.withLock { forgetSuggestion(place, id) }
                    Said(true, Words.added(name))
                }
                is Outcome.Failed -> Said(false, outcome.why)
                Outcome.Stopped -> null
            }
        }
        awaiting(through, begun)
    }

    /**
     * Takes an album off Lidarr's list (`entry`: its row on the Requests page, as it was
     * shown), and stops watching for it. Says "“…” is off the list", or why not.
     *
     * One change, in one step: left by its caller while Lidarr is answering, the answer is
     * still waited for (a short while), and if Lidarr took it off, it is forgotten here.
     *
     * **It is the entry that is taken off, not the number.** `entry` and `connection` are
     * **the row and the connection its list was found under, as they were when the row was
     * shown** (when its menu was opened: a choice made later is still for that row). The
     * connection must still be the one there is, as for [request]; and the entry must be
     * what this connection's own [onTheirWay] last listed under that number: Lidarr's
     * number, its own name for the album, the title and who it is by. A number the list
     * never showed, or now shows for another album (of the same title, even), is refused
     * in the same way, and nothing is sent.
     */
    suspend fun cancel(entry: org.medianexus.music.core.Pending, connection: Long): Said = withContext(io) {
        val (at, place, why) = connected()
        if (at == null) return@withContext Said(false, why)
        val (id, title) = entry.id to entry.title
        // Lidarr's own number means another album on another Lidarr, and can come to mean
        // another on the same one: besides coming from a row of this connection, the row
        // must be **the entry this very connection's list last showed** under it.
        val listed = synchronized(this@RequestsPages) { shownPending[id].takeIf { shownUnder == at.connection } }
        val same = listed != null && listed.album == entry.album && listed.title == entry.title && listed.artist == entry.artist
        if (!same || !still(connection, at)) return@withContext changed
        val begun = Core.cancelling(at, id, entry.album)
        if (at.feature != null) return@withContext serverChange(begun, at, Words.offTheList(title)) {
            Core.marked(place, entry.album, false, at.connection)
        }
        val through = outlasting(begun) { outcome ->
            when (outcome) {
                is Outcome.Got -> {
                    val kept = turn.withLock {
                        // **By what it is**: Lidarr's own name for the album where the
                        // list gave one, so that another album of the same title (by the
                        // same artist, even) is still watched for.
                        val forgotten = Core.askedForget(place, title, entry.artist, entry.album)
                        // (Nothing to forget, nothing to write: that is kept as it stands.)
                        if (forgotten.changed || askedUnreadableNow.value) keepAsked(place, forgotten.asked) { where -> Core.askedForget(where, title, entry.artist, entry.album).asked } else true
                    }
                    if (!kept) note("requests: an album was taken off Lidarr's list and that couldn't be kept on the phone, so its arrival may still be said")
                    // And its offer no longer says it was asked for (among what the
                    // connection it was taken off under has been offered): it can be asked
                    // for again.
                    if (entry.album.isNotEmpty()) Core.marked(place, entry.album, false, at.connection)
                    // (Lidarr took it off either way. If the phone's note of it couldn't be
                    // crossed out it is not simply "off the list": the note is still there,
                    // and after a restart the album would be watched for again.)
                    Said(true, if (kept) Words.offTheList(title) else Words.offTheListUnnoted(title), kept = kept)
                }
                is Outcome.Failed -> Said(false, outcome.why)
                Outcome.Stopped -> null
            }
        }
        awaiting(through, begun)
    }

    // ---- the way back ------------------------------------------------------------------------------

    /**
     * **Puts back what an earlier run of the app left changed on Lidarr**: an artist's "new
     * releases" setting, turned off for a single or an EP that the run never got to the end
     * of (the app was stopped; Lidarr went away). Only what was written down **for this
     * Lidarr**, `at`, the connection it has just answered on: an artist's number on another
     * Lidarr is another artist, and what is written for another is left as it is, to be
     * acted on if the app is pointed back at it.
     *
     * The rules are the library's (it waits until Lidarr has finished with the artist,
     * looking every few seconds, then sets the setting and crosses the entry off, one
     * artist at a time with whatever else is changing artists). It has a thread of its own
     * and is in nobody's way. Cancelled (the account gone, the connection changed), **it
     * does not return until the library's worker has stopped**: so there is never a second
     * at work beside it, and whoever waits for this account to have settled waits for it.
     * What it had not put back is still written down.
     */
    private suspend fun putBack(at: LidarrAt) {
        val place = ready()
        if (!Core.waysLeft(at, place)) return
        // **Whether the account is still here, and the start, are one step** ([starting]):
        // once the account's departure has begun (it is no longer here, and [settled] has
        // been asked) no putting back begins; one that began just before is waited for.
        val begun = synchronized(starting) {
            if (isGone()) return
            // (Said to be under way only once it is: nothing waits for one that never began.)
            Core.putBack(at, place).also { recovering.value = true }
        }
        note("requests: an earlier run left a setting changed on Lidarr; putting it back")
        try {
            when (val done = begun.done.await()) {
                is Outcome.Got -> note(if (done.value) "requests: what an earlier run left changed on Lidarr has been put back" else "requests: not everything an earlier run left changed on Lidarr could be put back yet; it is still written down")
                is Outcome.Failed -> note("requests: what an earlier run left changed on Lidarr couldn't be put back (${done.why}); it is still written down")
                Outcome.Stopped -> Unit
            }
        } finally {
            withContext(NonCancellable) {
                begun.stop()
                runCatching { begun.done.await() }
                recovering.value = false
            }
        }
    }

    // ---- arrivals ----------------------------------------------------------------------------------

    /**
     * Looks through the phone's copy of the library for what was asked for and has turned
     * up, and says so on [news]. Asks nobody: it is the copy that is looked through.
     *
     * **When**: each time the phone's copy of the library is read or replaced (as the app
     * starts, and at each refresh of the copy), and on opening the Requests page. There is
     * no background job for it: an album that arrives while the app is closed is said the
     * next time the app is opened and its copy refreshed.
     */
    suspend fun checkArrivals() = withContext(io) {
        if (requests.serverMode) return@withContext
        val place = ready()
        // (Also the moment a list that couldn't be read is tried again: see [keepAsked].)
        if (askedUnreadableNow.value) turn.withLock { if (askedUnreadableNow.value) keepAsked(place, askedNow.value) }
        // (With requests switched off in Settings nothing is looked for, and nothing said.)
        if (!haveLibrary || !requests.settings.value.on) return@withContext
        val news = turn.withLock {
            val arrived = try {
                Core.askedArrivals(place, clock())
            } catch (_: CoreException) {
                return@withLock false
            }
            // In this order: that there is something to say is written down before the
            // note is written as arrived. Stopped between the two, the next run finds the
            // arrival again and finds it already down, said or not; stopped before both,
            // it finds it again and says it then. It is never lost, and never said twice.
            val news = arrivalsFor(arrived.asked, found = arrived.told)
            if (arrived.changed) keepAsked(place, arrived.asked)
            news
        }
        if (news) wake.update { it + 1 }
    }

    /**
     * Brings what is kept of arrivals up to date: `found` are the notes of arrivals that are
     * news (as they were when found), `asked` everything still followed up. An arrival is
     * kept until its note is done with, said or not, so that it is known to have been said
     * for as long as it could be found again. True if there is something still to say.
     * Under [turn].
     */
    private fun arrivalsFor(asked: List<Asked>, found: List<Asked>): Boolean {
        val before = arrivalsKept()
        val still = before.filter { kept -> asked.any { kept.isOf(it) } }
        val after = still + found.filter { note -> still.none { it.isOf(note) } }.map { Arrived(it) }
        if (after != before) {
            held = after
            arrivalsFile.write(after)
        }
        return after.any { !it.said }
    }

    // ---- artists the person might like -------------------------------------------------------------

    /** What is kept of suggestions; nothing if it can't be read just now, **and then it is
     *  read again each time it is wanted**, so that nothing is settled on, or written,
     *  from a file that couldn't be read. */
    private fun keptSuggestions(): KeptSuggestions {
        suggested?.let { return it }
        val read = suggestionsFile.read() ?: return KeptSuggestions()
        suggested = read
        return read
    }

    /** Keeps suggestions that have just been found (or changed). If what was kept couldn't
     *  be read before, it is tried again now, so that a file that has become readable is
     *  written and one that still can't be read is left alone. Under [turn]. */
    private fun keepSuggestions(value: KeptSuggestions): Boolean {
        suggested = value
        suggestionsFile.read()
        return suggestionsFile.write(value)
    }

    private fun forgetSuggestion(place: Long, id: String) {
        val read = Core.discoverKept(place, keptSuggestions(), clock(), forget = id)
        if (read.changed) keepSuggestions(read.kept)
        suggestionsNow.value = read.shown
    }

    /**
     * Artists the library doesn't have, like the ones played most ("Artists you might
     * like"). What is kept is shown at once, on [suggestions] and as the answer; when it is
     * more than three days old (six hours, if there were none to be had) it is looked for
     * afresh first, which takes a dozen or so questions: of the music server, who each
     * favourite is like, and of Lidarr, who each of those is. One look at a time; a look
     * that is stopped or fails keeps nothing, and what was kept before stands.
     */
    suspend fun suggest(): List<Suggestion> = withContext(io) {
        val place = ready()
        val read = turn.withLock { Core.discoverKept(place, keptSuggestions(), clock()) }
        suggestionsNow.value = read.shown
        val (at, _, _) = connected()
        if (read.fresh || at == null || !haveLibrary || !looking.tryLock()) return@withContext read.shown
        try {
            val favourites = Core.discoverFavourites(played())
            // Four questions at a time, so the music server isn't leaned on (as on Windows).
            val alike = ArrayList<Pair<String, List<String>>>()
            for (group in favourites.chunked(4)) {
                val answers = coroutineScope { group.map { (id, _) -> async { runCatching { similar(id) }.getOrElse { if (it is CancellationException) throw it else emptyList() } } }.awaitAll() }
                alike += group.map { it.second }.zip(answers)
            }
            when (val found = Core.discoverLook(at, place, alike, clock())) {
                is Outcome.Got -> turn.withLock {
                    keepSuggestions(found.value)
                    Core.discoverKept(place, found.value, clock()).shown.also { suggestionsNow.value = it }
                }
                // (Not kept: what was there before stands, and is tried again next time.)
                else -> read.shown
            }
        } finally {
            looking.unlock()
        }
    }
}
