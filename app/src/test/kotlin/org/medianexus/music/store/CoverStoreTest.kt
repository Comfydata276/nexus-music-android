package org.medianexus.music.store

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError

class CoverStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_000_000L

    @After
    fun stop() = scope.cancel()

    /** The first bytes of a JPEG, and some more. */
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) + ByteArray(40) { it.toByte() }

    private fun store(fetch: suspend (String, Int) -> ByteArray) = CoverStore(folder.root, scope, Dispatchers.IO, fetch, clock = { now })

    @Test
    fun nowPlayingsLargerSizeIsKeptApartFromTheUsualOne() = runBlocking {
        val asked = mutableListOf<Pair<String, Int>>()
        val covers = store { id, size -> asked += id to size; jpeg }
        val usual = covers.file("al-9")
        val large = covers.file("al-9", CoverStore.LARGE)
        covers.file("al-9", CoverStore.LARGE)
        assertEquals(listOf("al-9" to CoverStore.SIZE, "al-9" to CoverStore.LARGE), asked)
        assertEquals(setOf("al-9.${CoverStore.SIZE}", "al-9.${CoverStore.LARGE}"), setOf(usual!!.name, large!!.name))
        // No other sizes: a file name can't be made up from an arbitrary number.
        assertNull(CoverStore.fileName("al-9", 77))
        assertNull(covers.file("al-9", 77))
    }

    @Test
    fun eachCoverIsFetchedOnceAtOneSizeAndKept() = runBlocking {
        val asked = mutableListOf<Pair<String, Int>>()
        val covers = store { id, size -> asked += id to size; jpeg }
        val file = covers.file("al-1_abc")
        assertNotNull(file)
        assertArrayEquals(jpeg, file!!.readBytes())
        covers.file("al-1_abc")
        // Even by a new store, as after a restart.
        assertNotNull(store { _, _ -> error("not asked") }.file("al-1_abc"))
        assertEquals(listOf("al-1_abc" to CoverStore.SIZE), asked)
    }

    @Test
    fun requestsForTheSameCoverAtOnceShareOneFetch() = runBlocking {
        val asked = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val covers = store { _, _ ->
            asked.incrementAndGet()
            gate.await()
            jpeg
        }
        val all = List(5) { async { covers.file("al-2") } }
        withTimeout(2_000) { while (asked.get() == 0) delay(5) }
        gate.complete(Unit)
        all.forEach { assertNotNull(it.await()) }
        assertEquals(1, asked.get())
    }

    @Test
    fun aCoverThatCouldNotBeFetchedIsLeftAloneForAMinute() = runBlocking {
        val asked = AtomicInteger()
        var answer: () -> ByteArray = { throw ApiError.Network("(down)") }
        val covers = store { _, _ -> asked.incrementAndGet(); answer() }
        assertNull(covers.file("al-3"))
        answer = { jpeg }
        assertNull(covers.file("al-3"))
        assertEquals(1, asked.get())
        now += CoverStore.RETRY_AFTER_MILLIS
        assertNotNull(covers.file("al-3"))
        // What the server sends for a cover it doesn't have isn't kept as one.
        answer = { "{\"subsonic-response\":{}}".toByteArray() }
        assertNull(covers.file("al-4"))
    }

    @Test
    fun onlyIdsAServerWouldGiveNameAFile() {
        assertNotNull(CoverStore.fileName("al-5f3c_6543fa"))
        for (id in listOf("", "../settings.json", "a/b", "..", "a.b", "a b", "é", "x".repeat(161))) {
            assertNull(id, CoverStore.fileName(id))
        }
        // Whatever is asked for, the file is inside the store's folder.
        val covers = store { _, _ -> jpeg }
        assertTrue(covers.fileFor("al-1")!!.parentFile == folder.root)
        assertNull(covers.fileFor("../../x"))
    }

    @Test
    fun theOldestCoversGoWhenThereAreTooMany() = runBlocking {
        val covers = store { _, _ -> jpeg }
        for (i in 1..10) {
            covers.file("c$i")!!.setLastModified(i * 1000L)
        }
        covers.trim(budget = jpeg.size * 8L)
        // Down to three quarters of the budget: the newest six stay.
        assertEquals((5..10).map { "c$it.${CoverStore.SIZE}" }.toSet(), folder.root.list()!!.toSet())
    }
}
