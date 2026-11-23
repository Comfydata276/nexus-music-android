package org.medianexus.music.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
        val pages = AlbumPages(4) { page(it) }
        pages.first(page(0))
        pages.more()
        pages.more()
        assertEquals((0 until 10).map { "a$it" }, ids(pages))
        assertTrue(pages.ended)
    }

    @Test
    fun anAlbumAddedAtAPageBoundaryIsNotShownTwice() = runBlocking {
        val pages = AlbumPages(4) { page(it) }
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
        val pages = AlbumPages(4) { offset -> if (slow) gate.await(); page(offset) }
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
        val pages = AlbumPages(4) { offset -> if (fail) throw ApiError.Network("Gone.") else page(offset) }
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
        val pages = AlbumPages(4) { page(it) }
        pages.first(page(0))
        pages.more()
        // The kept first page is followed by the server's, which has moved on by one.
        library = listOf(Album(id = "new")) + library
        pages.first(page(0))
        assertEquals(listOf("new", "a0", "a1", "a2", "a4", "a5", "a6", "a7").take(3), ids(pages).take(3))
        assertEquals(ids(pages).size, ids(pages).toSet().size)
    }
}
