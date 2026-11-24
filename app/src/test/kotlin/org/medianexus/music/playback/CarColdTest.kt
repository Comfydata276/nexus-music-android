package org.medianexus.music.playback

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        // A list the phone has no copy of is empty, at once: not a wait.
        assertEquals(emptyList<String>(), fromThePhone { titles(tree.children(CarNode.AlbumsBy("newest"))) })
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
    fun beforeItIsKnownWhetherTheServerIsThereWhatIsOnThePhoneIsNotWaitedFor() {
        // The app has just started, the phone has a network of some kind, and the server
        // hasn't answered yet (it never will). Not offline: unsure.
        offline = false
        unsure = true
        val tree = tree()
        assertEquals(listOf("Mix"), fromThePhone { titles(tree.children(CarNode.Playlists)) })
        assertEquals(listOf("Shuffle", "Song 4"), fromThePhone { titles(tree.children(CarNode.Playlist("p1"))) })
        assertEquals(listOf("Shuffle", "Song 2"), fromThePhone { titles(tree.children(CarNode.Liked)) })
        assertEquals(listOf("Shuffle", "Song 2", "Song 4"), fromThePhone { titles(tree.children(CarNode.Album("al-1"))) })
        assertEquals(listOf("Songs", "Liked songs", "Mix", "First Light"), fromThePhone { titles(tree.children(CarNode.Downloads)) })
        assertTrue(fromThePhone { tree.search("song 4") }.any { it.title == "Song 4" })
        assertEquals("album:al-1", fromThePhone { tree.forVoice(VoiceRequest(query = "First Light")) }!!.source)
    }
}
