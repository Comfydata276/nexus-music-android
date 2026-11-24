package org.medianexus.music.playback

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.downloads.DownloadedList
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.library.Availability
import org.medianexus.music.library.Library
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.PageCache

/**
 * The car, started cold with no signal, on the phone's worst day: **no page is kept at all**
 * (Android clears the kept pages when it wants the room; the downloads are elsewhere and
 * stay), and the server is a black hole: it takes the connection and never answers. Not
 * offline mode, where a request fails at once by the app's own switch: here a request that is
 * made hangs until it times out.
 *
 * So what these tests hold the tree to is not "it answered eventually" but **the server was
 * never asked**: every tab, the search and a spoken request are answered from the downloads'
 * own record, and the black hole sees no connection.
 */
class CarColdTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var library: Library
    private lateinit var kept: KeptIndex

    /** Takes connections, answers nothing, and counts them. */
    private val hole = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val taken: MutableList<Socket> = Collections.synchronizedList(mutableListOf())

    @Volatile private var offline = true
    @Volatile private var unsure = false

    private fun song(n: Int) = Song(id = "s$n", title = "Song $n", album = "First Light", albumId = "al-1", artist = "Paper Lanterns", artistId = "ar-1", track = n, suffix = "flac", genre = "Folk", coverArt = "al-1")

    @Before
    fun start() {
        Thread {
            while (true) taken += try { hole.accept() } catch (_: java.io.IOException) { break }
        }.apply { isDaemon = true }.start()
        catalogue = Catalogue(folder.newFolder("downloads"))
        kept = KeptIndex(File(folder.root, "kept.json"), scope, Dispatchers.IO)
        // Not offline mode: the client believes the server is there.
        val api = Subsonic("http://127.0.0.1:${hole.localPort}", "ada", "sesame", OkHttpClient())
        library = Library(api, PageCache(folder.newFolder("pages"), scope, Dispatchers.IO))
        // Downloaded: songs 2 and 4 of the album; the playlist "Mix" whole (songs 4 and 1, of
        // which 4 has arrived); Liked songs whole (song 2).
        for (n in listOf(2, 4)) {
            File(catalogue.dir, "s$n.flac").writeBytes(ByteArray(4))
            catalogue.add(Downloaded(song(n), "s$n.flac", 4, at = n.toLong(), suffix = "flac"))
        }
        catalogue.keep(DownloadedList("playlist:p1", "Mix", songs = listOf("s4", "s1")))
        catalogue.keep(DownloadedList("liked", "Liked songs", songs = listOf("s2")))
    }

    @After
    fun stop() {
        scope.cancel()
        hole.close()
        synchronized(taken) { taken.forEach { runCatching { it.close() } } }
    }

    private fun tree(): CarTree {
        val availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true })
        val state = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) }
        return CarTree(library, CarWords(), CarOffline(now = { offline }, availability = availability, downloads = state, unsure = { unsure }))
    }

    /** Everything in here must be answered from the phone: generously timed (a request made
     *  of the black hole would hang for many seconds), and held to no connection at all. */
    private fun <T> fromThePhone(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }.also {
        assertEquals("the server was asked", 0, taken.size)
    }

    /** Answered without waiting on the server: within a couple of seconds, where a wait on
     *  the black hole would be the better part of a minute. (Not held to "the server was not
     *  asked": while it isn't known whether the server is there, it is asked behind the
     *  answer, to fill it in.) */
    private fun <T> atOnce(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    private fun titles(entries: List<CarEntry>?) = entries!!.map { it.title }

    @Test
    fun offlineEveryTabAnswersFromThePhoneWithNoPageKept() {
        val tree = tree()
        // Home: Liked songs (it has something that plays), and the albums that play.
        assertEquals(listOf("Liked songs", "First Light"), fromThePhone { titles(tree.children(CarNode.Home)) })
        // Playlists: the downloaded one is listed, though the server's list of playlists
        // isn't on the phone.
        assertEquals(listOf("Mix"), fromThePhone { titles(tree.children(CarNode.Playlists)) })
        assertEquals(listOf("Shuffle", "Song 4"), fromThePhone { titles(tree.children(CarNode.Playlist("p1"))) })
        assertEquals(listOf("Shuffle", "Song 2"), fromThePhone { titles(tree.children(CarNode.Liked)) })
        // Library.
        assertEquals(listOf("Albums", "Artists", "Genres"), fromThePhone { titles(tree.children(CarNode.Library)) })
        assertEquals(listOf("First Light"), fromThePhone { titles(tree.children(CarNode.Albums)) })
        assertEquals(listOf("Paper Lanterns"), fromThePhone { titles(tree.children(CarNode.Artists)) })
        assertEquals(listOf("Folk"), fromThePhone { titles(tree.children(CarNode.Genres)) })
        assertEquals(listOf("Shuffle", "Song 2", "Song 4"), fromThePhone { titles(tree.children(CarNode.Album("al-1"))) })
        assertEquals(listOf("First Light"), fromThePhone { titles(tree.children(CarNode.Artist("ar-1"))) })
        assertEquals(listOf("First Light"), fromThePhone { titles(tree.children(CarNode.Genre("Folk"))) })
        // A list of albums the phone has no copy of (Recently added, Recently played, Most
        // played): the albums on the phone, at once. (It was empty.)
        for (kind in listOf("newest", "recent", "frequent")) {
            assertEquals(listOf("First Light"), fromThePhone { titles(tree.children(CarNode.AlbumsBy(kind))) })
        }
        // Downloads.
        assertEquals(listOf("Songs", "Liked songs", "Mix", "First Light"), fromThePhone { titles(tree.children(CarNode.Downloads)) })
        // Each place on its own, as a car asks for one by id.
        assertEquals("First Light", fromThePhone { tree.item(CarNode.Album("al-1")) }!!.title)
        assertEquals("Mix", fromThePhone { tree.item(CarNode.Playlist("p1")) }!!.title)
        assertEquals("Paper Lanterns", fromThePhone { tree.item(CarNode.Artist("ar-1")) }!!.title)
    }

    @Test
    fun offlineChoosingSearchingAndASpokenRequestNeverAskTheServer() {
        val tree = tree()
        // A song chosen from the playlist plays the playlist, as it is on the phone.
        val row = fromThePhone { tree.children(CarNode.Playlist("p1")) }!![1]
        val chosen = fromThePhone { tree.play(CarIds.parse(row.id) as CarNode.Track) }!!
        assertEquals("playlist:p1", chosen.source)
        assertTrue(chosen.songs.any { it.id == "s4" })
        assertNotNull(fromThePhone { tree.shuffled(ListRef.Liked) })
        // Search.
        val found = fromThePhone { tree.search("song 4") }
        assertTrue(found.any { it.title == "Song 4" })
        assertTrue(fromThePhone { tree.search("mix") }.any { it.title == "Mix" })
        // "Play Mix", "play First Light", "play some music".
        assertEquals("playlist:p1", fromThePhone { tree.forVoice(VoiceRequest(query = "Mix")) }!!.source)
        assertEquals("album:al-1", fromThePhone { tree.forVoice(VoiceRequest(query = "First Light")) }!!.source)
        assertEquals(listOf("s2"), fromThePhone { tree.shuffledLiked() }!!.songs.map { it.id })
    }

    @Test
    fun aKeptPageWithNothingThatPlaysGivesWayToTheDownloadsOwnRecord() {
        // The phone has copies of these pages from before the server read its library again
        // and gave the songs other ids: every song in them is one the phone doesn't have.
        fun old(n: Int) = song(n).copy(id = "old-$n")
        kotlinx.coroutines.runBlocking {
            library.pages.put(library.album("al-1"), org.medianexus.music.net.Album(id = "al-1", name = "First Light", artist = "Paper Lanterns", songs = (1..4).map(::old)))
            library.pages.put(library.playlist("p1"), org.medianexus.music.net.Playlist(id = "p1", name = "Mix", songs = listOf(old(4), old(1))))
            library.pages.put(library.liked(), org.medianexus.music.net.Found(songs = listOf(old(2))))
        }
        val tree = tree()
        // Listed, and not empty when opened: what is downloaded of each, from the downloads.
        assertEquals(listOf("Shuffle", "Song 2", "Song 4"), fromThePhone { titles(tree.children(CarNode.Album("al-1"))) })
        assertEquals(listOf("Shuffle", "Song 4"), fromThePhone { titles(tree.children(CarNode.Playlist("p1"))) })
        assertEquals(listOf("Shuffle", "Song 2"), fromThePhone { titles(tree.children(CarNode.Liked)) })
        assertTrue("Liked songs" in fromThePhone { titles(tree.children(CarNode.Home)) })
    }

    @Test
    fun beforeItIsKnownWhetherTheServerIsThereWhatIsOnThePhoneIsNotWaitedFor() {
        // The app has just started, the phone has a network of some kind, and the server
        // hasn't answered yet (it never will). Not offline: unsure.
        offline = false
        unsure = true
        val tree = tree()
        assertEquals(listOf("Mix"), atOnce { titles(tree.children(CarNode.Playlists)) })
        assertEquals(listOf("Shuffle", "Song 4"), atOnce { titles(tree.children(CarNode.Playlist("p1"))) })
        assertEquals(listOf("Shuffle", "Song 2"), atOnce { titles(tree.children(CarNode.Liked)) })
        assertEquals(listOf("Shuffle", "Song 2", "Song 4"), atOnce { titles(tree.children(CarNode.Album("al-1"))) })
        assertEquals(listOf("Songs", "Liked songs", "Mix", "First Light"), atOnce { titles(tree.children(CarNode.Downloads)) })
        assertTrue(atOnce { tree.search("song 4") }.any { it.title == "Song 4" })
        assertEquals("album:al-1", atOnce { tree.forVoice(VoiceRequest(query = "First Light")) }!!.source)
        // Something that isn't on the phone waits only to know whether the server is there
        // (here: it turns out not to be, when the test says so), and is then answered from
        // the phone, at once and with nothing: the server itself is never waited on.
        val known = kotlinx.coroutines.CompletableDeferred<Unit>()
        val tree2 = CarTree(library, CarWords(), CarOffline(
            now = { offline }, availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true }),
            downloads = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) },
            unsure = { unsure }, settle = { known.await() },
        ))
        val search = scope.async { tree2.search("nothing of the kind") }
        val folder = scope.async { tree2.children(CarNode.Playlist("not-downloaded")) }
        // The first check fails: out of reach.
        offline = true
        unsure = false
        known.complete(Unit)
        assertEquals(emptyList<CarEntry>(), atOnce { search.await() })
        assertEquals(emptyList<CarEntry>(), atOnce { folder.await() })
    }

    // ---- Cold, on a link that swallows packets (offline hardening 2, item 6) -----------------

    /** A tree for which it is never known whether the server is there: anything that waits
     *  to know, waits for ever. */
    private fun neverKnown(): CarTree {
        offline = false
        unsure = true
        return CarTree(library, CarWords(), CarOffline(
            now = { offline }, availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true }),
            downloads = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) },
            unsure = { unsure }, settle = { kotlinx.coroutines.awaitCancellation() },
        ))
    }

    @Test
    fun homeShowsWhatIsKeptWithoutWaitingForThePagesThatAreNot() {
        // Liked songs is kept; the two rows of albums are not.
        runBlocking { library.pages.put(library.liked(), org.medianexus.music.net.Found(songs = listOf(song(1), song(2), song(3)))) }
        val tree = neverKnown()
        // (It waited for all three if any one was not kept.) Liked songs as it is kept, the
        // whole of it; and, neither row of albums being kept, the albums the phone has.
        val home = atOnce { tree.children(CarNode.Home) }!!
        assertEquals(listOf("Liked songs", "First Light"), home.map { it.title })
        assertEquals("3 songs", home.first().subtitle)
        // A row that is kept is shown as it is.
        runBlocking { library.pages.put(library.albums(org.medianexus.music.net.AlbumList.Recent, 0, CarTree.HOME_ROW), listOf(org.medianexus.music.net.Album(id = "al-9", name = "Slow Rivers"))) }
        assertEquals(listOf("Liked songs", "Slow Rivers"), atOnce { titles(tree.children(CarNode.Home)) })
        // With none of the three kept: what the phone has by itself.
        runBlocking {
            library.pages.forget(library.liked().key)
            library.pages.forget(library.albums(org.medianexus.music.net.AlbumList.Recent, 0, CarTree.HOME_ROW).key)
        }
        assertEquals(listOf("Liked songs", "First Light"), atOnce { titles(neverKnown().children(CarNode.Home)) })
    }

    @Test
    fun theArtistAndGenreBranchesAnswerFromThePhoneWhileItIsNotKnown() {
        val tree = neverKnown()
        // No page of any of these is kept: each used to go straight to the server.
        assertEquals(listOf("Paper Lanterns"), atOnce { titles(tree.children(CarNode.Artists)) })
        assertEquals(listOf("Folk"), atOnce { titles(tree.children(CarNode.Genres)) })
        assertEquals(listOf("First Light"), atOnce { titles(tree.children(CarNode.Artist("ar-1"))) })
        assertEquals(listOf("First Light"), atOnce { titles(tree.children(CarNode.Genre("Folk"))) })
        assertEquals("Paper Lanterns", atOnce { tree.item(CarNode.Artist("ar-1")) }!!.title)
        assertEquals("First Light", atOnce { tree.item(CarNode.Album("al-1")) }!!.title)
        // A kept page is shown as it is (the whole of it, as when online), also at once.
        runBlocking {
            library.pages.put(library.artists(), listOf(org.medianexus.music.net.Artist(id = "ar-1", name = "Paper Lanterns"), org.medianexus.music.net.Artist(id = "ar-2", name = "Sable Arcade")))
        }
        assertEquals(listOf("Paper Lanterns", "Sable Arcade"), atOnce { titles(tree.children(CarNode.Artists)) })
    }

    @Test
    fun aSongFoundOnThePhoneIsNotHeldUpForTheListOfPlaylists() {
        // No playlist is downloaded, and the server's list of them is not kept.
        catalogue.forget("playlist:p1")
        val tree = neverKnown()
        // (The song was found at once, and then the list of playlists was asked of the server.)
        assertTrue(atOnce { tree.search("song 4") }.any { it.title == "Song 4" })
        assertEquals("album:al-1", atOnce { tree.forVoice(VoiceRequest(query = "First Light")) }!!.source)
    }

    @Test
    fun aPlaylistAskedForByNameAndDownloadedPlaysWithoutWaitingToKnow() {
        val tree = neverKnown()
        // "Play Mix": no song, album or artist on the phone is called that; a playlist is.
        assertEquals("playlist:p1", atOnce { tree.forVoice(VoiceRequest(query = "Mix")) }!!.source)
        assertEquals("playlist:p1", atOnce { tree.forVoice(VoiceRequest(query = "Mix", focus = VoiceRequest.Focus.PLAYLIST)) }!!.source)
        assertTrue(atOnce { tree.search("mix") }.any { it.title == "Mix" })
    }

    @Test
    fun aSongChosenWhileItIsNotKnownPlaysFromThePhone() {
        val tree = neverKnown()
        val row = atOnce { tree.children(CarNode.Album("al-1")) }!!.first { it.title == "Song 4" }
        val chosen = atOnce { tree.play(CarIds.parse(row.id) as CarNode.Track) }!!
        assertEquals("s4", chosen.songs[chosen.start].id)
        // A song with no album to speak of, that is on the phone: not asked of the server.
        assertEquals("s2", atOnce { tree.play(CarNode.Track("s2", ListRef.Album(""), -1)) }!!.songs.single().id)
    }

    // ---- The last local gaps (offline hardening 3, item 5) --------------------------------------

    @Test
    fun theAlbumsSortFoldersAnswerFromThePhoneWhileItIsNotKnown() {
        val tree = neverKnown()
        // None of the three lists is kept. (Each waited to know whether the server was there.)
        for (kind in listOf("newest", "recent", "frequent")) {
            assertEquals(listOf("First Light"), atOnce { titles(tree.children(CarNode.AlbumsBy(kind))) })
        }
        // One that is kept is shown as it is.
        runBlocking { library.pages.put(library.albums(org.medianexus.music.net.AlbumList.Newest, 0, Library.GRID_PAGE), listOf(org.medianexus.music.net.Album(id = "al-9", name = "Slow Rivers"))) }
        assertEquals(listOf("Slow Rivers"), atOnce { titles(tree.children(CarNode.AlbumsBy("newest"))) })
    }

    @Test
    fun onePlaylistLookedUpByIdDoesNotWaitForTheListOfPlaylistsWhenItsOwnPageIsKept() {
        // A playlist that is not downloaded; its own page is kept, the list of playlists is not.
        runBlocking { library.pages.put(library.playlist("p7"), org.medianexus.music.net.Playlist(id = "p7", name = "Evening", songs = listOf(song(2), song(9)), songCount = 2)) }
        // Not known whether the server is there, and it never will be: answered from its
        // own page. (It was looked for only in the list of playlists, which was waited for.)
        assertEquals("Evening", atOnce { neverKnown().item(CarNode.Playlist("p7")) }!!.title)
        assertEquals("Mix", atOnce { neverKnown().item(CarNode.Playlist("p1")) }!!.title)
        // Offline: the same, from the phone alone; and one with nothing that plays is not there.
        offline = true
        unsure = false
        val tree = tree()
        assertEquals("Evening", fromThePhone { tree.item(CarNode.Playlist("p7")) }!!.title)
        runBlocking { library.pages.put(library.playlist("p8"), org.medianexus.music.net.Playlist(id = "p8", name = "Not here", songs = listOf(song(9)), songCount = 1)) }
        assertEquals(null, fromThePhone { tree.item(CarNode.Playlist("p8")) })
    }

    /** A tree for which the server is out of reach or not yet known for the first `looks`
     *  times it is asked, and after that is there: it "comes back" while a request that has
     *  already answered from the phone is still being put together. Its answers, like its
     *  list of playlists, are then the black hole's. */
    private fun comingBack(looks: Int, unsureFirst: Boolean): CarTree {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        fun early() = asked.incrementAndGet() <= looks
        return CarTree(library, CarWords(), CarOffline(
            now = { if (unsureFirst) false else early() },
            availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true }),
            downloads = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) },
            unsure = { if (unsureFirst) early() else false },
            settle = { kotlinx.coroutines.awaitCancellation() },
        ))
    }

    @Test
    fun anAnswerWorkedOutOnThePhoneIsNotHeldUpWhenTheServerComesBackMeanwhile() {
        // The search has found its songs on the phone (the server was out of reach when it
        // looked), and the server is back by the time it turns to the playlists. No list of
        // playlists is kept: asked of the server now, that is a wait on the black hole.
        for (unsureFirst in listOf(false, true)) {
            for (looks in 1..4) {
                val found = atOnce { comingBack(looks, unsureFirst).search("song 4") }
                assertTrue("after $looks looks: $found", found.any { it.title == "Song 4" })
            }
            // A spoken request the same: "play First Light", "play Mix", "play Song 4".
            for (looks in 2..8) {
                assertEquals("after $looks looks", "album:al-1", atOnce { comingBack(looks, unsureFirst).forVoice(VoiceRequest(query = "First Light")) }!!.source)
                assertEquals("after $looks looks", "playlist:p1", atOnce { comingBack(looks, unsureFirst).forVoice(VoiceRequest(query = "Mix")) }!!.source)
                assertEquals("after $looks looks", "s4", atOnce { comingBack(looks, unsureFirst).forVoice(VoiceRequest(query = "Song 4")) }!!.let { it.songs[it.start].id })
            }
        }
    }

    @Test
    fun whileItIsNotKnownAKeptPageWithNothingThatPlaysGivesWayToTheSongsOnThePhone() {
        // Kept from before the server read its library again and gave the songs other ids:
        // nothing in these pages is on the phone, and the songs that are, are downloaded.
        fun old(n: Int) = song(n).copy(id = "old-$n")
        runBlocking {
            library.pages.put(library.album("al-1"), org.medianexus.music.net.Album(id = "al-1", name = "First Light", artist = "Paper Lanterns", songs = (1..4).map(::old)))
            library.pages.put(library.playlist("p1"), org.medianexus.music.net.Playlist(id = "p1", name = "Mix", songs = listOf(old(4), old(1))))
            library.pages.put(library.liked(), org.medianexus.music.net.Found(songs = listOf(old(2))))
        }
        val tree = neverKnown()
        // (Offline they already gave way; while it wasn't known whether the server was
        // there, the old page was shown: a folder of songs none of which would play.)
        assertEquals(listOf("Shuffle", "Song 2", "Song 4"), atOnce { titles(tree.children(CarNode.Album("al-1"))) })
        assertEquals(listOf("Shuffle", "Song 4"), atOnce { titles(tree.children(CarNode.Playlist("p1"))) })
        assertEquals(listOf("Shuffle", "Song 2"), atOnce { titles(tree.children(CarNode.Liked)) })
        // A song chosen there plays, from the phone.
        val row = atOnce { tree.children(CarNode.Album("al-1")) }!!.first { it.title == "Song 4" }
        assertEquals("s4", atOnce { tree.play(CarIds.parse(row.id) as CarNode.Track) }!!.let { it.songs[it.start].id })
        // A kept page with something that plays is still shown whole, as when online.
        runBlocking { library.pages.put(library.album("al-1"), org.medianexus.music.net.Album(id = "al-1", name = "First Light", songs = (1..4).map(::song))) }
        assertEquals(listOf("Shuffle", "Song 1", "Song 2", "Song 3", "Song 4"), atOnce { titles(neverKnown().children(CarNode.Album("al-1"))) })
    }

    // ---- Offline loose ends, item 7 -------------------------------------------------------------

    @Test
    fun offlineAKeptListOfAlbumsWithNothingThatPlaysGivesWayToTheAlbumsOnThePhone() {
        // Recently played and Most played are kept, and none of their albums is on the phone.
        runBlocking {
            for (order in listOf(org.medianexus.music.net.AlbumList.Recent, org.medianexus.music.net.AlbumList.Frequent)) {
                library.pages.put(library.albums(order, 0, Library.GRID_PAGE), listOf(org.medianexus.music.net.Album(id = "al-9", name = "Slow Rivers")))
            }
        }
        val tree = tree()
        // The albums on the phone, as with no list kept. (Both were empty folders.)
        for (kind in listOf("recent", "frequent")) {
            assertEquals(listOf("First Light"), fromThePhone { titles(tree.children(CarNode.AlbumsBy(kind))) })
        }
    }

    @Test
    fun withTheServerThereAndNoPageKeptASongOrAnAlbumTheDownloadsHaveIsPlayedFromThem() {
        // The server is known to be there (it has just gone silent: the black hole), and no
        // page of the album is kept. Its songs 2 and 4 are downloaded, and an album of two
        // songs is downloaded whole.
        offline = false
        unsure = false
        fun other(n: Int) = song(n).copy(id = "t$n", album = "Second Wind", albumId = "al-2")
        for (n in listOf(1, 2)) {
            File(catalogue.dir, "t$n.flac").writeBytes(ByteArray(4))
            catalogue.add(Downloaded(other(n), "t$n.flac", 4, at = 10L + n, suffix = "flac"))
        }
        catalogue.keep(DownloadedList("album:al-2", "Second Wind", songs = listOf("t1", "t2")))
        val tree = tree()
        // A song of the album chosen: the album as the downloads have it, from that song.
        // (The album was asked of the server first, and waited on.)
        val chosen = fromThePhone { tree.play(CarNode.Track("s4", ListRef.Album("al-1"), 1)) }!!
        assertEquals("album:al-1", chosen.source)
        assertEquals("s4", chosen.songs[chosen.start].id)
        assertEquals(listOf("s2", "s4"), chosen.songs.map { it.id })
        // A song by itself, downloaded.
        assertEquals("s2", fromThePhone { tree.play(CarNode.Track("s2", ListRef.Album(""), -1)) }!!.songs.single().id)
        // "Shuffle" on the album downloaded whole.
        assertEquals(setOf("t1", "t2"), fromThePhone { tree.shuffled(ListRef.Album("al-2")) }!!.songs.map { it.id }.toSet())
    }
}
