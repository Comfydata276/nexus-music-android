package org.medianexus.music.store

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
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
class KeptSongs(private val dir: File, private val database: DatabaseProvider, private val limitBytes: () -> Long) {
    private val evictor = LimitEvictor(limitBytes)
    private val shared = SharedResource<SimpleCache>(
        // Only one may be open on a folder at a time. If the one before is still closing
        // (the same account signed out and straight back in), this read goes without.
        open = { if (SimpleCache.isCacheFolderLocked(dir)) null else SimpleCache(dir, evictor, database) },
        close = { it.release() },
    )

    /**
     * The limit has changed: whatever is over it goes now, least lately played first, and
     * with a limit of nothing, all of it. (Left to itself the cache only looks at its limit
     * when a song is added, so turning it down freed nothing until something else played,
     * and turning it off, which stops songs being added, freed nothing at all.) Opens the
     * cache if need be; call it off the main thread.
     */
    fun enforce() {
        val cache = shared.acquire() ?: return
        try {
            evictor.trim(cache)
        } finally {
            shared.release()
        }
    }

    /** The cache, for one read, which must call [release] when it closes. Null if the
     *  account has gone, or the folder isn't free yet: then the read does without. */
    fun acquire(): Cache? = shared.acquire()

    fun release() = shared.release()

    /** No more reads; the cache is released when the ones under way have closed. */
    fun close() = shared.close()
}

/**
 * Removes the least lately played songs once the kept ones outgrow the limit. Media3's own
 * evictor of this kind fixes its limit when it's made; this one reads it each time, so a
 * change in Settings applies at once.
 */
@OptIn(UnstableApi::class)
internal class LimitEvictor(private val limitBytes: () -> Long) : CacheEvictor {
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
        while (size + required > limit && leastRecentlyUsed.isNotEmpty()) cache.removeSpan(leastRecentlyUsed.first())
    }
}
