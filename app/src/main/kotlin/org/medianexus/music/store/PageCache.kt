package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.medianexus.music.net.ApiError

/**
 * What a screen, or the car, is shown of one page: whatever there is to show, and how the
 * check with the server went.
 */
data class Page<out T>(
    /** The page as kept on the phone, or as the server just sent it. Null until there is
     *  something to show. */
    val value: T? = null,
    /** `value` is what the server said just now. */
    val fresh: Boolean = false,
    /** The check with the server failed. Whatever was kept is still in `value`. */
    val error: ApiError? = null,
) {
    /** Still waiting to hear from the server. */
    val checking: Boolean get() = !fresh && error == null
}

/** A page as the server gave it, and when it was asked for. */
class Heard(val key: String, val value: Any?, val askedAt: Long)

/** One page that can be asked for: what it's kept under, how it's written down, and how it's
 *  fetched. `key` is what was asked for, so the same question always finds the same copy. */
class PageSpec<T>(val key: String, val serializer: KSerializer<T>, val fetch: suspend () -> T)

/**
 * Pages kept on the phone, so every page can be drawn at once from what was there last time
 * and then put right from the server ("Show what you have, then check").
 *
 * Each page is a JSON file under the account's cache folder, named after a hash of its key.
 * The pages used lately are also held in memory, already read, so going back to one costs
 * nothing; how many is set by their size (see [weigh]), not their number, so one playlist of
 * thousands of songs can't hold dozens of pages' worth. Two requests for the same page at
 * once share one trip to the server.
 *
 * **Each page has one order of events.** Every change to a page (a fetch kept, a change made
 * here such as a like) takes the next number in that page's own count. Whatever was begun
 * before a change and finishes after it (a slow refresh, a read from disk) is dropped rather
 * than put over it: the change is newer. Writes of one page to disk happen one at a time, in
 * that order, each through a temporary file of its own, and one that a newer write has
 * already overtaken is not made at all.
 */
class PageCache(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** What the pages held in memory may weigh in all (see [weigh]). */
    private val budget: Long = MEMORY_BUDGET,
) {
    private class Entry(val value: Any?, val at: Long, val fresh: Boolean, val weight: Long)

    /** One page's place in the order of events, and the lock its writes take turns on. */
    private class Slot {
        /** Changes made to it so far. */
        var version = 0L
        /** The newest of them written to disk. */
        var written = 0L
        val disk = Mutex()
        /** Read-then-change edits ([update]) take turns, so none is lost. */
        val edits = Mutex()
    }

    /** Guards `memory`, `held` and every slot's `version`. */
    private val state = Any()
    private val slots = HashMap<String, Slot>()

    /** The pages in memory, least lately used first, and what they weigh together. */
    private val memory = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private var held = 0L

    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /** Every page as it is kept afresh (a fetch, or a change made here such as a like), with
     *  its key. For whatever shows or plays a list to follow it. */
    private val keptNow = MutableSharedFlow<Pair<String, Any?>>(extraBufferCapacity = 32)
    val changes: SharedFlow<Pair<String, Any?>> = keptNow.asSharedFlow()

    /** Every answer from the server that was kept, with when it was asked for (by `clock`):
     *  for whatever follows what the server says about things that are on many pages
     *  (likes), and must know whether an answer is older than a change it made since. */
    private val heardNow = MutableSharedFlow<Heard>(extraBufferCapacity = 32)
    val heard: SharedFlow<Heard> = heardNow.asSharedFlow()

    private fun slotFor(key: String): Slot = synchronized(state) { slots.getOrPut(key) { Slot() } }

    private fun versionOf(key: String): Long = synchronized(state) { slots[key]?.version ?: 0L }

    /** The kept copy of a page, from memory or disk; null if there isn't one (or it can no
     *  longer be read, as when the app has changed how pages are written). */
    suspend fun <T> kept(spec: PageSpec<T>): T? {
        repeat(3) {
            val before = synchronized(state) {
                memory[spec.key]?.let {
                    @Suppress("UNCHECKED_CAST")
                    return it.value as T
                }
                versionOf(spec.key)
            }
            val read = withContext(io) { readFile(spec) }
            synchronized(state) {
                memory[spec.key]?.let {
                    @Suppress("UNCHECKED_CAST")
                    return it.value as T
                }
                // Nothing changed it while the file was read: this is the page as it stands.
                if (versionOf(spec.key) == before) {
                    if (read != null) remember(spec.key, read, fresh = false)
                    return read
                }
            }
            // It was changed while being read, and has since left memory: read it again,
            // after the newer write.
            slotFor(spec.key).disk.withLock { }
        }
        return withContext(io) { readFile(spec) }
    }

    /** Asks the server for the page now (or joins a request for it already on its way) and
     *  keeps the answer. Throws `ApiError` if the server can't be asked. If the page was
     *  changed here while the server was being asked, that change stands, and is what is
     *  given back. */
    suspend fun <T> fresh(spec: PageSpec<T>): T {
        val shared = inFlight.computeIfAbsent(spec.key) {
            // On the cache's own scope: a caller that gives up (a screen closed, the car moved
            // on) doesn't cancel the request for anyone else, and the answer is kept anyway.
            scope.async(io, start = CoroutineStart.LAZY) {
                try {
                    val since = versionOf(spec.key)
                    val askedAt = clock()
                    val value = spec.fetch()
                    if (store(spec, value, since)) {
                        heardNow.emit(Heard(spec.key, value, askedAt))
                        value
                    } else {
                        kept(spec) ?: value
                    }
                } finally {
                    inFlight.remove(spec.key)
                }
            }
        }
        shared.start()
        @Suppress("UNCHECKED_CAST")
        return shared.await() as T
    }

    /** The page as the server sent it within the last `withinMillis`, without asking again. */
    private fun recent(key: String, withinMillis: Long): Entry? {
        val entry = synchronized(state) { memory[key] } ?: return null
        return if (entry.fresh && clock() - entry.at <= withinMillis) entry else null
    }

    /**
     * The kept copy straight away (or nothing yet), then the server's. A failure while there
     * is a kept copy leaves that copy on show, with the error alongside: never a blank page.
     * A page that was fetched moments ago isn't fetched again.
     */
    fun <T> page(spec: PageSpec<T>, freshFor: Long = FRESH_FOR_MILLIS): Flow<Page<T>> = flow {
        recent(spec.key, freshFor)?.let {
            @Suppress("UNCHECKED_CAST")
            emit(Page(it.value as T, fresh = true))
            return@flow
        }
        val kept = kept(spec)
        emit(Page(kept))
        try {
            emit(Page(fresh(spec), fresh = true))
        } catch (e: ApiError) {
            emit(Page(kept, error = e))
        }
    }

    /**
     * As [page], and then every later change to the kept copy (a like here, a fetch made for
     * someone else) as it happens, for as long as it's collected: what a screen shows.
     */
    fun <T> live(spec: PageSpec<T>, freshFor: Long = FRESH_FOR_MILLIS): Flow<Page<T>> = channelFlow {
        // Listening before anything is fetched, so a change made meanwhile isn't missed.
        launch(start = UNDISPATCHED) {
            changes.collect { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                if (key == spec.key) send(Page(value as T, fresh = true))
            }
        }
        page(spec, freshFor).collect { send(it) }
    }

    /**
     * One answer, as soon as possible: the kept copy if there is one, else the server's.
     * When a kept copy is given, it is checked with the server afterwards, and `changed` is
     * told if the server's differs. For the car, which wants one list and can be told later
     * that it has changed.
     */
    suspend fun <T> quick(spec: PageSpec<T>, changed: ((T) -> Unit)? = null): T {
        recent(spec.key, FRESH_FOR_MILLIS)?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val kept = kept(spec) ?: return fresh(spec)
        scope.launch {
            val now = try {
                fresh(spec)
            } catch (_: ApiError) {
                return@launch
            }
            if (now != kept) changed?.invoke(now)
        }
        return kept
    }

    /**
     * What is kept of a page, at once and without waiting on the server: null if nothing is.
     * Either way the page is asked for in the background, unless it was fetched within
     * `freshFor`, and `changed` is told if the server's answer differs from what was given
     * (or is the first there has been). For a page that is slow to fetch (every album in the
     * library) and worth showing a little out of date rather than not at all.
     */
    suspend fun <T> later(spec: PageSpec<T>, freshFor: Long = FRESH_FOR_MILLIS, changed: ((T) -> Unit)? = null): T? {
        recent(spec.key, freshFor)?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val kept = kept(spec)
        scope.launch {
            val now = try {
                fresh(spec)
            } catch (_: ApiError) {
                return@launch
            }
            if (now != kept) changed?.invoke(now)
        }
        return kept
    }

    /** Replaces the kept copy, as when a change made here (a like, say) is known to have
     *  reached the server. */
    suspend fun <T> put(spec: PageSpec<T>, value: T) {
        store(spec, value, since = null)
    }

    /**
     * Keeps `value` as the page, as the newest change to it, and writes it to disk. With
     * `since`, only if the page hasn't changed since it stood at that number (what was begun
     * then is older than what has happened since): false if it has, and nothing is kept.
     */
    private suspend fun <T> store(spec: PageSpec<T>, value: T, since: Long?): Boolean {
        val slot = slotFor(spec.key)
        val mine = synchronized(state) {
            if (since != null && slot.version != since) return false
            slot.version++
            remember(spec.key, value, fresh = true)
            slot.version
        }
        keptNow.tryEmit(spec.key to value)
        slot.disk.withLock {
            // A newer write that got here first has made this one pointless.
            if (slot.written < mine) {
                withContext(io) { writeFile(spec, value) }
                slot.written = mine
            }
        }
        return true
    }

    /** Changes the kept copy, if there is one. Edits of one page take turns, and one that
     *  crosses a fetch is made again on what the fetch brought. */
    suspend fun <T> update(spec: PageSpec<T>, change: (T) -> T) {
        slotFor(spec.key).edits.withLock {
            repeat(5) {
                val since = versionOf(spec.key)
                val kept = kept(spec) ?: return
                val changed = change(kept)
                if (changed == kept || store(spec, changed, since)) return
            }
        }
    }

    /** Forgets a kept page, so the next request for it goes to the server. */
    suspend fun forget(key: String) {
        val slot = slotFor(key)
        synchronized(state) {
            slot.version++
            memory.remove(key)?.let { held -= it.weight }
        }
        slot.disk.withLock { withContext(io) { fileFor(key).delete() } }
    }

    private fun remember(key: String, value: Any?, fresh: Boolean) {
        val weight = weigh(value)
        memory.put(key, Entry(value, clock(), fresh, weight))?.let { held -= it.weight }
        held += weight
        // The least lately used go until it all fits, but the newest stays whatever its size:
        // it is the page someone is looking at.
        val each = memory.entries.iterator()
        while (held > budget && memory.size > 1 && each.hasNext()) {
            val eldest = each.next()
            if (eldest.key == key) continue
            held -= eldest.value.weight
            each.remove()
        }
    }

    /** What is held in memory now, by [weigh]'s measure. */
    internal fun heldWeight(): Long = synchronized(state) { held }

    internal fun fileFor(key: String): File = File(dir, "${nameFor(key)}.json")

    // Read from the file and written to it directly, never by way of the whole page as
    // text: a playlist of thousands of songs is megabytes, and as text it is twice that.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun <T> readFile(spec: PageSpec<T>): T? {
        val file = fileFor(spec.key)
        return try {
            file.inputStream().buffered(64 * 1024).use { json.decodeFromStream(spec.serializer, it) }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            file.delete()
            null
        } catch (_: IllegalArgumentException) {
            file.delete()
            null
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun <T> writeFile(spec: PageSpec<T>, value: T) {
        try {
            writeAtomically(fileFor(spec.key)) { out -> json.encodeToStream(spec.serializer, value, out) }
        } catch (_: IOException) {
            // A page that can't be written is fetched again next time; nothing is lost.
        } catch (_: SerializationException) {
            // The same.
        }
    }

    companion object {
        /**
         * What the pages held in memory may weigh in all: about a song's worth of memory each
         * (a kilobyte or so), so twelve thousand is around 12 MB. A long playlist and the
         * pages around it fit; a 20,000-song one is held on its own.
         */
        const val MEMORY_BUDGET = 12_000L

        /**
         * Roughly how much memory a page takes, in songs: each song, album, artist, playlist
         * or genre in it counts one. Close enough to share out the memory by; exactness
         * would cost more than it saves.
         */
        fun weigh(value: Any?): Long = when (value) {
            null -> 0
            is org.medianexus.music.net.Song -> 1
            is org.medianexus.music.net.Album -> 1L + value.songs.size
            is org.medianexus.music.net.Playlist -> 1L + value.songs.size
            is org.medianexus.music.net.Artist -> 1L + value.albums.size
            is org.medianexus.music.net.Found -> 1L + value.songs.size + value.albums.size + value.artists.size
            is Collection<*> -> 1L + value.sumOf { weigh(it) }
            else -> 1
        }

        /** A page fetched within this long is shown as it is, without asking again. */
        const val FRESH_FOR_MILLIS = 15_000L

        /** Small files: defaults and nulls are left out, and anything unknown when reading
         *  back (written by a newer version) is ignored. */
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = false
        }

        /** A file name for a key: keys hold anything (a genre's name, a search), so they're
         *  hashed. */
        fun nameFor(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
    }
}
