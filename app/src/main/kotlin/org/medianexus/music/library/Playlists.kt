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

/** A change to a playlist that didn't happen, or may not have, and whatever was waiting
 *  behind it, already dropped. `name` is the playlist's, as shown when it was made. */
data class PlaylistRefused(val playlist: String, val name: String, val outcome: Outcome, val error: ApiError) {
    enum class Outcome {
        /** The server said no. */
        REFUSED,
        /** No answer came: it may or may not have been done. */
        UNCONFIRMED,
        /** The server was never reached: it wasn't done. */
        UNSENT,
    }
}

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
 * neighbours in src/actions.rs).
 *
 * - **Only with the server there, and never kept for later.** With the server not known to be
 *   reachable no change is made ([Unavailable.OFFLINE]). A change waits in memory only, behind
 *   the changes to the same playlist made before it, and goes with the process: closed before
 *   it was sent, it never happened. (Changes used to be held on the phone and sent when the
 *   server came back; a review showed what that did to a playlist changed elsewhere meanwhile.)
 * - **By the song, on the server's copy as it is then** (src/playlists.rs): a removal or a move
 *   names the songs, which copy of each included, and is worked out on the playlist as the
 *   server sends it, read immediately before the write with nothing in between. Subsonic can't
 *   make the write conditional on that read, so a change another device makes in that instant
 *   can still be overwritten: the window Windows has too, accepted (docs/DESIGN.md).
 * - **One attempt.** The write goes once ([PlaylistServer] goes by `Subsonic.once`) and is never
 *   sent again: done, refused, or nobody knows (then the playlist is read again and the person
 *   is asked to check it).
 * - **A change that doesn't happen takes the ones behind it with it**: every change to that
 *   playlist still waiting is dropped unsent, the playlist read again, and the person told
 *   once. Undo is the reverse change, waiting behind what it undoes, under the same rule.
 * - **What is shown is the server's copy with the changes still waiting on top** ([shown]),
 *   worked out each time: the kept page only ever holds what the server said. A fetch of a
 *   playlist and a change to it take turns, and a change leaves [waiting] as the server's copy
 *   with it in is kept, so it is never shown twice.
 * - **Only the account's own** ([canEdit]), known: checked as a change is made and again on the
 *   playlist read just before it is written.
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
    }

    /** A change waiting its turn: `done` is told whether the server took it. */
    class Waiting(val edit: PlaylistEdit, val name: String, val done: (Boolean) -> Unit)

    private val lock = Any()
    private val queues = MutableStateFlow<Map<String, List<Waiting>>>(emptyMap())

    /** The changes waiting, by playlist, in the order made. */
    val waiting: StateFlow<Map<String, List<Waiting>>> = queues.asStateFlow()

    /** Each playlist's fetches and changes take turns. */
    private val turns = HashMap<String, Mutex>()
    private fun turn(id: String) = synchronized(lock) { turns.getOrPut(id) { Mutex() } }

    /** The playlists whose changes are being sent now. */
    private val working = HashSet<String>()

    private val refusals = MutableSharedFlow<PlaylistRefused>(extraBufferCapacity = 8)
    val refused: SharedFlow<PlaylistRefused> = refusals.asSharedFlow()

    private val lastUndo = MutableStateFlow<PlaylistUndo?>(null)

    /** The last removal or move, while it can still be taken back. */
    val undo: StateFlow<PlaylistUndo?> = lastUndo

    private val goneNow = MutableStateFlow<Set<String>>(emptySet())

    /** Playlists the server said no longer exist (its error 70): their pages close. */
    val gone: StateFlow<Set<String>> = goneNow.asStateFlow()

    /** Whether this account may change `playlist`: its own (one that names no owner is taken
     *  to be), and not one Navidrome calls read-only. Someone else's plays, unchanged. */
    fun canEdit(playlist: Playlist): Boolean = !playlist.readonly && (playlist.owner.isEmpty() || playlist.owner.equals(username, ignoreCase = true))

    /** A playlist as the server sent it with `waiting` (the changes to it still waiting) on
     *  top, in order. */
    fun shown(playlist: Playlist, waiting: Map<String, List<Waiting>> = queues.value): Playlist =
        waiting[playlist.id].orEmpty().fold(playlist) { shown, it -> PlaylistEdits.applyTo(shown, it.edit) }

    /** Fetches a playlist (`fetch`, the server's), taking turns with the changes to it. */
    suspend fun fetched(id: String, fetch: suspend () -> Playlist): Playlist = turn(id).withLock { fetch() }

    // ---- Making changes ---------------------------------------------------------------------

    /**
     * Makes a change to playlist `id` (`record`: the playlist as the app knows it, for whether
     * it is the account's own; none for a new one). It waits behind the changes to the same
     * playlist made before it; `done` is told whether the server took it. Returns why it can't
     * be made now, doing nothing, or null.
     */
    fun edit(id: String, record: Playlist?, edit: PlaylistEdit, name: String, done: (Boolean) -> Unit = {}): Unavailable? =
        queue(id, record, edit, name, done, undo = null)

    /** [edit], remembering `undo` as the way to take it back: before it can be sent, so a
     *  refusal however quick forgets it. */
    private fun queue(id: String, record: Playlist?, edit: PlaylistEdit, name: String, done: (Boolean) -> Unit, undo: PlaylistUndo?): Unavailable? {
        if (!reachable()) return Unavailable.OFFLINE
        if (edit !is PlaylistEdit.Create && (record == null || !canEdit(record))) return Unavailable.NOT_YOURS
        val start = synchronized(lock) {
            if (undo != null) lastUndo.value = undo
            queues.value = queues.value + (id to (queues.value[id].orEmpty() + Waiting(edit, name, done)))
            working.add(id)
        }
        if (start) scope.launch { work(id) }
        return null
    }

    /** Takes these rows of `playlist` (as shown) out, and remembers how to put them back. */
    fun remove(playlist: Playlist, rows: Collection<Int>): Unavailable? {
        val places = rows.filter { it in playlist.songs.indices }.distinct().sorted()
        if (places.isEmpty()) return null
        return queue(playlist.id, playlist, PlaylistEdits.removing(playlist.songs.map { it.id }, places), playlist.name, {}, PlaylistUndo.Removed(playlist.id, playlist.songs, places, clock()))
    }

    /** Moves the song at row `from` of `playlist` (as shown) to row `to`. */
    fun move(playlist: Playlist, from: Int, to: Int): Unavailable? {
        val songs = playlist.songs
        if (from == to || from !in songs.indices || to !in songs.indices) return null
        return queue(playlist.id, playlist, PlaylistEdits.moving(songs.map { it.id }, from, to), playlist.name, {}, PlaylistUndo.Moved(playlist.id, songs, from, to, clock()))
    }

    /**
     * Takes back the last removal or move, if it was made in the last minute (Windows'
     * `undo_last`): the reverse change, behind what it undoes. It is never sent if that didn't
     * happen (its failure dropped it, and forgot it could be undone). False if there is nothing
     * to undo, or it can't be done now. Taken and queued in one step, under the lock a
     * failure drops changes under: so the reverse is either behind what it undoes (and goes
     * with it if that fails) or not made at all.
     */
    fun undoLast(record: Playlist): Boolean = synchronized(lock) {
        val undo = lastUndo.value?.takeIf { it.playlist == record.id } ?: return false
        lastUndo.value = null
        if (clock() - undo.at > UNDO_MILLIS) return false
        val reverse = when (undo) {
            is PlaylistUndo.Removed -> PlaylistEdits.restoring(undo.before, undo.places)
            is PlaylistUndo.Moved -> {
                val after = undo.before.map { it.id }.toMutableList().apply { add(undo.to, removeAt(undo.from)) }
                PlaylistEdits.moving(after, undo.to, undo.from)
            }
        }
        edit(record.id, record, reverse, record.name) == null
    }

    /** Adds songs at the end of a playlist. */
    fun add(record: Playlist, songs: List<Song>, done: (Boolean) -> Unit = {}): Unavailable? {
        val wanted = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }
        if (wanted.isEmpty()) return null
        return edit(record.id, record, PlaylistEdit.Append(wanted), record.name, done)
    }

    /** A new playlist with these songs. */
    fun create(name: String, songs: List<Song>, done: (Boolean) -> Unit = {}): Unavailable? =
        edit("new:" + java.util.UUID.randomUUID(), null, PlaylistEdit.Create(name, songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() }), name, done)

    fun rename(record: Playlist, name: String): Unavailable? = if (name.isBlank()) null else edit(record.id, record, PlaylistEdit.Rename(name.trim()), name.trim())

    fun delete(record: Playlist, done: (Boolean) -> Unit = {}): Unavailable? = edit(record.id, record, PlaylistEdit.Delete, record.name, done)

    /** Why no change can be made now, if none can: the server isn't known to be there. */
    fun reachableNow(): Unavailable? = if (reachable()) null else Unavailable.OFFLINE

    /**
     * Which of `songs` are already in the playlist, to warn before adding them, as the server
     * has the playlist now (Windows asks it too). Null if it can't be read: then they are added
     * without the question, as on Windows.
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

    /** Sends the changes to `id`, one at a time, until none is waiting. */
    private suspend fun work(id: String) {
        while (true) {
            val next = synchronized(lock) {
                queues.value[id]?.firstOrNull() ?: run {
                    working.remove(id)
                    return
                }
            }
            val failure = try {
                send(id, next)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Failed) {
                e
            }
            if (failure == null) {
                next.done(true)
                continue
            }
            // It didn't happen, or may not have: nothing is applied on top of it.
            val dropped = synchronized(lock) {
                if (lastUndo.value?.playlist == id) lastUndo.value = null
                queues.value[id].orEmpty().also { queues.value = queues.value - id }
            }
            dropped.forEach { it.done(false) }
            refusals.emit(PlaylistRefused(id, next.name, failure.outcome, failure.error))
            catchUp(id, failure.error)
        }
    }

    private class Failed(val outcome: PlaylistRefused.Outcome, val error: ApiError) : Exception()

    /**
     * Sends one change, as Windows' `edit_playlist` does: the playlist read as the server has it
     * now, whether it is still the account's to change, and only what the change comes to there
     * written, once. Then the server's copy with it in is kept and the change leaves [waiting],
     * before anything else may read the playlist.
     */
    private suspend fun send(id: String, waiting: Waiting) = turn(id).withLock {
        var writing = false
        try {
            val edit = waiting.edit
            val now = if (edit is PlaylistEdit.Create) null else server.playlist(id)
            if (now != null && !canEdit(now)) throw ApiError.Server(50, "")
            val ids = now?.songs?.map { it.id }.orEmpty()
            writing = true
            when (edit) {
                is PlaylistEdit.Create -> {
                    val songIds = edit.songs.map { it.id }
                    val made = server.createPlaylist(edit.name, songIds)
                    if (made.id.isNotEmpty()) added?.create(made.id, songIds, clock() / 1000)
                }
                is PlaylistEdit.Rename -> server.renamePlaylist(id, edit.name)
                PlaylistEdit.Delete -> server.deletePlaylist(id)
                is PlaylistEdit.Append -> {
                    val songIds = edit.songs.map { it.id }
                    server.addToPlaylist(id, songIds)
                    added?.record(id, songIds, clock() / 1000)
                }
                is PlaylistEdit.Remove -> PlaylistEdits.positions(ids, edit.spots).takeIf { it.isNotEmpty() }?.let { server.removeManyFromPlaylist(id, it) }
                is PlaylistEdit.Move, is PlaylistEdit.Insert -> PlaylistEdits.apply(ids, edit).takeIf { it != ids }?.let { server.replacePlaylist(id, it) }
            }
            writing = false
            // Done. The server's copy with it in, then the change leaves what is waiting.
            val after = when (edit) {
                PlaylistEdit.Delete, is PlaylistEdit.Create -> null
                else -> runCatching { server.playlist(id) }.getOrNull()
            }
            synchronized(lock) { queues.value = (queues.value + (id to queues.value[id].orEmpty().filter { it !== waiting })).filterValues { it.isNotEmpty() } }
            when {
                edit == PlaylistEdit.Delete -> pages.forget(playlistSpec(id).key)
                after != null -> pages.put(playlistSpec(id), after)
            }
            if (edit is PlaylistEdit.Create || edit is PlaylistEdit.Rename || edit == PlaylistEdit.Delete) scope.launch { runCatching { pages.fresh(listSpec()) } }
        } catch (e: ApiError) {
            throw when {
                e is ApiError.Server -> Failed(PlaylistRefused.Outcome.REFUSED, e)
                !writing || (e is ApiError.Network && e.unsent) -> Failed(PlaylistRefused.Outcome.UNSENT, e)
                else -> Failed(PlaylistRefused.Outcome.UNCONFIRMED, e)
            }
        }
    }

    /** After a change that didn't happen, or may not have: the playlist as the server has it
     *  now; one the server says is gone (70) goes from the phone and Library. */
    private suspend fun catchUp(id: String, error: ApiError) {
        if (id.startsWith("new:")) return
        try {
            if (error is ApiError.Server && error.code == 70L) {
                goneNow.value = goneNow.value + id
                pages.forget(playlistSpec(id).key)
                pages.update(listSpec()) { list -> list.filter { it.id != id } }
            } else {
                pages.fresh(playlistSpec(id))
            }
        } catch (_: ApiError) {
            // Asked for again the next time it is looked at.
        }
    }

    companion object {
        /** How long after a removal or move it can be taken back (Windows' minute). */
        const val UNDO_MILLIS = 60_000L
    }
}
