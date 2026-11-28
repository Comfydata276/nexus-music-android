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
    fun displaySizesRoundUpToACoverTier() {
        val widths = listOf(113, 160, 161, 320, 321, 512, 513, 640, 641, 2000)
        assertEquals(listOf(160, 160, 320, 320, 512, 512, 640, 640, 640, 640),
            widths.map { CoverStore.sizeFor(it) })
        assertEquals(listOf(320, 320, 512, 512, 640, 640, 640, 640, 640, 640),
            widths.map { CoverStore.sizeFor(it, round = true) })
    }

    @Test
    fun aWideOfflineAlbumPageUsesItsDownloadedCover() = runBlocking {
        val pinned = folder.newFolder("downloads")
        val offline = pinned.resolve("album.640").apply { writeBytes(jpeg) }
        val covers = CoverStore(folder.root, scope, Dispatchers.IO,
            { _, _ -> error("the downloaded album is offline") }, pinned = pinned)
        val size = CoverStore.sizeFor(800)
        assertEquals(offline, covers.forScreen("album", size))
        assertNull(covers.screenPreview("album", size))
    }

    @Test
    fun downloadingArtworkFetchesItsOwnCopyWhenOnlyASmallerTierIsKept() = runBlocking {
        val pinned = folder.newFolder("downloads")
        val small = folder.newFile("album.512").apply { writeBytes(jpeg) }
        val downloaded = jpeg + byteArrayOf(42)
        val covers = CoverStore(folder.root, scope, Dispatchers.IO,
            { _, _ -> error("the download supplies its own network fetch") }, pinned = pinned)
        // The car and content provider check the usual size.
        assertNull(covers.kept("album"))
        assertTrue(covers.pin("album") { downloaded })
        assertArrayEquals(downloaded, pinned.resolve("album.640").readBytes())
        assertArrayEquals(jpeg, small.readBytes())
    }

    @Test
    fun aFailureAtASmallerTierDoesNotBlockTheUsualSize() = runBlocking {
        val asked = mutableListOf<Int>()
        val covers = store { _, size ->
            asked += size
            if (size == 160) throw ApiError.Network("(down)")
            jpeg
        }
        assertNull(covers.forScreen("album", 160))
        assertNotNull(covers.forOutside("album", 2_000))
        assertEquals(listOf(160, 640), asked)
    }

    @Test
    fun aCachedLargeCoverCanServeASmallScreenWithoutCreatingAnotherFile() = runBlocking {
        val old = folder.newFile("old.640").apply { writeBytes(jpeg) }
        val pinned = folder.newFolder("downloads")
        val offline = pinned.resolve("offline.640").apply { writeBytes(jpeg) }
        val covers = CoverStore(folder.root, scope, Dispatchers.IO,
            { _, _ -> error("both covers are on the phone") }, pinned = pinned)
        assertEquals(old, covers.forScreen("old", 160))
        assertEquals(offline, covers.forScreen("offline", 320))
        assertTrue(!folder.root.resolve("old.160").exists())
        assertTrue(!folder.root.resolve("offline.320").exists())
        assertArrayEquals(jpeg, old.readBytes())
    }

    @Test
    fun enlargingAScreenAndServingTheCarBothFetchTheirOwnTier() = runBlocking {
        val asked = mutableListOf<Int>()
        val covers = store { _, size -> asked += size; jpeg }
        assertEquals("album.160", covers.forScreen("album", 160)!!.name)
        assertEquals("album.512", covers.forScreen("album", 512)!!.name)
        assertEquals("album.640", covers.forOutside("album", 2_000)!!.name)
        assertEquals(listOf(160, 512, 640), asked)
    }

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

    @Test
    fun bothSizesWrittenAtOnceEachKeepTheirOwnBytes() = runBlocking {
        // Straight through the shared helper, many times over: before, both wrote "al-7.tmp".
        val small = ByteArray(200_000) { 1 }
        val big = ByteArray(300_000) { 2 }
        repeat(40) {
            val a = scope.async { writeAtomically(java.io.File(folder.root, "al-7.640"), small) }
            val b = scope.async { writeAtomically(java.io.File(folder.root, "al-7.1200"), big) }
            a.await()
            b.await()
            assertArrayEquals(small, java.io.File(folder.root, "al-7.640").readBytes())
            assertArrayEquals(big, java.io.File(folder.root, "al-7.1200").readBytes())
        }
        // And through the store, the server answering both at once.
        val gate = CompletableDeferred<Unit>()
        val smallJpeg = jpeg + ByteArray(5000) { 3 }
        val bigJpeg = jpeg + ByteArray(9000) { 4 }
        val covers = store { _, size -> gate.await(); if (size == CoverStore.LARGE) bigJpeg else smallJpeg }
        val one = scope.async { covers.file("al-8") }
        val two = scope.async { covers.file("al-8", CoverStore.LARGE) }
        gate.complete(Unit)
        assertArrayEquals(smallJpeg, one.await()!!.readBytes())
        assertArrayEquals(bigJpeg, two.await()!!.readBytes())
        assertTrue(folder.root.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun aFetchNobodyWaitsForAnyMoreIsDroppedBeforeItsTurn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val covers = store { id, _ -> asked += id; gate.await(); jpeg }
        // Every turn taken by a cover still wanted...
        val wanted = (1..CoverStore.FETCHES_AT_ONCE).map { scope.async { covers.file("on-$it") } }
        withTimeout(5_000) { while (asked.size < CoverStore.FETCHES_AT_ONCE) delay(5) }
        // ...and one more waits for a turn, then scrolls away.
        val gone = scope.async { covers.file("gone-1") }
        delay(50)
        gone.cancel()
        delay(50)
        gate.complete(Unit)
        wanted.forEach { assertNotNull(it.await()) }
        delay(100)
        assertTrue(asked.toString(), "gone-1" !in asked)
        // Asked for again later, it is fetched as usual.
        assertNotNull(covers.file("gone-1"))
    }

    @Test
    fun aCoverIsNeverFetchedTwice() = runBlocking {
        val fetches = AtomicInteger()
        val covers = store { _, _ -> fetches.incrementAndGet(); delay(20); jpeg }
        val all = (1..20).map { scope.async { covers.file("al-once") } }
        all.forEach { assertNotNull(it.await()) }
        (1..5).map { scope.async { covers.file("al-once") } }.forEach { it.await() }
        assertEquals(1, fetches.get())
    }
}
