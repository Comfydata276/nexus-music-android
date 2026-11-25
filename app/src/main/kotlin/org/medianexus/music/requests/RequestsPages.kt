package org.medianexus.music.requests

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.medianexus.music.core.AlbumPage
import org.medianexus.music.core.Asked
import org.medianexus.music.core.Asking
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException
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
    data class Got<T>(val value: T) : Found<T>

    data class Not(val why: String) : Found<Nothing>
}

/** How something the person asked to be done ended: what to say, and whether it was done. */
data class Said(val done: Boolean, val words: String)

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
 *   requests in flight are cut off and nothing is delivered. The two exceptions are asking
 *   for an album and adding an artist once Lidarr has begun to be changed: those are seen
 *   through behind the screen's back, and what was asked for is written down all the same
 *   (it is the account's doing by then, not the screen's).
 * - **Nothing is retried in a loop.** A page that could not be had says why, once.
 * - **Kept on the phone**, per account, written whole and never over a file that couldn't be
 *   read: `asked.json` (what was asked for through this phone, so that its arrival can be
 *   said) and `suggestions.json` (artists the person might like, for three days).
 */
class RequestsPages internal constructor(
    private val requests: Requests,
    /** The account's own work: cancelled when it signs out. */
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
) {
    private val askedFile = Kept(File(dir, "asked.json"), ListSerializer(Asked.serializer()), emptyList(), note)
    private val suggestionsFile = Kept(File(dir, "suggestions.json"), KeptSuggestions.serializer(), KeptSuggestions(), note)

    private val askedNow = MutableStateFlow<List<Asked>>(emptyList())
    private val newsNow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val suggestionsNow = MutableStateFlow<List<Suggestion>>(emptyList())

    /** What was asked for through this phone and is still followed up, newest note last. */
    val asked: StateFlow<List<Asked>> = askedNow.asStateFlow()

    /** Said when albums that were asked for have turned up in the library ("“Yellow” has
     *  arrived, on “Parachutes”."), once for each arrival. The screens add where to see it. */
    val news: SharedFlow<String> = newsNow.asSharedFlow()

    /** Artists the person might like, as last found; empty until [suggest] has been asked. */
    val suggestions: StateFlow<List<Suggestion>> = suggestionsNow.asStateFlow()

    /** One change to what is kept at a time. Never held across a request. */
    private val turn = Mutex()
    private var place: Long? = null
    private var gone = false
    private var suggested: KeptSuggestions? = null
    @Volatile private var haveLibrary = false
    private val looking = Mutex()

    init {
        scope.launch(io) {
            ready()
            // The phone's copy of the library, handed to the library's rules each time it
            // changes; and each time, a look for what has arrived (see [checkArrivals]).
            library.collectLatest { lists ->
                val place = ready()
                Core.placeLibrary(place, lists)
                haveLibrary = lists.albums.isNotEmpty() || lists.songs.isNotEmpty()
                checkArrivals()
            }
        }
        scope.launch(io) {
            requests.settings.collectLatest { settings -> Core.placeSingles(ready(), settings.singles) }
        }
        // (The place goes with the account.)
        scope.coroutineContext.job.invokeOnCompletion {
            val was = synchronized(this) {
                gone = true
                place.also { place = null }
            }
            was?.let { runCatching { Core.placeClose(it) } }
        }
    }

    /** The place the library keeps for this account's connection (see `Core.placeOpen`),
     *  opened the first time anything is asked, with what was asked for read from the phone. */
    private suspend fun ready(): Long = withContext(io) {
        turn.withLock {
            synchronized(this@RequestsPages) {
                // (The account has gone: nothing more is asked for it.)
                if (gone) throw CancellationException("the account has gone")
                place
            }?.let { return@withLock it }
            val opened = Core.placeOpen()
            val kept = askedFile.read()
            if (kept != null) {
                Core.placeAsked(opened, kept)
                // (As the account starts, as on Windows: what has been listed long enough,
                // or waited for too long, is dropped.)
                val tidied = Core.askedTidy(opened, clock())
                if (tidied.changed) askedFile.write(tidied.asked)
                askedNow.value = tidied.asked
            }
            val late = synchronized(this@RequestsPages) {
                if (!gone) place = opened
                gone
            }
            if (late) {
                Core.placeClose(opened)
                throw CancellationException("the account has gone")
            }
            opened
        }
    }

    /** The way to Lidarr, or why there is none: the library's sentence for where the
     *  connection stands. */
    private suspend fun way(): Pair<LidarrAt?, String> {
        requests.lidarr()?.let { return it to "" }
        return null to when (val state = requests.state.value) {
            is RequestsState.NeedsSignIn, is RequestsState.SigningIn -> Words.signInNeeded
            is RequestsState.Unreachable -> state.why
            RequestsState.KeyForgotten -> Words.keyForgotten
            else -> Words.notConnected
        }
    }

    private suspend fun <T> page(ask: suspend (LidarrAt, Long) -> Outcome<T>): Found<T> = withContext(io) {
        val (at, why) = way()
        if (at == null) return@withContext Found.Not(why)
        when (val outcome = ask(at, ready())) {
            is Outcome.Got -> Found.Got(outcome.value)
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
     */
    suspend fun search(query: String, soFar: (Outside) -> Unit = {}): Found<Outside> = page { at, place -> Core.pageSearch(at, place, query, soFar) }

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
        checkArrivals()
        return page { at, place -> Core.pageOnTheirWay(at, place) }
    }

    // ---- asking ------------------------------------------------------------------------------------

    /** Whether to ask for an album, and how the question is put, in the library's words:
     *  its heading and its text, or that it has been asked for already. `album` is Lidarr's
     *  name for it; `song` the song that led here, if it was one; `kind` "EP" or "Single" if
     *  the caller knows. Asks nobody. */
    suspend fun asking(album: String, title: String, by: String = "", song: String = "", kind: String = ""): Asking =
        withContext(io) { Core.asking(ready(), album, title, by, song, kind) }

    /** What to say while an album is being asked for ("Asking for “Parachutes”…"). */
    fun askingFor(title: String): String = Words.askingFor(title)

    /**
     * Asks Lidarr for an album. Says how it went: "Asked for “…”. It'll be in your library
     * once it arrives.", or why it couldn't be asked for.
     *
     * Once Lidarr has begun to be changed this runs to its end though the caller is
     * cancelled, and the note of what was asked for is kept all the same.
     */
    suspend fun request(album: String, title: String, song: String = ""): Said = withContext(io) {
        val (at, why) = way()
        if (at == null) return@withContext Said(false, why)
        val place = ready()
        val begun = Core.request(at, place, album)
        // In the account's own scope: what follows must happen whether or not whoever
        // asked is still there.
        val through = scope.async(io) {
            when (val outcome = runCatching { begun.done.await() }.getOrNull()) {
                is Outcome.Got -> {
                    withContext(NonCancellable) {
                        turn.withLock {
                            // The order the library asks of every app: the note is kept
                            // first, and only then is the offer marked as asked for.
                            val all = Core.noted(place, album, title, outcome.value, song, clock())
                            if (!askedFile.write(all)) note("requests: what was asked for couldn't be kept on the phone, so its arrival won't be said")
                            askedNow.value = all
                            Core.marked(place, album, true)
                        }
                    }
                    Said(true, Words.askedFor(title))
                }
                is Outcome.Failed -> Said(false, outcome.why)
                else -> null
            }
        }
        try {
            through.await() ?: throw CancellationException("stopped")
        } catch (e: CancellationException) {
            // (Honoured only if Lidarr has not begun to be changed.)
            begun.stop()
            throw e
        }
    }

    /** The question before Lidarr is set to keep track of an artist: its heading and its
     *  text. `open` is [OutsideArtist.open]. */
    suspend fun adding(name: String, open: Int): Pair<String, String> = withContext(io) { Core.adding(name, open) }

    /** What to say while an artist is being added ("Adding …"). */
    fun addingNow(name: String): String = Words.addingNow(name)

    /**
     * Has Lidarr get everything by an artist. Says "Added …. Their albums are on Lidarr's
     * list" (the screens add where to see them), or why not. Seen through like [request];
     * and they are no longer suggested.
     */
    suspend fun addArtist(id: String, name: String): Said = withContext(io) {
        val (at, why) = way()
        if (at == null) return@withContext Said(false, why)
        val place = ready()
        val begun = Core.addArtist(at, id, name)
        val through = scope.async(io) {
            when (val outcome = runCatching { begun.done.await() }.getOrNull()) {
                is Outcome.Got -> {
                    withContext(NonCancellable) { turn.withLock { forgetSuggestion(place, id) } }
                    Said(true, Words.added(name))
                }
                is Outcome.Failed -> Said(false, outcome.why)
                else -> null
            }
        }
        try {
            through.await() ?: throw CancellationException("stopped")
        } catch (e: CancellationException) {
            begun.stop()
            throw e
        }
    }

    /** Takes an album off Lidarr's list (`id` is its [org.medianexus.music.core.Pending]
     *  number), and stops watching for it. Says "“…” is off the list", or why not. */
    suspend fun cancel(id: Long, title: String): Said = withContext(io) {
        val (at, why) = way()
        if (at == null) return@withContext Said(false, why)
        val place = ready()
        when (val outcome = Core.lidarrCancel(at, id)) {
            is Outcome.Got -> {
                withContext(NonCancellable) {
                    turn.withLock {
                        val forgotten = Core.askedForget(place, title)
                        if (forgotten.changed) {
                            askedFile.write(forgotten.asked)
                            askedNow.value = forgotten.asked
                        }
                    }
                }
                Said(true, Words.offTheList(title))
            }
            is Outcome.Failed -> Said(false, outcome.why)
            Outcome.Stopped -> throw CancellationException("stopped")
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
        val place = ready()
        if (!haveLibrary) return@withContext
        val arrived = turn.withLock {
            val arrived = try {
                Core.askedArrivals(place, clock())
            } catch (_: CoreException) {
                return@withLock null
            }
            if (arrived.changed) {
                askedFile.write(arrived.asked)
                askedNow.value = arrived.asked
            }
            arrived
        } ?: return@withContext
        if (arrived.news.isNotEmpty()) newsNow.tryEmit(arrived.news)
    }

    // ---- artists the person might like -------------------------------------------------------------

    private fun keptSuggestions(): KeptSuggestions = suggested ?: (suggestionsFile.read() ?: KeptSuggestions()).also { suggested = it }

    private fun forgetSuggestion(place: Long, id: String) {
        val read = Core.discoverKept(place, keptSuggestions(), clock(), forget = id)
        if (read.changed) {
            suggested = read.kept
            suggestionsFile.write(read.kept)
        }
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
        val (at, _) = way()
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
                    suggested = found.value
                    suggestionsFile.write(found.value)
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
