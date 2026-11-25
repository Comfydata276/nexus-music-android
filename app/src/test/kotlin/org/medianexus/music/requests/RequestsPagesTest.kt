package org.medianexus.music.requests

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.core.AlbumPage
import org.medianexus.music.core.Asking
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.core.LibraryAlbum
import org.medianexus.music.core.LibraryLists
import org.medianexus.music.core.StandIn
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.SecretBox
import org.medianexus.music.store.accountKey

/**
 * What the screens of requests will use ([RequestsPages]), for an account connected to a
 * stand-in Lidarr: that each page and action reaches the library and comes back as
 * something to show; what is kept on the phone and in what order; when arrivals are said;
 * and what happens to something asked for when the screen that asked goes away.
 *
 * (What the library decides for each page is the library's own tests'; that each call
 * arrives in the right shape is `core/PagesTest`'s.)
 */
class RequestsPagesTest {
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)
    @get:Rule val folder = TemporaryFolder()

    private val closing = mutableListOf<StandIn>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val behind = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notes = CopyOnWriteArrayList<String>()

    private object Plain : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = plain
        override fun open(sealed: ByteArray): ByteArray = sealed
    }

    private val routes = CopyOnWriteArrayList<Pair<String, (StandIn.Asked, StandIn.Reply) -> Unit>>()
    private fun route(part: String, json: String) = routes.add(0, part to { _, reply -> reply.json(json) })
    private fun route(part: String, answer: (StandIn.Asked, StandIn.Reply) -> Unit) = routes.add(0, part to answer)

    /** Lidarr, MusicBrainz and ListenBrainz in one (as `PagesTest` has it). */
    private val server = StandIn { asked, reply ->
        val answer = routes.firstOrNull { (part, _) -> part in asked.line }?.second
        if (answer == null) reply.json("[]", 404) else answer(asked, reply)
    }.also { closing += it }

    private val library = MutableStateFlow(LibraryLists())
    /** (A time of day in the world as it is: with a clock near 1970, suggestions never
     *  looked for would pass for looked for lately.) */
    private val start = 1_800_000_000L
    private val now = AtomicLong(start)
    private val account = Account("http://music.example", "tester", accountKey("http://music.example", "tester"), AccountDirs(File("unset"), File("unset")))

    init {
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
        scopes.forEach { it.cancel() }
        behind.cancel()
        closing.forEach { it.close() }
        CoreHttp.dress = {}
    }

    private class Made(val requests: Requests, val pages: RequestsPages, val scope: CoroutineScope, val dir: File)

    /** An account's requests, as the app makes them; `similar` is the music server's word
     *  on who an artist is like. */
    private fun made(connected: Boolean = true, similar: suspend (String) -> List<String> = { emptyList() }, played: () -> List<Core.Played> = { emptyList() }): Made = runBlocking {
        val dir = File(folder.root, "data").apply { mkdirs() }
        val mine = account.copy(dirs = AccountDirs(dir, File(folder.root, "cache")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val requests = Requests(mine, scope, RequestsSecrets(File(folder.root, "sealed"), Plain, mine.server, mine.username), afterwards = { work -> behind.launch { work() } }, note = { notes += it })
        if (connected) {
            route("system/status", """{"version":"2.9.6.4552"}""")
            requests.switch(true)
            val state = requests.connect(server.address, "k")
            assertTrue("$state", state is RequestsState.Connected)
        }
        Made(requests, RequestsPages(requests, scope, dir, library, played, similar, note = { notes += it }, clock = { now.get() }), scope, dir)
    }

    private fun <T> got(found: Found<T>): T = (found as? Found.Got<T>)?.value ?: throw AssertionError("expected something found, got $found")

    private fun quietBand() {
        route("artist/lookup", """[{"artistName":"Quiet Band","foreignArtistId":"qb"}]""")
        route("album/lookup", """[{"title":"Soft","foreignAlbumId":"rg-s","albumType":"Album","artist":{"artistName":"Quiet Band","foreignArtistId":"qb"}}]""")
        route(
            "recording?query",
            """{"recordings":[{"id":"r1","title":"Soft Song","score":100,"artist-credit":[{"name":"Quiet Band","artist":{"id":"qb","name":"Quiet Band"}}],""" +
                """"releases":[{"status":"Official","date":"2001","release-group":{"id":"rg-s","title":"Soft","primary-type":"Album","secondary-types":[]}}]}]}""",
        )
    }

    /** A Lidarr with one artist and one single of theirs, which can be asked for; `slow`
     *  holds each answer while it is set. Says whether the single is wanted. */
    private fun lidarrWithASingle(slow: AtomicBoolean = AtomicBoolean(false)): () -> Boolean {
        var newReleases = "all"
        var profile = 1
        var wanted = false
        fun artist() = """{"id":1,"foreignArtistId":"artist-1","artistName":"Quillon Vane","monitored":true,"metadataProfileId":$profile,"monitorNewItems":"$newReleases"}"""
        val standard = """{"id":1,"name":"Standard","primaryAlbumTypes":[{"albumType":{"name":"Album"},"allowed":true},{"albumType":{"name":"EP"},"allowed":false},{"albumType":{"name":"Single"},"allowed":false}],""" +
            """"secondaryAlbumTypes":[{"albumType":{"name":"Studio"},"allowed":true}],"releaseStatuses":[{"releaseStatus":{"name":"Official"},"allowed":true}]}"""
        route("/api/v1/") { asked, reply ->
            val path = asked.target.substringAfter("/api/v1/")
            while (slow.get()) Thread.sleep(20)
            when {
                path == "system/status" -> reply.json("""{"version":"2.9.6.4552"}""")
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
                path.startsWith("queue") -> reply.json("""{"records":[]}""")
                else -> reply.send(404)
            }
        }
        return { wanted }
    }

    @Test
    fun `not connected, each page says so in the library's sentence and nothing is asked of anyone`() = runBlocking {
        val made = made(connected = false)
        assertEquals(Found.Not("Not connected yet."), made.pages.search("quiet band"))
        assertEquals(Found.Not("Not connected yet."), made.pages.artist("qb"))
        assertEquals(Found.Not("Not connected yet."), made.pages.onTheirWay())
        assertEquals(Said(false, "Not connected yet."), made.pages.request("rg-s", "Soft"))
        assertEquals(emptyList<Any>(), made.pages.suggest())
        assertTrue(server.asked.isEmpty())
    }

    @Test
    fun `a search fills as the answers arrive, and its pages follow from what it offers`() = runBlocking {
        quietBand()
        val made = made()
        val stages = CopyOnWriteArrayList<String>()
        val found = got(made.pages.search("quiet band") { stages += it.note })
        assertEquals(listOf("Looking for songs beyond your library…", found.note), stages.toList())
        val song = found.songs.single().first
        // The song's album can be asked about, in the library's words, without asking anyone.
        val asking = made.pages.asking(song.album, song.albumTitle, by = song.artistName, song = song.title) as Asking.Ask
        assertEquals("Ask for this album?", asking.heading)
        assertEquals("“Soft Song” is on “Soft” by Quiet Band. That album will go on Lidarr's list, and Lidarr will start looking for it. The whole album is fetched.", asking.text)
        // And its page opens from what was offered.
        route("release?release-group=rg-s", """{"releases":[{"media":[{"position":1,"tracks":[{"title":"Soft Song","length":200000,"position":1}]}]}]}""")
        val page = got(made.pages.album(song.album)) as AlbumPage.Found
        assertEquals("Request album", page.album.action)
    }

    @Test
    fun `asking for an album keeps the note, then marks the offer, and its arrival is said once`() = runBlocking {
        val wanted = lidarrWithASingle()
        val made = made()
        assertEquals("Asking for “One Song”…", made.pages.askingFor("One Song"))
        assertEquals(Said(true, "Asked for “One Song”. It'll be in your library once it arrives."), made.pages.request("single-1", "One Song", song = "Its Song"))
        assertTrue(wanted())
        // Kept on the phone, and shown as asked for.
        val note = made.pages.asked.value.single()
        assertEquals(listOf("single-1", "One Song", "Quillon Vane", "Its Song", start), listOf(note.album, note.title, note.artist, note.song, note.at))
        assertTrue(File(made.dir, "asked.json").readText().contains("\"One Song\""))
        assertEquals(Asking.Already("That one has already been asked for."), made.pages.asking("single-1", "One Song"))
        assertEquals("On their way (1)", got(made.pages.onTheirWay()).heading)

        // It turns up in the phone's copy of the library: said, once.
        val said = CopyOnWriteArrayList<String>()
        val listening = launch(Dispatchers.Default) { made.pages.news.collect { said += it } }
        delay(100)
        now.set(start + 4_000)
        library.value = LibraryLists(albums = listOf(LibraryAlbum("al-9", "One Song", "Quillon Vane", "cover-9")), songs = listOf(listOf("Its Song", "Quillon Vane")), artists = listOf("Quillon Vane"))
        withTimeout(20_000) { made.pages.asked.first { it.singleOrNull()?.arrived == start + 4_000 } }
        delay(300)
        made.pages.checkArrivals()
        made.pages.onTheirWay()
        delay(300)
        assertEquals(listOf("“Its Song” has arrived, on “One Song”."), said.toList())
        assertEquals("al-9", made.pages.asked.value.single().libraryAlbum)
        listening.cancel()

        // A new run of the app reads what was kept: it is still there, and not said again.
        val again = RequestsPages(made.requests, made.scope, made.dir, library, { emptyList() }, { emptyList() }, clock = { now.get() })
        again.checkArrivals()
        assertEquals("al-9", again.asked.value.single().libraryAlbum)
        assertEquals("al-9", got(again.onTheirWay()).arrived.single().first.libraryAlbum)
    }

    @Test
    fun `asked for and then the screen gone, a request that had begun is finished and written down, and one that hadn't changes nothing`() = runBlocking {
        val slow = AtomicBoolean(false)
        val wanted = lidarrWithASingle(slow)
        val made = made()
        // Stopped before Lidarr has been changed at all: nothing is asked for, nothing kept.
        slow.set(true)
        val early = async(Dispatchers.Default) { made.pages.request("single-1", "One Song") }
        assertTrue(server.awaitAsked { "album/lookup" in it.target })
        early.cancelAndJoin()
        slow.set(false)
        delay(1_500)
        assertFalse(wanted())
        assertEquals(emptyList<Any>(), made.pages.asked.value)
        assertFalse(server.asked.any { it.method != "GET" })

        // Stopped once Lidarr has begun to be changed: it is seen through, and noted.
        val late = async(Dispatchers.Default) { made.pages.request("single-1", "One Song") }
        assertTrue(server.awaitAsked(20_000) { it.method == "PUT" && it.target.endsWith("artist/1") })
        late.cancelAndJoin()
        assertTrue(late.isCancelled)
        withTimeout(40_000) { made.pages.asked.first { it.isNotEmpty() } }
        assertTrue(wanted())
        assertEquals("One Song", made.pages.asked.value.single().title)
        assertEquals(Asking.Already("That one has already been asked for."), made.pages.asking("single-1", "One Song"))
    }

    @Test
    fun `an album taken off the list is no longer watched for`() = runBlocking {
        val wanted = lidarrWithASingle()
        val made = made()
        made.pages.request("single-1", "One Song")
        val pending = got(made.pages.onTheirWay()).pending.single().first
        assertEquals(Said(true, "“One Song” is off the list"), made.pages.cancel(pending.id, pending.title))
        assertFalse(wanted())
        assertEquals(emptyList<Any>(), made.pages.asked.value)
        assertEquals("[]", File(made.dir, "asked.json").readText().trim())
    }

    @Test
    fun `artists the person might like are looked for once, kept, and an artist added is suggested no more`() = runBlocking {
        route("api/v1/artist", "[]")
        route("artist/lookup") { asked, reply ->
            val name = java.net.URLDecoder.decode(asked.target.substringAfter("term="), "UTF-8")
            reply.json("""[{"artistName":"$name","foreignArtistId":"${name.lowercase().replace(" ", "-")}"}]""")
        }
        val askedOfTheServer = CopyOnWriteArrayList<String>()
        val made = made(
            similar = { id -> askedOfTheServer += id; if (id == "a1") listOf("New One", "New Two") else listOf("New One") },
            played = { listOf(Core.Played("a1", "Often", 9), Core.Played("a2", "Seldom", 1)) },
        )
        // With no copy of the library yet, nothing is looked for.
        assertEquals(emptyList<Any>(), made.pages.suggest())
        assertTrue(askedOfTheServer.isEmpty())
        library.value = LibraryLists(songs = listOf(listOf("A", "Often")), artists = listOf("Often", "Seldom"))
        delay(500)
        assertEquals(listOf("New One", "New Two"), made.pages.suggest().map { it.name })
        assertEquals(listOf("a1", "a2"), askedOfTheServer.sorted())
        assertEquals(made.pages.suggestions.value, made.pages.suggest())
        // Kept: asked again within three days, nobody is asked anything.
        val before = server.asked.size
        now.addAndGet(3600)
        assertEquals(2, made.pages.suggest().size)
        assertEquals(before, server.asked.size)
        assertEquals(2, askedOfTheServer.size)
        assertTrue(File(made.dir, "suggestions.json").readText().contains("New Two"))

        // One of them is added to Lidarr: no longer suggested, here or after a restart.
        route("/api/v1/artist") { asked, reply -> if (asked.method == "POST") reply.json("""{"id":9,"foreignArtistId":"new-one","artistName":"New One"}""") else reply.json("[]") }
        route("rootfolder", """[{"id":1,"path":"/music","defaultMetadataProfileId":1,"defaultQualityProfileId":1}]""")
        route("POST /api/v1/command", "{}")
        val (heading, _) = made.pages.adding("New One", 2)
        assertEquals("Add New One?", heading)
        assertEquals(Said(true, "Added New One. Their albums are on Lidarr's list"), made.pages.addArtist("new-one", "New One"))
        assertEquals(listOf("New Two"), made.pages.suggestions.value.map { it.name })
        val again = RequestsPages(made.requests, made.scope, made.dir, library, { emptyList() }, { emptyList() }, clock = { now.get() })
        delay(300)
        assertEquals(listOf("New Two"), again.suggest().map { it.name })
    }

    @Test
    fun `what was asked for that can't be read is left as it is, and nothing is written over it`() = runBlocking {
        lidarrWithASingle()
        val dir = File(folder.root, "data").apply { mkdirs() }
        // (A folder where the file should be: there, and not to be read.)
        File(dir, "asked.json").mkdirs()
        val made = made()
        assertEquals(Said(true, "Asked for “One Song”. It'll be in your library once it arrives."), made.pages.request("single-1", "One Song"))
        assertTrue(File(dir, "asked.json").isDirectory)
        assertTrue("$notes", notes.any { "asked.json" in it && "couldn't be read" in it })
        assertTrue("$notes", notes.any { "couldn't be kept on the phone" in it })
        // It is still shown as asked for, for as long as the app runs.
        assertEquals("One Song", made.pages.asked.value.single().title)
    }

    @Test
    fun `once the account has gone nothing more is asked for it`() = runBlocking {
        quietBand()
        val made = made()
        got(made.pages.search("quiet band"))
        made.scope.cancel()
        delay(300)
        val before = server.asked.size
        val after = runCatching { made.pages.search("quiet band") }
        assertTrue("$after", after.exceptionOrNull() is kotlin.coroutines.cancellation.CancellationException)
        assertEquals(before, server.asked.size)
        // (And a wait that would be left behind is not: this ended.)
        assertTrue(CountDownLatch(0).await(1, TimeUnit.SECONDS))
    }
}
