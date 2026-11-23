package org.medianexus.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.store.KeptSongs
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Which conversions are kept, tested with Media3's real cache (`SimpleCache`, and the
 * `CacheDataSource` and `CacheDataSink` the player's data source stands behind it), because
 * the fault this guards against was one of their ordering: the sink commits what it has
 * written when it is closed, which is after the read has ended and been judged.
 */
@RunWith(RobolectricTestRunner::class)
class KeptConversionTest {
    private lateinit var dir: File
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var kept: KeptSongs
    private var offline = false

    /** The fewest bytes a whole conversion of the song comes to. */
    private var least = 100_000L

    /** What the server sends for each request, in the order they are made; and the requests. */
    private val answers = ArrayDeque<ByteArray>()
    private val asked = mutableListOf<String>()

    @Before
    fun start() {
        dir = Files.createTempDirectory("kept").toFile()
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        kept = KeptSongs(dir, database) { 1L shl 30 }
    }

    @After
    fun stop() {
        kept.close()
        database.close()
        dir.deleteRecursively()
    }

    /** A conversion's bytes: `which` tells one answer from another, byte for byte. */
    private fun conversion(size: Int, which: Int = 1) = ByteArray(size) { (it * 31 + it / 255 + which * 101).toByte() }

    /** The server: a fresh conversion every time, from its start whatever place was asked
     *  for, with no length, as Navidrome sends one. */
    private inner class Server : BaseDataSource(true) {
        private var bytes = ByteArray(0)
        private var at = 0
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            synchronized(asked) {
                asked += "from byte ${dataSpec.position}"
                bytes = answers.removeFirstOrNull() ?: throw IOException("The server was asked for more than the test allowed")
            }
            at = 0
            uri = dataSpec.uri
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - at, 8_192)
            bytes.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {}
    }

    private val form = StreamForm(128, "mp3")
    private val conversions = Conversions()

    private inner class Leases : SongLeases {
        override fun generation() = 1L

        override fun lease(generation: Long): SongLease {
            val cache: Cache? = kept.acquire()
            return object : SongLease {
                override val cache = cache
                override val offline = this@KeptConversionTest.offline
                override val conversions = this@KeptConversionTest.conversions
                override fun resolve(songId: String) = SongWhere(form, cacheKey(songId, form), least) { "http://server.invalid/stream?id=$songId&timeOffset=$it" }
                override fun release() {
                    if (cache != null) kept.release()
                }
            }
        }
    }

    private val sources = SongDataSource.Factory(Leases(), DataSource.Factory { Server() })

    /** Opens the song as the player's loader does: closed again if it couldn't be opened. */
    private fun open(at: Long = 0): DataSource {
        val source = sources.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(songUri("one")).setPosition(at).setFlags(PLAYER_FLAGS).build())
        } catch (e: IOException) {
            source.close()
            throw e
        }
        return source
    }

    /** Reads up to `most` bytes, or to the end. */
    private fun read(source: DataSource, most: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (out.size() < most) {
            val got = source.read(buffer, 0, minOf(buffer.size, most - out.size()))
            if (got == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, got)
        }
        return out.toByteArray()
    }

    private fun play(): ByteArray {
        val source = open()
        try {
            return read(source)
        } finally {
            source.close()
        }
    }

    /** Whether the song can be played with the server out of reach: only a copy kept whole can. */
    private fun playsOffline(): ByteArray? {
        offline = true
        return try {
            play()
        } catch (e: StreamFailure) {
            assertTrue("refused for another reason: $e", e.offline)
            null
        } finally {
            offline = false
        }
    }

    @Test
    fun aConversionThatEndsEarlyAndCleanlyIsNotKept() {
        // Half of what the song should come to, and then a clean end: the server (or
        // something between) gave up on it without an error.
        answers += conversion(50_000)
        assertEquals(50_000, play().size)
        // Media3's sink commits what it wrote as it is closed, after the read ended. Asked
        // for again with no server, the short one must not be played as if it were the song.
        assertEquals("a conversion cut short was kept, and played as the whole song", null, playsOffline()?.size)
        // And with the server back, it is fetched afresh, not read from what was left.
        answers += conversion(150_000, which = 2)
        assertArrayEquals(conversion(150_000, which = 2), play())
    }

    @Test
    fun aWholeConversionIsKeptAndPlayedWithoutTheServer() {
        answers += conversion(150_000)
        assertArrayEquals(conversion(150_000), play())
        assertArrayEquals(conversion(150_000), playsOffline())
        // And online, nothing more is asked of the server for it.
        assertArrayEquals(conversion(150_000), play())
        assertEquals(listOf("from byte 0"), asked)
    }

    @Test
    fun aConversionClosedPartWayIsNotKept() {
        answers += conversion(150_000)
        val source = open()
        assertEquals(120_000, read(source, 120_000).size)
        source.close()
        assertEquals(null, playsOffline())
    }

    @Test
    fun aWholeConversionSurvivesTheCacheBeingClosedAndOpenedAgain() {
        answers += conversion(150_000)
        play()
        kept.close()
        kept = KeptSongs(dir, database) { 1L shl 30 }
        assertArrayEquals(conversion(150_000), playsOffline())
    }

    @Test
    fun aPlacePastTheEndOfAKeptConversionIsItsEndAndTheCopyStays() {
        answers += conversion(150_000)
        play()
        // A seek to the very end of the bar, or to a place the library believes in and the
        // conversion never reached (it thought the song longer). Media3's cache calls a place
        // past the end an error; that used to be taken for a damaged copy, which was thrown
        // away, and offline it failed the song instead of ending it.
        for (place in listOf(150_000L, 150_001L, 400_000L)) {
            for (without in listOf(false, true)) {
                offline = without
                val source = open(place)
                assertEquals("at byte $place", 0, read(source).size)
                source.close()
            }
        }
        offline = false
        assertArrayEquals(conversion(150_000), playsOffline())
        assertEquals(listOf("from byte 0"), asked)
    }

    @Test
    fun aConversionWithoutTheMarkIsNeverPlayedAndIsClearedAway() {
        // Every byte of it in the cache, and the cache sure of its length: what an older
        // build left for a conversion that ended early, or a read that was never closed
        // because the app was stopped. Put there by Media3's cache data source, as that was.
        val key = cacheKey("one", form)
        answers += conversion(150_000)
        val cache = kept.acquire()!!
        val through = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { Server() }.createDataSource()
        through.open(DataSpec.Builder().setUri("http://server.invalid/stream").setKey(key).build())
        assertEquals(150_000, read(through).size)
        through.close()
        assertTrue(cache.isCached(key, 0, 150_000))
        kept.release()
        assertEquals(null, playsOffline())
        // The next time the kept songs are opened, it is gone.
        kept.close()
        kept = KeptSongs(dir, database) { 1L shl 30 }
        val again = kept.acquire()!!
        assertTrue(key !in again.keys)
        kept.release()
    }

    @Test
    fun twoReadersOfOneConversionNeverMix() {
        // Longer than one file of the cache (5 MB), so the first reader has put some of it
        // away before the second starts.
        val size = 6 * 1024 * 1024
        least = size.toLong() / 2
        answers += conversion(size, which = 1)
        answers += conversion(size, which = 2)
        // Anything further asked of the server would be a third conversion, begun in the
        // middle of the second reader's song: it would get that one's start, part-way through.
        val first = open()
        val firstHalf = read(first, 5_500_000)
        val second = open()
        val secondStart = read(second, 50_000)
        // The first reader finishes, and closes, while the second is still reading.
        val firstRest = read(first)
        first.close()
        val secondRest = try {
            read(second)
        } catch (e: IOException) {
            fail("the second reader went back to the server part-way through its song: $asked")
            throw e
        } finally {
            second.close()
        }
        assertArrayEquals("the first reader's song", conversion(size, which = 1), firstHalf + firstRest)
        assertArrayEquals("the second reader's song is one conversion from start to end", conversion(size, which = 2), secondStart + secondRest)
        assertEquals(listOf("from byte 0", "from byte 0"), asked)
        // What is kept afterwards is one reader's conversion, whole, or nothing: never a
        // piece of each.
        playsOffline()?.let { assertArrayEquals(conversion(size, which = 1), it) }
    }

    private companion object {
        /** What the player's own loads ask with. */
        const val PLAYER_FLAGS = DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION
    }
}
