package org.medianexus.music.store

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
}
