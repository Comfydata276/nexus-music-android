package org.medianexus.music.library

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec
import org.medianexus.music.store.Pending

/** What a playlist change asks of the server (the Subsonic calls behind it). */
interface PlaylistServer {
    suspend fun playlist(id: String): Playlist
    suspend fun createPlaylist(name: String, songIds: List<String>): Playlist
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun addToPlaylist(id: String, songIds: List<String>)
    suspend fun replacePlaylist(id: String, songIds: List<String>)
    suspend fun removeManyFromPlaylist(id: String, positions: List<Int>)
    suspend fun deletePlaylist(id: String)
}

/** A change to a playlist that didn't save, already put right on the phone. `name` is the
 *  playlist's, as shown when the change was made. */
data class PlaylistRefused(val playlist: String, val name: String, val edit: PlaylistEdit, val error: ApiError)

/** The last removal or move, which "Undo" takes back (Windows' `Undo`). */
sealed interface PlaylistUndo {
    val playlist: String
    val at: Long

    /** Songs were taken out; `places` is where they were in `before`, lowest first. */
    data class Removed(override val playlist: String, val before: List<Song>, val places: List<Int>, override val at: Long) : PlaylistUndo

    /** The song at `from` in `before` was moved to `to`. */
    data class Moved(override val playlist: String, val before: List<Song>, val from: Int, val to: Int, override val at: Long) : PlaylistUndo
}

/**
 * The one owner of changes to the account's playlists (Windows' `edit_playlist` and its
 * neighbours in src/actions.rs), and of how a playlist is shown while they are on their way.
 *
 * **The safety rule, from src/playlists.rs.** A removal or a move is described by the songs
 * themselves, which copy of a song included ([Spot]), never by position, and carried out on
 * the playlist as the server has it at the moment it is sent ([send]): a change made on another
 * device in the meantime is kept, and the wrong song is never removed.
 *
 * **One sender, in order, and held when offline.** Every change goes through the account's one
 * ordered sender (`HeldChanges`), like a like: written to the phone, then sent, one at a time,
 * in the order made; with no server, held and sent when it is back. Because a change says
 * which songs it means, sending it later, to a playlist that has changed since, is as safe as
 * sending it now. Only a new playlist is not made offline: the server gives it its id.
 *
 * **Shown at once, and what is held wins over what the server says.** A playlist is shown as
 * the server last sent it with every change still on its way carried out on top, in the order
 * made ([onTop]): the kept copy has the change the moment it is made, and a fresh copy from the
 * server, which won't have it until it is sent, is shown with it too. A fetch of a playlist and
 * the sending of a change to it take turns ([fetched]), and a change is marked sent while its
 * turn is still held, so no copy is shown with a change carried out twice. Refused (the
 * playlist has gone, or isn't this account's), the change is dropped, the playlist is asked for
 * again, and the person is told ([refused]).
 *
 * **What can't be promised**, as for plays: the Subsonic interface has no way to make a change
 * safe to send twice. An app killed in the instant between the server taking a change and the
 * phone recording it sends it again: a move comes to the same thing, but a removal of one of
 * several copies of a song takes out another, and songs added are added twice.
 */
class Playlists(
    private val server: PlaylistServer,
    private val pages: PageCache,
    private val listSpec: () -> PageSpec<List<Playlist>>,
    private val playlistSpec: (String) -> PageSpec<Playlist>,
    /** The account's one sender: writes the change, tells `written` once it is in the queue,
     *  and returns once it is sent or held; throws if refused or if it couldn't be kept. */
    private val submit: suspend (Pending, written: () -> Unit) -> Unit,
    /** What the account's sender holds, oldest first. */
    private val held: StateFlow<List<Pending>>,
    /** Nothing may be asked of the server now. */
    private val offline: () -> Boolean,
    /** Whose playlists can be changed: the account's own. */
    private val username: String,
    private val scope: CoroutineScope,
    /** When songs joined a playlist (Windows' `added`); null where none is kept. */
    private val added: org.medianexus.music.store.Added? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** Changes made and not yet in the sender's queue (being written). */
    private val making = ArrayList<Pending.PlaylistChange>()

    /** Changes the server has taken that may still be in the sender's queue for a moment. */
    private val sent: MutableSet<Pending.PlaylistChange> = Collections.newSetFromMap(IdentityHashMap())

    /** Each playlist's fetches and sends take turns. */
    private val turns = HashMap<String, Mutex>()

    /** Changes are put in the sender's queue in the order they were made. */
    private val order = Mutex()

    private val refusals = MutableSharedFlow<PlaylistRefused>(extraBufferCapacity = 8)
    val refused: SharedFlow<PlaylistRefused> = refusals.asSharedFlow()

    private val lastUndo = MutableStateFlow<PlaylistUndo?>(null)

    /** The last removal or move, while it can still be taken back. */
    val undo: StateFlow<PlaylistUndo?> = lastUndo

    /** Whether this account may change `playlist`: its own (a playlist that names no owner is
     *  taken to be). Someone else's can be played, not changed: Navidrome refuses. */
    fun canEdit(playlist: Playlist): Boolean = playlist.owner.isEmpty() || playlist.owner.equals(username, ignoreCase = true)

    private fun turn(id: String) = synchronized(lock) { turns.getOrPut(id) { Mutex() } }

    /** The changes to `id` still on their way, in the order made. */
    private fun onTheirWay(id: String): List<PlaylistEdit> = synchronized(lock) {
        val queued = held.value.filterIsInstance<Pending.PlaylistChange>()
        val all = queued + making.filter { mine -> queued.none { it === mine } }
        all.filter { it.id == id && it !in sent }.map { it.edit }
    }

    /** A playlist as the server sent it, with every change to it still on its way on top. */
    fun onTop(playlist: Playlist): Playlist = onTheirWay(playlist.id).fold(playlist) { shown, edit -> PlaylistEdits.applyTo(shown, edit) }

    /** The list of playlists as the server sent it, with renames and deletions still on their
     *  way on top. */
    fun onTopOfList(list: List<Playlist>): List<Playlist> = list.mapNotNull { playlist ->
        val edits = onTheirWay(playlist.id)
        if (edits.isEmpty()) return@mapNotNull playlist
        if (edits.any { it == PlaylistEdit.Delete }) return@mapNotNull null
        edits.filterIsInstance<PlaylistEdit.Rename>().lastOrNull()?.let { playlist.copy(name = it.name) } ?: playlist
    }

    /** Fetches a playlist (`fetch`, the server's), taking turns with the sending of changes to
     *  it, and shows it with the changes still on their way. */
    suspend fun fetched(id: String, fetch: suspend () -> Playlist): Playlist = turn(id).withLock { onTop(fetch()) }

    // ---- Making changes ---------------------------------------------------------------------

    /**
     * Makes a change to a playlist: shown at once, then sent in its turn (see the class). `name`
     * is the playlist's, for what is said if it doesn't save. Returns false, doing nothing, if
     * it can't be made now: a new playlist while offline.
     */
    fun edit(playlist: String, edit: PlaylistEdit, name: String): Boolean {
        if (edit is PlaylistEdit.Create && offline()) return false
        val change = Pending.PlaylistChange(playlist, edit, at = clock())
        synchronized(lock) { making += change }
        // Started here and now: the changes take their turns in the order they were made.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            order.lock()
            var let = false
            val letGo = { if (!let) { let = true; order.unlock() } }
            try {
                show(change)
                submit(change, letGo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                refused(change, name, e)
            } finally {
                letGo()
                synchronized(lock) { making.remove(change) }
            }
        }
        return true
    }

    /** The phone's own copies take the change: the playlist, and the list of playlists. */
    private suspend fun show(change: Pending.PlaylistChange) {
        when (val edit = change.edit) {
            is PlaylistEdit.Create -> {}
            is PlaylistEdit.Rename, PlaylistEdit.Delete -> pages.update(listSpec()) { onTopOfList(it) }
            else -> pages.update(playlistSpec(change.id)) { PlaylistEdits.applyTo(it, edit) }
        }
        if (change.edit is PlaylistEdit.Rename) pages.update(playlistSpec(change.id)) { PlaylistEdits.applyTo(it, change.edit) }
    }

    /** Takes these rows of `playlist` (as shown) out, and remembers how to put them back. */
    fun remove(playlist: Playlist, rows: Collection<Int>) {
        val places = rows.filter { it in playlist.songs.indices }.distinct().sorted()
        if (places.isEmpty()) return
        val ids = playlist.songs.map { it.id }
        lastUndo.value = PlaylistUndo.Removed(playlist.id, playlist.songs, places, clock())
        edit(playlist.id, PlaylistEdits.removing(ids, places), playlist.name)
    }

    /** Moves the song at row `from` of `playlist` (as shown) to row `to`. */
    fun move(playlist: Playlist, from: Int, to: Int) {
        val songs = playlist.songs
        if (from == to || from !in songs.indices || to !in songs.indices) return
        lastUndo.value = PlaylistUndo.Moved(playlist.id, songs, from, to, clock())
        edit(playlist.id, PlaylistEdits.moving(songs.map { it.id }, from, to), playlist.name)
    }

    /**
     * Takes back the last removal or move, if it was made in the last minute (Windows'
     * `undo_last`): the songs go back beside their old neighbours, or the song back after the
     * one it followed, in the playlist as it is by then. False if there is nothing to undo.
     */
    fun undoLast(name: String): Boolean {
        val undo = lastUndo.value ?: return false
        lastUndo.value = null
        if (clock() - undo.at > UNDO_MILLIS) return false
        val edit = when (undo) {
            is PlaylistUndo.Removed -> PlaylistEdits.restoring(undo.before, undo.places)
            is PlaylistUndo.Moved -> {
                val after = undo.before.map { it.id }.toMutableList().apply { add(undo.to, removeAt(undo.from)) }
                PlaylistEdits.moving(after, undo.to, undo.from)
            }
        }
        return edit(undo.playlist, edit, name)
    }

    /** Adds songs at the end of a playlist. */
    fun add(playlist: String, name: String, songs: List<Song>) {
        val wanted = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }
        if (wanted.isNotEmpty()) edit(playlist, PlaylistEdit.Append(wanted), name)
    }

    /** A new playlist with these songs; false (nothing done) while offline. */
    fun create(name: String, songs: List<Song>): Boolean =
        edit("new:" + java.util.UUID.randomUUID(), PlaylistEdit.Create(name, songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }), name)

    fun rename(playlist: String, name: String) {
        if (name.isNotBlank()) edit(playlist, PlaylistEdit.Rename(name.trim()), name.trim())
    }

    fun delete(playlist: String, name: String) {
        edit(playlist, PlaylistEdit.Delete, name)
    }

    /**
     * Which of `songs` are already in the playlist, to warn before adding them (Windows asks
     * the server for the playlist as it is now; the phone does too when it can, and otherwise
     * goes by what it shows). Null if the playlist can't be read at all: then they are added
     * without the question, as on Windows.
     */
    suspend fun alreadyIn(playlist: String, songs: List<Song>): Set<String>? {
        val now = try {
            if (offline()) pages.kept(playlistSpec(playlist)) else pages.fresh(playlistSpec(playlist))
        } catch (_: ApiError) {
            pages.kept(playlistSpec(playlist))
        } ?: return null
        val present = now.songs.mapTo(HashSet()) { it.id }
        return songs.mapNotNullTo(HashSet()) { song -> song.id.takeIf { it in present } }
    }

    // ---- Sending --------------------------------------------------------------------------

    /**
     * Sends one change, as the account's sender asks (Windows' `edit_playlist`): a removal or a
     * move is worked out on the playlist as the server has it now, and only what that comes to
     * is sent. Throws the request's [ApiError] if it didn't go.
     */
    suspend fun send(change: Pending.PlaylistChange) {
        val id = change.id
        turn(id).withLock {
            when (val edit = change.edit) {
                is PlaylistEdit.Create -> {
                    val ids = edit.songs.map { it.id }
                    val made = server.createPlaylist(edit.name, ids)
                    if (made.id.isNotEmpty()) added?.create(made.id, ids, clock() / 1000)
                }
                is PlaylistEdit.Rename -> server.renamePlaylist(id, edit.name)
                PlaylistEdit.Delete -> {
                    server.deletePlaylist(id)
                    added?.forget(id)
                }
                is PlaylistEdit.Append -> {
                    val ids = edit.songs.map { it.id }
                    server.addToPlaylist(id, ids)
                    added?.record(id, ids, clock() / 1000)
                }
                is PlaylistEdit.Remove -> {
                    val now = server.playlist(id).songs.map { it.id }
                    val places = PlaylistEdits.positions(now, edit.spots)
                    if (places.isNotEmpty()) server.removeManyFromPlaylist(id, places)
                }
                is PlaylistEdit.Move, is PlaylistEdit.Insert -> {
                    val now = server.playlist(id).songs.map { it.id }
                    val next = PlaylistEdits.apply(now, edit)
                    if (next != now) server.replacePlaylist(id, next)
                }
            }
            // Sent: no copy fetched from now on is to have it carried out again.
            synchronized(lock) { sent += change }
        }
        // The server's own copies: how the playlist stands now, and the list (its name, its
        // length). Asked for behind the change, not waited on.
        scope.launch { catchUp(change) }
    }

    private suspend fun catchUp(change: Pending.PlaylistChange) {
        try {
            when (change.edit) {
                PlaylistEdit.Delete -> pages.forget(playlistSpec(change.id).key)
                is PlaylistEdit.Create -> {}
                else -> pages.fresh(playlistSpec(change.id))
            }
            pages.fresh(listSpec())
        } catch (_: ApiError) {
            // Asked for again the next time either is looked at.
        }
    }

    /** What is held has moved on: changes that have left it are forgotten here too. */
    fun heldChanged() = synchronized(lock) {
        val queued = held.value
        sent.retainAll { mine -> queued.any { it === mine } }
    }

    /**
     * A change that had been held has had its answer (told by the sender, as it leaves the
     * queue). Refused, it is put right and said.
     */
    fun answered(change: Pending, refusal: ApiError?) {
        if (change !is Pending.PlaylistChange || refusal == null) return
        scope.launch { refused(change, nameOf(change), refusal) }
    }

    /** A playlist's name as the phone shows it, for what is said about a change to it. */
    private suspend fun nameOf(change: Pending.PlaylistChange): String = when (val edit = change.edit) {
        is PlaylistEdit.Create -> edit.name
        else -> pages.kept(playlistSpec(change.id))?.name ?: (edit as? PlaylistEdit.Rename)?.name.orEmpty()
    }

    /** A change that didn't save: the phone's copies are put right from the server, and the
     *  person is told. */
    private suspend fun refused(change: Pending.PlaylistChange, name: String, error: ApiError) {
        synchronized(lock) { making.remove(change) }
        refusals.emit(PlaylistRefused(change.id, name, change.edit, error))
        if (offline()) return
        try {
            if (change.edit !is PlaylistEdit.Create) pages.fresh(playlistSpec(change.id))
            pages.fresh(listSpec())
        } catch (_: ApiError) {
        }
    }

    companion object {
        /** How long after a removal or move it can be taken back (Windows' minute). */
        const val UNDO_MILLIS = 60_000L
    }
}
