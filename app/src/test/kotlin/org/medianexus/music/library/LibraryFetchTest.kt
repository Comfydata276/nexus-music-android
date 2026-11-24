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
 * A fetch of the library a page at a time can't lose songs and still be taken for whole. The
 * server's library changing between pages (a song deleted before the page being read makes
 * one fall between two pages; one added repeats one and skips another) is caught by asking
 * the server how its library stands again once the last page is in, and by a song coming
 * round twice. Such a copy is used all the same, but stamped as out of date, so the next
 * ordinary check fetches it again.
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
    fun aSongDeletedBetweenPagesGivesACopyThatIsUsedAndFetchedAgain() = runBlocking {
        val server = Server(thirty)
        // After the first page, a song on it is deleted: s10 moves up onto page one, which
        // has been read, and the second page starts at s11. Fetched as it stood, s10 is lost.
        server.served = { page -> if (page == 0 && server.scans == 0) server.change { remove("s03") } }
        val copy = copy(server)
        assertTrue(copy.check())
        // Used (there is no better), but marked as out of date.
        val first = copy.ready!!
        assertEquals(thirty - "s10", ids(first))
        assertEquals(LibraryCopy.UNSETTLED, first.stamp.count)
        assertTrue(LibraryCopy.isStale(first.stamp, server.scanStatus(), 10_000, unmetered = false))
        // The next ordinary check fetches it again, and the library has stood still since.
        server.pagesAsked.clear()
        assertTrue(copy.check())
        assertEquals(listOf(0, 1, 2), server.pagesAsked)
        val clean = copy.ready!!
        assertEquals(thirty - "s03", ids(clean))
        assertEquals(29L, clean.stamp.count)
        assertEquals("scan-1", clean.stamp.lastScan)
        // And then not again; what is on the phone is the clean copy.
        assertFalse(copy.check())
        assertEquals(29L, CopyFile.read(File(folder.root, "copy.bin"))!!.stamp.count)
    }

    @Test
    fun aSongAddedBetweenPagesIsCaughtByTheSongThatCameTwice() = runBlocking {
        val server = Server(thirty)
        // A song added near the top, and the scan status (wrongly) not moved: s09 is pushed
        // onto the second page and comes round twice, and "new" is never seen.
        server.says = 30
        server.served = { page -> if (page == 0 && server.ids.size == 30) server.ids.add(1, "new") }
        val copy = copy(server)
        assertTrue(copy.check())
        assertEquals(thirty, ids(copy.ready))
        assertEquals(LibraryCopy.UNSETTLED, copy.ready!!.stamp.count)
        server.served = {}
        server.says = null
        assertTrue(copy.check())
        assertEquals(listOf("s00", "new") + thirty.drop(1), ids(copy.ready))
        assertEquals(31L, copy.ready!!.stamp.count)
    }

    @Test
    fun aQuietLibraryIsFetchedOnce() = runBlocking {
        val server = Server(thirty)
        val copy = copy(server)
        assertTrue(copy.check())
        assertEquals(thirty, ids(copy.ready))
        assertEquals(30L, copy.ready!!.stamp.count)
        assertFalse(copy.check())
        assertFalse(copy.check())
        assertEquals(listOf(0, 1, 2, 3), server.pagesAsked)
    }

    @Test
    fun nothingIsFetchedWhileTheServerIsScanning() = runBlocking {
        val server = Server(thirty)
        server.scanning = true
        val copy = copy(server)
        assertFalse(copy.check())
        assertFalse(copy.check())
        assertTrue(server.pagesAsked.isEmpty())
        // A copy marked out of date waits for the scan to finish too.
        server.scanning = false
        server.served = { page -> if (page == 0 && server.scans == 0) server.change { } }
        assertTrue(copy.check())
        server.served = {}
        server.scanning = true
        server.pagesAsked.clear()
        assertFalse(copy.check())
        assertTrue(server.pagesAsked.isEmpty())
        server.scanning = false
        assertTrue(copy.check())
    }

    @Test
    fun aServerThatCantBeAskedAgainGivesACopyMarkedOutOfDate() = runBlocking {
        var asked = 0
        val server = object : LibraryServer {
            override suspend fun scanStatus(): ScanStatus {
                if (++asked == 2) throw org.medianexus.music.net.ApiError.Network("(gone)")
                return ScanStatus(count = 3, lastScan = "a")
            }
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int) =
                Found(songs = listOf("a", "b", "c").drop(offset).take(songs).map { Song(id = it) })
        }
        val copy = copy(server)
        assertTrue(copy.check())
        assertEquals(listOf("a", "b", "c"), ids(copy.ready))
        assertEquals(LibraryCopy.UNSETTLED, copy.ready!!.stamp.count)
    }

    @Test
    fun aServerThatGivesTheSamePageForEverIsStopped() = runBlocking {
        val pages = java.util.concurrent.atomic.AtomicInteger()
        val server = object : LibraryServer {
            override suspend fun scanStatus() = ScanStatus(count = 10, lastScan = "a")
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int): Found {
                pages.incrementAndGet()
                return Found(songs = (0 until 10).map { Song(id = "s$it") })
            }
        }
        val copy = copy(server)
        assertTrue(copy.check())
        assertEquals(2, pages.get())
        assertEquals(10, copy.ready!!.songs.size)
        assertEquals(LibraryCopy.UNSETTLED, copy.ready!!.stamp.count)
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
