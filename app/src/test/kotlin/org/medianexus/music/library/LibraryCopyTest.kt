package org.medianexus.music.library

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Album
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Credit
import org.medianexus.music.net.Found
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.ScanStatus
import org.medianexus.music.net.Song
import org.medianexus.music.store.SongTable

class LibraryCopyTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    /** A server with `songs` songs (and an album and an artist for every ten), listing them
     *  a page at a time as Navidrome does to an empty search. `gate`, if set, holds the
     *  answer for that page until it is let go. */
    private class Server(var songs: Int, var scan: ScanStatus = ScanStatus(count = songs.toLong(), lastScan = "2026-11-24T10:00:00Z")) : LibraryServer {
        val pagesAsked = mutableListOf<Int>()
        val gates = HashMap<Int, CompletableDeferred<Unit>>()
        val reached = HashMap<Int, CompletableDeferred<Unit>>()
        var refuse: ApiError? = null

        override suspend fun scanStatus(): ScanStatus = refuse?.let { throw it } ?: scan

        override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int): Found {
            val page = offset / maxOf(artists, albums, songs, 1)
            pagesAsked += page
            reached.getOrPut(page) { CompletableDeferred() }.complete(Unit)
            gates[page]?.await()
            refuse?.let { throw it }
            fun range(n: Int, total: Int) = (offset until minOf(offset + n, total))
            return Found(
                artists = range(artists, this.songs / 10).map { Artist(id = "ar$it", name = "Artist $it") },
                albums = range(albums, this.songs / 10).map { Album(id = "al$it", name = "Album $it", artist = "Artist $it") },
                songs = range(songs, this.songs).map { song(it) },
            )
        }

        fun song(n: Int) = Song(id = "s$n", title = "Song $n", album = "Album ${n / 10}", albumId = "al${n / 10}", artist = "Artist ${n / 10}", created = "2026-01-01T00:00:%02dZ".format(n % 60), track = n % 10 + 1)
    }

    private fun copy(server: Server, dir: File = folder.root, now: Long = 10_000, unmetered: Boolean = true, kept: (Int) -> Unit = {}) =
        LibraryCopy(dir, server, Dispatchers.IO, now = { now }, unmetered = { unmetered }, pageSize = 10, pageKept = kept)

    @Test
    fun fetchesEveryPageAndKeepsTheCopy() = runBlocking {
        val server = Server(35)
        val copy = copy(server)
        assertTrue(copy.check())
        val snapshot = copy.state.value!!
        assertTrue(snapshot.complete)
        assertEquals(35, snapshot.songs.size)
        assertEquals((0 until 35).map { "s$it" }, (0 until 35).map { snapshot.songs.id(it) })
        assertEquals(3, snapshot.albums.size)
        // Asked a page at a time, until each kind came back short.
        assertEquals(listOf(0, 1, 2, 3), server.pagesAsked)
        // Read again from the phone, as it was.
        val again = copy(Server(0))
        again.open()
        assertEquals(35, again.state.value!!.songs.size)
        assertTrue(again.state.value!!.complete)
        // Found by search, with a slip.
        assertEquals("Song 17", again.ready!!.find("sogn 17", SearchLimits(), { false })!!.songs.first().title)
    }

    @Test
    fun killedMidFetchItCarriesOnWhereItStopped() = runBlocking {
        val server = Server(55)
        // Held at its fourth page: three are on the phone.
        val held = CompletableDeferred<Unit>()
        server.gates[3] = held
        val first = copy(server)
        val fetch = scope.async { first.check() }
        withTimeout(10_000) { server.reached.getOrPut(3) { CompletableDeferred() }.await() }
        // The app is killed: the fetch stops where it is.
        fetch.cancelAndJoin()
        // Started again, with the server's library as it was.
        server.pagesAsked.clear()
        server.gates.clear()
        val again = copy(server)
        again.open()
        // What had arrived is there to be shown at once, and isn't whole yet.
        val partial = again.state.value!!
        assertFalse(partial.complete)
        assertEquals(30, partial.songs.size)
        assertNull(again.ready)
        assertTrue(again.check())
        // Only what it hadn't got was asked for.
        assertEquals(listOf(3, 4, 5), server.pagesAsked)
        assertEquals((0 until 55).map { "s$it" }, (0 until 55).map { again.state.value!!.songs.id(it) })
        assertTrue(again.state.value!!.complete)
    }

    @Test
    fun aLibraryThatChangedWhileItWasStoppedIsFetchedFromNothing() = runBlocking {
        val server = Server(55)
        server.gates[3] = CompletableDeferred()
        val fetch = scope.async { copy(server).check() }
        withTimeout(10_000) { server.reached.getOrPut(3) { CompletableDeferred() }.await() }
        fetch.cancelAndJoin()
        // Meanwhile the server found five more songs.
        server.songs = 60
        server.scan = ScanStatus(count = 60, lastScan = "2026-11-24T11:00:00Z")
        server.gates.clear()
        server.pagesAsked.clear()
        val again = copy(server)
        assertTrue(again.check())
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), server.pagesAsked)
        assertEquals(60, again.state.value!!.songs.size)
    }

    @Test
    fun theFirstCopyIsShownAsItArrives() = runBlocking {
        val server = Server(55)
        val held = CompletableDeferred<Unit>()
        server.gates[5] = held
        val copy = LibraryCopy(folder.root, server, Dispatchers.IO, pageSize = 10)
        val fetch = scope.async { copy.check() }
        withTimeout(10_000) { server.reached.getOrPut(5) { CompletableDeferred() }.await() }
        // (Shown at most once a second: the fetch reached page five well within one, so what
        // is shown is whatever the first page that came after a second brought, or nothing.)
        assertEquals(50, copy.fetching.value?.songs)
        assertTrue(copy.fetching.value!!.first)
        held.complete(Unit)
        assertTrue(fetch.await())
        assertNull(copy.fetching.value)
        assertEquals(55, copy.ready!!.songs.size)
    }

    @Test
    fun aNewerCopyIsFetchedBesideTheOneInUse() = runBlocking {
        val server = Server(25)
        val copy = copy(server, now = 10_000)
        copy.check()
        val before = copy.state.value!!
        // The server found more songs: fetched again; the copy in use stays until the new one
        // is whole.
        server.songs = 30
        server.scan = ScanStatus(count = 30, lastScan = "2026-11-24T11:00:00Z")
        val held = CompletableDeferred<Unit>()
        server.gates[2] = held
        // (The first fetch reached its third page too.)
        server.reached.clear()
        val fetch = scope.async { copy.check() }
        withTimeout(10_000) { server.reached.getOrPut(2) { CompletableDeferred() }.await() }
        assertTrue(copy.state.value === before)
        assertTrue(copy.ready === before)
        assertFalse(copy.fetching.value!!.first)
        held.complete(Unit)
        fetch.await()
        assertEquals(30, copy.ready!!.songs.size)
    }

    @Test
    fun nothingIsFetchedWhileTheServerIsScanning() = runBlocking {
        val server = Server(15, ScanStatus(scanning = true, count = 3))
        val copy = copy(server)
        assertFalse(copy.check())
        assertTrue(server.pagesAsked.isEmpty())
        assertNull(copy.state.value)
        // A server that can't be reached: said, and nothing lost.
        server.refuse = ApiError.Network("(down)")
        assertTrue(runCatching { copy.check() }.exceptionOrNull() is ApiError.Network)
    }

    @Test
    fun aDamagedCopyIsFetchedAgain() = runBlocking {
        val server = Server(25)
        copy(server).check()
        val file = File(folder.root, "copy.bin")
        val bytes = file.readBytes()
        // One byte changed in the middle: the checksum doesn't add up.
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)
        val again = copy(server)
        again.open()
        assertNull(again.state.value)
        assertFalse(file.exists())
        server.pagesAsked.clear()
        assertTrue(again.check())
        assertEquals(listOf(0, 1, 2), server.pagesAsked)
        // Cut short: the same.
        file.writeBytes(file.readBytes().copyOf(100))
        val third = copy(server)
        third.open()
        assertNull(third.state.value)
    }

    @Test
    fun aDamagedPageIsFetchedAgainAndTheRestAfterIt() = runBlocking {
        val server = Server(55)
        server.gates[4] = CompletableDeferred()
        val fetch = scope.async { copy(server).check() }
        withTimeout(10_000) { server.reached.getOrPut(4) { CompletableDeferred() }.await() }
        fetch.cancelAndJoin()
        // The second page's file is damaged; the third and fourth are whole.
        val second = File(folder.root, "fetching/page-00001.bin")
        second.writeBytes(second.readBytes().copyOf(40))
        server.gates.clear()
        server.pagesAsked.clear()
        val again = copy(server)
        again.check()
        assertEquals(listOf(1, 2, 3, 4, 5), server.pagesAsked)
        assertEquals(55, again.ready!!.songs.size)
    }

    @Test
    fun theSameSongTwiceIsKeptOnce() = runBlocking {
        // A song came round on two pages (a server that lists one twice): it is one song, and
        // the copy is as many songs as the server says it has.
        val server = object : LibraryServer {
            override suspend fun scanStatus() = ScanStatus(count = 2)
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int) = when (offset) {
                0 -> Found(songs = listOf(Song(id = "a"), Song(id = "b")))
                else -> Found(songs = listOf(Song(id = "b")))
            }
        }
        val copy = LibraryCopy(folder.root, server, Dispatchers.IO, pageSize = 2)
        copy.check()
        assertEquals(listOf("a", "b"), (0 until copy.ready!!.songs.size).map { copy.ready!!.songs.id(it) })
    }

    @Test
    fun aServerThatListsNothingToAnEmptySearchIsNotACopy() = runBlocking {
        val server = object : LibraryServer {
            override suspend fun scanStatus() = ScanStatus(count = 400)
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int) = Found()
        }
        val copy = LibraryCopy(folder.root, server, Dispatchers.IO)
        copy.check()
        assertNotNull(copy.state.value)
        assertNull(copy.ready)
    }

    @Test
    fun whenTheCopyIsOutOfDate() {
        val copy = CopyStamp(count = 100, lastScan = "a", fetched = 1_000)
        fun stale(status: ScanStatus, now: Long, unmetered: Boolean = true) = LibraryCopy.isStale(copy, status, now, unmetered)
        // As it was: no.
        assertFalse(stale(ScanStatus(count = 100, lastScan = "a"), 1_001))
        // Another number of songs: at once.
        assertTrue(stale(ScanStatus(count = 101, lastScan = "a"), 1_001))
        // Scanning: wait for it to finish, whatever else.
        assertFalse(stale(ScanStatus(scanning = true, count = 101, lastScan = "b"), 999_999))
        // Scanned again with the same number: only once fifteen minutes have passed.
        assertFalse(stale(ScanStatus(count = 100, lastScan = "b"), 1_000 + LibraryCopy.RESCAN_AFTER))
        assertTrue(stale(ScanStatus(count = 100, lastScan = "b"), 1_001 + LibraryCopy.RESCAN_AFTER))
        // A day old: fetched afresh for the play counts, on a network that isn't metered.
        assertTrue(stale(ScanStatus(count = 100, lastScan = "a"), 1_001 + LibraryCopy.REFRESH_AFTER))
        assertFalse(stale(ScanStatus(count = 100, lastScan = "a"), 1_001 + LibraryCopy.REFRESH_AFTER, unmetered = false))
    }

    @Test
    fun aSongComesBackAsItWasKept() {
        val song = Song(
            id = "s1", title = "Lantern Season", album = "First Light", artist = "Paper Lanterns", albumId = "al1", artistId = "ar1",
            displayArtist = "Paper Lanterns feat. Juniper Vale", artists = listOf(Credit("ar1", "Paper Lanterns"), Credit("ar2", "Juniper Vale")),
            track = 3, discNumber = 1, year = 2017, coverArt = "mf-s1_abc", duration = 245, suffix = "flac", size = 31_234_567_890,
            bitRate = 1411, samplingRate = 48_000, bitDepth = 24, genre = "Folk", starred = "2026-03-01T10:15:00.123Z",
            created = "2024-03-09T10:15:00Z", playCount = 7, played = "2026-11-20T08:00:00.5Z", userRating = 4,
            replayGain = ReplayGain(trackGain = -6.2f, albumGain = -5.9f, trackPeak = 0.98f),
        )
        val plain = Song(id = "s2", title = "Untagged")
        val odd = Song(id = "s3", title = "Odd", created = "yesterday")
        val table = SongTable.Builder().apply { add(song); add(plain); add(odd) }.build()
        // Times in the standard form, to the millisecond; everything else as it was.
        assertEquals(song.copy(created = "2024-03-09T10:15:00Z", played = "2026-11-20T08:00:00.500Z"), table.song(0))
        assertEquals(plain, table.song(1))
        // A time that isn't one is kept as it was.
        assertEquals("yesterday", table.song(2).created)
        // Written and read back: the same.
        val packed = org.medianexus.music.store.Packer().also { table.write(it) }
        val back = SongTable.read(org.medianexus.music.store.Unpacker(packed.bytes, 0, packed.size))
        assertEquals((0 until 3).map { table.song(it) }, (0 until 3).map { back.song(it) })
        // Shared text is kept once: two songs of one album name it once.
        assertEquals("First Light", back.album(0))
        assertEquals("Paper Lanterns feat. Juniper Vale", back.artistLabel(0))
        assertEquals(0L, back.createdMillis(1))
    }

    @Test
    fun theSongsPageOrderIsNewestFirst() = runBlocking {
        val server = object : LibraryServer {
            override suspend fun scanStatus() = ScanStatus(count = 5)
            override suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int) = Found(
                songs = listOf(
                    Song(id = "old", album = "A", created = "2020-01-01T00:00:00Z"),
                    Song(id = "b2", album = "B", track = 2, created = "2026-01-01T00:00:00Z"),
                    Song(id = "b1", album = "B", track = 1, created = "2026-01-01T00:00:00Z"),
                    Song(id = "a1", album = "A", track = 1, created = "2026-01-01T00:00:00Z"),
                    Song(id = "none", album = "A"),
                ),
            )
        }
        val copy = LibraryCopy(folder.root, server, Dispatchers.IO)
        copy.check()
        val snap = copy.ready!!
        assertEquals(listOf("a1", "b1", "b2", "old", "none"), snap.newestFirst.map { snap.songs.id(it) })
    }

    @Test
    fun eachPageIsKeptAsItArrives() = runBlocking {
        val kept = AtomicInteger()
        val server = Server(25)
        copy(server, kept = { kept.incrementAndGet() }).check()
        assertEquals(3, kept.get())
        // Whole: the pages have gone, the copy is one file.
        assertFalse(File(folder.root, "fetching").exists())
        assertTrue(File(folder.root, "copy.bin").isFile)
    }
}
