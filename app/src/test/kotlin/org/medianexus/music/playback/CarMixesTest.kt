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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.library.Availability
import org.medianexus.music.library.Library
import org.medianexus.music.library.MixRules
import org.medianexus.music.library.MixSet
import org.medianexus.music.library.Mixes
import org.medianexus.music.net.Album
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.PageCache
import org.medianexus.music.store.SongTable

/** The "Made for you" mixes in the car: under Home, as lists of songs, online and offline. */
class CarMixesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var library: Library
    private var offline = false
    private val now = 1_795_000_000L

    /** Twenty songs played a lot (Your top songs), the first four on the phone. */
    private val songs = (1..20).map { Song(id = "s$it", title = "Song $it", album = "First Light", albumId = "al-1", artist = "Paper Lanterns", coverArt = "c$it", suffix = "flac", playCount = 40 - it, duration = 100) }
    private val table = SongTable.Builder().apply { songs.forEach { add(it) } }.build()
    private var rules = listOf(MixRules(id = 7, name = "Everything", order = 1))
    private fun set() = Mixes.From(table, { false }, emptyList(), rules, now).let { MixSet(Mixes.all(it), it) }

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
        val api = Subsonic("http://127.0.0.1:9", "ada", "sesame", OkHttpClient(), OfflineSwitch(true))
        library = Library(api, PageCache(folder.newFolder("pages"), scope, Dispatchers.IO))
        runBlocking {
            library.pages.put(library.liked(), org.medianexus.music.net.Found())
            library.pages.put(library.albums(org.medianexus.music.net.AlbumList.Recent, 0, CarTree.HOME_ROW), listOf(Album(id = "al-1", name = "First Light")))
            library.pages.put(library.albums(org.medianexus.music.net.AlbumList.Newest, 0, CarTree.HOME_ROW), emptyList())
        }
        for (song in songs.take(4)) {
            File(catalogue.dir, "${song.id}.flac").writeBytes(ByteArray(4))
            catalogue.add(Downloaded(song, "${song.id}.flac", 4, at = 1, suffix = "flac"))
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun tree(): CarTree {
        val availability = Availability(catalogue, KeptIndex(File(folder.root, "kept.json"), scope, Dispatchers.IO), quality = org.medianexus.music.playback.StreamQuality(kotlinx.coroutines.flow.MutableStateFlow(org.medianexus.music.store.Settings())), keeping = { true })
        val state = { DownloadsState(downloaded = catalogue.state.value.songs, collections = catalogue.state.value.collections) }
        return CarTree(library, CarWords(mixTitle = { it.name.ifEmpty { it.id } }), CarOffline(now = { offline }, availability = availability, downloads = state), mixes = { set() })
    }

    @Test
    fun homeListsTheMixesAndEachPlaysLikeAnyList() = runBlocking {
        val home = tree().children(CarNode.Home)!!
        val made = home.filter { it.group == "Made for you" }
        // Yours first, then Your top songs (too few plays this month for On repeat, none long
        // ago for Rediscover, no genre).
        assertEquals(listOf("mix/rule:7", "mix/top"), made.map { it.id })
        assertTrue(made.all { it.browsable && !it.playable && it.inside == CarStyle.LIST })
        assertEquals("c1", made[1].cover)
        // Inside: Shuffle, then the songs in the mix's order, each playing the mix from itself.
        val inside = tree().children(CarNode.Mix("top"))!!
        assertEquals("Shuffle", inside[0].title)
        assertEquals(songs.map { it.id }, inside.drop(1).map { it.song!!.id })
        val chosen = tree().play(CarIds.parse(inside[3].id) as CarNode.Track)!!
        assertEquals(2, chosen.start)
        assertEquals("mix:top", chosen.source)
        assertEquals(20, chosen.songs.size)
        val shuffled = tree().shuffled(ListRef.Mix("top"))!!
        assertEquals(true, shuffled.shuffle)
        assertEquals("mix:top", shuffled.source)
        assertEquals("mix/rule:7", CarIds.format(CarNode.Mix("rule:7")))
        assertEquals("top", (tree().item(CarNode.Mix("top")))!!.title)
        // A mix that has gone (its rules deleted) is nothing to open or play.
        rules = emptyList()
        assertNull(tree().children(CarNode.Mix("rule:7"))?.takeIf { it.isNotEmpty() })
        assertNull(tree().shuffled(ListRef.Mix("rule:7")))
    }

    @Test
    fun offlineAMixListsWhatPlays() = runBlocking {
        offline = true
        val home = tree().children(CarNode.Home)!!
        assertEquals(listOf("mix/rule:7", "mix/top"), home.filter { it.group == "Made for you" }.map { it.id })
        val inside = tree().children(CarNode.Mix("top"))!!
        // The four on the phone, each still named by its place in the whole mix.
        assertEquals(listOf("s1", "s2", "s3", "s4"), inside.drop(1).map { it.song!!.id })
        assertEquals(CarNode.Track("s4", ListRef.Mix("top"), 3), CarIds.parse(inside[4].id))
        // A mix with nothing on the phone isn't shown.
        rules = listOf(MixRules(id = 7, name = "Never played", played = 1))
        assertEquals(listOf("mix/top"), tree().children(CarNode.Home)!!.filter { it.group == "Made for you" }.map { it.id })
    }

    /** A mix's folder is said to have changed by what it holds (M5b fixes, item 9): other songs
     *  at the same places in a newer copy of the library are a change (the places alone were
     *  compared), and so, offline, is a download arriving. */
    @Test
    fun aMixsFolderChangesByWhatItHolds() {
        val before = tree().mixShown("top")
        assertEquals(20, before.size)
        // A newer copy: the same number of songs, in the same places, all different.
        val swapped = SongTable.Builder().apply { songs.forEach { add(it.copy(id = "n" + it.id)) } }.build()
        val newer = CarTree(library, CarWords(), null, mixes = { Mixes.From(swapped, { false }, emptyList(), rules, now).let { MixSet(Mixes.all(it), it) } })
        val after = newer.mixShown("top")
        assertEquals(20, after.size)
        assertTrue(before != after)
        // Offline: what plays; a fifth song downloaded is a change.
        offline = true
        val four = tree().mixShown("top")
        assertEquals(listOf("0/s1", "1/s2", "2/s3", "3/s4"), four)
        val fifth = songs[4]
        File(catalogue.dir, "${fifth.id}.flac").writeBytes(ByteArray(4))
        catalogue.add(Downloaded(fifth, "${fifth.id}.flac", 4, at = 1, suffix = "flac"))
        assertEquals(four + "4/s5", tree().mixShown("top"))
        // Home's row says what it shows.
        assertEquals(listOf("mix/rule:7", "mix/top"), tree().madeForYouShown().map { it.id })
    }
}
