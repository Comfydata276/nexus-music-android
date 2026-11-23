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
    fun theListOfFailuresIsBoundedAndForgetsWhatHasHadItsMinute() = runBlocking {
        // A car (or anything else) asking for covers that don't exist used to add to this
        // list for as long as the app ran.
        val covers = store { _, _ -> throw ApiError.Network("(down)") }
        for (i in 1..CoverStore.MOST_FAILURES + 100) assertNull(covers.file("missing-$i"))
        assertEquals(CoverStore.MOST_FAILURES, covers.failures())
        // A minute on, one more failure clears out the rest.
        now += CoverStore.RETRY_AFTER_MILLIS
        assertNull(covers.file("another"))
        assertEquals(1, covers.failures())
    }

    @Test
    fun theBudgetHoldsAsCoversArrive() = runBlocking {
        // Room for eight covers. Nothing trims but the arrivals themselves.
        val covers = CoverStore(folder.root, scope, Dispatchers.IO, { _, _ -> jpeg }, clock = { now }, budget = jpeg.size * 8L)
        for (i in 1..30) {
            covers.file("c$i")!!.setLastModified(i * 1000L)
            val kept = folder.root.listFiles()!!.sumOf { it.length() }
            assertTrue("$kept bytes kept after cover $i", kept <= jpeg.size * 8L)
        }
        // The newest are the ones still there.
        assertNotNull(covers.kept("c30"))
        assertNull(covers.kept("c1"))
    }

    @Test
    fun onlyAFewMissesAreFetchedAtOnce() = runBlocking {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val covers = store { _, _ ->
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(30)
            running.decrementAndGet()
            jpeg
        }
        List(40) { i -> async { covers.file("burst-$i") } }.forEach { assertNotNull(it.await()) }
        assertTrue("${most.get()} at once", most.get() <= CoverStore.FETCHES_AT_ONCE)
    }

    @Test
    fun anotherAppWaitsBrieflyAndCannotStartFetchesWithoutLimit() = runBlocking {
        val asked = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val covers = store { _, _ ->
            asked.incrementAndGet()
            gate.await()
            jpeg
        }
        // The server is slow: each caller is let go after its short wait...
        val started = System.nanoTime()
        val first = List(CoverStore.OUTSIDE_AT_ONCE) { i -> async { covers.forOutside("slow-$i", 100) } }
        first.forEach { assertNull(it.await()) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        // ...and while those fetches are still under way, more callers start none: they are
        // told "not now" at once.
        for (i in 0 until 50) assertNull(covers.forOutside("more-$i", 5_000))
        assertTrue("${asked.get()} fetches", asked.get() <= CoverStore.OUTSIDE_AT_ONCE)
        // The fetches that outlasted their callers finish, and the covers are there next time.
        gate.complete(Unit)
        withTimeout(5_000) { while (covers.kept("slow-0") == null) delay(5) }
        assertNotNull(covers.forOutside("slow-0", 100))
        // With those done, a new one can be fetched.
        withTimeout(5_000) { while (covers.forOutside("more-0", 1_000) == null) delay(20) }
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
