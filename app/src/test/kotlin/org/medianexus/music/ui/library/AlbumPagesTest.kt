package org.medianexus.music.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.ApiError

class AlbumPagesTest {
    /** The server's albums, "a0", "a1", … as the test sets them. */
    private var library = (0 until 10).map { Album(id = "a$it") }

    private fun page(offset: Int, size: Int = 4) = library.drop(offset).take(size)

    private fun ids(pages: AlbumPages) = pages.albums.map { it.id }

    @Test
    fun pagesFollowOneAnotherToTheEnd() = runBlocking {
        val pages = AlbumPages(4) { offset, _ -> page(offset) }
        pages.first(page(0))
        pages.more()
        pages.more()
        assertEquals((0 until 10).map { "a$it" }, ids(pages))
        assertTrue(pages.ended)
    }

    @Test
    fun anAlbumAddedAtAPageBoundaryIsNotShownTwice() = runBlocking {
        val pages = AlbumPages(4) { offset, _ -> page(offset) }
        pages.first(page(0))
        // A new album arrives at the top: every later page shifts along by one, so the next
        // page starts with the last one already shown.
        library = listOf(Album(id = "new")) + library
        pages.more()
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4", "a5", "a6"), ids(pages))
        assertEquals(ids(pages).size, ids(pages).toSet().size)
    }

    @Test
    fun aRefreshDuringAnAppendDropsTheAppend() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var slow = true
        val pages = AlbumPages(4) { offset, _ -> if (slow) gate.await(); page(offset) }
        pages.first(page(0))
        val append = async(start = CoroutineStart.UNDISPATCHED) { pages.more() }
        assertTrue(pages.asking)
        // Pulled down meanwhile: what's shown stays until the new first page arrives.
        pages.refresh()
        assertEquals(listOf("a0", "a1", "a2", "a3"), ids(pages))
        library = listOf(Album(id = "new")) + library
        pages.first(page(0))
        assertEquals(listOf("new", "a0", "a1", "a2"), ids(pages))
        // The old append comes back: it belonged to the list before the refresh.
        gate.complete(Unit)
        append.await()
        assertEquals(listOf("new", "a0", "a1", "a2"), ids(pages))
        // The next append is of the new list.
        slow = false
        pages.more()
        assertEquals(listOf("new", "a0", "a1", "a2", "a3", "a4", "a5", "a6"), ids(pages))
    }

    @Test
    fun aFailedAppendIsSaidAndTriedAgain() = runBlocking {
        var fail = true
        val pages = AlbumPages(4) { offset, _ -> if (fail) throw ApiError.Network("Gone.") else page(offset) }
        pages.first(page(0))
        pages.more()
        assertNotNull(pages.failed)
        assertEquals(4, pages.albums.size)
        // Not asked again by scrolling while it's showing the failure.
        fail = false
        pages.more()
        assertEquals(4, pages.albums.size)
        // "Try again".
        pages.retry()
        assertNull(pages.failed)
        assertEquals(8, pages.albums.size)
    }

    @Test
    fun aNewerFirstPageKeepsTheRestWithoutRepeatingAny() = runBlocking {
        val pages = AlbumPages(4) { offset, _ -> page(offset) }
        pages.first(page(0))
        pages.more()
        // The kept first page is followed by the server's, which has moved on by one.
        library = listOf(Album(id = "new")) + library
        pages.first(page(0))
        assertEquals(listOf("new", "a0", "a1", "a2", "a4", "a5", "a6", "a7").take(3), ids(pages).take(3))
        assertEquals(ids(pages).size, ids(pages).toSet().size)
    }
    @Test
    fun aPageCancelledOnItsWayCanBeAskedForAgain() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var slow = true
        val pages = AlbumPages(4) { offset, _ -> if (slow) gate.await(); page(offset) }
        pages.first(page(0))
        // The job asking for the next page is cancelled (a fresh first page restarted it).
        val append = launch(start = CoroutineStart.UNDISPATCHED) { pages.more() }
        assertTrue(pages.asking)
        append.cancel()
        append.join()
        assertTrue(!pages.asking)
        slow = false
        pages.more()
        assertEquals((0 until 8).map { "a$it" }, ids(pages))
    }

    @Test
    fun aRefreshLeavesTheGridAsItIsUntilTheServersFirstPageComes() = runBlocking {
        val pages = AlbumPages(4) { offset, _ -> page(offset) }
        pages.first(page(0))
        pages.more()
        assertEquals(8, pages.albums.size)
        pages.refresh()
        // The phone's copy of the first page comes first: what's on screen stays.
        pages.first(page(0), fresh = false)
        assertEquals(8, pages.albums.size)
        // The server's: the list starts afresh from it.
        library = listOf(Album(id = "new")) + library
        pages.first(page(0), fresh = true)
        assertEquals(listOf("new", "a0", "a1", "a2"), ids(pages))
        pages.more()
        assertEquals(listOf("new", "a0", "a1", "a2", "a3", "a4", "a5", "a6"), ids(pages))
    }

    @Test
    fun aPageOfAlbumsAlreadyShownStillLetsLoadingCarryOn() = runBlocking {
        val pages = AlbumPages(4) { offset, _ -> page(offset) }
        pages.first(page(0))
        // Four new albums at the top: the next page is the four already shown.
        library = (1..4).map { Album(id = "new$it") } + library
        val before = pages.fetched
        assertTrue(pages.more())
        assertEquals(4, pages.albums.size)
        // It counts as progress, and what waits for more goes on to the pages after it.
        assertEquals(before + 1, pages.fetched)
        pages.loadAll()
        assertEquals((0 until 10).map { "a$it" }, ids(pages))
        assertTrue(pages.ended)
    }

    @Test
    fun comingBackLoadsAsFarAsItHadGot() = runBlocking {
        val pages = AlbumPages(4) { offset, _ -> page(offset) }
        pages.first(page(0))
        pages.loadUntil(9)
        assertEquals(10, pages.albums.size)
    }

    @Test
    fun aLaterPageFromThePhonesCopyIsPutRightByTheServers() = runBlocking {
        var tell: ((List<Album>) -> Unit)? = null
        val pages = AlbumPages(4) { offset, changed -> if (offset == 4) tell = changed; page(offset) }
        pages.first(page(0))
        pages.more()
        assertEquals((0 until 8).map { "a$it" }, ids(pages))
        // The server's copy of the second page differs from the phone's.
        tell!!(listOf(Album(id = "a4"), Album(id = "renamed"), Album(id = "a6"), Album(id = "a7")))
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4", "renamed", "a6", "a7"), ids(pages))
        // One that arrives after a refresh belongs to a list that has gone.
        pages.refresh()
        pages.first(page(0))
        tell!!(listOf(Album(id = "stale")))
        assertEquals((0 until 4).map { "a$it" }, ids(pages))
    }
}
