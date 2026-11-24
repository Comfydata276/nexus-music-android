package org.medianexus.music.library

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.ScanStatus
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Queue
import org.medianexus.music.playback.QueueFiles
import org.medianexus.music.store.PageCache

/**
 * What a library of tens of thousands of songs costs where the measurements didn't look: a
 * queue started from the Songs page, saved and read back, and a refresh of the copy.
 */
class MemoryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun song(n: Int) = Song(
        id = "song-%06d".format(n), title = "Title number $n of many", album = "Album ${n / 12}", albumId = "al${n / 12}",
        artist = "Artist ${n / 72}", artistId = "ar${n / 72}", track = n % 12 + 1, duration = 200 + n % 100, suffix = "mp3",
        created = "2026-01-%02dT10:00:%02dZ".format(n % 28 + 1, n % 60), genre = "Rock", size = 1_000_000L + n,
    )

    /** A server of `count` songs, a page at a time; `atLast` runs as the last page is asked for. */
    private class Server(val count: Int, val make: (Int) -> Song, val atLast: () -> Unit = {}) : LibraryServer {
        override suspend fun scanStatus() = ScanStatus(count = count.toLong(), lastScan = "x")
        override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int): Found {
            if (offset + songs >= count) atLast()
            return Found(
                songs = (offset until minOf(offset + songs, count)).map(make),
                albums = (offset until minOf(offset + albums, count / 12)).map { Album(id = "al$it", name = "Album $it") },
                artists = (offset until minOf(offset + artists, count / 72)).map { Artist(id = "ar$it", name = "Artist $it") },
            )
        }
    }

    private fun allocated(block: () -> Unit): Long {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        block()
        return threads.getThreadAllocatedBytes(id) - before
    }

    private fun copyOf(count: Int): LibraryCopy = runBlocking {
        LibraryCopy(folder.newFolder(), Server(count, ::song), Dispatchers.Unconfined, pageSize = 5_000).also { it.check() }
    }

    @Test
    fun aQueueFromTheSongsPageHoldsPlacesNotSongs() = runBlocking {
        val copy = copyOf(50_000)
        val library = Library(org.medianexus.music.net.Subsonic("http://127.0.0.1:9", "a", "b", okhttp3.OkHttpClient(), org.medianexus.music.net.OfflineSwitch(true)), PageCache(folder.newFolder(), kotlinx.coroutines.GlobalScope, Dispatchers.IO), copy = copy)
        library.keptSongs(Library.SONGS)
        val queue = Queue()
        // Started as the service starts it, and saved as the service saves it: the list as the
        // phone keeps it, the queue made of it, a copy of it for the writer.
        val bytes = allocated {
            val songs = runBlocking { library.keptSongs(Library.SONGS)!! }
            queue.replace(songs, 10, "Songs", source = Library.SONGS)
            queue.saved(0.0, 0)
        }
        // A song made of each is a few hundred bytes each, tens of megabytes (29 MB before);
        // what is left is numbers, and boxes for them made and let go as they are copied.
        assertTrue("starting and saving a queue of 50,000 allocated $bytes bytes", bytes < 12_000_000)
        assertEquals(copy.ready!!.songs.id(copy.ready!!.newestFirst[10]), queue.current()!!.id)
    }

    @Test
    fun aSavedQueueIsReadBackIntoATable() {
        val songs = (0 until 20_000).map(::song)
        val queue = Queue()
        queue.replace(songs, 3, "Long", source = "playlist:p", sourceList = songs.map { it.id })
        val files = QueueFiles.of(folder.newFolder())
        assertTrue(files.save(queue.saved(12.5, 1)))
        val back = files.load()!!
        // Not a list of 20,000 songs: a table of them (see PlacedSongs).
        assertEquals("PlacedSongs", back.songs.javaClass.simpleName)
        assertEquals(songs.map { it.id }, List(back.songs.size) { back.songs[it].id })
        assertEquals(songs[3], back.songs[3])
        assertEquals(3, back.cursor)
    }

    @Test
    fun aFetchLetsGoOfEachPageOnceItIsIn() {
        // Each page's songs as the server handed them over, watched: once a page is in the
        // copy, nothing may hold on to its songs (it used to keep every page's, as songs,
        // until the whole copy was put together at the end).
        val pages = ArrayList<java.lang.ref.WeakReference<List<Song>>>()
        var stillHeld = -1
        val server = object : LibraryServer {
            override suspend fun scanStatus() = ScanStatus(count = 20_000L, lastScan = "x")
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int): Found {
                if (offset >= 19_500) {
                    // The last page: everything before it is in.
                    repeat(4) { System.gc(); Thread.sleep(20) }
                    stillHeld = pages.count { it.get() != null }
                }
                val list = (offset until minOf(offset + songs, 20_000)).map(::song)
                pages += java.lang.ref.WeakReference(list)
                return Found(songs = list)
            }
        }
        runBlocking { LibraryCopy(folder.newFolder(), server, Dispatchers.Unconfined, pageSize = 500).check() }
        assertEquals("pages of songs still held as the last was asked for", 0, stillHeld)
    }

    @Test
    fun startingAndSavingAQueueFromTheCopyMakesSongsOnlyOfWhatPlays() = runBlocking {
        val copy = copyOf(20_000)
        val table = copy.ready!!.songs
        val library = Library(org.medianexus.music.net.Subsonic("http://127.0.0.1:9", "a", "b", okhttp3.OkHttpClient(), org.medianexus.music.net.OfflineSwitch(true)), PageCache(folder.newFolder(), kotlinx.coroutines.GlobalScope, Dispatchers.IO), copy = copy)
        val before = table.made
        val songs = library.keptSongs(Library.SONGS)!!
        val queue = Queue()
        queue.replace(songs, 10, "Songs", source = Library.SONGS)
        queue.current()
        val saved = queue.saved(0.0, 0)
        saved.shape()
        // The song playing, and nothing like a song for each of 20,000.
        assertTrue("songs made: ${table.made - before}", table.made - before < 10)
    }
}
