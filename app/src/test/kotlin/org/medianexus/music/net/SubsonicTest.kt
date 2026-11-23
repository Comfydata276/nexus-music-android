package org.medianexus.music.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The client against a stand-in server: what it sends, and how it reads what comes back. */
class SubsonicTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client(password: String = "sesame", salt: String = "abcdefghijklmnop", offline: OfflineSwitch = OfflineSwitch()) =
        Subsonic(server.url("/").toString(), "Ada Lovelace", password, http, offline, salt)

    private fun answer(body: String, code: Int = 200, type: String = "application/json") =
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", type).body(body).build())

    private fun ok(fields: String = "") =
        answer("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0"${if (fields.isEmpty()) "" else ",$fields"}}}""")

    private fun failed(code: Int, message: String) =
        answer("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}""")

    private inline fun <reified E : ApiError> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: ApiError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}, got nothing")
        throw AssertionError()
    }

    @Test
    fun signsEveryRequestWithASaltedToken() = runBlocking {
        ok()
        assertEquals("0.58.0", client().ping().version)
        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/rest/ping", sent.url.encodedPath)
        val query = sent.url
        assertEquals("Ada Lovelace", query.queryParameter("u"))
        assertEquals("abcdefghijklmnop", query.queryParameter("s"))
        // md5("sesame" + "abcdefghijklmnop"), worked out apart from the app.
        assertEquals(Subsonic.md5Hex("sesameabcdefghijklmnop"), query.queryParameter("t"))
        assertEquals("1.16.1", query.queryParameter("v"))
        assertEquals("NexusMusic", query.queryParameter("c"))
        assertEquals("json", query.queryParameter("f"))
        // The password itself never goes.
        assertFalse("sesame" in sent.target.substringAfter("t="))
        assertNull(query.queryParameter("p"))
        // The username is encoded as the Windows app encodes it.
        assertTrue(sent.target, "u=Ada%20Lovelace&" in sent.target)
    }

    @Test
    fun theTokenIsMd5OfPasswordAndSalt() {
        // The example from the Subsonic API's own documentation.
        assertEquals("26719a1196d2a940705a59634eb18eab", Subsonic.md5Hex("sesamec19b2d"))
        assertEquals(16, Subsonic.randomSalt().length)
        assertTrue(Subsonic.randomSalt().all { it.isLetterOrDigit() })
    }

    @Test
    fun mapsTheServersRefusalsToTheWindowsWording() = runBlocking {
        failed(40, "Wrong username or password")
        val wrong = expect<ApiError.Server> { client().ping() }
        assertEquals("Wrong username or password.", wrong.message)
        assertTrue(wrong.signInRefused)

        failed(41, "Token authentication not supported for LDAP users")
        val ldap = expect<ApiError.Server> { client().ping() }
        assertEquals("The server reported: Token authentication not supported for LDAP users", ldap.message)
        assertTrue(ldap.signInRefused)

        failed(50, "Not authorized")
        val notAllowed = expect<ApiError.Server> { client().deletePlaylist("1") }
        assertEquals("This account isn't allowed to do that.", notAllowed.message)
        assertFalse(notAllowed.signInRefused)

        failed(70, "Album not found")
        assertEquals("The server couldn't find that item.", expect<ApiError.Server> { client().album("x") }.message)

        // An error with nothing in it still says something.
        answer("""{"subsonic-response":{"status":"failed"}}""")
        val bare = expect<ApiError.Server> { client().ping() }
        assertEquals(0L, bare.code)
        assertEquals("The server reported: unknown error", bare.message)
    }

    @Test
    fun aProxysErrorPageIsNotTakenForTheServer() = runBlocking {
        answer("<html><body><h1>502 Bad Gateway</h1></body></html>", code = 502, type = "text/html")
        val page = expect<ApiError.NotSubsonic> { client().ping() }
        assertEquals("That address didn't answer like a Navidrome server. Check the address (HTTP status 502).", page.message)
        // JSON, but not the server's.
        answer("""{"message":"Unauthorized"}""", code = 401)
        assertEquals(
            "That address didn't answer like a Navidrome server. Check the address (HTTP status 401).",
            expect<ApiError.NotSubsonic> { client().ping() }.message,
        )
        // A field of the wrong kind says which call it was.
        ok(""""album":{"id":"1","songCount":"many"}""")
        val odd = expect<ApiError.NotSubsonic> { client().album("1") }
        assertEquals("That address didn't answer like a Navidrome server. Unexpected response from getAlbum (at $.songCount).", odd.message)
    }

    @Test
    fun longRequestsGoAsAFormPost() = runBlocking {
        ok()
        client().addToPlaylist("p1", listOf("a", "b"))
        val short = server.takeRequest()
        assertEquals("GET", short.method)
        assertEquals(listOf("a", "b"), short.url.queryParameterValues("songIdToAdd"))

        val ids = (1..200).map { "song-id-%04d".format(it) }
        ok()
        client().addToPlaylist("p1", ids)
        val long = server.takeRequest()
        assertEquals("POST", long.method)
        assertEquals("/rest/updatePlaylist", long.target)
        assertEquals("application/x-www-form-urlencoded", long.headers["Content-Type"])
        val form = long.body!!.utf8()
        assertTrue(form.startsWith("u=Ada%20Lovelace&t="))
        assertTrue("&c=NexusMusic&f=json&playlistId=p1&songIdToAdd=song-id-0001&" in form)
        assertEquals(200, form.split('&').count { it.startsWith("songIdToAdd=") })
    }

    @Test
    fun offlineModeSendsNothing() = runBlocking {
        val switch = OfflineSwitch()
        val api = client(offline = switch)
        api.setOffline(true)
        val error = expect<ApiError.Network> { api.ping() }
        assertEquals("Can't reach the server. Offline mode is on.", error.message)
        expect<ApiError.Network> { api.fetchBytes(api.coverUrl("c1", 300), 1024) }
        assertEquals(0, server.requestCount)
        // Another client sharing the switch is offline too, and both come back together.
        assertFalse(client(offline = switch).answersWithin(1000))
        switch.on = false
        ok()
        api.ping()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aServerThatIsntThereSaysSo() = runBlocking {
        val gone = client()
        server.close()
        val error = expect<ApiError.Network> { gone.ping() }
        assertEquals("Can't reach the server. The connection was refused. Check the port and that Navidrome is running.", error.message)
        assertFalse(gone.answersWithin(1000))
    }

    @Test
    fun readsWhatTheServerSendsAndToleratesWhatItLeavesOut() = runBlocking {
        ok(
            """"album":{"id":"al-1","name":"Field Notes","artist":"Marrow & Pine","songCount":2,"year":2021,
              "someFieldFromTheFuture":{"x":1},
              "song":[{"id":"s1","title":"One","track":1,"duration":200,"size":12345678901,
                       "replayGain":{"trackGain":-7.5,"albumPeak":0.98},"starred":"2025-01-01T00:00:00Z"},
                      {"id":"s2","title":"Two","displayArtist":"Marrow & Pine feat. Elm"}]}""",
        )
        val album = client().album("al-1")
        assertEquals("Field Notes", album.name)
        assertEquals(2, album.songs.size)
        assertEquals(12345678901L, album.songs[0].size)
        assertEquals(-7.5f, album.songs[0].replayGain!!.trackGain)
        assertNull(album.songs[0].replayGain!!.albumGain)
        assertEquals("2025-01-01T00:00:00Z", album.songs[0].starred)
        assertNull(album.songs[1].starred)
        assertEquals("Marrow & Pine feat. Elm", album.songs[1].artistLabel)
        assertEquals("", album.songs[1].album)

        // An empty library section is left out altogether.
        ok()
        assertTrue(client().playlists().isEmpty())
        ok(""""artists":{"index":[{"name":"A","artist":[{"id":"1","name":"Ash"}]},{"name":"B","artist":[{"id":"2","name":"Birch"}]}]}""")
        assertEquals(listOf("Ash", "Birch"), client().artists().map { it.name })
    }

    @Test
    fun theSavedQueueCurrentSongMayBeANumber() = runBlocking {
        ok(""""playQueue":{"entry":[{"id":"7"}],"current":7,"position":81234,"changedBy":"NexusMusic"}""")
        val queue = client().playQueue()
        assertEquals("7", queue.current)
        assertEquals(81234L, queue.position)
        ok(""""playQueue":{"current":"s-9"}""")
        assertEquals("s-9", client().playQueue().current)
        ok(""""playQueue":{"current":{"odd":true}}""")
        assertEquals("", client().playQueue().current)
    }

    @Test
    fun theQueueIsSavedUnderTheDevicesName() = runBlocking {
        ok()
        client().savePlayQueue("Nexus Music on Pixel", listOf("a", "b"), "b", 1500)
        val sent = server.takeRequest().url
        assertEquals("Nexus Music on Pixel", sent.queryParameter("c"))
        assertEquals(listOf("a", "b"), sent.queryParameterValues("id"))
        assertEquals("1500", sent.queryParameter("position"))
    }

    @Test
    fun lyricsPreferASyncedSetAndTakeItsOffsetOff() = runBlocking {
        ok(
            """"lyricsList":{"structuredLyrics":[
                 {"synced":false,"line":[{"value":"plain"}]},
                 {"synced":true,"offset":100,"line":[{"start":1000,"value":"timed"}]}]}""",
        )
        val lyrics = client().lyrics("s1")!!
        assertTrue(lyrics.synced)
        assertEquals(900L, lyrics.line[0].start)
        ok(""""lyricsList":{}""")
        assertNull(client().lyrics("s1"))
    }

    @Test
    fun radioStationsBecomePlayableEntries() = runBlocking {
        ok(
            """"internetRadioStations":{"internetRadioStation":[
                 {"id":"1","name":"Night Air","streamUrl":"https://radio.example.org/live","homePageUrl":"https://www.example.org/"},
                 {"id":"2","name":"No stream"},
                 {"id":"3","name":"Bare","streamUrl":"http://x/y"}]}""",
        )
        val stations = client().radioStations()
        assertEquals(listOf("radio:1", "radio:3"), stations.map { it.id })
        assertEquals("example.org", stations[0].artist)
        assertEquals("Internet radio", stations[1].artist)
        assertEquals("https://radio.example.org/live", stations[0].radioUrl)
    }

    @Test
    fun asksForTheRightAlbumListsAndStreams() = runBlocking {
        ok()
        client().albumList(AlbumList.ByYearDescending, 40, 80)
        val byYear = server.takeRequest().url
        assertEquals("byYear", byYear.queryParameter("type"))
        assertEquals("9999", byYear.queryParameter("fromYear"))
        assertEquals("80", byYear.queryParameter("offset"))
        ok()
        client().albumList(AlbumList.ByGenre("Folk & Roots"), 10, 0)
        assertTrue(server.takeRequest().target.contains("&genre=Folk%20%26%20Roots&type=byGenre"))

        val api = client()
        assertTrue(api.streamUrl("s1", 0).endsWith("&id=s1&format=raw"))
        assertTrue(api.streamUrl("s1", 160, 30).endsWith("&id=s1&format=mp3&maxBitRate=160&timeOffset=30"))
        assertTrue(api.streamUrl("s1", 160).endsWith("&id=s1&format=mp3&maxBitRate=160"))
    }

    /** A long answer that arrives a little at a time: reading it all would take a minute. */
    private fun slowAnswer(start: String = "") = server.enqueue(
        MockResponse.Builder()
            .setHeader("Content-Type", "application/json")
            .body(Buffer().writeUtf8(start).writeUtf8("x".repeat(1_000_000)))
            .throttleBody(1024, 60, TimeUnit.MILLISECONDS)
            .build(),
    )

    /** Starts `request`, cancels it part-way through reading the answer, and checks that it
     *  stopped at once and gave its connection back. */
    private fun cancelsMidBody(http: OkHttpClient, request: suspend () -> Unit) = runBlocking {
        val job = launch(Dispatchers.Default) { request() }
        server.takeRequest()
        // Well into the body by now.
        delay(400)
        assertFalse(job.isCompleted)
        val started = System.nanoTime()
        withTimeout(5_000) { job.cancelAndJoin() }
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $took ms", took < 3_000)
        assertTrue(job.isCancelled)
        // No call still running, and no connection kept: it was closed, not left half-read.
        assertEquals(0, http.dispatcher.runningCallsCount())
        withTimeout(5_000) { while (http.connectionPool.connectionCount() > 0) delay(20) }
    }

    @Test(timeout = 30_000)
    fun aRequestCancelledMidAnswerLetsGoOfItsConnection() {
        val http = OkHttpClient()
        val api = Subsonic(server.url("/").toString(), "ada", "sesame", http)
        slowAnswer("{\"subsonic-response\":{\"status\":\"ok\",\"padding\":\"")
        cancelsMidBody(http) { api.ping() }
        slowAnswer()
        cancelsMidBody(http) { api.fetchBytes(api.coverUrl("c1", 300), 10_000_000) }
    }

    /** Nothing private in an error's words: not the host, the token, its salt, or `also`. */
    private fun assertNothingPrivate(error: Throwable, vararg also: String) {
        val text = error.toString()
        val token = Subsonic.md5Hex("sesame" + "abcdefghijklmnop")
        for (secret in listOf(server.hostName, "abcdefghijklmnop", token) + also) {
            assertFalse("\"$secret\" in: $text", text.contains(secret, ignoreCase = true))
        }
        assertNull(error.cause)
    }

    @Test
    fun errorsSayNothingPrivate() = runBlocking {
        val token = Subsonic.md5Hex("sesame" + "abcdefghijklmnop")
        // The decoder's own message would quote the answer.
        ok(""""album":{"id":"1","songCount":"secret-payload"}""")
        val odd = expect<ApiError.NotSubsonic> { client().album("1") }
        assertNothingPrivate(odd, "secret-payload", "{")
        // A server that echoes the request back in its error.
        failed(10, "Required parameter is missing: u=Ada&t=$token&s=abcdefghijklmnop")
        val echoed = expect<ApiError.Server> { client().ping() }
        assertEquals("The server reported an error (code 10).", echoed.message)
        assertEquals("", echoed.serverMessage)
        assertNothingPrivate(echoed)
        failed(0, "Couldn't reach ${server.hostName} for this")
        assertNothingPrivate(expect<ApiError.Server> { client().ping() })
        failed(0, "bad request: {\\\"id\\\":\\\"x\\\"}")
        assertEquals("The server reported an error (code 0).", expect<ApiError.Server> { client().ping() }.message)
        // The username is half of the sign-in: a message that names it isn't shown either.
        failed(40, "Wrong username or password for user Ada Lovelace")
        val named = expect<ApiError.Server> { client().ping() }
        assertEquals("", named.serverMessage)
        assertFalse(named.toString(), named.toString().contains("Lovelace"))
        // An ordinary message still comes through.
        failed(10, "Required parameter is missing")
        assertEquals("The server reported: Required parameter is missing", expect<ApiError.Server> { client().ping() }.message)
        // An address OkHttp can't use: its complaint would quote the whole signed address.
        val bad = Subsonic("http://bad host:4533", "Ada Lovelace", "sesame", http, salt = "abcdefghijklmnop")
        val unusable = expect<ApiError.Network> { bad.ping() }
        assertEquals("Can't reach the server. The server's address isn't one the app can use. Check it.", unusable.message)
        assertNothingPrivate(unusable, "bad host")
    }

    @Test
    fun aDroppedConnectionIsReportedWithoutTheAddress() {
        val dropped = java.io.IOException("unexpected end of stream on http://music.example.org:4533/...")
        val hint = networkHint(dropped)
        assertEquals("The connection dropped.", hint)
        // No class names, ever, and never the address, whatever the system says.
        val cases = mapOf(
            java.net.ConnectException("Failed to connect to music.example.org/203.0.113.9:4533") to "Couldn't connect. Check the address and your network.",
            java.net.ConnectException("failed to connect to /10.0.2.2 (port 4533): connect failed: ENETUNREACH (Network is unreachable)") to "There's no way to the server from this network. Check your network.",
            java.net.SocketException("connect failed: EPERM (Operation not permitted)") to "The phone didn't allow the connection.",
            java.net.SocketException("Connection reset") to "The connection dropped.",
            java.io.IOException("something nobody planned for, music.example.org") to "The connection failed.",
        )
        for ((error, words) in cases) {
            val said = networkHint(error)
            assertEquals(error.toString(), words, said)
            assertFalse(said, said.contains("Exception") || said.contains("example") || said.contains("203.0") || said.contains("10.0.2.2"))
        }
        val unverified = javax.net.ssl.SSLPeerUnverifiedException(
            "Hostname music.example.org not verified: certificate: sha256/AAAA DN: CN=music.example.org",
        )
        assertEquals("The server's certificate is for a different address. Check the address.", networkHint(unverified))
        val handshake = javax.net.ssl.SSLHandshakeException("Received fatal alert for music.example.org")
        assertEquals("The secure connection failed.", networkHint(handshake))
    }

    @Test
    fun fetchesSmallFilesWithinALimit() = runBlocking {
        server.enqueue(MockResponse.Builder().body("12345").build())
        val api = client()
        assertEquals("12345", api.fetchBytes(api.coverUrl("c1", 300), 5).decodeToString())
        server.enqueue(MockResponse.Builder().body("123456").build())
        expect<ApiError.Network> { api.fetchBytes(api.coverUrl("c1", 300), 5) }
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals("Can't reach the server. HTTP status 404.", expect<ApiError.Network> { api.fetchBytes(api.coverUrl("c1", 300), 5) }.message)
    }
}
