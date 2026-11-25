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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
 * here such as a like) takes the next number in that page's own count, and becomes the page's
 * one current value under the same lock: that value (a `StateFlow` per page) is what screens
 * follow, and [changes] and [heard] are told of each change under that lock too, in that
 * order, and never drop one. So nothing can be published out of order, or lost. Whatever was
 * begun before a change and finishes after it (a slow refresh, a read from disk) is dropped
 * rather than put over it: the change is newer. A failed refresh says so beside the page as it
 * now is, never beside the copy it began with.
 *
 * Writes of one page to disk happen one at a time, in that order, each through a temporary
 * file of its own, and one that a newer write has already overtaken is not made at all. A
 * page is held in memory until its newest change is safely on disk, so the file is never
 * older than what was let go of, and a read from disk can't bring back a value older than the
 * one in use. What writes cut short by the app being killed left behind is swept away when the
 * account's pages are first opened.
 */
class PageCache(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** What the pages held in memory may weigh in all (see [weigh]). */
    private val budget: Long = MEMORY_BUDGET,
) {
    /** One page as it stands: its number in the page's order of events, and, if the page is
     *  held in memory, its value (`fresh`: kept in this run, rather than read from disk). */
    private class Current(val version: Long, val held: Boolean, val value: Any?, val fresh: Boolean, val at: Long)

    /** One page's place in the order of events, its current value, and the lock its writes
     *  take turns on. Everything but the locks is guarded by `state`. */
    private class Slot {
        /** Changes made to it so far. */
        var version = 0L
        /** The newest of them safely on disk. */
        var written = 0L
        /** What its value weighs, while held. */
        var weight = 0L
        val current = MutableStateFlow(Current(0, held = false, value = null, fresh = false, at = 0))
        val disk = Mutex()
        /** Read-then-change edits ([update]) take turns, so none is lost. */
        val edits = Mutex()
    }

    /** Guards `memory`, `held` and every slot. */
    private val state = Any()
    private val slots = HashMap<String, Slot>()

    /** The pages held in memory, least lately used first, and what they weigh together. */
    private val memory = LinkedHashMap<String, Slot>(32, 0.75f, true)
    private var held = 0L

    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /** For tests: a page kept in memory waits here before it is written to disk. */
    internal var beforeWrite: suspend (key: String) -> Unit = {}

    /** Every page as it is kept afresh (a fetch, or a change made here such as a like), with
     *  its key, in the order kept, none dropped. For whatever shows or plays a list to follow
     *  it. */
    private val keptNow = MutableSharedFlow<Pair<String, Any?>>(extraBufferCapacity = Int.MAX_VALUE)
    val changes: SharedFlow<Pair<String, Any?>> = keptNow.asSharedFlow()

    /** Every answer from the server that was kept, with when it was asked for (by `clock`),
     *  in the order kept: for whatever follows what the server says about things that are on
     *  many pages (likes), and must know whether an answer is older than a change it made
     *  since. */
    private val heardNow = MutableSharedFlow<Heard>(extraBufferCapacity = Int.MAX_VALUE)
    val heard: SharedFlow<Heard> = heardNow.asSharedFlow()

    init {
        // Left by writes the app was killed in the middle of. Only an earlier run's: this
        // run's own may be under way.
        scope.launch(io) { sweepTemporaries(dir) }
    }

    private fun slotFor(key: String): Slot = synchronized(state) { slots.getOrPut(key) { Slot() } }

    private fun versionOf(key: String): Long = synchronized(state) { slots[key]?.version ?: 0L }

    /** The page's value, if it is held in memory (and marks it used lately). Under `state`. */
    private fun heldValue(key: String, slot: Slot): Current? {
        val now = slot.current.value
        if (!now.held) return null
        memory[key]
        return now
    }

    /** The kept copy of a page, from memory or disk; null if there isn't one (or it can no
     *  longer be read, as when the app has changed how pages are written). */
    suspend fun <T> kept(spec: PageSpec<T>): T? {
        val slot = slotFor(spec.key)
        repeat(3) {
            val before = synchronized(state) {
                heldValue(spec.key, slot)?.let {
                    @Suppress("UNCHECKED_CAST")
                    return it.value as T
                }
                // The file is the page as it stands only once its newest change is written
                // (a page isn't let go of before then, so this is a moment at most).
                if (slot.written == slot.version) slot.version else -1L
            }
            if (before >= 0) {
                val read = withContext(io) { readFile(spec) }
                synchronized(state) {
                    heldValue(spec.key, slot)?.let {
                        @Suppress("UNCHECKED_CAST")
                        return it.value as T
                    }
                    // Nothing changed it while the file was read: this is the page as it stands.
                    if (slot.version == before && slot.written == before) {
                        if (read != null) hold(spec.key, slot, read, fresh = false)
                        return read
                    }
                }
            }
            // Changed meanwhile, and not held: read it again after the newer write.
            slot.disk.withLock { }
        }
        return withContext(io) { readFile(spec) }
    }

    /**
     * Asks the server for the page now (or joins a request for it already on its way) and
     * keeps the answer. Throws `ApiError` if the server can't be asked. If the page was
     * changed here while the server was being asked, that change stands, and is what is
     * given back.
     *
     * A request is on its way until its answer has arrived and is kept in memory; writing it
     * to disk is not part of it. Asked for from then on, the page is asked of the server
     * again: joined while it was being written, a refresh was given the answer from before it
     * was asked (a like made on another device, missed). The older answer, if its write comes
     * last, does not overwrite the newer ([store] numbers each in turn).
     */
    suspend fun <T> fresh(spec: PageSpec<T>): T {
        val shared = inFlight.computeIfAbsent(spec.key) { key ->
            lateinit var self: Deferred<Any?>
            // On the cache's own scope: a caller that gives up (a screen closed, the car moved
            // on) doesn't cancel the request for anyone else, and the answer is kept anyway.
            self = scope.async(io, start = CoroutineStart.LAZY) {
                try {
                    val since = versionOf(key)
                    val askedAt = clock()
                    val value = spec.fetch()
                    val stored = store(spec, value, since, askedAt, kept = { inFlight.remove(key, self) })
                    if (stored) value else kept(spec) ?: value
                } finally {
                    // (This request's own: another may have taken its place since it ended.)
                    inFlight.remove(key, self)
                }
            }
            self
        }
        shared.start()
        @Suppress("UNCHECKED_CAST")
        return shared.await() as T
    }

    /** The page as the server sent it within the last `withinMillis`, without asking again. */
    private fun recent(key: String, withinMillis: Long): Current? {
        val now = synchronized(state) { slots[key]?.let { heldValue(key, it) } } ?: return null
        return if (now.fresh && clock() - now.at <= withinMillis) now else null
    }

    /**
     * The kept copy straight away (or nothing yet), then the server's. A failure while there
     * is a kept copy leaves the page as it now stands on show, with the error alongside:
     * never a blank page. A page that was fetched moments ago isn't fetched again.
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
            emit(Page(kept(spec) ?: kept, error = e))
        }
    }

    /** How a screen's check with the server stands, and where the page's order of events had
     *  got to when it did. */
    private sealed interface Check {
        data object Reading : Check
        data class Asking(val at: Long) : Check
        data class Done(val at: Long) : Check
        data class Failed(val error: ApiError, val at: Long) : Check
    }

    /**
     * As [page], and then every later change to the page (a like here, a fetch made for
     * someone else) as it happens, for as long as it's collected: what a screen shows. It is
     * the page's one current value that is followed, so what is shown never goes back to an
     * older one; the check with the server only says how it went beside it. A change made
     * after the check failed is newer than the failure, and shows without it.
     */
    fun <T> live(spec: PageSpec<T>, freshFor: Long = FRESH_FOR_MILLIS): Flow<Page<T>> = channelFlow {
        val slot = slotFor(spec.key)
        val check = MutableStateFlow<Check>(Check.Reading)
        launch {
            if (recent(spec.key, freshFor) != null) {
                check.value = Check.Done(versionOf(spec.key))
                return@launch
            }
            kept(spec)
            check.value = Check.Asking(versionOf(spec.key))
            check.value = try {
                fresh(spec)
                Check.Done(versionOf(spec.key))
            } catch (e: ApiError) {
                Check.Failed(e, versionOf(spec.key))
            }
        }
        var lastVersion = -1L
        var lastValue: T? = null
        var lastCheck: Check = Check.Reading
        combine(slot.current, check) { now, how -> now to how }.collect { (now, how) ->
            // Nothing to say until the kept copy has been looked for.
            if (how == Check.Reading) return@collect
            // Let go of from memory, or held again as it was: still the same page, as last seen.
            if (now.version == lastVersion && how == lastCheck && (!now.held || now.value === lastValue)) return@collect
            @Suppress("UNCHECKED_CAST")
            val value = when {
                now.held -> now.value as T
                now.version == lastVersion -> lastValue
                // A change this collector never saw, already let go of from memory (a
                // collector can go from one value straight to the mark that the next has been
                // let go of). The page as it now stands is read again: from disk, where its
                // newest change is before it is let go of. It used to be shown as nothing,
                // which leaves a screen on the value before, for good.
                else -> kept(spec)
            }
            lastVersion = now.version
            lastValue = value
            lastCheck = how
            send(
                when (how) {
                    is Check.Done -> Page(value, fresh = true)
                    is Check.Failed -> if (now.version > how.at) Page(value, fresh = true) else Page(value, error = how.error)
                    is Check.Asking -> if (now.version > how.at && now.fresh) Page(value, fresh = true) else Page(value)
                    Check.Reading -> return@collect
                },
            )
        }
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
        store(spec, value, since = null, askedAt = null)
    }

    /**
     * Keeps `value` as the page, as the newest change to it, and writes it to disk. With
     * `since`, only if the page hasn't changed since it stood at that number (what was begun
     * then is older than what has happened since): false if it has, and nothing is kept.
     * `askedAt`, for an answer from the server: when it was asked for (see [heard]). `kept`
     * is told once it is kept in memory, before it is written (see [fresh]). Each change is
     * numbered as it is kept, and a write that comes after a newer one's is not made: an
     * older answer never overwrites a newer, in memory or on disk.
     */
    private suspend fun <T> store(spec: PageSpec<T>, value: T, since: Long?, askedAt: Long?, kept: () -> Unit = {}): Boolean {
        val slot = slotFor(spec.key)
        val mine = synchronized(state) {
            if (since != null && slot.version != since) return false
            slot.version++
            hold(spec.key, slot, value, fresh = true)
            // Told under the same lock that numbers the change: in its order, and (the buffer
            // has no end) never dropped.
            keptNow.tryEmit(spec.key to value)
            if (askedAt != null) heardNow.tryEmit(Heard(spec.key, value, askedAt))
            slot.version
        }
        // Kept, and numbered: whatever is asked from now on is newer than this.
        kept()
        beforeWrite(spec.key)
        slot.disk.withLock {
            // A newer write that got here first has made this one pointless.
            if (slot.written < mine) {
                val safe = withContext(io) { writeFile(spec, value) || fileFor(spec.key).let { !it.exists() || it.delete() } }
                synchronized(state) {
                    // Written, or at least no older copy left on disk to be read back: it may
                    // be let go of now. Otherwise it stays held until a write succeeds.
                    if (safe && slot.written < mine) slot.written = mine
                    trim(keep = null)
                }
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
                if (changed == kept || store(spec, changed, since, askedAt = null)) return
            }
        }
    }

    /** Forgets a kept page, so the next request for it goes to the server. */
    suspend fun forget(key: String) {
        val slot = slotFor(key)
        val mine = synchronized(state) {
            slot.version++
            if (memory.remove(key) != null) held -= slot.weight
            slot.weight = 0
            slot.current.value = Current(slot.version, held = false, value = null, fresh = false, at = 0)
            slot.version
        }
        slot.disk.withLock {
            val gone = withContext(io) { fileFor(key).let { !it.exists() || it.delete() } }
            synchronized(state) { if (gone && slot.written < mine) slot.written = mine }
        }
    }

    /** Makes `value` the page's current value, held in memory. Under `state`. */
    private fun hold(key: String, slot: Slot, value: Any?, fresh: Boolean) {
        val weight = weigh(value)
        if (memory.put(key, slot) != null) held -= slot.weight
        slot.weight = weight
        held += weight
        slot.current.value = Current(slot.version, held = true, value = value, fresh = fresh, at = clock())
        trim(keep = key)
    }

    /**
     * The least lately used go until it all fits, but never the page just kept (it is the
     * one someone is looking at), and never one whose newest change isn't on disk yet: let go
     * of, it would be read back from the older file. Under `state`.
     */
    private fun trim(keep: String?) {
        val each = memory.entries.iterator()
        while (held > budget && memory.size > 1 && each.hasNext()) {
            val (key, slot) = each.next()
            if (key == keep || slot.written < slot.version) continue
            held -= slot.weight
            slot.weight = 0
            each.remove()
            slot.current.value = Current(slot.version, held = false, value = null, fresh = false, at = 0)
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

    /** Writes the page; false if it couldn't be (the caller makes sure no older copy is left
     *  to be read back in its place). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun <T> writeFile(spec: PageSpec<T>, value: T): Boolean = try {
        writeAtomically(fileFor(spec.key)) { out -> json.encodeToStream(spec.serializer, value, out) }
        true
    } catch (_: IOException) {
        // A page that can't be written is fetched again next time; nothing is lost.
        false
    } catch (_: SerializationException) {
        false
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
