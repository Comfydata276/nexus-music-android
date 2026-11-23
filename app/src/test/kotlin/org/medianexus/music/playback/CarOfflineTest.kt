package org.medianexus.music.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.medianexus.music.net.Album
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.PageCache

/**
 * The car with no server: the Downloads tab, read from the downloads' own record alone, and
 * the other tabs showing only what plays. The server here is offline mode itself (every
 * request fails at once); what the phone has kept is put there by the test.
 */
class CarOfflineTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var kept: KeptIndex
    private lateinit var library: Library
    private var offline = true

    private val album = Album(id = "al-1", name = "First Light", artist = "Paper Lanterns", artistId = "ar-1", coverArt = "al-1",
        songs = (1..4).map { Song(id = "s$it", title = "Song $it", album = "First Light", albumId = "al-1", artist = "Paper Lanterns", artistId = "ar-1", track = it, suffix = "flac", genre = "Folk") })

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
        kept = KeptIndex(File(folder.root, "kept.json"), scope, Dispatchers.IO)
        val api = Subsonic("http://127.0.0.1:9", "ada", "sesame", OkHttpClient(), OfflineSwitch(true))
        library = Library(api, PageCache(folder.newFolder("pages"), scope, Dispatchers.IO))
        runBlocking {
            library.pages.put(library.album("al-1"), album)
            library.pages.put(library.playlists(), listOf(Playlist(id = "p1", name = "Mix", songCount = 2), Playlist(id = "p2", name = "Nothing here", songCount = 1)))
            library.pages.put(library.playlist("p1"), Playlist(id = "p1", name = "Mix", songs = listOf(album.songs[3], album.songs[0])))
            library.pages.put(library.playlist("p2"), Playlist(id = "p2", name = "Nothing here", songs = listOf(Song(id = "x9", title = "Elsewhere"))))
        }
        // Songs 2 and 4 are downloaded; song 3 is kept whole from an earlier play.
        for (song in listOf(album.songs[1], album.songs[3])) {
            File(catalogue.dir, "${song.id}.flac").writeBytes(ByteArray(4))
            catalogue.add(Downloaded(song, "${song.id}.flac", 4, at = song.track.toLong(), suffix = "flac"))
        }
        catalogue.keep(DownloadedList("playlist:p1", "Mix", songs = listOf("s4", "s1")))
        kept.kept(cacheKey("s3", StreamForm(0, "flac")), album.songs[2])
    }

    @After
    fun stop() = scope.cancel()

    private fun tree(): CarTree {
        val availability = Availability(catalogue, kept, cap = { 0 }, keeping = { true })
        val state = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) }
        return CarTree(library, CarWords(), CarOffline(now = { offline }, availability = availability, downloads = state))
    }

    @Test
    fun fourTabsDownloadsLast() {
        assertEquals(listOf("Home", "Playlists", "Library", "Downloads"), tree().tabs().map { it.title })
    }

    @Test
    fun theDownloadsTabNeedsNothingButTheDownloads() = runBlocking {
        val tab = tree().children(CarNode.Downloads)!!
        assertEquals(listOf("Songs", "Mix", "First Light"), tab.map { it.title })
        assertEquals(listOf("", "Playlists", "Albums"), tab.map { it.group })
        // The album, as the downloads have it: Shuffle, then what is downloaded, in its order.
        val songs = tree().children(CarNode.DownloadedAlbum("al-1"))!!
        assertEquals(listOf("Shuffle", "Song 2", "Song 4"), songs.map { it.title })
        // The playlist downloaded whole, in its own order, only what arrived.
        assertEquals(listOf("Shuffle", "Song 4"), tree().children(CarNode.DownloadedList("playlist:p1"))!!.map { it.title })
        // Every song, the most lately downloaded first.
        assertEquals(listOf("Shuffle", "Song 4", "Song 2"), tree().children(CarNode.DownloadedSongs)!!.map { it.title })
        // A song chosen there plays that list, from it, under a name of its own.
        val chosen = CarIds.parse(songs[2].id) as CarNode.Track
        val list = tree().play(chosen)!!
        assertEquals(listOf("s2", "s4"), list.songs.map { it.id })
        assertEquals(1, list.start)
        assertEquals("downloads:album:al-1", list.source)
    }

    @Test
    fun offlineTheOtherTabsShowOnlyWhatPlays() = runBlocking {
        val tree = tree()
        // The album page: songs 2, 3 (kept) and 4, each still named by its place in the album.
        val songs = tree.children(CarNode.Album("al-1"))!!
        assertEquals(listOf("Shuffle", "Song 2", "Song 3", "Song 4"), songs.map { it.title })
        assertEquals(listOf(1, 2, 3), songs.drop(1).map { (CarIds.parse(it.id) as CarNode.Track).index })
        // Playlists: the one with something that plays, not the one without.
        assertEquals(listOf("Mix"), tree.children(CarNode.Playlists)!!.map { it.title })
        // Albums, artists and genres: from what plays.
        assertEquals(listOf("First Light"), tree.children(CarNode.Albums)!!.map { it.title })
        assertEquals(listOf("Paper Lanterns"), tree.children(CarNode.Artists)!!.map { it.title })
        assertEquals(listOf("Folk"), tree.children(CarNode.Genres)!!.map { it.title })
        // Online again, the album is all of it.
        offline = false
        assertEquals(5, tree.children(CarNode.Album("al-1"))!!.size)
    }

    @Test
    fun offlineSearchAndSpokenRequestsLookInWhatIsHere() = runBlocking {
        val tree = tree()
        val found = tree.search("song 3")
        assertTrue(found.any { it.title == "Song 3" })
        assertTrue(found.none { it.title == "Song 1" && it.playable })
        // "Play First Light": the album, from the phone (the server is never asked).
        val list = tree.forVoice(VoiceRequest(query = "First Light"))!!
        assertEquals("album:al-1", list.source)
        assertTrue(list.songs.map { it.id }.containsAll(listOf("s2", "s3", "s4")))
    }

    @Test
    fun aListStartedOfflineHoldsOnlyWhatPlaysFromTheSongAsked() {
        val plays: (Song) -> Boolean = { it.id in setOf("s2", "s4") }
        // Asked for song 3, which doesn't play: from the next that does.
        val cut = playableOnly(album.songs, 2, plays)!!
        assertEquals(listOf("s2", "s4"), cut.songs.map { it.id })
        assertEquals(listOf(1, 3), cut.origins)
        assertEquals(1, cut.start)
        // Past the last that plays: the last before it.
        assertEquals(0, playableOnly(album.songs.take(3), 2, plays)!!.start)
        // None: nothing to start.
        assertEquals(null, playableOnly(album.songs, 0) { false })
    }

    @Test
    fun fetchingAheadCountsPlacesAndPassesOverWhatIsHere() {
        val queue = Queue().apply { replace(album.songs, 0, "First Light", "album:al-1", null, emptyList(), false, false, kotlin.random.Random(1)) }
        // The next three places, less what is downloaded or kept (songs 2 and 3 here).
        assertEquals(listOf("s4"), FetchAhead.upcoming(queue, 3) { it.id == "s2" || it.id == "s3" })
        assertEquals(listOf("s2"), FetchAhead.upcoming(queue, 1) { false })
        assertEquals(emptyList<String>(), FetchAhead.upcoming(queue, 0) { false })
    }
}
