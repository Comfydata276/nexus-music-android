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
        Made(requests, RequestsPages(requests, scope, dir, library, played, similar, note = { notes += it }, clock = { now.get() }, afterwards = { work -> behind.launch { work() } }), scope, dir)
    }

    /** The connection as it is now: what a page shown this moment would be found under. */
    private suspend fun Made.now(): Long = requests.lidarr()!!.connection

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
        assertEquals(Said(false, "Not connected yet."), made.pages.request("rg-s", "Soft", connection = 0))
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
        assertEquals(Said(true, "Asked for “One Song”. It'll be in your library once it arrives."), made.pages.request("single-1", "One Song", song = "Its Song", connection = made.now()))
        assertTrue(wanted())
        // Kept on the phone, and shown as asked for.
        val note = made.pages.asked.value.single()
        assertEquals(listOf("single-1", "One Song", "Quillon Vane", "Its Song", start), listOf(note.album, note.title, note.artist, note.song, note.at))
        assertTrue(File(made.dir, "asked.json").readText().contains("\"One Song\""))
        assertEquals(Asking.Already("That one has already been asked for."), made.pages.asking("single-1", "One Song"))
        assertEquals("On their way (1)", got(made.pages.onTheirWay()).heading)

        // It turns up in the phone's copy of the library while no screen is listening: the
        // news is kept, and said once, to the first that does.
        now.set(start + 4_000)
        library.value = LibraryLists(albums = listOf(LibraryAlbum("al-9", "One Song", "Quillon Vane", "cover-9")), songs = listOf(listOf("Its Song", "Quillon Vane")), artists = listOf("Quillon Vane"))
        withTimeout(20_000) { made.pages.asked.first { it.singleOrNull()?.arrived == start + 4_000 } }
        delay(300)
        made.pages.checkArrivals()
        made.pages.onTheirWay()
        val said = CopyOnWriteArrayList<String>()
        val listening = launch(Dispatchers.Default) { made.pages.news.collect { said += it } }
        withTimeout(10_000) { while (said.isEmpty()) delay(20) }
        made.pages.checkArrivals()
        delay(300)
        assertEquals(listOf("“Its Song” has arrived, on “One Song”."), said.toList())
        assertEquals("al-9", made.pages.asked.value.single().libraryAlbum)
        listening.cancel()

        // A new run of the app reads what was kept: it is still there, and not said again.
        val again = RequestsPages(made.requests, made.scope, made.dir, library, { emptyList() }, { emptyList() }, clock = { now.get() }, afterwards = { work -> behind.launch { work() } })
        again.checkArrivals()
        assertEquals("al-9", again.asked.value.single().libraryAlbum)
        assertEquals("al-9", got(again.onTheirWay()).arrived.single().first.libraryAlbum)
    }

    /** The account's pages as a new run of the app makes them, over what the last one kept. */
    private fun anotherRun(made: Made): Pair<RequestsPages, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return RequestsPages(made.requests, scope, made.dir, library, { emptyList() }, { emptyList() }, note = { notes += it }, clock = { now.get() }, afterwards = { work -> behind.launch { work() } }) to scope
    }

    @Test
    fun `an arrival found and never said, the app having stopped first, is said by its next run, and only once ever`() = runBlocking {
        lidarrWithASingle()
        val made = made()
        assertTrue(made.pages.request("single-1", "One Song", song = "Its Song", connection = made.now()).done)
        // It turns up in the library, and is found; nobody is listening; and the app stops.
        now.set(start + 4_000)
        library.value = LibraryLists(albums = listOf(LibraryAlbum("al-9", "One Song", "Quillon Vane", "cover-9")), songs = listOf(listOf("Its Song", "Quillon Vane")), artists = listOf("Quillon Vane"))
        withTimeout(20_000) { made.pages.asked.first { it.singleOrNull()?.arrived == start + 4_000 } }
        delay(300)
        made.scope.cancel()

        // The next run says it, to whoever listens first.
        val (second, secondScope) = anotherRun(made)
        val said = CopyOnWriteArrayList<String>()
        val listening = launch(Dispatchers.Default) { second.news.collect { said += it } }
        withTimeout(20_000) { while (said.isEmpty()) delay(20) }
        delay(500)
        assertEquals(listOf("“Its Song” has arrived, on “One Song”."), said.toList())
        listening.cancel()
        secondScope.cancel()

        // And no run after that says it again: it was said.
        val (third, _) = anotherRun(made)
        val again = launch(Dispatchers.Default) { third.news.collect { said += it } }
        third.checkArrivals()
        delay(1_500)
        assertEquals(1, said.size)
        again.cancel()
    }

    @Test
    fun `news is for the account it happened to, an account that has gone says nothing more`() = runBlocking {
        lidarrWithASingle()
        val made = made()
        val said = CopyOnWriteArrayList<String>()
        val listening = launch(Dispatchers.Default) { made.pages.news.collect { said += it } }
        delay(300)
        // The account goes (signed out of the music server): whoever was listening to it is
        // let go, and hears nothing that happens afterwards.
        made.scope.cancel()
        withTimeout(5_000) { listening.join() }
        assertTrue(said.isEmpty())
        // One who starts listening to it now hears nothing either, and is let go at once.
        withTimeout(5_000) { made.pages.news.collect { said += it } }
        assertTrue(said.isEmpty())
    }

    @Test
    fun `asked for and then the screen gone, a request that had begun is finished and written down, and one that hadn't changes nothing`() = runBlocking {
        val slow = AtomicBoolean(false)
        val wanted = lidarrWithASingle(slow)
        val made = made()
        // Stopped before Lidarr has been changed at all: nothing is asked for, nothing kept.
        slow.set(true)
        val early = async(Dispatchers.Default) { made.pages.request("single-1", "One Song", connection = made.now()) }
        assertTrue(server.awaitAsked { "album/lookup" in it.target })
        early.cancelAndJoin()
        slow.set(false)
        delay(1_500)
        assertFalse(wanted())
        assertEquals(emptyList<Any>(), made.pages.asked.value)
        assertFalse(server.asked.any { it.method != "GET" })

        // Stopped once Lidarr has begun to be changed: it is seen through, and noted.
        val late = async(Dispatchers.Default) { made.pages.request("single-1", "One Song", connection = made.now()) }
        assertTrue(server.awaitAsked(20_000) { it.method == "PUT" && it.target.endsWith("artist/1") })
        late.cancelAndJoin()
        assertTrue(late.isCancelled)
        withTimeout(40_000) { made.pages.asked.first { it.isNotEmpty() } }
        assertTrue(wanted())
        assertEquals("One Song", made.pages.asked.value.single().title)
        assertEquals(Asking.Already("That one has already been asked for."), made.pages.asking("single-1", "One Song"))
    }

    @Test
    fun `asked for, and the account signed out before Lidarr had finished, what Lidarr accepted is still written down for that account`() = runBlocking {
        val slow = AtomicBoolean(false)
        val wanted = lidarrWithASingle(slow)
        val made = made()
        val asking = async(Dispatchers.Default) { runCatching { made.pages.request("single-1", "One Song", song = "Its Song", connection = made.now()) } }
        // Lidarr has begun to be changed...
        assertTrue(server.awaitAsked(20_000) { it.method == "PUT" && it.target.endsWith("artist/1") })
        // ...and now the screen goes, and the account with it (signed out of the music server).
        slow.set(true)
        asking.cancel()
        made.scope.cancel()
        delay(300)
        slow.set(false)
        // Lidarr is seen through all the same, and the note is in the account's own folder.
        val file = File(made.dir, "asked.json")
        withTimeout(40_000) { while (!(file.exists() && "\"One Song\"" in file.readText())) delay(50) }
        assertTrue(wanted())
        assertTrue(file.readText(), "\"Its Song\"" in file.readText())
        // A new run of the app, the same account signed in again, finds it.
        val again = made(connected = false)
        again.pages.checkArrivals()
        assertEquals("One Song", again.pages.asked.value.single().title)
    }

    @Test
    fun `taken off the list, and the screen gone while Lidarr was answering, it is forgotten here all the same`() = runBlocking {
        val slow = AtomicBoolean(false)
        val wanted = lidarrWithASingle(slow)
        val made = made()
        assertTrue(made.pages.request("single-1", "One Song", connection = made.now()).done)
        val pending = got(made.pages.onTheirWay()).pending.single().first
        val before = server.asked.size
        // Lidarr is slow to answer the change, and the screen that asked is left meanwhile.
        slow.set(true)
        val taking = async(Dispatchers.Default) { runCatching { made.pages.cancel(pending.id, pending.title, connection = made.now()) } }
        assertTrue(server.awaitAsked(10_000) { asked -> server.asked.drop(before).any { it === asked && it.method == "PUT" && "\"monitored\":false" in it.body } })
        taking.cancel()
        delay(300)
        slow.set(false)
        // Lidarr took it off: so it is no longer watched for here, whoever is still looking.
        val file = File(made.dir, "asked.json")
        withTimeout(20_000) { while (file.readText().trim() != "[]") delay(50) }
        assertFalse(wanted())
        assertEquals(emptyList<Any>(), made.pages.asked.value)
    }

    @Test
    fun `an album taken off the list is no longer watched for`() = runBlocking {
        val wanted = lidarrWithASingle()
        val made = made()
        made.pages.request("single-1", "One Song", connection = made.now())
        val pending = got(made.pages.onTheirWay()).pending.single().first
        assertEquals(Said(true, "“One Song” is off the list"), made.pages.cancel(pending.id, pending.title, connection = made.now()))
        assertFalse(wanted())
        assertEquals(emptyList<Any>(), made.pages.asked.value)
        assertEquals("[]", File(made.dir, "asked.json").readText().trim())
    }

    // ---- a row acts on the connection it came from ------------------------------------------------

    /** Another Lidarr altogether, which takes anything: so what it was sent shows what
     *  would have been changed on it. */
    private fun anotherLidarr() = StandIn { asked, reply ->
        val path = asked.target.substringAfter("/api/v1/", "")
        when {
            path == "system/status" -> reply.json("""{"version":"9.9.9.9"}""")
            path.startsWith("wanted/missing") -> reply.json("""{"totalRecords":1,"records":[{"id":7,"title":"Somebody Else's","artist":{"artistName":"Another"}}]}""")
            path.startsWith("queue") -> reply.json("""{"records":[]}""")
            path.startsWith("album/lookup") -> reply.json("""[{"title":"One Song","foreignAlbumId":"single-1","albumType":"Album","artist":{"artistName":"Quillon Vane","foreignArtistId":"artist-1"}}]""")
            path == "rootfolder" -> reply.json("""[{"id":1,"path":"/music","defaultMetadataProfileId":1,"defaultQualityProfileId":1}]""")
            asked.method == "GET" -> reply.json("[]")
            else -> reply.json("{}")
        }
    }.also { closing += it }

    private val changedWords = "The connection to Lidarr has changed since this was shown. Open it again."

    @Test
    fun `what was shown under one Lidarr changes nothing on another`() = runBlocking {
        val wanted = lidarrWithASingle()
        val made = made()
        // Under the first Lidarr: an album asked for, and listed as on its way.
        assertTrue(made.pages.request("single-1", "One Song", connection = made.now()).done)
        val way = made.pages.onTheirWay() as Found.Got
        val row = way.value.pending.single().first
        assertEquals(7L, row.id)
        val first = way.connection

        // The address is changed to another Lidarr (which has an album numbered 7 of its own).
        val other = anotherLidarr()
        assertTrue(made.requests.connect(other.address, "k") is RequestsState.Connected)
        val before = other.asked.size

        // The rows still on the screen were the first Lidarr's: each is refused, in one
        // sentence, and the other Lidarr is asked nothing at all for them. Its number for
        // an album is refused whether or not the screen says where its row came from.
        assertEquals(Said(false, changedWords, stale = true), made.pages.cancel(row.id, row.title, connection = made.now()))
        assertEquals(Said(false, changedWords, stale = true), made.pages.cancel(row.id, row.title, connection = first))
        assertEquals(Said(false, changedWords, stale = true), made.pages.request("single-1", "One Song", connection = first))
        assertEquals(Said(false, changedWords, stale = true), made.pages.addArtist("artist-1", "Quillon Vane", connection = first))
        delay(300)
        assertEquals("the other Lidarr was sent ${other.asked.drop(before).map { it.line }}", before, other.asked.size)
        // And the first one's note stands: it was asked for there.
        assertTrue(wanted())
        assertEquals("One Song", made.pages.asked.value.single().title)

        // Shown afresh under the other Lidarr, which has a 7 of its own. The old row is
        // still the first Lidarr's, and still refused; the new one can be acted on.
        val now = made.pages.onTheirWay() as Found.Got
        val theirs = now.value.pending.single().first
        assertEquals("Somebody Else's" to 7L, theirs.title to theirs.id)
        assertTrue(now.connection != first)
        assertEquals(Said(false, changedWords, stale = true), made.pages.cancel(row.id, row.title, connection = first))
        assertTrue(other.asked.none { it.method == "PUT" })
        assertEquals(Said(true, "“Somebody Else's” is off the list"), made.pages.cancel(theirs.id, theirs.title, connection = now.connection))
        assertTrue(other.asked.any { it.method == "PUT" && "\"albumIds\":[7]" in it.body })
        // What a search hands over as it goes says which connection it is found under too.
        route("recording?query", """{"recordings":[]}""")
        val soFar = CopyOnWriteArrayList<Long>()
        val found = made.pages.searchShowing("one song") { soFar += it.connection } as Found.Got
        assertEquals(now.connection, found.connection)
        assertTrue("$soFar", soFar.isNotEmpty() && soFar.all { it == found.connection })
    }

    @Test
    fun `a page loaded under one connection and pressed after the address has changed, the press is refused and said, the page asks again, and nothing is sent`() = runBlocking<Unit> {
        lidarrWithASingle()
        val made = made()
        assertTrue(made.pages.request("single-1", "One Song", connection = made.now()).done)
        // The screens' own pieces, as the app puts them together: the Requests page and an
        // album's page, each loaded now; and the asker their rows and buttons press.
        val requestsPage = org.medianexus.music.ui.requests.PageLoad { made.pages.onTheirWay() }
        val albumPage = org.medianexus.music.ui.requests.PageLoad { made.pages.album("single-1") }
        val loading = listOf(launch(Dispatchers.Default) { requestsPage.run() }, launch(Dispatchers.Default) { albumPage.run() })
        val onTheirWay = withTimeout(30_000) { requestsPage.state.first { it.value != null } }
        val album = withTimeout(30_000) { albumPage.state.first { it.value != null } }
        val row = onTheirWay.value!!.pending.single().first
        assertTrue(onTheirWay.connection != 0L && onTheirWay.connection == album.connection)
        val said = CopyOnWriteArrayList<String>()
        val askedAgain = java.util.concurrent.atomic.AtomicInteger()
        val show = object : org.medianexus.music.ui.requests.AskShow {
            override fun say(words: String, wayIn: Boolean) {
                said += words
            }

            // (The person says yes to every question.)
            override fun confirm(heading: String, text: String, button: org.medianexus.music.ui.requests.AskShow.Button, onYes: () -> Unit) = onYes()

            override fun changed() {
                askedAgain.incrementAndGet()
            }
        }
        val asker = org.medianexus.music.ui.requests.Asker(behind, behind, org.medianexus.music.ui.requests.askOps(made.pages), show, org.medianexus.music.ui.requests.UnderWay())

        // The address is changed to another Lidarr; the pages are still on the screen.
        val other = anotherLidarr()
        assertTrue(made.requests.connect(other.address, "k") is RequestsState.Connected)
        val before = other.asked.size

        // A row of the Requests page is pressed: refused, in the one sentence, and the pages
        // are told to ask again.
        asker.takeOff(row.id, row.title, under = onTheirWay.connection)
        withTimeout(10_000) { while (said.size < 1) delay(20) }
        assertEquals(listOf(changedWords), said.toList())
        assertEquals(1, askedAgain.get())
        // The album page's button: the question, yes, and the same.
        asker.album("single-1", "One Song", "Quillon Vane", under = album.connection)
        withTimeout(10_000) { while (said.none { it == "Asking for “One Song”…" } || said.count { it == changedWords } < 2) delay(20) }
        assertEquals(2, askedAgain.get())
        // "Add artist", from a page of the old connection.
        asker.artist("artist-1", "Quillon Vane", open = 1, under = album.connection)
        withTimeout(10_000) { while (said.count { it == changedWords } < 3) delay(20) }
        assertEquals(3, askedAgain.get())
        delay(300)
        // Nothing was sent to the Lidarr the app is now pointed at.
        assertEquals("the other Lidarr was sent ${other.asked.drop(before).map { it.line }}", before, other.asked.size)
        loading.forEach { it.cancel() }
    }

    @Test
    fun `the connection changed while Lidarr was being asked, what the first Lidarr accepted is written down and the second is untouched`() = runBlocking {
        val slow = AtomicBoolean(false)
        val wanted = lidarrWithASingle(slow)
        val made = made()
        val asking = async(Dispatchers.Default) { made.pages.request("single-1", "One Song", connection = made.now()) }
        assertTrue(server.awaitAsked(20_000) { it.method == "PUT" && it.target.endsWith("artist/1") })
        // Lidarr has begun to be changed; and now the person points the app at another one.
        slow.set(true)
        val other = anotherLidarr()
        assertTrue(made.requests.connect(other.address, "k") is RequestsState.Connected)
        val before = other.asked.size
        slow.set(false)
        // The first Lidarr is seen through, and the person is told; the note is kept.
        assertEquals(Said(true, "Asked for “One Song”. It'll be in your library once it arrives."), withTimeout(40_000) { asking.await() })
        assertTrue(wanted())
        assertTrue("\"One Song\"" in File(made.dir, "asked.json").readText())
        // The second Lidarr was asked nothing for it, and what it offers is not marked by it.
        assertEquals(before, other.asked.size)
        val offer = got(made.pages.search("one song")).albums.single { it.album == "single-1" }
        // (It was the first Lidarr that was asked: the second has not been, and says so.)
        assertTrue(made.pages.asking(offer.album, offer.title) is Asking.Ask)
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
        assertEquals(Said(true, "Added New One. Their albums are on Lidarr's list"), made.pages.addArtist("new-one", "New One", connection = made.now()))
        assertEquals(listOf("New Two"), made.pages.suggestions.value.map { it.name })
        val again = RequestsPages(made.requests, made.scope, made.dir, library, { emptyList() }, { emptyList() }, clock = { now.get() }, afterwards = { work -> behind.launch { work() } })
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
        // Lidarr took it, and that is what is said; and that this phone could not write it down.
        assertEquals(Said(true, "Asked for “One Song”. It'll be in your library once it arrives.", kept = false), made.pages.request("single-1", "One Song", connection = made.now()))
        assertTrue(File(dir, "asked.json").isDirectory)
        assertTrue("$notes", notes.any { "asked.json" in it && "couldn't be read" in it })
        assertTrue("$notes", notes.any { "couldn't be kept on the phone" in it })
        // It is still shown as asked for, for as long as the app runs.
        assertEquals("One Song", made.pages.asked.value.single().title)
    }

    @Test
    fun `a list that couldn't be read is put together again once it can be, with what was asked for meanwhile`() = runBlocking {
        lidarrWithASingle()
        val dir = File(folder.root, "data").apply { mkdirs() }
        val file = File(dir, "asked.json")
        // (A folder where the file should be: there, and not to be read.)
        file.mkdirs()
        val made = made()
        assertEquals(Said(true, "Asked for “One Song”. It'll be in your library once it arrives.", kept = false), made.pages.request("single-1", "One Song", connection = made.now()))
        assertTrue(made.pages.askedUnreadable.value)
        assertEquals(listOf("One Song"), made.pages.asked.value.map { it.title })

        // It can be read again, and holds what an earlier run of the app had asked for.
        assertTrue(file.delete())
        file.writeText("""[{"album":"earlier","title":"Earlier Album","artist":"Someone","at":${start - 100},"song":"","fills":[],"arrived":0,"libraryAlbum":"","cover":"","unfilled":false}]""")
        // Opening the Requests page is one of the moments it is tried again: neither list
        // is lost to the other.
        made.pages.onTheirWay()
        assertFalse(made.pages.askedUnreadable.value)
        assertEquals(listOf("Earlier Album", "One Song"), made.pages.asked.value.map { it.title })
        val kept = file.readText()
        assertTrue(kept, "\"Earlier Album\"" in kept && "\"One Song\"" in kept)
        // And from here it is written as ever: taking the new one off the list forgets it.
        val pending = got(made.pages.onTheirWay()).pending.single().first
        assertTrue(made.pages.cancel(pending.id, pending.title, connection = made.now()).done)
        assertEquals(listOf("Earlier Album"), made.pages.asked.value.map { it.title })
        assertFalse("\"One Song\"" in file.readText())
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
