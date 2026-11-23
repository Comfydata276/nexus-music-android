package org.medianexus.music.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Found
import org.medianexus.music.net.Song
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec

/** A like or unlike the server didn't take, already put back on the phone. */
data class LikeRefused(val song: Song, val liked: Boolean, val error: ApiError)

/**
 * Which songs are liked, shared by everything that shows a heart. A like or unlike shows at
 * once and is sent to the server; if the server refuses, it is put back as it was and
 * [refused] says so.
 *
 * The set starts from the kept copy of Liked songs and follows the server's whenever that
 * page is fetched. Changes still on their way to the server stay on top of whatever the
 * server last said, so a fetch that crosses one doesn't undo it on screen.
 */
class Likes(
    private val pages: PageCache,
    private val spec: PageSpec<Found>,
    private val send: suspend (id: String, liked: Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    /** What the server last said, and the changes made since that it hasn't answered yet. */
    private var server: Set<String> = emptySet()
    private val pending = mutableMapOf<String, Boolean>()
    private val lock = Any()

    private val current = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = current.asStateFlow()

    private val refusals = MutableSharedFlow<LikeRefused>(extraBufferCapacity = 8)
    val refused: SharedFlow<LikeRefused> = refusals.asSharedFlow()

    fun isLiked(id: String): Boolean = id in current.value

    /** Starts from the kept Liked songs, then the server's. */
    fun load() {
        scope.launch {
            pages.kept(spec)?.let { heard(it) }
            refresh()
        }
    }

    /** Asks the server for Liked songs again. */
    suspend fun refresh() {
        try {
            heard(pages.fresh(spec))
        } catch (_: ApiError) {
            // Kept as it was; the next look at Liked songs tries again.
        }
    }

    /** The server's list of liked songs, from a fetch of Liked songs. */
    fun heard(found: Found) {
        synchronized(lock) {
            server = found.songs.mapTo(HashSet()) { it.id }
            publish()
        }
    }

    fun toggle(song: Song) = set(song, !isLiked(song.id))

    /** Likes or unlikes a song: at once here, then on the server. */
    fun set(song: Song, liked: Boolean) {
        if (song.id.isEmpty() || song.radioUrl.isNotEmpty()) return
        synchronized(lock) {
            if ((pending[song.id] ?: (song.id in server)) == liked) return
            pending[song.id] = liked
            publish()
        }
        scope.launch {
            val error = try {
                send(song.id, liked)
                null
            } catch (e: ApiError) {
                e
            }
            synchronized(lock) {
                // A later change to the same song supersedes this one's answer.
                if (pending[song.id] == liked) pending.remove(song.id)
                if (error == null) {
                    server = if (liked) server + song.id else server - song.id
                }
                publish()
            }
            if (error != null) {
                refusals.tryEmit(LikeRefused(song, liked, error))
                return@launch
            }
            // Liked songs, as kept, gains or loses it too: newest likes first, as the server
            // lists them.
            pages.update(spec) { found ->
                val without = found.songs.filter { it.id != song.id }
                found.copy(songs = if (liked) listOf(song.copy(starred = song.starred ?: "", queued = false)) + without else without)
            }
        }
    }

    private fun publish() {
        val now = server.toMutableSet()
        for ((id, like) in pending) if (like) now += id else now -= id
        current.update { now }
    }
}
