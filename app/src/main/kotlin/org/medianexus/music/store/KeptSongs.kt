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
    private val shared = SharedResource<SimpleCache>(
        // Only one may be open on a folder at a time. If the one before is still closing
        // (the same account signed out and straight back in), this read goes without.
        open = { if (SimpleCache.isCacheFolderLocked(dir)) null else SimpleCache(dir, LimitEvictor(limitBytes), database) },
        close = { it.release() },
    )

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
private class LimitEvictor(private val limitBytes: () -> Long) : CacheEvictor {
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

    private fun evict(cache: Cache, required: Long) {
        val limit = limitBytes()
        while (size + required > limit && leastRecentlyUsed.isNotEmpty()) cache.removeSpan(leastRecentlyUsed.first())
    }
}
