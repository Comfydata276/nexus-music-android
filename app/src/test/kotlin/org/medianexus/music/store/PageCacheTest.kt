package org.medianexus.music.store

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError

class PageCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_000_000L

    @After
    fun stop() = scope.cancel()

    private fun cache() = PageCache(folder.root, scope, Dispatchers.IO, clock = { now })

    private fun spec(key: String = "playlists", fetch: suspend () -> List<String>) = PageSpec(key, ListSerializer(String.serializer()), fetch)

    @Test
    fun showsTheKeptCopyAtOnceThenTheServers() = runBlocking {
        cache().fresh(spec { listOf("old") })
        now += 60_000
        // A new start: nothing in memory, the copy on disk.
        val pages = cache().page(spec { listOf("new") }).toList()
        assertEquals(listOf(Page(listOf("old")), Page(listOf("new"), fresh = true)), pages)
    }

    @Test
    fun aFailureLeavesTheKeptCopyOnShow() = runBlocking {
        cache().fresh(spec { listOf("kept") })
        now += 60_000
        val refused = ApiError.Network("(down)")
        val pages = cache().page(spec { throw refused }).toList()
        assertEquals(2, pages.size)
        assertEquals(listOf("kept"), pages[1].value)
        assertEquals(refused, pages[1].error)
        // With nothing kept, the error comes with nothing.
        val nothing = cache().page(spec("other") { throw refused }).toList()
        assertNull(nothing.last().value)
        assertEquals(refused, nothing.last().error)
    }

    @Test
    fun aPageFetchedMomentsAgoIsNotFetchedAgain() = runBlocking {
        val asked = AtomicInteger()
        val cache = cache()
        val spec = spec { listOf("n${asked.incrementAndGet()}") }
        cache.page(spec).toList()
        now += 5_000
        assertEquals(listOf(Page(listOf("n1"), fresh = true)), cache.page(spec).toList())
        now += PageCache.FRESH_FOR_MILLIS
        cache.page(spec).toList()
        assertEquals(2, asked.get())
    }

    @Test
    fun twoAskingAtOnceShareOneTripToTheServer() = runBlocking {
        val asked = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = cache()
        val spec = spec {
            asked.incrementAndGet()
            gate.await()
            listOf("one")
        }
        val first = async { cache.fresh(spec) }
        val second = async { cache.fresh(spec) }
        withTimeout(2_000) { while (asked.get() == 0) kotlinx.coroutines.delay(5) }
        gate.complete(Unit)
        assertEquals(listOf("one"), first.await())
        assertEquals(listOf("one"), second.await())
        assertEquals(1, asked.get())
    }

    @Test
    fun quickGivesTheKeptCopyAndSaysWhenTheServersDiffers() = runBlocking {
        cache().fresh(spec { listOf("old") })
        now += 60_000
        val changed = CompletableDeferred<List<String>>()
        val got = cache().quick(spec { listOf("new") }) { changed.complete(it) }
        assertEquals(listOf("old"), got)
        assertEquals(listOf("new"), withTimeout(2_000) { changed.await() })
        // With nothing kept, it waits for the server.
        assertEquals(listOf("x"), cache().quick(spec("fresh") { listOf("x") }))
    }

    @Test
    fun keysOfAnyKindAreKeptApart() = runBlocking {
        val cache = cache()
        cache.fresh(spec("genre:Drum/Bass") { listOf("a") })
        cache.fresh(spec("genre:Drum Bass") { listOf("b") })
        val reread = cache()
        assertEquals(listOf("a"), reread.kept(spec("genre:Drum/Bass") { emptyList() }))
        assertEquals(listOf("b"), reread.kept(spec("genre:Drum Bass") { emptyList() }))
        assertTrue(reread.fileFor("genre:Drum/Bass").parentFile == folder.root)
    }

    @Test
    fun aDamagedCopyIsTreatedAsNone() = runBlocking {
        val cache = cache()
        cache.fileFor("playlists").writeText("{ not a list")
        assertNull(cache.kept(spec { emptyList() }))
    }

    @Test
    fun aKeptCopyCanBeChangedInPlace() = runBlocking {
        val cache = cache()
        cache.fresh(spec { listOf("a") })
        cache.update(spec { emptyList() }) { it + "b" }
        assertEquals(listOf("a", "b"), cache().kept(spec { emptyList() }))
    }

    @Test
    fun aLivePageCarriesOnWithChangesMadeAfterwards() = runBlocking {
        val cache = cache()
        cache.fresh(spec { listOf("a") })
        now += 60_000
        val seen = mutableListOf<Page<List<String>>>()
        val watching = async {
            cache.live(spec { listOf("a") }).first { page ->
                seen += page
                // Once it has the server's copy, a change made here (a like, say) follows.
                if (page.fresh && page.value == listOf("a")) {
                    launch { cache.update(spec { emptyList() }) { it + "b" } }
                }
                page.value == listOf("a", "b")
            }
        }
        withTimeout(5_000) { watching.await() }
        assertEquals(Page(listOf("a")), seen.first())
        assertEquals(listOf("a", "b"), seen.last().value)
    }

    @Test
    fun aSlowRefreshThatCrossesAChangeDoesNotUndoIt() = runBlocking {
        val cache = cache()
        cache.put(spec { emptyList() }, listOf("a", "b"))
        // The server is asked, and is slow; meanwhile "b" is unliked here.
        val gate = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
        val slow = spec { asked.complete(Unit); gate.await(); listOf("a", "b") }
        val refresh = scope.async { cache.fresh(slow) }
        withTimeout(5_000) { asked.await() }
        cache.update(slow) { it - "b" }
        gate.complete(Unit)
        // The answer began before the unlike, so the unlike stands: in memory, on disk, and
        // in what the refresh gives back.
        assertEquals(listOf("a"), refresh.await())
        assertEquals(listOf("a"), cache.kept(slow))
        assertEquals(listOf("a"), cache().kept(slow))
        // The next refresh, begun after it, is the server's word.
        assertEquals(listOf("a", "b"), cache.fresh(spec { listOf("a", "b") }))
    }

    @Test
    fun aReadFromDiskThatCrossesAWriteDoesNotPutTheOldCopyBack() = runBlocking {
        cache().put(spec { emptyList() }, listOf("old"))
        val cache = cache()
        val reads = (1..50).map { scope.async { cache.kept(spec { emptyList() }) } }
        cache.put(spec { emptyList() }, listOf("new"))
        reads.forEach { it.await() }
        assertEquals(listOf("new"), cache.kept(spec { emptyList() }))
        assertEquals(listOf("new"), cache().kept(spec { emptyList() }))
    }

    @Test
    fun writesOfOnePageTakeTurnsAndTheLastStays() = runBlocking {
        val cache = cache()
        val writes = (1..60).map { n -> scope.async { cache.put(spec { emptyList() }, List(200) { "v$n" }) } }
        writes.forEach { it.await() }
        val last = cache.kept(spec { emptyList() })!!
        // Whatever finished last in memory is what is on disk, whole.
        assertEquals(last, cache().kept(spec { emptyList() }))
        assertTrue(folder.root.list()!!.none { it.endsWith(".tmp") })
        // Edits made at once all land.
        cache.put(spec { emptyList() }, emptyList())
        (1..40).map { n -> scope.async { cache.update(spec { emptyList() }) { it + "e$n" } } }.forEach { it.await() }
        assertEquals(40, cache.kept(spec { emptyList() })!!.size)
    }

    @Test
    fun memoryHoldsPagesBySizeNotNumber() = runBlocking {
        val cache = PageCache(folder.root, scope, Dispatchers.IO, clock = { now }, budget = 1_000)
        cache.put(spec("big") { emptyList() }, List(900) { "s" })
        cache.put(spec("small") { emptyList() }, List(50) { "s" })
        assertTrue(cache.heldWeight() <= 1_000)
        // A page bigger than everything allowed is still held while it's the newest.
        cache.put(spec("huge") { emptyList() }, List(5_000) { "s" })
        assertEquals(5_001L, cache.heldWeight())
        // The others are still on disk.
        assertEquals(900, cache.kept(spec("big") { emptyList() })!!.size)
    }
    // ---- One order of events per page, as observers see it ----------------------------------

    @Test
    fun aFailedRefreshDoesNotPutBackTheCopyFromBeforeAChangeMadeHere() = runBlocking {
        val cache = cache()
        cache.put(spec { emptyList() }, listOf("kept"))
        now += 60_000
        val asked = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val failing = spec { asked.complete(Unit); gate.await(); throw ApiError.Network("(down)") }
        val seen = java.util.Collections.synchronizedList(mutableListOf<Page<List<String>>>())
        val watching = scope.launch { cache.live(failing).collect { seen += it } }
        withTimeout(5_000) { asked.await() }
        // A change made here while the server is being asked (an unlike, say).
        cache.update(failing) { it + "changed" }
        withTimeout(5_000) { while (seen.none { it.value == listOf("kept", "changed") }) kotlinx.coroutines.delay(5) }
        // The refresh fails: the error is said, beside the page as it now is.
        gate.complete(Unit)
        withTimeout(5_000) { while (seen.none { it.error != null }) kotlinx.coroutines.delay(5) }
        watching.cancel()
        assertEquals(listOf("kept", "changed"), seen.last().value)
        assertTrue(seen.dropWhile { it.value != listOf("kept", "changed") }.all { it.value == listOf("kept", "changed") })
    }

    @Test
    fun noChangeIsDroppedHoweverSlowlyItIsRead() = runBlocking {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        val got = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val listening = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            cache.changes.collect { (_, value) ->
                // The first change is held until every write has been made.
                gate.await()
                @Suppress("UNCHECKED_CAST")
                got += value as List<String>
            }
        }
        for (n in 1..100) cache.put(spec { emptyList() }, listOf("v$n"))
        gate.complete(Unit)
        withTimeout(5_000) { while (got.size < 100 && got.lastOrNull() != listOf("v100")) kotlinx.coroutines.delay(5) }
        listening.cancel()
        // Every change, in the order made.
        assertEquals((1..100).map { listOf("v$it") }, got.toList())
    }

    /** A dispatcher whose work waits until the test runs it, in the order the test chooses. */
    private class ByHand : kotlinx.coroutines.CoroutineDispatcher() {
        val waiting = java.util.concurrent.LinkedBlockingDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            waiting.add(block)
        }

        fun await(count: Int) {
            val until = System.currentTimeMillis() + 5_000
            while (waiting.size < count) {
                check(System.currentTimeMillis() < until) { "only ${waiting.size} of $count" }
                Thread.sleep(1)
            }
        }

        fun runAll() {
            while (true) (waiting.pollFirst() ?: return).run()
        }
    }

    @Test
    fun aPageLetGoOfBeforeItsFileIsWrittenIsNotReadBackOld() = runBlocking {
        cache().put(spec("a") { emptyList() }, listOf("old"))
        val io = ByHand()
        val cache = PageCache(folder.root, scope, io, clock = { now }, budget = 100)
        // Anything the cache does on starting (a sweep) is left waiting too.
        val base = io.waiting.size
        // A newer copy is kept; its file waits to be written.
        val write = scope.launch { cache.put(spec("a") { emptyList() }, listOf("new")) }
        io.await(base + 1)
        // Another page, big enough that memory lets the first go.
        val other = scope.launch { cache.put(spec("b") { emptyList() }, List(150) { "s" }) }
        io.await(base + 2)
        // Asked for meanwhile: the old file must not come back as the page.
        val read = scope.async { cache.kept(spec("a") { emptyList() }) }
        // Whatever reading it needs is done before either write.
        val until = System.currentTimeMillis() + 2_000
        while (!read.isCompleted && System.currentTimeMillis() < until) {
            if (io.waiting.size > base + 2) io.waiting.pollLast()!!.run() else Thread.sleep(1)
        }
        io.runAll()
        val got = withTimeout(5_000) { read.await() }
        io.runAll()
        write.join()
        other.join()
        assertEquals(listOf("new"), got)
        // And on disk, for the next start. (Read by a cache of its own: this one's reads wait
        // for the test's hand.)
        assertEquals(listOf("new"), cache().kept(spec("a") { emptyList() }))
    }

    @Test
    fun whatAnInterruptedWriteLeftIsSweptAway() = runBlocking {
        val stray = java.io.File(folder.root, ".abc.json.1-2.tmp").apply { writeText("half") }
        val cache = cache()
        cache.put(spec { emptyList() }, listOf("a"))
        withTimeout(5_000) { while (stray.exists()) kotlinx.coroutines.delay(5) }
        assertTrue(!stray.exists())
    }

    @Test
    fun aLivePageThatMissesAValueBeforeItIsLetGoOfReadsItAgain() = runBlocking {
        // Room in memory for very little.
        val cache = PageCache(folder.root, scope, Dispatchers.IO, clock = { now }, budget = 10)
        val page = spec("a") { listOf("a") }
        cache.fresh(page)
        // The screen follows the page on a thread of its own, which the test can keep busy.
        val screen = java.util.concurrent.Executors.newSingleThreadExecutor()
        val seen = kotlinx.coroutines.channels.Channel<Page<List<String>>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val watching = scope.launch(screen.asCoroutineDispatcher()) { cache.live(page, freshFor = Long.MAX_VALUE).collect { seen.send(it) } }
        try {
            assertEquals(listOf("a"), withTimeout(5_000) { seen.receive() }.value)
            // The screen's thread is busy for a moment...
            val busy = java.util.concurrent.CountDownLatch(1)
            val holding = java.util.concurrent.CountDownLatch(1)
            screen.execute {
                holding.countDown()
                busy.await()
            }
            holding.await()
            // ...while the page changes (a like), and is then let go of from memory because
            // another, bigger page is kept.
            cache.update(page) { it + "b" }
            cache.fresh(spec("big") { List(20) { "x$it" } })
            assertEquals("the page was not let go of", 21L, cache.heldWeight())
            busy.countDown()
            // What the screen next sees is the page as it now is. It used to go from the old
            // value straight to the mark that the new one had been let go of, and so show the
            // old one for good.
            assertEquals(listOf("a", "b"), withTimeout(5_000) { seen.receive() }.value)
        } finally {
            watching.cancel()
            screen.shutdown()
        }
    }
}
