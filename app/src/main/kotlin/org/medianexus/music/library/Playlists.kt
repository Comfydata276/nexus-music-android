package org.medianexus.music.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/** What a playlist change asks of the server (the Subsonic calls behind it). The changes go
 *  by a way that never sends a request twice (`Subsonic.once`). */
interface PlaylistServer {
    suspend fun playlist(id: String): Playlist
    suspend fun createPlaylist(name: String, songIds: List<String>): Playlist
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun addToPlaylist(id: String, songIds: List<String>)
    suspend fun replacePlaylist(id: String, songIds: List<String>)
    suspend fun removeManyFromPlaylist(id: String, positions: List<Int>)
    suspend fun deletePlaylist(id: String)
}

/** A change to a playlist that didn't happen, or may not have. `name` is the playlist's, as
 *  shown when it was made. */
data class PlaylistRefused(val playlist: String, val name: String, val outcome: Outcome, val error: ApiError? = null) {
    enum class Outcome {
        /** The server said no. */
        REFUSED,
        /** No answer came: it may or may not have been done. */
        UNCONFIRMED,
        /** The server was never reached: it wasn't done. */
        UNSENT,
        /** The playlist was not as it was drawn: nothing was sent, and the page shows it now. */
        CHANGED,
        /** The same, for an Undo. */
        UNDO_CHANGED,
        /** The server no longer has it (its error 70). */
        GONE,
    }
}

/** What "Undo" takes back: the last removal or move the server said yes to. The playlist is
 *  `leftAs` that change left it; `before` is how it was. */
data class PlaylistUndo(val playlist: String, val leftAs: Playlist, val before: List<Song>, val at: Long)

/**
 * The one owner of changes to the account's playlists.
 *
 * - **On the playlist exactly as it was drawn, or not at all.** A change carries the copy of
 *   the playlist it was made on (the page's, or the list's for a card), and the playlist is read
 *   fresh just before it is written. Only if the two are the same (the songs in order, and the
 *   name) is it written, and then the rows the person chose are simply those rows. If they
 *   differ, nothing is sent: the page shows the fresh copy and the person is told, once.
 *   Adding songs at the end is the one change that doesn't depend on what the playlist holds.
 *   (Windows instead carries a change out on a playlist changed since, by the song and which
 *   copy of it; the phone refuses and asks again: docs/DESIGN.md.)
 * - **One change at a time for a playlist, and nothing waits behind it.** While one is on its
 *   way the playlist is [busy]: a second change is refused ([Unavailable.BUSY]).
 * - **Only with the server there, and attempted once** ([PlaylistServer] goes by
 *   `Subsonic.once`): done, refused, never sent, or nobody knows (then the playlist is read
 *   again and the person asked to check it).
 * - **After the server says yes, the page is what was sent**: the drawn copy with the change
 *   carried out, kept at once; a read afterwards catches up with other devices if it can.
 * - **Undo** is the reverse of what the server said yes to, made on the playlist as that left
 *   it, under the same rule; out of reach, it says so and is kept.
 * - **Only the account's own** ([canEdit]): checked as a change is made and on the fresh copy.
 * - **Gone** (the server's error 70, on any read of a playlist): its page and its row in Library
 *   go, and the person is told ([fetched]).
 */
class Playlists(
    private val server: PlaylistServer,
    private val pages: PageCache,
    private val listSpec: () -> PageSpec<List<Playlist>>,
    private val playlistSpec: (String) -> PageSpec<Playlist>,
    /** The server is known to be there. */
    private val reachable: () -> Boolean,
    /** Whose playlists can be changed: the account's own. */
    private val username: String,
    private val scope: CoroutineScope,
    /** When songs joined a playlist (Windows' `added`); null where none is kept. */
    private val added: org.medianexus.music.store.Added? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Why a change can't be made now. */
    enum class Unavailable {
        /** The server isn't known to be there. */
        OFFLINE,
        /** Not known to be the account's own (someone else's, read-only, or not loaded). */
        NOT_YOURS,
        /** A change to this playlist is still on its way. */
        BUSY,
        /** There is nothing (any more) to undo. */
        NOTHING_TO_UNDO,
    }

    /** What is changed: rows taken out, or the whole order (a move, or an Undo). */
    private sealed interface Change {
        data class Remove(val rows: List<Int>) : Change
        data class Order(val songs: List<Song>) : Change
        data class Rename(val name: String) : Change
        data object Delete : Change
        data class Append(val songs: List<Song>) : Change
        data class Create(val name: String, val songs: List<Song>) : Change
    }

    private class Flight(val drawn: Playlist?, val change: Change, val undo: Boolean)

    private val lock = Any()
    private var flights = emptyMap<String, Flight>()
    private val busyNow = MutableStateFlow<Set<String>>(emptySet())

    /** The playlists with a change on its way: any other change to them is refused until it
     *  has settled. */
    val busy: StateFlow<Set<String>> = busyNow.asStateFlow()

    /** Each playlist's fetches and changes take turns. */
    private val turns = HashMap<String, Mutex>()
    private fun turn(id: String) = synchronized(lock) { turns.getOrPut(id) { Mutex() } }

    private val refusals = MutableSharedFlow<PlaylistRefused>(extraBufferCapacity = 8)
    val refused: SharedFlow<PlaylistRefused> = refusals.asSharedFlow()

    private val lastUndo = MutableStateFlow<PlaylistUndo?>(null)

    /** The last removal or move the server said yes to, while it can still be taken back. */
    val undo: StateFlow<PlaylistUndo?> = lastUndo

    private val goneNow = MutableStateFlow<Set<String>>(emptySet())

    /** Playlists the server said no longer exist (its error 70): their pages close. */
    val gone: StateFlow<Set<String>> = goneNow.asStateFlow()

    /** Whether this account may change `playlist`: one the server says is the account's, and
     *  not one Navidrome calls read-only. One whose owner isn't given is not taken to be. */
    fun canEdit(playlist: Playlist): Boolean = !playlist.readonly && playlist.owner.isNotEmpty() && playlist.owner.equals(username, ignoreCase = true) &&
        // (Never the playlist the app keeps what it shares between devices in.)
        playlist.name != org.medianexus.music.sync.PLAYLIST_NAME

    /** A playlist as the page shows it: as kept, or with the change on its way carried out (if
     *  it was made on this very copy, or adds songs at the end). */
    fun shown(playlist: Playlist): Playlist {
        val flight = synchronized(lock) { flights[playlist.id] } ?: return playlist
        return when {
            flight.change is Change.Append -> applied(playlist, flight.change)
            flight.drawn != null && same(playlist, flight.drawn) -> applied(playlist, flight.change)
            else -> playlist
        }
    }

    /** Fetches a playlist's page (`fetch`, the server's), taking turns with the changes to it.
     *  The one place that decides a playlist is gone. */
    suspend fun fetched(id: String, fetch: suspend () -> Playlist): Playlist = turn(id).withLock { reading(id, fetch) }

    // ---- Making changes ---------------------------------------------------------------------

    /** Takes these rows of `playlist` (as drawn) out; `done` is told whether the server did. */
    fun remove(playlist: Playlist, rows: Collection<Int>, done: (Boolean) -> Unit = {}): Unavailable? {
        val places = rows.filter { it in playlist.songs.indices }.distinct().sorted()
        if (places.isEmpty()) return null
        return begin(playlist.id, playlist, Change.Remove(places), done)
    }

    /** Moves the song at row `from` of `playlist` (as drawn) to row `to`. */
    fun move(playlist: Playlist, from: Int, to: Int, done: (Boolean) -> Unit = {}): Unavailable? {
        val songs = playlist.songs
        if (from == to || from !in songs.indices || to !in songs.indices) return null
        return begin(playlist.id, playlist, Change.Order(songs.toMutableList().apply { add(to, removeAt(from)) }), done)
    }

    /**
     * Takes back the last removal or move of this playlist the server said yes to, within a
     * minute (Windows' `undo_last`), on the playlist exactly as that left it. Out of reach, or
     * with a change on its way, it says so and is kept for later.
     */
    fun undoLast(id: String, done: (Boolean) -> Unit = {}): Unavailable? = synchronized(lock) {
        val undo = lastUndo.value?.takeIf { it.playlist == id && clock() - it.at <= UNDO_MILLIS } ?: return Unavailable.NOTHING_TO_UNDO
        begin(id, undo.leftAs, Change.Order(undo.before), done, undo = true)
    }

    /** Adds songs at the end of a playlist. */
    fun add(record: Playlist, songs: List<Song>, done: (Boolean) -> Unit = {}): Unavailable? {
        val wanted = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }
        if (wanted.isEmpty()) return null
        return begin(record.id, record, Change.Append(wanted), done)
    }

    /** A new playlist with these songs. */
    fun create(name: String, songs: List<Song>, done: (Boolean) -> Unit = {}): Unavailable? =
        begin("new:" + java.util.UUID.randomUUID(), null, Change.Create(name, songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }), done)

    fun rename(record: Playlist, name: String, done: (Boolean) -> Unit = {}): Unavailable? =
        if (name.isBlank()) null else begin(record.id, record, Change.Rename(name.trim()), done)

    fun delete(record: Playlist, done: (Boolean) -> Unit = {}): Unavailable? = begin(record.id, record, Change.Delete, done)

    /** Why no change can be made now, if none can: the server isn't known to be there. */
    fun reachableNow(): Unavailable? = if (reachable()) null else Unavailable.OFFLINE

    /**
     * Which of `songs` are already in the playlist, to warn before adding them, as the server
     * has the playlist now (Windows asks it too). Null if it can't be read: then nothing is
     * added, and the person is told.
     */
    suspend fun alreadyIn(playlist: String, songs: List<Song>): Set<String>? {
        val now = try {
            pages.fresh(playlistSpec(playlist))
        } catch (_: ApiError) {
            return null
        }
        val present = now.songs.mapTo(HashSet()) { it.id }
        return songs.mapNotNullTo(HashSet()) { song -> song.id.takeIf { it in present } }
    }

    // ---- Sending ----------------------------------------------------------------------------

    private fun begin(id: String, drawn: Playlist?, change: Change, done: (Boolean) -> Unit, undo: Boolean = false): Unavailable? {
        if (!reachable()) return Unavailable.OFFLINE
        if (change !is Change.Create && (drawn == null || !canEdit(drawn))) return Unavailable.NOT_YOURS
        val flight = Flight(drawn, change, undo)
        synchronized(lock) {
            if (id in flights) return Unavailable.BUSY
            flights = flights + (id to flight)
            busyNow.value = flights.keys
            // An Undo is for the last change only: a new one, or the Undo itself, uses it up.
            if (lastUndo.value?.playlist == id) lastUndo.value = null
        }
        scope.launch {
            val refusal = try {
                send(id, flight)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Failed) {
                e.refusal
            } finally {
                synchronized(lock) {
                    flights = flights - id
                    busyNow.value = flights.keys
                }
            }
            done(refusal == null)
            // (Gone has been said where it was learnt.)
            if (refusal != null && refusal.outcome != PlaylistRefused.Outcome.GONE) refusals.emit(refusal)
            if (refusal?.outcome == PlaylistRefused.Outcome.UNCONFIRMED) catchUp(id)
        }
        return null
    }

    private class Failed(val refusal: PlaylistRefused) : Exception()

    /**
     * Sends one change: the playlist read fresh, whether it is still the account's and still
     * as the change was drawn, then the change written, once. Then the page is the drawn copy
     * with it carried out, before anything else may read the playlist.
     */
    private suspend fun send(id: String, flight: Flight) = turn(id).withLock {
        val change = flight.change
        val name = (change as? Change.Create)?.name ?: flight.drawn?.name.orEmpty()
        fun failed(outcome: PlaylistRefused.Outcome, error: ApiError? = null) = Failed(PlaylistRefused(id, name, outcome, error))
        var writing = false
        try {
            val now = if (change is Change.Create) null else reading(id) { server.playlist(id) }
            if (now != null && !canEdit(now)) throw failed(PlaylistRefused.Outcome.REFUSED, ApiError.Server(50, ""))
            val drawn = flight.drawn
            if (now != null && change !is Change.Append && drawn != null && !same(now, drawn)) {
                pages.put(playlistSpec(id), now)
                throw failed(if (flight.undo) PlaylistRefused.Outcome.UNDO_CHANGED else PlaylistRefused.Outcome.CHANGED)
            }
            writing = true
            when (change) {
                is Change.Create -> {
                    val songIds = change.songs.map { it.id }
                    val made = server.createPlaylist(change.name, songIds)
                    if (made.id.isNotEmpty()) added?.create(made.id, songIds, clock() / 1000)
                }
                is Change.Rename -> server.renamePlaylist(id, change.name)
                Change.Delete -> server.deletePlaylist(id)
                is Change.Append -> {
                    val songIds = change.songs.map { it.id }
                    server.addToPlaylist(id, songIds)
                }
                is Change.Remove -> server.removeManyFromPlaylist(id, change.rows)
                is Change.Order -> server.replacePlaylist(id, change.songs.map { it.id })
            }
            writing = false
            // Yes: the page is what was sent, at once.
            when (change) {
                is Change.Create -> {}
                Change.Delete -> {
                    pages.forget(playlistSpec(id).key)
                    runCatching { pages.update(listSpec()) { list -> list.filter { it.id != id } } }
                }
                else -> {
                    val after = applied(now!!, change)
                    // The person's own change: the songs it added or took out are dated now,
                    // for other devices too (before the page is told, which only notices).
                    added?.changed(id, now.songs.map { it.id }, after.songs.map { it.id }, clock() / 1000)
                    pages.put(playlistSpec(id), after)
                    if (!flight.undo && (change is Change.Remove || change is Change.Order)) {
                        synchronized(lock) { lastUndo.value = PlaylistUndo(id, after, now.songs, clock()) }
                    }
                }
            }
            if (change is Change.Create || change is Change.Rename) scope.launch { runCatching { pages.fresh(listSpec()) } }
        } catch (e: ApiError) {
            throw when {
                e is ApiError.Server && e.code == 70L -> failed(PlaylistRefused.Outcome.GONE, e).also { gone(id, e) }
                e is ApiError.Server -> failed(PlaylistRefused.Outcome.REFUSED, e)
                !writing || (e is ApiError.Network && e.unsent) -> failed(PlaylistRefused.Outcome.UNSENT, e)
                else -> failed(PlaylistRefused.Outcome.UNCONFIRMED, e)
            }
        }
    }

    /** A read of a playlist: one the server says is gone (70) goes from the phone and Library,
     *  and the person is told, however it was read. */
    private suspend fun reading(id: String, fetch: suspend () -> Playlist): Playlist = try {
        fetch()
    } catch (e: ApiError.Server) {
        if (e.code == 70L) gone(id, e)
        throw e
    }

    private suspend fun gone(id: String, error: ApiError) {
        val first = synchronized(lock) { (id !in goneNow.value).also { goneNow.value = goneNow.value + id } }
        pages.forget(playlistSpec(id).key)
        runCatching { pages.update(listSpec()) { list -> list.filter { it.id != id } } }
        if (first) refusals.emit(PlaylistRefused(id, "", PlaylistRefused.Outcome.GONE, error))
    }

    /** After a change nobody knows the fate of: the playlist as the server has it now (or,
     *  for a new one, the list of playlists). */
    private fun catchUp(id: String) = scope.launch {
        runCatching { if (id.startsWith("new:")) pages.fresh(listSpec()) else pages.fresh(playlistSpec(id)) }
    }

    private fun same(a: Playlist, b: Playlist): Boolean = a.name == b.name && drawnIds(b).let { it == null || it == a.songs.map { s -> s.id } }

    /** The songs a copy was drawn with, in order; null for one from the list of playlists,
     *  which names a playlist without its songs (a card's menu: only its name was seen). */
    private fun drawnIds(p: Playlist): List<String>? = if (p.songs.isEmpty() && p.songCount > 0) null else p.songs.map { it.id }

    private fun applied(p: Playlist, change: Change): Playlist = when (change) {
        is Change.Remove -> p.songs.filterIndexed { i, _ -> i !in change.rows }.let { withSongs(p, it) }
        is Change.Order -> withSongs(p, change.songs)
        is Change.Append -> withSongs(p, p.songs + change.songs)
        is Change.Rename -> p.copy(name = change.name)
        Change.Delete, is Change.Create -> p
    }

    private fun withSongs(p: Playlist, songs: List<Song>) = p.copy(songs = songs, songCount = songs.size, duration = songs.sumOf { it.duration })

    companion object {
        /** How long after a removal or move it can be taken back (Windows' minute). */
        const val UNDO_MILLIS = 60_000L
    }
}
