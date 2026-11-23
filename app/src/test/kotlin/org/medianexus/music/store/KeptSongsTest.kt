package org.medianexus.music.store

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptSongsTest {
    private val megabyte = 1024L * 1024

    /** A cache that only does what the evictor asks of it: remove a span, and say so. */
    private fun cacheFor(evictor: LimitEvictor, removed: MutableList<String>): Cache {
        lateinit var cache: Cache
        cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            if (method.name == "removeSpan") {
                val span = args[0] as CacheSpan
                removed += span.key
                evictor.onSpanRemoved(cache, span)
            }
            null
        } as Cache
        return cache
    }

    private fun span(key: String, megabytes: Int, playedAt: Long) = CacheSpan(key, 0, megabytes * megabyte, playedAt, File("/kept/$key"))

    @Test
    fun loweringTheLimitFreesTheSpaceAtOnce() {
        var limit = 200 * megabyte
        val evictor = LimitEvictor { limit }
        val removed = mutableListOf<String>()
        val cache = cacheFor(evictor, removed)
        // Ten songs of 10 MB, played one after another.
        for (i in 1..10) evictor.onSpanAdded(cache, span("song$i", 10, playedAt = i.toLong()))
        assertEquals(100 * megabyte, evictor.kept)
        assertTrue(removed.isEmpty())
        // Turned down to 35 MB in Settings. Nothing else is played; the space is given back
        // now, the songs played longest ago going first.
        limit = 35 * megabyte
        evictor.trim(cache)
        assertEquals(listOf("song1", "song2", "song3", "song4", "song5", "song6", "song7"), removed)
        assertEquals(30 * megabyte, evictor.kept)
        // Turned off: nothing is kept.
        limit = 0
        evictor.trim(cache)
        assertEquals(0, evictor.kept)
        assertEquals(10, removed.size)
        // Turned back up: nothing more to do.
        limit = 500 * megabyte
        evictor.trim(cache)
        assertEquals(10, removed.size)
    }

    @Test
    fun aSongPlayedAgainIsTheLastToGo() {
        var limit = 100 * megabyte
        val evictor = LimitEvictor { limit }
        val removed = mutableListOf<String>()
        val cache = cacheFor(evictor, removed)
        val first = span("first", 10, playedAt = 1)
        evictor.onSpanAdded(cache, first)
        evictor.onSpanAdded(cache, span("second", 10, playedAt = 2))
        evictor.onSpanAdded(cache, span("third", 10, playedAt = 3))
        // The first is played again.
        evictor.onSpanTouched(cache, first, span("first", 10, playedAt = 4))
        limit = 10 * megabyte
        evictor.trim(cache)
        assertEquals(listOf("second", "third"), removed)
    }
}
