package org.medianexus.music.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.medianexus.music.net.Album
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Found
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.net.StarTarget
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.PageSpec
import org.medianexus.music.store.Pending

/** A like or unlike the server didn't take, already put back on the phone. `name` is the
 *  song's title or the album's name. */
data class LikeRefused(val target: StarTarget, val id: String, val name: String, val liked: Boolean, val error: ApiError)

/** What is liked, as everything shows it: songs and albums, by id. */
data class Liked(val songs: Set<String> = emptySet(), val albums: Set<String> = emptySet())

/**
 * The one owner of what is liked. Everything that shows a heart, and Liked songs itself,
 * reads it from here; nothing keeps a like of its own.
 *
 * It holds what the server last said, and what the person has just done that is still on
 * its way to the server; what is shown is the one with the other on top. So a like shows at
 * once everywhere, Liked songs included, and a refusal puts it back everywhere.
 *
 * **A change still waiting to be sent wins over what the server says.** "On its way" lasts
 * as long as the change is in the account's queue of held changes (`held`: made offline, or
 * kept back behind another): what is shown is the server's last word, then every held like
 * and unlike in the order they were made, then what is being sent this moment. (A held like
 * used to be taken as if the server had it: an answer for Liked songs asked for after the
 * like was held and before it was delivered, as the server comes back, turned the heart off
 * until the next refresh.)
 *
 * What the server says comes from every page it sends that says whether something is liked:
 * Liked songs in full, and the `starred` on an album or a playlist's songs. A like made on
 * another device therefore shows in every view as soon as any page that names it has been
 * fetched again. An answer asked for before a change here was settled is older than that
 * change, and doesn't undo it.
 *
 * Changes to one song or album go to the server one at a time, in the order they were made,
 * so however quickly the heart is tapped the server ends with the last.
 */
class Likes(
    private val pages: PageCache,
    private val spec: PageSpec<Found>,
    private val send: suspend (target: StarTarget, id: String, liked: Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The account's changes still waiting to be sent to the server, oldest first. */
    private val held: StateFlow<List<Pending>> = MutableStateFlow(emptyList()),
) {
    private data class Item(val target: StarTarget, val id: String)

    /** What each item with a like or unlike still waiting to be sent was last set to. */
    private fun waiting(): Map<Item, Boolean> {
        val changes = held.value
        if (changes.isEmpty()) return emptyMap()
        val last = HashMap<Item, Boolean>()
        for (change in changes) {
            if (change !is Pending.Star) continue
            val target = when {
                change.kind.equals(StarTarget.SONG.name, ignoreCase = true) -> StarTarget.SONG
                change.kind.equals(StarTarget.ALBUM.name, ignoreCase = true) -> StarTarget.ALBUM
                else -> continue
            }
            last[Item(target, change.id)] = change.starred
        }
        return last
    }

    private val lock = Any()

    /** What the server last said. */
    private var server = Liked()
    /** For each item with changes still being sent: what it was last set to, and how many
     *  of its changes are still on their way. */
    private val wanted = HashMap<Item, Boolean>()
    private val unsent = HashMap<Item, Int>()
    /** When each item's last change was settled with the server (by `clock`): an answer
     *  asked for before then doesn't speak for it. */
    private val settledAt = HashMap<Item, Long>()
    /** Songs liked here, or found liked on another page, and not yet in the server's Liked
     *  songs as last heard: they head it meanwhile. */
    private val justLiked = LinkedHashMap<String, Song>()
    /** The songs in the server's Liked songs as last heard. */
    private var listed: Set<String> = emptySet()
    /** Each item's changes take turns on its own lock. */
    private val turns = HashMap<Item, Mutex>()

    private val current = MutableStateFlow(Liked())

    /** What's liked now, as shown. */
    val state: StateFlow<Liked> = current.asStateFlow()

    private val songsNow = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the songs liked, as shown. */
    val liked: StateFlow<Set<String>> = songsNow.asStateFlow()

    private val refusals = MutableSharedFlow<LikeRefused>(extraBufferCapacity = 8)
    val refused: SharedFlow<LikeRefused> = refusals.asSharedFlow()

    fun isLiked(id: String): Boolean = id in current.value.songs

    fun isAlbumLiked(id: String): Boolean = id in current.value.albums

    /** Starts from the kept Liked songs, then the server's; and from then on follows every
     *  page the server sends. */
    fun load() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pages.heard.collect { heard ->
                when {
                    heard.key == spec.key -> (heard.value as? Found)?.let { heard(it, heard.askedAt) }
                    heard.value is Album -> heardAlbum(heard.value, heard.askedAt)
                    heard.value is Playlist -> heardSongs(heard.value.songs, heard.askedAt)
                }
            }
        }
        scope.launch {
            pages.kept(spec)?.let { heard(it, askedAt = 0) }
            refresh()
        }
    }

    /** The held changes are not what they were (what an earlier run left has been read; one
     *  that had waited has gone): what is shown is worked out again. */
    fun heldChanged() = synchronized(lock) { publish() }

    /**
     * A like or unlike that had been held has had its answer: the server took it, or (not
     * `taken`) never will. Told just before it leaves the held changes, so that what is shown
     * never falls back to what the server said before it.
     */
    fun answered(change: Pending, taken: Boolean) {
        if (change !is Pending.Star) return
        val target = StarTarget.entries.firstOrNull { it.name.equals(change.kind, ignoreCase = true) } ?: return
        val item = Item(target, change.id)
        synchronized(lock) {
            if (taken) {
                when (target) {
                    StarTarget.ALBUM -> server = server.copy(albums = if (change.starred) server.albums + item.id else server.albums - item.id)
                    StarTarget.SONG -> server = server.copy(songs = if (change.starred) server.songs + item.id else server.songs - item.id)
                    else -> return
                }
            }
            // An answer asked for before this one doesn't speak for it.
            settledAt[item] = clock()
        }
    }

    /** Asks the server for Liked songs again. */
    suspend fun refresh() {
        try {
            pages.fresh(spec)
        } catch (_: ApiError) {
            // Kept as it was; the next look at Liked songs tries again.
        }
    }

    /**
     * The server's whole list of what's liked (Liked songs), asked for at `askedAt`. An item
     * whose change here was settled after that keeps what the change made it.
     */
    fun heard(found: Found, askedAt: Long = clock()) {
        synchronized(lock) {
            val songs = found.songs.mapTo(HashSet()) { it.id }
            val albums = found.albums.mapTo(HashSet()) { it.id }
            server = Liked(
                songs = songs.keepingNewer(server.songs, StarTarget.SONG, askedAt),
                albums = albums.keepingNewer(server.albums, StarTarget.ALBUM, askedAt),
            )
            listed = songs
            // A song with a change still on its way keeps its place at the head: what the
            // server listed was asked for before that change is settled.
            justLiked.keys.retainAll { it !in songs || Item(StarTarget.SONG, it) in unsent }
            publish()
        }
    }

    /** An album page from the server: whether it, and each of its songs, is liked. */
    private fun heardAlbum(album: Album, askedAt: Long) = synchronized(lock) {
        if (album.id.isNotEmpty()) server = server.copy(albums = server.albums.with(Item(StarTarget.ALBUM, album.id), album.starred != null, askedAt))
        heardSongsLocked(album.songs, askedAt)
        publish()
    }

    private fun heardSongs(songs: List<Song>, askedAt: Long) = synchronized(lock) {
        heardSongsLocked(songs, askedAt)
        publish()
    }

    /**
     * Songs from a page the server sent (an album, a playlist), each saying whether it is
     * liked: taken in one pass, however many (a playlist of 20,000 is one update, not 20,000
     * copies of the set). One found liked that Liked songs doesn't list yet (liked on another
     * device) joins it at once, with what the page knows of it.
     */
    private fun heardSongsLocked(songs: List<Song>, askedAt: Long) {
        val now = server.songs.toMutableSet()
        for (song in songs) {
            if (song.id.isEmpty()) continue
            if ((settledAt[Item(StarTarget.SONG, song.id)] ?: Long.MIN_VALUE) >= askedAt) continue
            if (song.starred != null) {
                now += song.id
                if (song.id !in listed && song.id !in justLiked) justLiked[song.id] = song.copy(queued = false)
            } else {
                now -= song.id
            }
        }
        server = server.copy(songs = now)
    }

    private fun Set<String>.keepingNewer(before: Set<String>, target: StarTarget, askedAt: Long): Set<String> {
        val result = toMutableSet()
        for ((item, at) in settledAt) {
            if (item.target != target || at < askedAt) continue
            if (item.id in before) result += item.id else result -= item.id
        }
        return result
    }

    private fun Set<String>.with(item: Item, liked: Boolean, askedAt: Long): Set<String> {
        if ((settledAt[item] ?: Long.MIN_VALUE) >= askedAt) return this
        return if (liked) this + item.id else this - item.id
    }

    fun toggle(song: Song) = set(song, !isLiked(song.id))

    /** Likes or unlikes a song: at once here, then on the server. */
    fun set(song: Song, liked: Boolean) {
        if (song.id.isEmpty() || song.radioUrl.isNotEmpty()) return
        val kept = song.copy(starred = song.starred ?: "", queued = false)
        change(Item(StarTarget.SONG, song.id), liked, song.title, kept) {
            // Even if the server still lists it: an unlike of it may be on its way, and when
            // the server takes that, the kept Liked songs loses it. The list shows the last
            // thing asked for, so the like heads it until the like itself is kept.
            if (liked) justLiked[song.id] = kept
        }
    }

    /** Likes an album if it isn't, as things stand now (not as last drawn: two quick taps
     *  are two changes), or unlikes it. Says whether it is liked now. */
    fun toggleAlbum(album: Album): Boolean {
        val liked = !isAlbumLiked(album.id)
        setAlbum(album, liked)
        return liked
    }

    fun setAlbum(album: Album, liked: Boolean) {
        if (album.id.isEmpty()) return
        change(Item(StarTarget.ALBUM, album.id), liked, album.name, null) {}
    }

    /** `also` runs under the lock as the change is made. */
    private fun change(item: Item, liked: Boolean, name: String, song: Song?, also: () -> Unit) {
        val turn = synchronized(lock) {
            if (shown(item) == liked) return
            wanted[item] = liked
            unsent[item] = (unsent[item] ?: 0) + 1
            also()
            publish()
            turns.getOrPut(item) { Mutex() }
        }
        // Started here and now, so the turns are taken in the order the changes were made.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            turn.withLock {
                val error = try {
                    send(item.target, item.id, liked)
                    null
                } catch (e: ApiError) {
                    e
                }
                val last = synchronized(lock) {
                    // Held, not taken (a like or unlike of it is still waiting to be sent):
                    // the server has not got it, and what is shown comes from what is held.
                    if (error == null && item !in waiting()) {
                        server = when (item.target) {
                            StarTarget.ALBUM -> server.copy(albums = if (liked) server.albums + item.id else server.albums - item.id)
                            else -> server.copy(songs = if (liked) server.songs + item.id else server.songs - item.id)
                        }
                    }
                    settledAt[item] = clock()
                    val left = (unsent[item] ?: 1) - 1
                    if (left <= 0) {
                        unsent.remove(item)
                        wanted.remove(item)
                        turns.remove(item)
                    } else {
                        unsent[item] = left
                    }
                    // What heads Liked songs belongs to the last tap: an earlier one's answer
                    // leaves it to the taps still to be sent.
                    if (left <= 0 && item.target == StarTarget.SONG && !shown(item)) justLiked.remove(item.id)
                    publish()
                    left <= 0
                }
                if (error != null) {
                    // Said only of the last tap: one a later tap has overtaken was never
                    // what the person was left looking at.
                    if (last) refusals.emit(LikeRefused(item.target, item.id, name, liked, error))
                } else if (item.target == StarTarget.SONG) {
                    // Every change the server took (or the phone holds for it), in the order
                    // made: the kept Liked songs is what the server has, or will have.
                    keep(item.id, liked, song)
                }
            }
        }
    }

    /** Liked songs, as kept, gains or loses a song the server has taken the change for:
     *  newest likes first, as the server lists them. */
    private suspend fun keep(id: String, liked: Boolean, song: Song?) {
        pages.update(spec) { found ->
            when {
                !liked -> found.copy(songs = found.songs.filter { it.id != id })
                found.songs.any { it.id == id } || song == null -> found
                else -> found.copy(songs = listOf(song) + found.songs)
            }
        }
        // Once kept, it no longer needs to head the list, unless a later tap is still to go,
        // or the like is only held: the server's own list, which doesn't have it yet, may
        // replace the kept one before the like is delivered.
        synchronized(lock) {
            val item = Item(StarTarget.SONG, id)
            if (liked && item !in unsent && item !in waiting()) justLiked.remove(id)
        }
    }

    private fun shown(item: Item): Boolean = wanted[item] ?: waiting()[item] ?: when (item.target) {
        StarTarget.ALBUM -> item.id in server.albums
        else -> item.id in server.songs
    }

    /**
     * Liked songs as it is to be shown: the server's list as kept (`found`), less what has
     * been unliked here, with what has been liked here and isn't in it yet on top.
     */
    fun songsOf(found: Found?): List<Song>? {
        val (shown, fresh) = synchronized(lock) { current.value.songs to justLiked.values.toList() }
        if (found == null) return if (fresh.isEmpty()) null else fresh.filter { it.id in shown }
        val ids = found.songs.mapTo(HashSet()) { it.id }
        return fresh.filter { it.id in shown && it.id !in ids }.reversed() + found.songs.filter { it.id in shown }
    }

    private fun publish() {
        val songs = server.songs.toMutableSet()
        val albums = server.albums.toMutableSet()
        // The server's word, then what is held for it, then what is being sent now.
        for (changes in arrayOf(waiting(), wanted)) {
            for ((item, like) in changes) {
                val set = if (item.target == StarTarget.ALBUM) albums else songs
                if (like) set += item.id else set -= item.id
            }
        }
        current.value = Liked(songs, albums)
        songsNow.value = songs
    }
}
