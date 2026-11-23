package org.medianexus.music.store

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

/**
 * Songs kept as they're played, so a replay or a dropped connection costs nothing: Media3's
 * cache, in the account's own folder, limited to the space chosen in Settings. Opened on
 * first use. Closed when the account goes, but only once every read still using it has
 * finished: the player's reads run on their own threads and outlive a sign-out by a moment.
 *
 * Entries are keyed by song id and the form it was fetched in (see `cacheKey`), never by
 * address, which carries a sign-in token that changes.
 */
@OptIn(UnstableApi::class)
class KeptSongs(
    private val dir: File,
    private val database: DatabaseProvider,
    /** The cache has been opened (and swept): what is kept whole in it can be read. */
    private val opened: (Cache) -> Unit = {},
    /** A song's bytes are being cleared away for the limit: it is not kept whole any more. */
    removed: (String) -> Unit = {},
    private val limitBytes: () -> Long,
) {
    private val evictor = LimitEvictor(removed, limitBytes)
    private val shared = SharedResource<SimpleCache>(
        // Only one may be open on a folder at a time. If the one before is still closing
        // (the same account signed out and straight back in), this read goes without.
        // Opened, it is cleared of conversions that were never marked whole: what a read left
        // behind when the app was stopped part-way through one.
        open = { if (SimpleCache.isCacheFolderLocked(dir)) null else SimpleCache(dir, evictor, database).also(WholeMark::sweep).also(opened) },
        close = { it.release() },
    )

    /**
     * The limit has changed: whatever is over it goes now, least lately played first, and
     * with a limit of nothing, all of it. (Left to itself the cache only looks at its limit
     * when a song is added, so turning it down freed nothing until something else played,
     * and turning it off, which stops songs being added, freed nothing at all.) Opens the
     * cache if need be; call it off the main thread.
     */
    fun enforce(): Enforced {
        // Closed for good (the account has gone): nothing to do, now or later.
        if (shared.closing) return Enforced.CLOSED
        // The folder is still held by the account's turn before this one (the same account
        // signed out and straight back in: its reads are closing): not yet. It used to be
        // passed over there, and what was over the limit stayed until something else played.
        val cache = shared.acquire() ?: return if (shared.closing) Enforced.CLOSED else Enforced.BUSY
        try {
            evictor.trim(cache)
        } finally {
            shared.release()
        }
        return Enforced.DONE
    }

    /** What became of applying the limit. */
    enum class Enforced { DONE, BUSY, CLOSED }

    /**
     * As [enforce], if what is kept on disk is more than the limit allows. For when an account
     * is taken on: the limit may have been lowered, or set to nothing, while it was away.
     * (Told from the size of the folder, without opening anything.)
     */
    fun enforceIfOver(): Enforced {
        val onDisk = dir.listFiles()?.sumOf { file -> if (file.isDirectory) file.listFiles()?.sumOf { it.length() } ?: 0L else file.length() } ?: 0L
        return if (onDisk > limitBytes()) enforce() else Enforced.DONE
    }

    /**
     * Applies the limit, trying again while the folder is still held by whoever had it last:
     * `pause` waits between tries (a second at a time by default; call this off the main
     * thread). True once the limit has been applied; false if it was given up (the account
     * went, or the folder never came free).
     */
    fun enforceWhenFree(onlyIfOver: Boolean = false, tries: Int = TRIES, pause: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        repeat(tries) { attempt ->
            when (if (onlyIfOver) enforceIfOver() else enforce()) {
                Enforced.DONE -> return true
                Enforced.CLOSED -> return false
                Enforced.BUSY -> if (attempt + 1 < tries) pause(RETRY_MILLIS)
            }
        }
        return false
    }

    /** The cache, for one read, which must call [release] when it closes. Null if the
     *  account has gone, or the folder isn't free yet: then the read does without. */
    fun acquire(): Cache? = shared.acquire()

    fun release() = shared.release()

    /** No more reads; the cache is released when the ones under way have closed. */
    fun close() = shared.close()

    private companion object {
        /** How often, and how many times, the limit is tried again while the folder is
         *  held: a minute in all, far longer than reads take to close. */
        const val RETRY_MILLIS = 1_000L
        const val TRIES = 60
    }
}

/**
 * The mark of our own on a conversion that was kept whole.
 *
 * Media3's cache knows which bytes of a song it has and how long it believes the song is,
 * and for a file as it is on the server that is enough: the server says how long it is. A
 * conversion comes with no length, so the cache takes the length from wherever the stream
 * stopped, and a conversion that stopped early (cleanly: no error, just an end) then looks
 * exactly like a whole one, every byte present up to its "length". It also writes the last
 * of a song only as its writer closes, after the read has ended. So "this conversion is the
 * whole song" is not something the cache can be asked: it is noted here, in the cache's own
 * record of the song, once the write has closed and the result has been checked
 * (`SongReader.close`). A conversion without the mark is never played from the kept songs,
 * and is cleared away.
 *
 * The mark carries a version: raise [VERSION] and every conversion kept until now stops
 * counting, and is fetched afresh.
 */
@OptIn(UnstableApi::class)
object WholeMark {
    const val VERSION = 1
    private const val KEY = ContentMetadata.KEY_CUSTOM_PREFIX + "nexus_whole"

    /** The length of the song kept under `key` if every byte of it is there; -1 otherwise.
     *  For a file as it is on the server, whose length the server gave. */
    fun cached(cache: Cache, key: String): Long {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return if (length > 0 && cache.isCached(key, 0, length)) length else -1
    }

    /** The length of the conversion kept under `key`, if it is marked whole (by this
     *  version) and is all still there; -1 otherwise. */
    fun length(cache: Cache, key: String): Long {
        val marked = cache.getContentMetadata(key).get(KEY, null as String?) ?: return -1
        val length = marked.substringAfter(':', "").toLongOrNull() ?: return -1
        if (marked.substringBefore(':') != VERSION.toString()) return -1
        return if (length > 0 && cached(cache, key) == length) length else -1
    }

    /** Marks the conversion kept under `key` whole, if exactly `length` bytes of it are kept.
     *  Returns whether it was marked. */
    fun put(cache: Cache, key: String, length: Long): Boolean {
        if (length <= 0 || cached(cache, key) != length) return false
        return try {
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(KEY, "$VERSION:$length"))
            true
        } catch (_: Cache.CacheException) {
            false
        }
    }

    /** Forgets what is kept under `key`, mark and all. */
    fun drop(cache: Cache, key: String) {
        cache.removeResource(key)
        // The cache's record of a song goes with its last byte, unless something still has
        // it open: then the mark is taken off by hand.
        if (cache.getContentMetadata(key).get(KEY, null as String?) != null) {
            try {
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().remove(KEY))
            } catch (_: Cache.CacheException) {
                // Without all its bytes it doesn't count as whole anyway.
            }
        }
    }

    /** Whether a kept-song key is a conversion's (see `cacheKey`: the song's id, then the
     *  bit rate asked for, which is 0 for a file as it is). */
    fun isConversion(key: String): Boolean = key.startsWith("song:") && (key.substringAfterLast(':').toIntOrNull() ?: 0) > 0

    /** The keys of every song kept whole: a conversion marked so, or a file as it is with
     *  every byte the server said it has. */
    fun wholeKeys(cache: Cache): Set<String> = cache.keys.filterTo(HashSet()) { key ->
        if (isConversion(key)) length(cache, key) >= 0 else cached(cache, key) >= 0
    }

    /** Clears away every conversion that isn't marked whole. For when the cache is opened:
     *  nothing is reading or writing it yet. */
    fun sweep(cache: Cache) {
        for (key in cache.keys.toList()) if (isConversion(key) && length(cache, key) < 0) drop(cache, key)
    }
}

/**
 * Removes the least lately played songs once the kept ones outgrow the limit. Media3's own
 * evictor of this kind fixes its limit when it's made; this one reads it each time, so a
 * change in Settings applies at once.
 */
@OptIn(UnstableApi::class)
internal class LimitEvictor(private val removed: (String) -> Unit = {}, private val limitBytes: () -> Long) : CacheEvictor {
    private val leastRecentlyUsed = TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp == b.lastTouchTimestamp) a.compareTo(b) else a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /**
     * Brings what is kept down to the limit as it is now. The cache calls everything else
     * here while holding its own lock, so this takes that lock too: the same one, in the
     * same order, and nothing here has a lock of its own to be taken the other way round.
     */
    fun trim(cache: Cache) = synchronized(cache) { evict(cache, 0) }

    /** How much is kept, by this evictor's count. */
    val kept: Long get() = size

    private fun evict(cache: Cache, required: Long) {
        val limit = limitBytes()
        while (size + required > limit && leastRecentlyUsed.isNotEmpty()) {
            val span = leastRecentlyUsed.first()
            removed(span.key)
            cache.removeSpan(span)
        }
    }
}
