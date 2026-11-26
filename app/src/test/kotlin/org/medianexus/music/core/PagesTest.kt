package org.medianexus.music.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pages and actions of requests, through the real library and the phone's own client,
 * against a stand-in for Lidarr, MusicBrainz and ListenBrainz in one: each answers with what
 * the library's own tests give it (`core/src/requests.rs` in the Windows repository), so
 * what comes out here can be read against what comes out there. What the library decides is
 * tested there; here is that it **arrives**: the place and what it holds, each call's
 * shape, what is handed over as a search goes, and a stop.
 *
 * Nothing leaves this machine: MusicBrainz's and ListenBrainz's addresses, which the
 * library fixes, are turned to the stand-in.
 */
class PagesTest {
    private val closing = mutableListOf<StandIn>()

    /** What the stand-in answers: the first route whose text is in the request's line. */
    private val routes = CopyOnWriteArrayList<Pair<String, (StandIn.Asked, StandIn.Reply) -> Unit>>()
    private fun route(part: String, json: String) = routes.add(0, part to { _, reply -> reply.json(json) })
    private fun route(part: String, answer: (StandIn.Asked, StandIn.Reply) -> Unit) = routes.add(0, part to answer)

    private val server = StandIn { asked, reply ->
        val answer = routes.firstOrNull { (part, _) -> part in asked.line }?.second
        if (answer == null) reply.json("[]", 404) else answer(asked, reply)
    }.also { closing += it }

    private val lidarr get() = LidarrAt(server.address, "k")
    /** (What must be put back on Lidarr is "written down" here by being counted as written:
     *  where it is really kept, and what becomes of it, is `RequestsPagesTest`'s.) */
    private val place = Core.placeOpen { _, _ -> true }

    init {
        // (The library fixes where MusicBrainz and ListenBrainz are.)
        CoreHttp.dress = { client ->
            client.addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "127.0.0.1") return@addInterceptor chain.proceed(request)
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).build())
            }
        }
    }

    @After
    fun tidy() {
        Core.placeClose(place)
        closing.forEach { it.close() }
        CoreHttp.dress = {}
    }

    private fun <T> got(outcome: Outcome<T>): T = (outcome as? Outcome.Got<T>)?.value ?: throw AssertionError("expected an answer, got $outcome")
    private fun <T> wait(act: suspend () -> T): T = runBlocking { withTimeout(60_000) { act() } }

    // (`quiet_band` in the library's tests.)
    private fun quietBand() {
        route("artist/lookup", """[{"artistName":"Quiet Band","foreignArtistId":"qb"}]""")
        route("album/lookup", """[{"title":"Soft","foreignAlbumId":"rg-s","albumType":"Album","artist":{"artistName":"Quiet Band","foreignArtistId":"qb"}}]""")
        route(
            "recording?query",
            """{"recordings":[{"id":"r1","title":"Soft Song","score":100,"artist-credit":[{"name":"Quiet Band","artist":{"id":"qb","name":"Quiet Band"}}],""" +
                """"releases":[{"status":"Official","date":"2001","release-group":{"id":"rg-s","title":"Soft","primary-type":"Album","secondary-types":[]}}]}]}""",
        )
    }

    // (`MARROW_LAKE`.)
    private fun marrowLake() {
        route("api/v1/artist", "[]")
        // (MusicBrainz is asked for albums alone, or for EPs and singles too: it answers accordingly.)
        val album = """{"id":"rg-1","title":"Iron Kite","primary-type":"Album","secondary-types":[],"first-release-date":"2018-10-19","artist-credit":[{"name":"Marrow Lake","artist":{"id":"ml","name":"Marrow Lake"}}]}"""
        val ep = """{"id":"rg-2","title":"Small Hours","primary-type":"EP","secondary-types":[],"first-release-date":"2017","artist-credit":[{"name":"Marrow Lake","artist":{"id":"ml","name":"Marrow Lake"}}]}"""
        route("release-group?artist=ml") { asked, reply ->
            val smallerToo = "ep" in asked.target.substringAfter("type=").substringBefore("&").lowercase()
            reply.json(if (smallerToo) """{"release-group-count":2,"release-groups":[$album,$ep]}""" else """{"release-group-count":1,"release-groups":[$album]}""")
        }
        route("top-recordings-for-artist/ml", """[{"recording_name":"Kite","release_name":"Iron Kite","total_listen_count":9}]""")
    }

    @Test
    fun `a search hands over what it has found as it goes, and all of it at the end`() {
        quietBand()
        val soFar = CopyOnWriteArrayList<Outside>()
        val found = got(wait { Core.pageSearch(lidarr, place, "quiet band") { soFar += it } })
        assert(found.note == "Ask Lidarr for any of these. A song comes with the album it's on, or as its single if it isn't on one.") { found.note }
        assertEquals(listOf(1, 1, 1), listOf(found.songs.size, found.artists.size, found.albums.size))
        assertEquals("Soft Song" to false, found.songs.single().let { (song, asked) -> song.title to asked })
        assertEquals("rg-s", found.songs.single().first.album)
        assertEquals("qb", found.artists.single().id)
        assertEquals("Soft", found.albums.single().title)
        // (As in the library's own test: what is found is shown while more is looked for.)
        assertEquals(listOf("Looking for songs beyond your library…", found.note), soFar.map { it.note })
        // Each service was asked under the phone's own name, and nothing but the stand-in was.
        assertTrue(server.asked.all { it.header("User-Agent")?.startsWith("NexusMusic/") == true })
    }

    @Test
    fun `a search that is stopped comes back stopped, and hands nothing over afterwards`() = runBlocking {
        quietBand()
        // MusicBrainz is slow to answer: the search is stopped while it waits.
        val reached = CountDownLatch(1)
        val held = CountDownLatch(1)
        route("recording?query") { _, reply ->
            reached.countDown()
            held.await(10, TimeUnit.SECONDS)
            runCatching { reply.json("""{"recordings":[]}""") }
        }
        val soFar = CopyOnWriteArrayList<Outside>()
        val searching = async(Dispatchers.Default) { Core.pageSearch(lidarr, place, "quiet band") { soFar += it } }
        assertTrue(reached.await(20, TimeUnit.SECONDS))
        val before = soFar.size
        searching.cancelAndJoin()
        held.countDown()
        Thread.sleep(800)
        assertTrue(searching.isCancelled)
        assertEquals("something was handed over after the stop", before, soFar.size)
        Unit
    }

    @Test
    fun `an artist's page says what they have made, and shows the library's own copy as the library's`() {
        marrowLake()
        Core.placeSingles(place, true)
        val page = got(wait { Core.pageArtist(lidarr, place, "ml") })
        assertEquals(listOf("Marrow Lake", "1 album", 1), listOf(page.name, page.about, page.open))
        assertEquals(listOf("2018", "2017 • EP"), (page.albums + page.smaller).map { it.caption })
        val popular = got(wait { Core.pageArtistPopular(lidarr, place, "ml", "Marrow Lake") })
        assertEquals("Popular" to 1, popular.heading to popular.songs.size)

        // The phone's copy of the library gains that album: it is asked afresh, and shown as the library's.
        Core.placeLibrary(place, LibraryLists(albums = listOf(LibraryAlbum("al-77", "Iron Kite", "Marrow Lake", "cover-77")), songs = listOf(listOf("Kite", "Marrow Lake")), artists = listOf("Marrow Lake")))
        val again = got(wait { Core.pageArtist(lidarr, place, "ml") })
        assertEquals("1 album • 1 in your library", again.about)
        assertEquals("2018 • In your library", again.albums.single().caption)
        assertEquals(LibraryAlbum("al-77", "Iron Kite", "Marrow Lake", "cover-77"), again.albums.single().library)
        assertEquals(0, again.open)
        // And with singles and EPs switched off, the EP is not listed.
        Core.placeSingles(place, false)
        assertEquals(emptyList<Release>(), got(wait { Core.pageArtist(lidarr, place, "ml") }).smaller)
        // Nothing of theirs is "popular and not in your library" any more: no songs to show.
        assertEquals(emptyList<Pair<SongOffer, Boolean>>(), got(wait { Core.pageArtistPopular(lidarr, place, "ml", "Marrow Lake") }).songs)
    }

    @Test
    fun `an album's page says what is on it, or why it can't be shown`() {
        // Lidarr has no record of it.
        route("album/lookup", "[]")
        assertEquals(AlbumPage.Unavailable(Words.notListed), got(wait { Core.pageAlbum(lidarr, place, "rg-9") }))
        route("album/lookup?term=lidarr%3Arg-9", """[{"title":"Nine","foreignAlbumId":"rg-9","albumType":"EP","releaseDate":"2019-01-01","artist":{"artistName":"Someone","foreignArtistId":"so"}}]""")
        route("release?release-group=rg-9", """{"releases":[{"media":[{"position":1,"tracks":[{"title":"One","length":100000,"position":1}]}]}]}""")
        val page = got(wait { Core.pageAlbum(lidarr, place, "rg-9") }) as AlbumPage.Found
        assertEquals(listOf("2019 • 1 song • Not in your library", "Request EP", 1), listOf(page.album.about, page.album.action, page.album.tracks.size))
        assertEquals(TrackName("One", 100, 1, 1), page.album.tracks.single())
        // It was offered, so the question about it knows what kind of release it is.
        val asking = Core.asking(place, "rg-9", "Nine", by = "Someone") as Asking.Ask
        assertEquals("Ask for this EP?", asking.heading)
        assertTrue(asking.text, asking.text.startsWith("“Nine” by Someone is an EP."))
    }

    /** A Lidarr with one artist whose profile doesn't list singles, and one single of
     *  theirs (as `CoreHttpTest` has it). */
    private fun lidarrWithASingle(): () -> Boolean {
        var newReleases = "all"
        var profile = 1
        var wanted = false
        fun artist() = """{"id":1,"foreignArtistId":"artist-1","artistName":"Quillon Vane","monitored":true,"metadataProfileId":$profile,"monitorNewItems":"$newReleases"}"""
        val standard = """{"id":1,"name":"Standard","primaryAlbumTypes":[{"albumType":{"name":"Album"},"allowed":true},{"albumType":{"name":"EP"},"allowed":false},{"albumType":{"name":"Single"},"allowed":false}],""" +
            """"secondaryAlbumTypes":[{"albumType":{"name":"Studio"},"allowed":true}],"releaseStatuses":[{"releaseStatus":{"name":"Official"},"allowed":true}]}"""
        route("album/lookup", """[{"title":"One Song","foreignAlbumId":"single-1","albumType":"Single","artist":{"artistName":"Quillon Vane","foreignArtistId":"artist-1"}}]""")
        route("/api/v1/") { asked, reply ->
            val path = asked.target.substringAfter("/api/v1/")
            when {
                path.startsWith("album/lookup") -> reply.json("""[{"title":"One Song","foreignAlbumId":"single-1","albumType":"Single","artist":{"artistName":"Quillon Vane","foreignArtistId":"artist-1"}}]""")
                asked.method == "GET" && path == "artist" -> reply.json("[${artist()}]")
                asked.method == "GET" && path == "metadataprofile" -> reply.json("[$standard]")
                asked.method == "POST" && path == "metadataprofile" -> reply.json(asked.body.replaceFirst("{", """{"id":2,"""))
                asked.method == "GET" && path == "artist/1" -> reply.json(artist())
                asked.method == "PUT" && path == "artist/1" -> {
                    newReleases = Regex(""""monitorNewItems":"(\w+)"""").find(asked.body)!!.groupValues[1]
                    profile = Regex(""""metadataProfileId":(\d+)""").find(asked.body)!!.groupValues[1].toInt()
                    reply.json(artist())
                }
                asked.method == "GET" && path.startsWith("album?artistId=1") -> reply.json("""[{"id":7,"title":"One Song","foreignAlbumId":"single-1","albumType":"Single","monitored":$wanted}]""")
                asked.method == "GET" && path == "command" -> reply.json("[]")
                asked.method == "PUT" && path == "album/monitor" -> {
                    wanted = "\"monitored\":true" in asked.body
                    reply.json("[]")
                }
                asked.method == "GET" && path == "album/7" -> reply.json("""{"id":7,"monitored":$wanted}""")
                asked.method == "POST" && path == "command" -> reply.json("{}")
                path.startsWith("wanted/missing") -> reply.json(if (wanted) """{"totalRecords":1,"records":[{"id":7,"title":"One Song","artist":{"artistName":"Quillon Vane"}}]}""" else """{"records":[]}""")
                path.startsWith("queue") -> reply.json("""{"totalRecords":1,"records":[{"albumId":7,"size":200.0,"sizeleft":120.0}]}""")
                else -> reply.send(404)
            }
        }
        return { wanted }
    }

    @Test
    fun `asking for an album, from the question to its arrival`() {
        val wanted = lidarrWithASingle()
        // The question, in the library's words.
        val asking = Core.asking(place, "single-1", "One Song", by = "Quillon Vane", kind = "Single") as Asking.Ask
        assertEquals("Ask for this single?", asking.heading)
        assertTrue(asking.text, asking.text.startsWith("“One Song” by Quillon Vane came out as a single."))

        // Asked: Lidarr has it on its list, and says whose it is.
        val begun = Core.request(lidarr, place, "single-1")
        assertEquals(Outcome.Got("Quillon Vane"), wait { begun.done.await() })
        assertTrue(wanted())

        // The note of it, then the mark: in that order.
        val noted = Core.noted(place, "single-1", "One Song", "Quillon Vane", song = "", now = 1_000)
        assertEquals(listOf(Asked(album = "single-1", title = "One Song", artist = "Quillon Vane", at = 1_000)), noted)
        assertTrue("the offer says it was asked for before it was marked", Core.asking(place, "single-1", "One Song") is Asking.Ask)
        Core.marked(place, "single-1", true)
        assertEquals(Asking.Already("That one has already been asked for."), Core.asking(place, "single-1", "One Song"))

        // On its way: being fetched, forty hundredths done.
        val waiting = got(wait { Core.pageOnTheirWay(lidarr, place) })
        assertEquals("On their way (1)", waiting.heading)
        assertEquals(Pending(7, "One Song", "Quillon Vane", 40) to "Quillon Vane • Downloading, 40%", waiting.pending.single())

        // Not in the library yet: no arrival, and nothing to write down.
        val none = Core.askedArrivals(place, now = 2_000)
        assertEquals(listOf(false, emptyList<Asked>(), ""), listOf(none.changed, none.told, none.news))
        // The phone's copy of the library gains it: an arrival, said once.
        Core.placeLibrary(place, LibraryLists(albums = listOf(LibraryAlbum("al-9", "One Song", "Quillon Vane", "cover-9")), artists = listOf("Quillon Vane")))
        val arrived = Core.askedArrivals(place, now = 3_000)
        assertTrue(arrived.changed)
        assertEquals("“One Song” by Quillon Vane has arrived.", arrived.news)
        assertEquals(listOf(Asked(album = "single-1", title = "One Song", artist = "Quillon Vane", at = 1_000, arrived = 3_000, libraryAlbum = "al-9", cover = "cover-9")), arrived.asked)
        assertEquals("", Core.askedArrivals(place, now = 4_000).news)
        // And it is listed with what has arrived, the library's own album to open.
        val after = got(wait { Core.pageOnTheirWay(lidarr, place) })
        assertEquals("al-9" to "Quillon Vane", after.arrived.single().let { (asked, line) -> asked.libraryAlbum to line })
        // Long after, it is no longer listed.
        assertTrue(Core.askedTidy(place, now = 3_000 + 15 * 24 * 3600L).let { it.changed && it.asked.isEmpty() })
    }

    @Test
    fun `a soundtrack credited to various artists is asked for under that name, and the question says so`() {
        // (MusicBrainz's own id for "Various Artists", which the library knows them by.)
        val various = "89ad4ac3-39f7-470e-963a-56509c546377"
        val film = """{"title":"Film Music","foreignAlbumId":"ost-1","albumType":"Album","secondaryTypes":["Soundtrack"],"artist":{"artistName":"Various Artists","foreignArtistId":"$various"}}"""
        val standard = """{"id":1,"name":"Standard","primaryAlbumTypes":[{"albumType":{"name":"Album"},"allowed":true}],""" +
            """"secondaryAlbumTypes":[{"albumType":{"name":"Studio"},"allowed":true},{"albumType":{"name":"Soundtrack"},"allowed":false}],"releaseStatuses":[{"releaseStatus":{"name":"Official"},"allowed":true}]}"""
        route("recording?query", """{"recordings":[]}""")
        route("/api/v1/") { asked, reply ->
            val path = asked.target.substringAfter("/api/v1/")
            when {
                path.startsWith("album/lookup") -> reply.json("[$film]")
                path.startsWith("artist/lookup") -> reply.json("[]")
                asked.method == "GET" && path == "artist" -> reply.json("[]")
                asked.method == "GET" && path == "rootfolder" -> reply.json("""[{"path":"/music","defaultQualityProfileId":3,"defaultMetadataProfileId":1}]""")
                asked.method == "GET" && path == "metadataprofile" -> reply.json("[$standard]")
                asked.method == "POST" && path == "metadataprofile" -> reply.json(asked.body.replaceFirst("{", """{"id":2,"""))
                asked.method == "POST" && path == "album" -> reply.json("{}")
                else -> reply.send(404)
            }
        }
        // Offered by a search, as a soundtrack.
        val found = got(wait { Core.pageSearch(lidarr, place, "film music") {} })
        val offer = found.albums.single()
        assertEquals(listOf("ost-1", various, "Various Artists", true), listOf(offer.album, offer.artistId, offer.artist, offer.soundtrack))
        // The question says where it will be filed.
        val asking = Core.asking(place, "ost-1", "Film Music", by = "Various Artists") as Asking.Ask
        assertEquals("Ask for this album?", asking.heading)
        assertTrue(asking.text, asking.text.endsWith("It's a soundtrack credited to various artists, so it goes on Lidarr's list under “Various Artists”."))
        // Asked for: Lidarr is handed its own description of the album back, wanted, under
        // "Various Artists", with a profile that lists soundtracks, which it had to be given.
        val begun = Core.request(lidarr, place, "ost-1")
        assertEquals(Outcome.Got("Various Artists"), wait { begun.done.await() })
        val made = server.asked.single { it.method == "POST" && it.target.endsWith("/metadataprofile") }
        assertTrue(made.body, "\"name\":\"Albums, EPs, singles and soundtracks\"" in made.body)
        val added = server.asked.single { it.method == "POST" && it.target.endsWith("/album") }.body
        for (part in listOf("\"foreignAlbumId\":\"ost-1\"", "\"foreignArtistId\":\"$various\"", "\"metadataProfileId\":2", "\"qualityProfileId\":3", "\"rootFolderPath\":\"/music\"", "\"albumsToMonitor\":[\"ost-1\"]", "\"monitorNewItems\":\"none\"")) {
            assertTrue("$part in $added", part in added)
        }
    }

    @Test
    fun `what one connection is offered is nothing to another, whichever is asked first or last`() {
        // (The first Lidarr offers a soundtrack, which the question about it then says.)
        val various = "89ad4ac3-39f7-470e-963a-56509c546377"
        route("recording?query", """{"recordings":[]}""")
        route("artist/lookup", "[]")
        route("album/lookup", """[{"title":"Film Music","foreignAlbumId":"ost-1","albumType":"Album","secondaryTypes":["Soundtrack"],"artist":{"artistName":"Various Artists","foreignArtistId":"$various"}}]""")
        val first = LidarrAt(server.address, "k", connection = 1)
        val second = LidarrAt(server.address, "k", connection = 2)
        fun said(under: Long): String = (Core.asking(place, "ost-1", "Film Music", by = "Various Artists", connection = under) as Asking.Ask).text
        fun said(): String = said(2)
        got(wait { Core.pageSearch(first, place, "film music") {} })
        assertTrue(said(1), "soundtrack" in said(1))
        // The connection changes: something is asked under the second.
        route("album/lookup", "[]")
        got(wait { Core.pageSearch(second, place, "anything") {} })
        assertFalse(said(), "soundtrack" in said())
        // (And the first connection's offers are gone: nothing can be asked of it any more.)
        assertFalse(said(1), "soundtrack" in said(1))
        // Something of the first connection's that was still waiting its turn runs only now.
        // What it is offered is the first Lidarr's, and is nothing to the second.
        route("album/lookup", """[{"title":"Film Music","foreignAlbumId":"ost-1","albumType":"Album","secondaryTypes":["Soundtrack"],"artist":{"artistName":"Various Artists","foreignArtistId":"$various"}}]""")
        got(wait { Core.pageSearch(first, place, "film music") {} })
        assertFalse("the second connection was handed what the first Lidarr offered: ${said()}", "soundtrack" in said())
    }

    @Test
    fun `an album taken off Lidarr's list is no longer watched for`() {
        val wanted = lidarrWithASingle()
        assertEquals(Outcome.Got("Quillon Vane"), wait { Core.request(lidarr, place, "single-1").done.await() })
        Core.noted(place, "single-1", "One Song", "Quillon Vane", song = "Its Song", now = 1_000)
        assertEquals(Outcome.Got(true), wait { Core.lidarrCancel(lidarr, 7) })
        assertFalse(wanted())
        val forgotten = Core.askedForget(place, "One Song", "Quillon Vane")
        assertEquals(true to emptyList<Asked>(), forgotten.changed to forgotten.asked)
        assertEquals("“One Song” is off the list", Words.offTheList("One Song"))
        assertFalse(Core.askedForget(place, "One Song", "Quillon Vane").changed)
    }

    @Test
    fun `the question before an artist is added, and adding them`() {
        assertEquals("Add Marrow Lake?" to "Lidarr will look for all 3 albums by Marrow Lake that you don't have, and for new ones as they come out. That can be a lot to fetch.", Core.adding("Marrow Lake", 3))
        assertEquals("Added Marrow Lake. Their albums are on Lidarr's list", Words.added("Marrow Lake"))
        // An artist Lidarr keeps track of already: their albums not yet wanted are asked for.
        var monitored: String? = null
        route("/api/v1/") { asked, reply ->
            val path = asked.target.substringAfter("/api/v1/")
            when {
                asked.method == "GET" && path == "artist" -> reply.json("""[{"id":4,"foreignArtistId":"ml","artistName":"Marrow Lake","monitored":false,"metadataProfileId":1,"monitorNewItems":"none"}]""")
                asked.method == "GET" && path == "artist/4" -> reply.json("""{"id":4,"foreignArtistId":"ml","artistName":"Marrow Lake","monitored":false,"metadataProfileId":1,"monitorNewItems":"none"}""")
                asked.method == "PUT" && path == "artist/4" -> reply.json(asked.body)
                asked.method == "GET" && path.startsWith("album?artistId=4") -> reply.json("""[{"id":11,"title":"Iron Kite","monitored":false},{"id":12,"title":"Other","monitored":true}]""")
                asked.method == "PUT" && path == "album/monitor" -> {
                    monitored = asked.body
                    reply.json("[]")
                }
                asked.method == "POST" && path == "command" -> reply.json("{}")
                else -> reply.send(404)
            }
        }
        assertEquals(Outcome.Got(1), wait { Core.addArtist(lidarr, place, "ml", "Marrow Lake").done.await() })
        assertEquals("""{"albumIds":[11],"monitored":true}""", monitored)
    }

    @Test
    fun `artists the person might like, kept only when the look is finished`() = runBlocking {
        // The favourites: by plays, then by how much there is of them.
        val favourites = Core.discoverFavourites(listOf(Core.Played("a1", "Often", 9), Core.Played("a2", "Seldom", 1), Core.Played("a1", "Often", 3), Core.Played("", "Nobody", 99)))
        assertEquals(listOf("a1" to "Often", "a2" to "Seldom"), favourites)

        Core.placeLibrary(place, LibraryLists(artists = listOf("Often", "Seldom", "Already Here")))
        route("api/v1/artist", """[{"id":1,"foreignArtistId":"tracked","artistName":"Tracked Already"}]""")
        route("artist/lookup") { asked, reply ->
            val name = java.net.URLDecoder.decode(asked.target.substringAfter("term="), "UTF-8")
            val id = name.lowercase().replace(" ", "-")
            reply.json("""[{"artistName":"$name","foreignArtistId":"${if (name == "Tracked Already") "tracked" else id}","images":[{"coverType":"poster","remoteUrl":"http://pictures.test/$id.jpg"}]}]""")
        }
        val similar = listOf("Often" to listOf("New One", "Already Here", "Tracked Already"), "Seldom" to listOf("New One", "New Two"))
        val kept = got(withTimeout(60_000) { Core.discoverLook(lidarr, place, similar, now = 10_000) })
        assertEquals(10_000L, kept.at)
        // Named by both favourites first; who the library has, and who Lidarr keeps track of, left out.
        assertEquals(listOf("New One" to "Often", "New Two" to "Seldom"), kept.artists.map { it.name to it.like })
        assertEquals("http://pictures.test/new-one.jpg", kept.artists.first().cover)

        // What is kept, read as the library reads it.
        val read = Core.discoverKept(place, kept, now = 10_000 + 3600)
        assertTrue(read.fresh)
        assertEquals(listOf("New One", "New Two"), read.shown.map { it.name })
        assertFalse(Core.discoverKept(place, kept, now = 10_000 + 4 * 24 * 3600L).fresh)
        // The library gains one of them: no longer shown, though still kept.
        Core.placeLibrary(place, LibraryLists(artists = listOf("Often", "Seldom", "New Two")))
        assertEquals(listOf("New One"), Core.discoverKept(place, kept, now = 10_000).shown.map { it.name })
        // One is added to Lidarr: forgotten.
        val forgot = Core.discoverKept(place, kept, now = 10_000, forget = kept.artists.first().id)
        assertTrue(forgot.changed)
        assertEquals(listOf("New Two"), forgot.kept.artists.map { it.name })

        // A look that is stopped is no answer: the caller is cancelled, and has nothing to keep.
        val reached = CountDownLatch(1)
        route("artist/lookup") { _, reply ->
            reached.countDown()
            Thread.sleep(3_000)
            runCatching { reply.json("[]") }
        }
        val looking = async(Dispatchers.Default) { Core.discoverLook(lidarr, place, similar, now = 20_000) }
        assertTrue(reached.await(20, TimeUnit.SECONDS))
        looking.cancelAndJoin()
        assertTrue(looking.isCancelled)
        assertTrue(runCatching { looking.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `a place that has been closed is asked nothing`() {
        val gone = Core.placeOpen()
        Core.placeClose(gone)
        val thrown = runCatching { Core.asking(gone, "x", "X") }.exceptionOrNull()
        assertTrue("$thrown", thrown is CoreException && thrown.message == "That connection to Lidarr is no longer open.")
    }
}
