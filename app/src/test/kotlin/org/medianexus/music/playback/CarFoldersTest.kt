package org.medianexus.music.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.library.Library
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.PageCache

/** No folder the car can open is longer than reaches it whole. */
class CarFoldersTest {
    private fun song(i: Int, title: String = "Song number $i") = CarEntry(
        id = CarIds.format(CarNode.Track("5f3c9a1b2d4e6f708192a3b4c5d6e7f${i % 10}", ListRef.Playlist("0a1b2c3d4e5f60718293a4b5c6d7e8f9"), i)),
        title = title,
        subtitle = "An Artist of Ordinary Length",
        cover = "mf-5f3c9a1b2d4e6f708192a3b4c5d6e7f0_65a1b2c3",
        playable = true,
        song = Song(id = "s$i", title = title, artist = "An Artist of Ordinary Length", album = "An Album of Ordinary Length"),
    )

    // ---- The bound ----------------------------------------------------------------------------

    @Test
    fun theBoundIsWorkedOutFromTheSizeOfAnItemAndLeavesAMargin() {
        // An ordinary song, as the car gets it: this many bytes by the reckoning here. If
        // this number moves, the reckoning has changed and the bound below with it.
        assertEquals(924, CarFolders.bytes(song(1)))
        // A folder is at most 200 items and at most 190,000 of those bytes, which leaves more
        // than a quarter of Media3's 256 KiB for whatever the reckoning has missed.
        assertEquals(200, CarFolders.MOST)
        assertTrue(CarFolders.BUDGET <= CarFolders.LIMIT * 3 / 4)
        // A full folder of ordinary songs is inside the budget with room to spare...
        assertTrue(CarFolders.fits(List(200) { song(it) }))
        // ...and one more item is one too many, however small.
        assertFalse(CarFolders.fits(List(201) { song(it) }))
        // Long titles count: a folder of them closes early, by size.
        val long = List(200) { song(it, "A Very Long Title Indeed, ".repeat(12) + it) }
        assertFalse(CarFolders.fits(long))
        assertTrue(CarFolders.first(long).size in 50..150)
        // Text outside ASCII is counted at its longer encoding.
        assertTrue(CarFolders.bytes(song(1, "夜の散歩".repeat(10))) > CarFolders.bytes(song(1, "a".repeat(40))))
    }

    @Test
    fun aListThatFitsIsShownAsItIs() {
        assertNull(CarFolders.ranges(List(200) { song(it) }))
        assertNull(CarFolders.ranges(emptyList()))
        assertNull(CarFolders.ranges(listOf(song(1))))
    }

    @Test
    fun aLongListBecomesRangesThatEachFitAndTogetherAreAllOfIt() {
        val all = List(20_000) { song(it, if (it % 7 == 0) "A Much Longer Title Than The Others Have, Part ".repeat(4) + it else "Song number $it") }
        val top = CarFolders.ranges(all)!!
        assertTrue("${top.size} ranges", top.size in 2..CarFolders.MOST_RANGES)
        // Opened, each is either songs that fit, or ranges again; followed to the bottom,
        // every song is there exactly once and in order.
        val seen = mutableListOf<Int>()
        fun open(range: CarFolders.Range, depth: Int) {
            assertTrue("no deeper than a few levels", depth < 4)
            val inner = CarFolders.ranges(all, range.from, range.to)
            if (inner == null) {
                val songs = all.subList(range.from, range.to)
                assertTrue("a folder of ${songs.size} from ${range.from}", CarFolders.fits(songs))
                seen += range.from until range.to
            } else {
                assertTrue(inner.size <= CarFolders.MOST_RANGES)
                assertEquals(range.from, inner.first().from)
                assertEquals(range.to, inner.last().to)
                inner.forEach { open(it, depth + 1) }
            }
        }
        top.forEach { open(it, 0) }
        assertEquals((0 until 20_000).toList(), seen)
        // The same list splits the same way every time: an id that names a range means the
        // same songs when the car comes back to it.
        assertEquals(top, CarFolders.ranges(all))
    }

    @Test
    fun oneItemTooBigForAnyFolderStillGetsOne() {
        // Nothing real is this size; the point is that the split always ends.
        val huge = List(3) { song(it, "x".repeat(120_000)) }
        val ranges = CarFolders.ranges(huge)!!
        assertEquals(listOf(CarFolders.Range(0, 1), CarFolders.Range(1, 2), CarFolders.Range(2, 3)), ranges)
    }

    @Test
    fun rangesAndShuffleSurviveARoundTripThroughTheirIds() {
        val places = listOf(
            CarNode.Shuffle(ListRef.Liked), CarNode.Shuffle(ListRef.Album("al/1")), CarNode.Shuffle(ListRef.Playlist("p~1")),
            CarNode.AlbumsBy("newest"), CarNode.AlbumsBy("recent"), CarNode.AlbumsBy("frequent"),
            CarNode.Part(CarNode.Playlist("pl/3"), 200, 400), CarNode.Part(CarNode.Liked, 0, 200),
            CarNode.Part(CarNode.AlbumLetter("S"), 150, 300), CarNode.Part(CarNode.Genre("Drum/Bass ~ 100%"), 0, 5),
            CarNode.Part(CarNode.Playlists, 200, 400),
        )
        for (place in places) assertEquals(place, CarIds.parse(CarIds.format(place)))
        for (id in listOf("part/0/200/root", "part/5/5/liked", "part/-1/3/liked", "part/0/200/part/0/100/liked", "part/0/9/track/1/s/liked",
            "albums-by/whatever", "shuffle/nothing", "shuffle", "part/0/200")) {
            assertNull(id, CarIds.parse(id))
        }
    }

    // ---- The tree, against a stand-in server ----------------------------------------------------

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Held shut to make the full listing of albums slow. */
    private var listingGate: CountDownLatch? = null
    private var albums = 0
    private val asked = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun songsJson(count: Int) = (0 until count).joinToString(",") { """{"id":"s$it","title":"Song $it","album":"Album","artist":"Artist","duration":200}""" }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val what = url.pathSegments.last()
                fun ok(fields: String) = MockResponse.Builder().setHeader("Content-Type", "application/json")
                    .body("""{"subsonic-response":{"status":"ok","version":"1.16.1",$fields}}""").build()
                return when (what) {
                    "getPlaylist" -> {
                        val count = url.queryParameter("id")!!.removePrefix("pl").toInt()
                        ok(""""playlist":{"id":"pl$count","name":"List of $count","songCount":$count,"entry":[${songsJson(count)}]}""")
                    }
                    "getAlbum" -> ok(""""album":{"id":"al1","name":"Short","song":[${songsJson(9)}]}""")
                    "getAlbumList2" -> {
                        val type = url.queryParameter("type")!!
                        val offset = url.queryParameter("offset")!!.toInt()
                        val size = url.queryParameter("size")!!.toInt()
                        asked += "$type@$offset"
                        if (type == "alphabeticalByName") listingGate?.await(20, TimeUnit.SECONDS)
                        val total = if (type == "alphabeticalByName") albums else 12
                        val page = (offset until minOf(offset + size, total)).joinToString(",") {
                            // Half of them under S, the rest spread about.
                            val name = if (it % 2 == 0) "Sable %05d".format(it) else "${'A' + it % 20} album %05d".format(it)
                            """{"id":"al$it","name":"$name","artist":"Artist","songCount":10}"""
                        }
                        ok(""""albumList2":{"album":[$page]}""")
                    }
                    else -> ok(""""x":{}""")
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        listingGate?.countDown()
        scope.cancel()
        server.close()
    }

    private fun tree(): CarTree {
        val api = Subsonic(server.url("/").toString(), "ada", "sesame", OkHttpClient())
        return CarTree(Library(api, PageCache(folder.newFolder(), scope, Dispatchers.IO)))
    }

    @Test
    fun aLongPlaylistIsShuffleThenNumberedRangesAndEverySongIsReachable() = runBlocking {
        val tree = tree()
        val list = ListRef.Playlist("pl3000")
        val top = tree.children(CarNode.Playlist("pl3000"))!!
        // "Shuffle" first: in a car that is what a long list is for.
        assertEquals(CarNode.Shuffle(list), CarIds.parse(top.first().id))
        assertEquals("Shuffle", top.first().title)
        assertEquals("3000 songs", top.first().subtitle)
        assertTrue(top.first().playable)
        val ranges = top.drop(1)
        assertEquals("1–200", ranges.first().title)
        assertEquals("Song 0 – Song 199", ranges.first().subtitle)
        assertTrue(ranges.all { it.browsable && !it.playable })
        assertTrue(CarFolders.fits(top))
        // Every song is in exactly one range, with its place in the whole list.
        val seen = mutableListOf<Int>()
        for (range in ranges) {
            val inside = tree.children(CarIds.parse(range.id)!!)!!
            assertTrue(CarFolders.fits(inside))
            for (entry in inside) {
                val track = CarIds.parse(entry.id) as CarNode.Track
                assertEquals(list, track.list)
                seen += track.index
            }
        }
        assertEquals((0 until 3000).toList(), seen)
        // A song chosen deep in the list plays the whole list from there.
        val chosen = tree.play(CarNode.Track("s2345", list, 2345))!!
        assertEquals(3000, chosen.songs.size)
        assertEquals(2345, chosen.start)
        assertEquals("playlist:pl3000", chosen.source)
        // And Shuffle plays all of it, shuffled, starting anywhere.
        val shuffled = tree.shuffled(list)!!
        assertEquals(3000, shuffled.songs.size)
        assertEquals(true, shuffled.shuffle)
        assertTrue(shuffled.anyStart)
        assertEquals("playlist:pl3000", shuffled.source)
    }

    @Test
    fun aShortListIsShuffleThenItsSongsAndAnEmptyOneIsEmpty() = runBlocking {
        val tree = tree()
        val album = tree.children(CarNode.Album("al1"))!!
        assertEquals(10, album.size)
        assertEquals(CarNode.Shuffle(ListRef.Album("al1")), CarIds.parse(album.first().id))
        assertTrue(album.drop(1).all { CarIds.parse(it.id) is CarNode.Track })
        // Nothing to shuffle: no entry that would play nothing.
        assertEquals(emptyList<CarEntry>(), tree.children(CarNode.Playlist("pl0")))
        assertNull(tree.shuffled(ListRef.Playlist("pl0")))
    }

    @Test
    fun albumsShowsSomethingAtOnceAndTheWholeLibraryWhenItIsKept() = runBlocking {
        albums = 1300
        listingGate = CountDownLatch(1)
        val tree = tree()
        val changed = CompletableDeferred<Unit>()
        // The full listing is three requests here and many on a real library, and the server
        // is not answering it: Albums opens all the same, with the folders that cost one
        // request each. (It used to fetch every page before showing anything.)
        val first = withTimeout(3_000) { tree.children(CarNode.Albums) { changed.complete(Unit) } }!!
        assertEquals(listOf("Recently added", "Recently played", "Most played"), first.map { it.title })
        assertEquals(listOf(CarNode.AlbumsBy("newest"), CarNode.AlbumsBy("recent"), CarNode.AlbumsBy("frequent")), first.map { CarIds.parse(it.id) })
        // Each of those is one request.
        val recent = withTimeout(3_000) { tree.children(CarNode.AlbumsBy("newest")) }!!
        assertEquals(12, recent.size)
        assertEquals(1, asked.count { it.startsWith("newest@") })
        // The listing arrives in the background; the car is told, and now has A to Z as well.
        listingGate!!.countDown()
        withTimeout(10_000) { changed.await() }
        val second = withTimeout(3_000) { tree.children(CarNode.Albums) }!!
        assertEquals(listOf("Recently added", "Recently played", "Most played"), second.take(3).map { it.title })
        val letters = second.drop(3)
        assertTrue(letters.size in 2..27)
        assertTrue(letters.all { it.group == "A to Z" && CarIds.parse(it.id) is CarNode.AlbumLetter })
        assertTrue(CarFolders.fits(second))
        // S holds 650 albums: too many for one folder, so it is split further, and every
        // album under S is in exactly one part.
        val s = tree.children(CarNode.AlbumLetter("S"))!!
        assertTrue(s.all { CarIds.parse(it.id) is CarNode.Part })
        assertEquals("1–200", s.first().title)
        val under = s.flatMap { tree.children(CarIds.parse(it.id)!!)!! }
        assertEquals(650, under.size)
        assertEquals(650, under.map { it.id }.toSet().size)
        assertTrue(under.all { it.title.startsWith("Sable") })
        // Opening Albums again within ten minutes doesn't fetch the listing again.
        val before = asked.count { it.startsWith("alphabeticalByName") }
        tree.children(CarNode.Albums)
        tree.children(CarNode.AlbumLetter("S"))
        assertEquals(before, asked.count { it.startsWith("alphabeticalByName") })
        assertNotNull(letters.firstOrNull { it.title == "S" })
    }

    @Test
    fun whatHeadsAFolderCountsTowardsWhatFits() = runBlocking {
        val tree = tree()
        // Exactly as many songs as a folder holds, and "Shuffle" at their head: one more
        // than a folder holds. It used to be handed over like that, 201 entries.
        val top = tree.children(CarNode.Playlist("pl200"))!!
        assertTrue("${top.size} entries in one folder", top.size <= CarFolders.MOST)
        assertTrue(CarFolders.fits(top))
        assertEquals("Shuffle", top.first().title)
        // Still every song, once, in its place.
        val songs = top.drop(1).flatMap { range -> tree.children(CarIds.parse(range.id)!!)!!.also { assertTrue(CarFolders.fits(it)) } }
        assertEquals((0 until 200).toList(), songs.map { (CarIds.parse(it.id) as CarNode.Track).index })
        // One fewer, and it is the songs themselves again.
        val under = tree.children(CarNode.Playlist("pl199"))!!
        assertEquals(200, under.size)
        assertTrue(under.drop(1).all { CarIds.parse(it.id) is CarNode.Track })
        assertTrue(CarFolders.fits(under))
    }

    @Test
    fun aFolderOfRangesIsWeighedTooAndGatheredUntilItFits() {
        // A range's entry is small in practice, but it is made of two titles from the list,
        // and nothing used to check that a hundred of them, with "Shuffle", fit.
        val ranges = List(100) { CarFolders.Range(it * 200, it * 200 + 200) }
        val head = listOf(song(-1, "Shuffle"))
        fun entry(range: CarFolders.Range) = CarEntry("part/${range.from}/${range.to}/playlist/p", "${range.from + 1}–${range.to}", subtitle = "x".repeat(5_000), browsable = true)
        assertFalse(CarFolders.fits(head + ranges.map(::entry)))
        val gathered = CarFolders.gathered(ranges, head, ::entry)
        assertTrue(CarFolders.fits(head + gathered.map(::entry)))
        // The same songs, in the same order, in fewer and wider ranges.
        assertEquals(0, gathered.first().from)
        assertEquals(20_000, gathered.last().to)
        assertTrue(gathered.zipWithNext().all { (a, b) -> a.to == b.from })
        // Ranges that fit as they are are left as they are.
        fun small(range: CarFolders.Range) = CarEntry("part/${range.from}/${range.to}/playlist/p", "${range.from + 1}–${range.to}", browsable = true)
        assertEquals(ranges, CarFolders.gathered(ranges, head, ::small))
    }

    @Test
    fun whenAListChangesEveryFolderOfItTheCarHoldsIsStale() = runBlocking {
        val tree = tree()
        val list = CarNode.Playlist("pl3000")
        val top = tree.children(list)!!
        val second = top[2].id
        assertTrue(CarIds.parse(second) is CarNode.Part)
        // What the car has asked for lately: the list, two of its ranges, and other things.
        val asked = listOf("home", CarIds.format(list), top[1].id, second, CarIds.format(CarNode.Album("al1")), CarIds.format(CarNode.Playlist("pl200")), "playlists")
        // The list changes (a song was added at its top: every range now means other songs).
        // The list and both ranges are to be told; nothing else is. It used to be only the
        // folder that happened to be asked for at that moment.
        assertEquals(listOf(CarIds.format(list), top[1].id, second), tree.stale(asked, "playlist:pl3000"))
        assertEquals(listOf(CarIds.format(CarNode.Album("al1"))), tree.stale(asked, "album:al1"))
        assertEquals(listOf("playlists"), tree.stale(asked, "playlists"))
        assertEquals(emptyList<String>(), tree.stale(asked, "playlist:another"))
    }
}
