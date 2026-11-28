package org.medianexus.music.store

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.net.ApiError

/**
 * Cover art, as the Windows app keeps it (src/covers.rs): each picture is fetched from the
 * server once at a size and kept on disk for the account; whatever shows it (Coil in the
 * app, the car and the system's media controls through [CoverProvider]) cuts it down to the
 * size it needs. Two requests for the same picture at once share one fetch, a few are
 * fetched at a time, and a picture that couldn't be fetched is left alone for a minute
 * before it is asked for again.
 *
 * Screen requests use a size suited to the display. The car, system media controls and
 * downloads use [SIZE]. Now Playing asks for [LARGE] with a [SIZE] preview.
 *
 * `fetch` gets the picture's bytes from the server (the signed-in account's client).
 */
class CoverStore(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val fetch: suspend (id: String, size: Int) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Covers kept at most, on disk. */
    private val budget: Long = DISK_BUDGET,
    /** Where the covers of downloaded music are kept, outside the cache the system may clear
     *  and the budget trims (see [pin]). Null: none are. */
    private val pinned: File? = null,
) {
    /** When each cover that couldn't be fetched last failed, oldest first. Never more than
     *  [MOST_FAILURES]: a car asking for covers that don't exist can't make it grow. */
    private val failedAt = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MOST_FAILURES
    }

    /** Fetches from the server at once: a screenful of misses takes turns rather than
     *  opening a connection each. */
    private val fetching = Semaphore(FETCHES_AT_ONCE)

    /** Fetches under way that were started for another app (see [forOutside]). */
    private val outside = AtomicInteger(0)

    /** One at a time on the folder: writing a cover, and trimming. `total` is the bytes
     *  kept, once the folder has been read (-1 before). */
    private val folder = Mutex()
    private var total = -1L

    /** The kept file for a cover, if it's on the phone already: among the covers kept as
     *  they're shown, or those of downloaded music. Doesn't fetch. */
    fun kept(id: String, size: Int = SIZE): File? =
        fileFor(id, size)?.takeIf { it.isFile && it.length() > 0 } ?: pinnedFor(id, size)?.takeIf { it.isFile && it.length() > 0 }

    private fun pinnedFor(id: String, size: Int): File? = pinned?.let { dir -> fileName(id, size)?.let { File(dir, it) } }

    /**
     * Keeps a cover with the downloads, where the system won't clear it away when space runs
     * short and the budget doesn't trim it: downloaded music has to be shown with no signal
     * at all. Fetched first if it isn't kept already. Returns whether it is kept now.
     */
    suspend fun pin(id: String): Boolean {
        val target = pinnedFor(id, SIZE) ?: return false
        if (withContext(io) { target.isFile && target.length() > 0 }) return true
        val source = file(id) ?: return false
        return withContext(io) {
            try {
                writeAtomically(target, source.readBytes())
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    /**
     * As [pin], but a cover that isn't on the phone yet is had from `fetch` (its bytes, or
     * null if they can't be had now) and never by this store's own way to the server: with
     * "Download on Wi-Fi only" on, a download's cover is part of the download, and comes by
     * the unmetered network the songs come by or not at all.
     */
    suspend fun pin(id: String, fetch: suspend () -> ByteArray?): Boolean {
        val target = pinnedFor(id, SIZE) ?: return false
        if (withContext(io) { target.isFile && target.length() > 0 }) return true
        val bytes = withContext(io) { kept(id)?.takeIf { it.isFile && it.length() > 0 }?.readBytes() } ?: fetch() ?: return false
        if (bytes.isEmpty()) return false
        return withContext(io) {
            try {
                writeAtomically(target, bytes)
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    /** No downloaded music uses this cover any more. */
    fun unpin(id: String) {
        pinnedFor(id, SIZE)?.delete()
    }

    /**
     * The file holding a cover at `size` ([SIZE] or [LARGE]), fetched first if it isn't kept
     * yet. Null if there's no such cover or it can't be fetched now. Safe to call from many
     * places at once.
     */
    suspend fun file(id: String, size: Int = SIZE): File? {
        return load(id, size, larger = false)
    }

    /** A picture already on the phone can also serve a smaller display. */
    internal suspend fun forScreen(id: String, size: Int): File? = load(id, size, larger = true)

    /** The largest smaller picture already on the phone, only when the screen needs a fetch. */
    internal suspend fun screenPreview(id: String, size: Int): File? = withContext(io) {
        if (keptForScreen(id, size) != null) return@withContext null
        sizes.asReversed().firstNotNullOfOrNull { candidate ->
            if (candidate < size) kept(id, candidate) else null
        }
    }

    private fun keptForScreen(id: String, size: Int): File? =
        kept(id, size) ?: sizes.firstNotNullOfOrNull { candidate ->
            if (candidate > size) kept(id, candidate) else null
        }

    private suspend fun load(id: String, size: Int, larger: Boolean): File? {
        val file = fileFor(id, size) ?: return null
        val cached = withContext(io) { if (larger) keptForScreen(id, size) else kept(id, size) }
        if (cached != null) return cached
        val entry = start(id, size, file) ?: return null
        return try {
            await(file.name, entry)
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Dropped because nobody else was waiting; this caller is being cancelled anyway.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /**
     * A cover for another app (the car, the system's media controls), which waits on one of
     * the few threads the system gives this app for everyone's calls. So it waits no longer
     * than `waitMillis`, and at most [OUTSIDE_AT_ONCE] fetches started this way are under way
     * at any time: beyond that the answer is "not now" at once, and nothing is started. A
     * fetch that outlasts its wait carries on and counts until it ends; the cover is there
     * the next time it's asked for.
     */
    suspend fun forOutside(id: String, waitMillis: Long): File? {
        val file = fileFor(id, SIZE) ?: return null
        if (file.isFile && file.length() > 0) return file
        pinnedFor(id, SIZE)?.let { kept -> if (kept.isFile && kept.length() > 0) return kept }
        if (outside.incrementAndGet() > OUTSIDE_AT_ONCE) {
            outside.decrementAndGet()
            return null
        }
        val fetch = start(id, SIZE, file, kept = true)?.job
        if (fetch == null) {
            outside.decrementAndGet()
            return null
        }
        fetch.invokeOnCompletion { outside.decrementAndGet() }
        return withTimeoutOrNull(waitMillis) { fetch.await() }
    }

    /** A fetch shared by everyone waiting on one cover. `waiting` counts the screens that
     *  still want it; `begun` is set once it has a turn to fetch. */
    private class Shared(val job: Deferred<File?>) {
        var waiting = 0
        var begun = false
        /** Started for another app, which doesn't say when it stops waiting: never dropped. */
        var kept = false
    }

    private val shared = ConcurrentHashMap<String, Shared>()

    /**
     * The fetch of this cover: the one under way, or a new one. Null if it failed lately.
     * A screen waiting on it is counted in, and out again if it stops waiting (the row
     * scrolled away): a fetch that every screen has stopped waiting for, and that hasn't had
     * its turn yet, is dropped, so a fling through a grid doesn't leave dozens queued behind
     * the covers now on screen.
     */
    private fun start(id: String, size: Int, file: File, kept: Boolean = false): Shared? {
        val key = file.name
        if (failedLately(key)) return null
        val entry = synchronized(shared) {
            shared.getOrPut(key) {
                lateinit var made: Shared
                // On the store's scope, so a caller giving up doesn't stop the fetch for another.
                val job = scope.async(io, start = CoroutineStart.LAZY) {
                    try {
                        fetching.withPermit {
                            synchronized(shared) { made.begun = true }
                            // Fetched meanwhile by a fetch that has just ended (the two can
                            // pass in the moment between its end and this one's start).
                            if (file.isFile && file.length() > 0) file else download(id, size, file)
                        }
                    } finally {
                        synchronized(shared) { if (shared[key] === made) shared.remove(key) }
                    }
                }
                made = Shared(job)
                made
            }.also {
                it.waiting++
                if (kept) it.kept = true
            }
        }
        entry.job.start()
        return entry
    }

    /** Waits for a shared fetch; if this was the last one waiting and it hasn't begun, it is
     *  dropped. */
    private suspend fun await(key: String, entry: Shared): File? = try {
        entry.job.await()
    } finally {
        val drop = synchronized(shared) {
            entry.waiting--
            (entry.waiting <= 0 && !entry.begun && !entry.kept).also { if (it && shared[key] === entry) shared.remove(key) }
        }
        if (drop) entry.job.cancel()
    }

    private fun failedLately(key: String): Boolean = synchronized(failedAt) {
        val at = failedAt[key] ?: return false
        if (clock() - at < RETRY_AFTER_MILLIS) return true
        failedAt.remove(key)
        false
    }

    private fun failed(key: String) = synchronized(failedAt) {
        // Out and in again, so the list stays oldest first; and whatever has had its minute
        // goes, so it holds only what is still being left alone.
        failedAt.remove(key)
        val now = clock()
        failedAt.entries.removeAll { now - it.value >= RETRY_AFTER_MILLIS }
        failedAt[key] = now
    }

    /** How many covers are being left alone after failing. */
    internal fun failures(): Int = synchronized(failedAt) { failedAt.size }

    private suspend fun download(id: String, size: Int, file: File): File? {
        val key = file.name
        val bytes = try {
            fetch(id, size)
        } catch (_: ApiError) {
            failed(key)
            return null
        }
        if (!looksLikePicture(bytes)) {
            // The server sends a JSON error, or nothing, for a cover it doesn't have.
            failed(key)
            return null
        }
        val over = folder.withLock {
            try {
                writeAtomically(file, bytes)
            } catch (_: IOException) {
                return null
            }
            synchronized(failedAt) { failedAt.remove(key) }
            if (total >= 0) total += bytes.size
            total < 0 || total > budget
        }
        // The budget holds as covers arrive, not only at the next start.
        if (over) trim()
        return file
    }

    /** The server can be reached again: try the covers that failed straight away. */
    fun retryFailed() = synchronized(failedAt) { failedAt.clear() }

    /** Deletes the least lately written covers once they take more than `budget` bytes, down
     *  to three quarters of it, as Windows does. */
    suspend fun trim(budget: Long = this.budget) = withContext(io) {
        folder.withLock {
            // What a write cut short by the app being stopped left behind; never a write under
            // way, as those happen under this lock too.
            sweepTemporaries(dir)
            val files = dir.listFiles()?.filter { it.isFile } ?: return@withLock
            var kept = files.sumOf { it.length() }
            if (kept > budget) {
                for (file in files.sortedBy { it.lastModified() }) {
                    if (kept <= budget * 3 / 4) break
                    val size = file.length()
                    if (file.delete()) kept -= size
                }
            }
            total = kept
        }
    }

    internal fun fileFor(id: String, size: Int = SIZE): File? = fileName(id, size)?.let { File(dir, it) }

    companion object {
        /**
         * The size for the car, media controls, offline downloads and Now Playing's preview.
         */
        const val SIZE = 640

        /**
         * Now Playing's size: its cover is nearly the phone's width, over a thousand pixels on
         * a large phone. Now Playing shows the [SIZE] copy until it arrives. Navidrome never
         * makes a picture larger than its original.
         */
        const val LARGE = 1200

        internal const val SMALLEST = 160
        private val screenSizes = listOf(SMALLEST, 320, 512, SIZE)
        private val sizes = screenSizes + LARGE

        internal fun sizeFor(pixels: Int, round: Boolean = false): Int {
            val tier = screenSizes.indexOfFirst { it >= pixels }
            if (tier == -1) return SIZE
            return screenSizes[(tier + if (round) 1 else 0).coerceAtMost(screenSizes.lastIndex)]
        }

        const val RETRY_AFTER_MILLIS = 60_000L

        /** Covers that failed, remembered at most. */
        const val MOST_FAILURES = 256

        /** Covers fetched from the server at a time. */
        const val FETCHES_AT_ONCE = 6

        /** Fetches started for other apps that may be under way at a time. */
        const val OUTSIDE_AT_ONCE = 6

        /** Covers kept at most, on disk, per account. */
        const val DISK_BUDGET = 300L * 1024 * 1024

        /**
         * The file name for a cover id, or null if the id is not one a server gives: only
         * letters, digits, '-' and '_', as Navidrome's are ("al-…_…"). Anything else is refused
         * rather than tidied, so nothing handed to [CoverProvider] can name a file outside the
         * folder.
         */
        fun fileName(id: String, size: Int = SIZE): String? =
            if (id.isNotEmpty() && id.length <= 160 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } && size in sizes) {
                "$id.$size"
            } else {
                null
            }

        /** JPEG, PNG, GIF, WebP, or BMP, by their first bytes. */
        internal fun looksLikePicture(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            fun at(i: Int) = bytes[i].toInt() and 0xff
            return (at(0) == 0xff && at(1) == 0xd8) ||
                (at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code) ||
                (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code) ||
                (at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code) ||
                (at(0) == 'B'.code && at(1) == 'M'.code)
        }
    }
}
