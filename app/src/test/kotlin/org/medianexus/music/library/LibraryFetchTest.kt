package org.medianexus.music.library

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Found
import org.medianexus.music.net.ScanStatus
import org.medianexus.music.net.Song

/**
 * A fetch of the library a page at a time can't lose songs and still call the copy whole.
 * The server's library changing between pages (a song deleted before the page being read
 * makes one fall between two pages; one added repeats one and skips another) is caught by
 * asking the server how its library stands again once the last page is in, and by counting
 * the songs against the number the server gives; a copy that fails either is not published.
 */
class LibraryFetchTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    /** A server listing `ids` (in that order) a page at a time, as Navidrome lists them to an
     *  empty search. `served(page)` runs once each page has been answered: where a test
     *  changes the library, as a scan would between two of the phone's requests. */
    private class Server(ids: List<String>) : LibraryServer {
        val ids = ids.toMutableList()
        var scans = 0
        var scanning = false
        /** The number of songs the server says it has; null: the true number. */
        var says: Long? = null
        val pagesAsked = mutableListOf<Int>()
        var served: (Int) -> Unit = {}
        val gates = HashMap<Int, CompletableDeferred<Unit>>()
        val reached = HashMap<Int, CompletableDeferred<Unit>>()

        override suspend fun scanStatus() = ScanStatus(scanning = scanning, count = says ?: ids.size.toLong(), lastScan = "scan-$scans")

        override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int): Found {
            val page = offset / maxOf(artists, albums, songs, 1)
            pagesAsked += page
            reached.getOrPut(page) { CompletableDeferred() }.complete(Unit)
            gates[page]?.await()
            val found = Found(songs = ids.drop(offset).take(songs).map { Song(id = it, title = "Song $it") })
            served(page)
            return found
        }

        /** The library changes, and a scan reads it: its stamp moves on. */
        fun change(block: MutableList<String>.() -> Unit) {
            ids.block()
            scans++
        }
    }

    private fun copy(server: LibraryServer, dir: File = folder.root, notes: MutableList<String> = mutableListOf()) =
        LibraryCopy(dir, server, Dispatchers.IO, now = { 10_000 }, note = { notes += it }, pageSize = 10)

    private fun ids(copy: LibrarySnapshot?) = copy?.let { c -> (0 until c.songs.size).map { c.songs.id(it) } }

    private val thirty = (0 until 30).map { "s%02d".format(it) }

    @Test
    fun aSongDeletedBeforeThePageBeingReadIsNotLost() = runBlocking {
        val server = Server(thirty)
        // After the first page, a song on it is deleted: s10 moves up onto page one, which
        // has been read, and the second page starts at s11. Fetched as it stood, s10 is lost.
        server.served = { page -> if (page == 0 && server.scans == 0) server.change { remove("s03") } }
        val copy = copy(server)
        assertTrue(copy.check())
        val snap = copy.ready!!
        assertEquals(thirty - "s03", ids(snap))
        assertEquals(29L, snap.stamp.count)
        assertEquals("scan-1", snap.stamp.lastScan)
    }

    @Test
    fun aSongAddedBeforeThePageBeingReadIsNotLostEither() = runBlocking {
        val server = Server(thirty)
        // A song added near the top: s09 is pushed onto page two and comes round twice, and
        // s29 is pushed off the end of the last page read and never comes at all.
        server.served = { page -> if (page == 0 && server.scans == 0) server.change { add(1, "new") } }
        val copy = copy(server)
        assertTrue(copy.check())
        assertEquals(listOf("s00", "new") + thirty.drop(1), ids(copy.ready))
    }

    @Test
    fun aCopyShortOfTheServersCountIsNotPublished() = runBlocking {
        val server = Server(thirty)
        val notes = mutableListOf<String>()
        val copy = copy(server, notes = notes)
        assertTrue(copy.check())
        val good = copy.ready!!
        // A scan, after which the server says 31 songs but lists 30 (one that can't be listed,
        // or a page that came back short): a fetch that doesn't add up.
        server.scans++
        server.says = 31
        server.pagesAsked.clear()
        assertFalse(copy.check())
        // Tried a few times, then left for later; the copy in use is the last good one.
        assertTrue(server.pagesAsked.count { it == 0 } in 2..4)
        assertTrue(copy.ready === good)
        assertTrue(copy.state.value === good)
        assertTrue(notes.any { "not whole" in it })
        // Nor kept on the phone: read again, it is the good copy.
        val again = copy(server, dir = folder.root)
        again.open()
        assertEquals(thirty, ids(again.state.value))
    }

    @Test
    fun theFirstCopyIsNotCalledWholeWhenItIsnt() = runBlocking {
        val server = Server(thirty)
        server.says = 33
        val copy = copy(server)
        assertFalse(copy.check())
        assertNull(copy.ready)
        assertFalse(copy.state.value?.complete ?: false)
    }

    @Test
    fun aLibraryThatKeepsChangingIsLeftForLater() = runBlocking {
        val server = Server(thirty)
        // A scan after every page: no fetch can be trusted.
        server.served = { server.change { } }
        val copy = copy(server)
        assertFalse(copy.check())
        assertNull(copy.ready)
        // Quiet again: the next check fetches it.
        server.served = {}
        assertTrue(copy.check())
        assertEquals(thirty, ids(copy.ready))
    }

    @Test
    fun aResumedFetchUsesOnlyPagesOfTheSameScan() = runBlocking {
        val server = Server(thirty)
        server.gates[2] = CompletableDeferred()
        val fetch = scope.async { copy(server).check() }
        withTimeout(10_000) { server.reached.getOrPut(2) { CompletableDeferred() }.await() }
        fetch.cancelAndJoin()
        // The pages kept were read under one scan; the note of what the fetch began with now
        // names another (written over, and the pages left behind: the folder couldn't be
        // emptied, say). The library under the new scan is different.
        server.change { set(5, "x05") }
        CopyFile.writeBegan(File(folder.root, "fetching/began.bin"), CopyStamp(30, "scan-1", 9_000), 10)
        server.gates.clear()
        server.pagesAsked.clear()
        val again = copy(server)
        assertTrue(again.check())
        // Every page fetched again: none of those kept was of this scan.
        assertEquals(listOf(0, 1, 2, 3), server.pagesAsked)
        assertEquals(thirty.toMutableList().also { it[5] = "x05" }, ids(again.ready))
    }

    @Test
    fun aCopyAndroidClearedIsWrittenAgainFromMemory() = runBlocking {
        val server = Server(thirty)
        val copy = copy(server)
        assertTrue(copy.check())
        val file = File(folder.root, "copy.bin")
        // Android clears the cache folder while the app runs.
        folder.root.listFiles()!!.forEach { it.deleteRecursively() }
        server.pagesAsked.clear()
        assertFalse(copy.check())
        // Nothing fetched: written again from what is in memory.
        assertTrue(server.pagesAsked.isEmpty())
        assertTrue(file.isFile)
        val back = copy(server)
        back.open()
        assertEquals(thirty, ids(back.ready))
    }

    @Test
    fun aCopyAndroidClearedWhileTheAppWasStoppedIsFetchedAgain() = runBlocking {
        val server = Server(thirty)
        assertTrue(copy(server).check())
        folder.root.listFiles()!!.forEach { it.deleteRecursively() }
        server.pagesAsked.clear()
        // Started again: the server's library is as it was, but there is no copy.
        val again = copy(server)
        assertTrue(again.check())
        assertEquals(listOf(0, 1, 2, 3), server.pagesAsked)
        assertEquals(thirty, ids(again.ready))
    }
}
