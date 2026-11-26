package org.medianexus.music.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.BuildConfig

/**
 * The phone's side of the library's requests (`CoreHttp`, and `native/src/wire.rs` below it),
 * through the real library and the real client, against a stand-in server on this machine:
 * what goes out, how an answer is read, what stopping does, and what an answer that means
 * harm can and cannot do.
 */
class CoreHttpTest {
    private val closing = mutableListOf<StandIn>()

    /** No test here waits for long; one that does has met something that never ends, and
     *  fails for it rather than holding up every build on the machine. */
    @get:Rule
    val noLongerThan: Timeout = Timeout.builder().withTimeout(120, TimeUnit.SECONDS).build()

    private fun standIn(answer: (StandIn.Asked, StandIn.Reply) -> Unit) = StandIn(answer).also { closing += it }

    /** (The stand-ins for MusicBrainz and ListenBrainz are on this machine: the rule that
     *  those two are reached only at public addresses is off but where it is what is tested.) */
    @org.junit.Before
    fun standInsAreHere() {
        CoreHttp.publicOnly = false
    }

    @After
    fun tidy() {
        CoreHttp.publicOnly = true
        closing.forEach { it.close() }
        CoreHttp.unsent = { null }
        CoreHttp.dress = {}
        CoreHttp.afterStopMillis = 10_000
        CoreHttp.graceMillis = 30_000
        CoreHttp.nobodysMillis = 60_000
        CoreHttp.changeMillis = 120_000
    }

    private fun probe(probe: Core.Probe, changing: Boolean = false): Outcome<Core.Probed> = runBlocking { withTimeout(60_000) { Core.wireProbe(probe, changing) } }

    private fun <T> Outcome<T>.got(): T = (this as? Outcome.Got<T>)?.value ?: throw AssertionError("expected an answer, got $this")

    private fun elapsed(act: () -> Unit): Long {
        val started = System.nanoTime()
        act()
        return (System.nanoTime() - started) / 1_000_000
    }

    // ---- what goes out ------------------------------------------------------------------------

    @Test
    fun `a request goes out as the library wrote it, under the phone's own name and with nothing of the music server's`() {
        val server = standIn { _, reply -> reply.json("{}") }
        probe(Core.Probe(url = "${server.address}/api/v1/album/lookup?term=lidarr%3Aabc%20def", headers = listOf(listOf("X-Api-Key", "the-key")))).got()
        probe(
            Core.Probe(
                method = "PUT", url = "${server.address}/api/v1/artist/7", body = """{"monitored":true,"name":"Été 🎶"}""",
                headers = listOf(listOf("X-Api-Key", "the-key"), listOf("Content-Type", "application/json"), listOf("Authorization", "Bearer a-pass")),
            ),
        ).got()

        val (get, put) = server.asked
        assertEquals("GET /api/v1/album/lookup?term=lidarr%3Aabc%20def", get.line)
        assertEquals("NexusMusic/${BuildConfig.VERSION_NAME} (Android)", get.header("User-Agent"))
        assertEquals("the-key", get.header("X-Api-Key"))
        assertEquals("*/*", get.header("Accept"))
        assertEquals("gzip", get.header("Accept-Encoding"))
        assertEquals("PUT /api/v1/artist/7", put.line)
        assertEquals("""{"monitored":true,"name":"Été 🎶"}""", put.body)
        // The kind of text is what the library said and nothing added to it.
        assertEquals("application/json", put.header("Content-Type"))
        assertEquals("Bearer a-pass", put.header("Authorization"))
        for (request in server.asked) {
            val names = request.headers.map { it.first.lowercase() }.toSet()
            assertEquals(null, request.header("Cookie"))
            // Nothing but what the library sent and what any client must.
            assertTrue("$names", (names - setOf("x-api-key", "content-type", "authorization", "user-agent", "accept", "accept-encoding", "host", "connection", "content-length")).isEmpty())
            assertFalse(request.target, "u=" in request.target || "t=" in request.target || "s=" in request.target)
        }
    }

    @Test
    fun `the library's own Accept stands, and a request for another service names the same app`() {
        val server = standIn { _, reply -> reply.json("{}") }
        probe(Core.Probe(line = "musicbrainz", url = "${server.address}/ws/2/release?fmt=json", headers = listOf(listOf("Accept", "application/json")))).got()
        assertEquals(listOf("application/json"), server.asked.single().headers.filter { it.first.equals("Accept", true) }.map { it.second })
        assertEquals(CoreHttp.NAME, server.asked.single().header("User-Agent"))
    }

    @Test
    fun `an address the client can't use and a header it can't send fail in the app's words, never the client's`() {
        val server = standIn { _, reply -> reply.json("{}") }
        assertEquals(Outcome.Failed("That address isn't one the app can use."), probe(Core.Probe(url = "lidarr.example:8686/api")))
        // A key with a letter that can't go in a header: the client's own complaint quotes it.
        val secret = "sécret-key-0123456789"
        val failed = probe(Core.Probe(url = server.address, headers = listOf(listOf("X-Api-Key", secret))))
        assertEquals(Outcome.Failed("The app couldn't make the request."), failed)
        // And through the library's own sentence, which is what a person is shown.
        val shown = runBlocking { Core.lidarrVersion(LidarrAt(server.address, secret)) }
        assertEquals(Outcome.Failed("Couldn't reach Lidarr. The app couldn't make the request."), shown)
        assertTrue(server.asked.isEmpty())
    }

    @Test
    fun `while nothing may be sent every call fails as the library says, and nothing leaves the phone`() {
        val server = standIn { _, reply -> reply.json("""{"version":"2.9.6"}""") }
        CoreHttp.unsent = { "Offline mode is on." }
        assertEquals(Outcome.Failed("Couldn't reach Lidarr. Offline mode is on."), runBlocking { Core.lidarrVersion(LidarrAt(server.address, "k")) })
        assertEquals(Outcome.Failed("Couldn't reach Lidarr. Offline mode is on."), runBlocking { Core.gateFind(server.address) })
        assertTrue(server.asked.isEmpty())
        CoreHttp.unsent = { null }
        assertEquals(Outcome.Got("2.9.6"), runBlocking { Core.lidarrVersion(LidarrAt(server.address, "k")) })
    }

    // ---- how an answer is read ----------------------------------------------------------------

    @Test
    fun `a body nobody asks for is never waited for`() {
        // An error's headers, promising a body that never comes.
        val server = standIn { _, reply ->
            reply.raw("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/html\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n")
            reply.hungUpWithin(20_000)
        }
        val took = elapsed { assertEquals(500, probe(Core.Probe(url = server.address, then = "drop")).got().status) }
        assertTrue("waited $took ms for a body nothing was going to read", took < 5_000)
        // And through the library, which judges this one by its status alone.
        rerouteMusicBrainz(server)
        val asked = elapsed { assertEquals(Outcome.Failed("MusicBrainz answered with an error (500)."), runBlocking { Core.albumTracks("an-album") }) }
        assertTrue("waited $asked ms", asked < 5_000)
    }

    @Test
    fun `text is read whole and as the Windows app reads it`() {
        val bodies = mapOf(
            "/plain" to "plain".encodeToByteArray(),
            "/accents" to "Été 🎶 夜".encodeToByteArray(),
            // Bytes that aren't text become the mark for that, as on Windows; nothing fails.
            "/not-text" to byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()),
            "/empty" to ByteArray(0),
        )
        val server = standIn { asked, reply -> reply.send(200, bodies.getValue(asked.target)) }
        assertEquals("plain", probe(Core.Probe(url = "${server.address}/plain")).got().body)
        assertEquals("Été 🎶 夜", probe(Core.Probe(url = "${server.address}/accents")).got().body)
        assertEquals("a�b", probe(Core.Probe(url = "${server.address}/not-text")).got().body)
        assertEquals("", probe(Core.Probe(url = "${server.address}/empty")).got().body)
    }

    @Test
    fun `an answer sent in pieces, and one sent squeezed, are read the same`() {
        val squeezed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write("""{"version":"2.9.6"}""".encodeToByteArray()) } }.toByteArray()
        val server = standIn { asked, reply ->
            when (asked.target) {
                "/pieces" -> reply.raw("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n8\r\n{\"name\":\r\n5\r\n\"me\"}\r\n0\r\n\r\n")
                else -> reply.send(200, squeezed, "Content-Encoding" to "gzip")
            }
        }
        assertEquals("""{"name":"me"}""", probe(Core.Probe(url = "${server.address}/pieces", then = "json")).got().body)
        assertEquals("""{"version":"2.9.6"}""", probe(Core.Probe(url = "${server.address}/squeezed", then = "text")).got().body)
        assertEquals("""{"version":"2.9.6"}""", probe(Core.Probe(url = "${server.address}/squeezed", then = "json")).got().body)
    }

    private val limit = 10 * 1024 * 1024

    @Test
    fun `a body is read up to ten megabytes and no further`() {
        val server = standIn { asked, reply ->
            val size = asked.target.substringAfter("/").toInt()
            reply.send(200, ByteArray(size) { 'a'.code.toByte() })
        }
        // Exactly the limit is read; one more is not.
        assertEquals(limit, probe(Core.Probe(url = "${server.address}/$limit")).got().body!!.length)
        val over = probe(Core.Probe(url = "${server.address}/${limit + 1}")).got()
        assertNull(over.body)
        assertEquals("The answer was larger than 10 MB.", over.unread)
    }

    @Test
    fun `a body that never ends is given up at the limit, as text and as it arrives`() {
        // Hostile: an answer that goes on for ever. As text the app stops at ten megabytes;
        // read as it arrives, JSON that never closes stops there too.
        val server = standIn { asked, reply ->
            reply.raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n")
            val piece = ByteArray(64 * 1024) { 'a'.code.toByte() }
            if (asked.target == "/json") reply.raw("[\"")
            while (true) reply.raw(piece)
        }
        val text = probe(Core.Probe(url = "${server.address}/text")).got()
        assertEquals("The answer was larger than 10 MB.", text.unread)
        val json = probe(Core.Probe(url = "${server.address}/json", then = "json")).got()
        assertNull(json.body)
        assertTrue(json.unread, json.unread!!.contains("The answer was larger than 10 MB."))
    }

    @Test
    fun `a page that isn't JSON is given up at its first letter, however long it goes on`() {
        // The stream's own test: the sign-in's "who is this" answered with a page of HTML
        // that never ends. Nothing waits for its end.
        val server = standIn { _, reply ->
            reply.raw("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<html>")
            while (true) {
                reply.raw("<p>and more</p>")
                Thread.sleep(20)
            }
        }
        val took = elapsed {
            val answer = probe(Core.Probe(url = server.address, then = "json")).got()
            assertNull(answer.body)
            assertTrue(answer.unread, answer.unread!!.isNotEmpty())
        }
        assertTrue("took $took ms", took < 5_000)
    }

    @Test
    fun `signing in is not held up by a name that comes back as a page that never ends`() {
        // The same, through the library's own sign-in: the token is collected and tried,
        // then who it belongs to is asked, and the answer is that endless page.
        val service = standIn { asked, reply ->
            when {
                asked.target == "/application/o/token/" && "grant_type=authorization_code" in asked.body ->
                    reply.json("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":300}""")
                asked.target == "/application/o/token/" -> reply.json("""{"access_token":"pass-1","expires_in":300}""")
                asked.target == "/application/o/userinfo/" -> {
                    reply.raw("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<html>")
                    while (true) {
                        reply.raw("<p>and more</p>")
                        Thread.sleep(20)
                    }
                }
                else -> reply.send(404)
            }
        }
        val door = SignInDoor(service.address, "lidarr-proxy")
        val took = elapsed {
            val done = runBlocking { Core.gateFinish(door, "the-code", "http://127.0.0.1:41234/callback", "the-secret") }.got()
            assertEquals("refresh-1", done.token)
            assertEquals("", done.name)
        }
        assertTrue("took $took ms", took < 5_000)
        // What was sent for it, as the Windows app sends it.
        val (code, trade, who) = service.asked
        assertEquals("grant_type=authorization_code&code=the-code&redirect_uri=http%3A%2F%2F127.0.0.1%3A41234%2Fcallback&client_id=nexus-music&code_verifier=the-secret", code.body)
        assertEquals("application/x-www-form-urlencoded", code.header("Content-Type"))
        assertTrue(trade.body, trade.body.startsWith("grant_type=client_credentials&") && "client_assertion=access-1" in trade.body && "client_id=lidarr-proxy" in trade.body)
        assertEquals("Bearer access-1", who.header("Authorization"))
    }

    @Test
    fun `a header that isn't text is there and unreadable, and the first of a name still counts`() {
        val server = standIn { _, reply ->
            reply.raw("HTTP/1.1 503 Service Unavailable\r\nRetry-After: ".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                "\r\nRetry-After: 3\r\nX-Note: café\r\nX-Plain: fine\tand tabbed\r\nContent-Length: 4\r\nConnection: close\r\n\r\nbusy".encodeToByteArray())
        }
        val answer = probe(Core.Probe(url = server.address)).got()
        val named = answer.headers.map { it[0] to it[1] }
        assertEquals(listOf(null, "3"), named.filter { it.first.equals("Retry-After", true) }.map { it.second })
        assertEquals(listOf<String?>(null), named.filter { it.first.equals("X-Note", true) }.map { it.second })
        assertEquals(listOf<String?>("fine\tand tabbed"), named.filter { it.first.equals("X-Plain", true) }.map { it.second })
        assertEquals("busy", answer.body)
    }

    @Test
    fun `a busy answer whose first Retry-After can't be read is waited out as a second, not as the later one says`() {
        // Through the library's own rule for it (MusicBrainz's "come back in a moment").
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val server = standIn { asked, reply ->
            if ("an-album" in asked.target && count.getAndIncrement() == 0) {
                reply.raw("HTTP/1.1 503 Service Unavailable\r\nRetry-After: ".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                    "\r\nRetry-After: 3\r\nContent-Length: 4\r\nConnection: close\r\n\r\nbusy".encodeToByteArray())
            } else {
                reply.json("{}")
            }
        }
        rerouteMusicBrainz(server)
        runBlocking { Core.albumTracks("warm-up") }
        val took = elapsed { assertEquals(Outcome.Got(emptyList<TrackName>()), runBlocking { Core.albumTracks("an-album") }) }
        assertEquals(3, server.asked.size)
        // (The gap it keeps between any two requests, then a second for the busy answer:
        // three would be the later header's, and four seconds in all.)
        assertTrue("took $took ms", took in 1_900..3_400)
    }

    /** Sends what the library asks of MusicBrainz, whose address it fixes, to a stand-in. */
    private fun rerouteMusicBrainz(to: StandIn) {
        CoreHttp.dress = { client ->
            client.addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host != "musicbrainz.org") return@addInterceptor chain.proceed(request)
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(to.port).build()).build())
            }
        }
    }

    // ---- redirects ------------------------------------------------------------------------------

    @Test
    fun `Lidarr and its sign-in are never followed anywhere, and what the library then asks carries nothing`() {
        // Hostile: whatever answers at Lidarr's address points at the music server. The
        // request with the key in it goes no further than where it was sent.
        val music = standIn { _, reply -> reply.json("{}") }
        val lidarr = standIn { _, reply -> reply.send(302, "", "Location" to "${music.address}/rest/ping?u=someone", "Set-Cookie" to "session=abc; Path=/") }
        val answer = probe(Core.Probe(url = "${lidarr.address}/api/v1/system/status", headers = listOf(listOf("X-Api-Key", "the-key")))).got()
        assertEquals(302, answer.status)
        assertTrue(music.asked.isEmpty())

        // The library reads a redirect as a sign-in in front of Lidarr, and looks once at
        // where it points: with no key, no cookie, nothing of anyone's.
        val found = runBlocking { Core.gateFind(lidarr.address) }
        assertEquals(Outcome.Failed("Lidarr is behind a sign-in this app doesn't know how to use."), found)
        val looked = music.asked.single()
        assertEquals("GET /rest/ping?u=someone", looked.line)
        assertTrue(looked.headers.toString(), looked.headers.map { it.first.lowercase() }.toSet() == setOf("user-agent", "accept", "accept-encoding", "host", "connection"))
        // And asking Lidarr with the key says a sign-in is needed, and sends the key nowhere else.
        assertEquals(Outcome.Failed("Lidarr is behind a sign-in. In Settings, under Requests, sign in with your browser."), runBlocking { Core.lidarrVersion(LidarrAt(lidarr.address, "the-key")) })
        assertTrue(music.asked.none { it.header("X-Api-Key") != null || it.header("Cookie") != null })
    }

    /** Every address but this machine's own turned to `server`: so that a test can ask for
     *  `https://musicbrainz.example/…` and be answered by a stand-in. */
    private fun everythingGoesTo(server: StandIn) {
        CoreHttp.dress = { client ->
            client.addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "127.0.0.1") return@addInterceptor chain.proceed(request)
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).header("X-Was", request.url.toString()).build())
            }
        }
    }

    @Test
    fun `MusicBrainz is followed from https to https, a few times, carrying nothing but the app's name`() {
        val server = standIn { asked, reply ->
            val was = asked.header("X-Was").orEmpty()
            val step = was.substringAfterLast("/step/", "").toIntOrNull()
            when {
                was == "https://musicbrainz.example/ws/2/release" -> reply.send(302, "", "Location" to "https://mirror.example/moved", "Set-Cookie" to "session=abc; Path=/")
                was == "https://mirror.example/moved" -> reply.json("""{"moved":true}""")
                step != null -> reply.send(307, "", "Location" to "/step/${step + 1}")
                else -> reply.send(404)
            }
        }
        everythingGoesTo(server)
        val answer = probe(Core.Probe(line = "musicbrainz", url = "https://musicbrainz.example/ws/2/release", headers = listOf(listOf("Accept", "application/json")))).got()
        assertEquals(200, answer.status)
        assertEquals("""{"moved":true}""", answer.body)
        val followed = server.asked.last()
        assertEquals("https://mirror.example/moved", followed.header("X-Was"))
        assertNull(followed.header("Cookie"))
        assertNull(followed.header("Authorization"))
        assertEquals(CoreHttp.NAME, followed.header("User-Agent"))
        assertEquals("application/json", followed.header("Accept"))

        // Sent round and round, it is followed five times and then the answer stands.
        val before = server.asked.size
        val round = probe(Core.Probe(line = "listenbrainz", url = "https://listenbrainz.example/step/0")).got()
        assertEquals(307, round.status)
        assertEquals((0..5).map { "https://listenbrainz.example/step/$it" }, server.asked.drop(before).map { it.header("X-Was") })
    }

    @Test
    fun `a public service's answer cannot send a request off https, into a private network or to the music server, and a POST is never sent on`() {
        var location = ""
        var status = 302
        val server = standIn { asked, reply -> if ("/start" in asked.target) reply.send(status, "", "Location" to location) else reply.json("""{"reached":true}""") }
        everythingGoesTo(server)
        CoreHttp.publicOnly = true
        fun asked(line: String = "musicbrainz", method: String = "GET", from: String = "https://musicbrainz.example/start"): Core.Probed {
            val before = server.asked.size
            val answer = probe(Core.Probe(line = line, method = method, url = from, body = if (method == "GET") null else "ids=1", home = "https://music.example:443")).got()
            assertEquals("$method $from sent on to $location: ${server.asked.drop(before).map { it.header("X-Was") }}", 1, server.asked.size - before)
            return answer
        }
        for (to in listOf(
            "http://musicbrainz.example/plain",
            "http://127.0.0.1:${server.port}/rest/ping",
            "https://127.0.0.1/",
            "https://localhost/",
            "https://[::1]/",
            "https://10.0.0.7/",
            "https://192.168.1.10:4533/rest/ping",
            "https://172.16.5.5/",
            "https://169.254.169.254/latest/meta-data/",
            "https://100.64.0.1/",
            "https://[fd00::1]/",
            "https://music.example/rest/ping?u=someone",
            "ftp://musicbrainz.example/",
        )) {
            location = to
            assertEquals(to, 302, asked().status)
        }
        // From plain http nothing is followed at all, wherever it points.
        location = "https://mirror.example/fine"
        assertEquals(302, asked(from = "http://musicbrainz.example/start").status)
        // A POST is never sent on, whatever the answer's number (ListenBrainz is asked by POST).
        for (number in listOf(301, 302, 303, 307, 308)) {
            status = number
            assertEquals(number, asked(line = "listenbrainz", method = "POST", from = "https://listenbrainz.example/start").status)
        }
        // (And what may be followed still is.)
        status = 302
        val before = server.asked.size
        assertEquals(200, probe(Core.Probe(line = "musicbrainz", url = "https://musicbrainz.example/start")).got().status)
        assertEquals(2, server.asked.size - before)
    }

    @Test
    fun `a sign-in service that turns out to be the music server is sent nothing, though the place is the one its traffic is bound to`() {
        // (What answered at Lidarr's address pointed at a "sign-in service" that is the
        // music server itself. The sign-in's own requests are bound to that place: that
        // is no leave to send them there. Only Lidarr's API at an address the person typed
        // may be on the music server's host and port.)
        val music = standIn { _, reply -> reply.json("""{"access_token":"a","refresh_token":"r"}""") }
        val place = Core.lidarrPlace(music.address)!!
        val home = Outcome.Failed("It was for the music server's own address, which requests are never sent to.")
        assertEquals(home, probe(Core.Probe(method = "POST", url = "${music.address}/application/o/token/", body = "grant_type=authorization_code&code=c", bound = place, home = place), changing = true))
        assertEquals(home, probe(Core.Probe(url = "${music.address}/application/o/userinfo/", headers = listOf(listOf("Authorization", "Bearer a")), bound = place, home = place)))
        assertTrue(music.asked.isEmpty())
        // Through the library's own sign-in: the trade of a code, the handing back of a token.
        val door = SignInDoor(music.address, "lidarr-proxy")
        val traded = runBlocking { Core.gateFinish(door, "the-code", "http://127.0.0.1:41234/callback", "the-secret", home = place) }
        assertTrue("$traded", traded is Outcome.Failed)
        runBlocking { Core.gateGiveUp(door, "a-token", home = place) }
        assertTrue(music.asked.isEmpty())
        // The place is judged for the account the request is made for, and comes with it:
        // for an account whose music server is somewhere else, this address is just an
        // address, whatever account the app has signed in by now.
        assertEquals(200, probe(Core.Probe(method = "POST", url = "${music.address}/application/o/token/", body = "x", bound = place, home = "https://elsewhere.example:443"), changing = true).got().status)
        assertEquals(1, music.asked.size)
        // And where a browser may be sent is judged the same way.
        assertFalse(CoreHttp.mayOpen("${music.address}/application/o/authorize/?x=1", place, place))
        assertTrue(CoreHttp.mayOpen("${music.address}/application/o/authorize/?x=1", place, "https://elsewhere.example:443"))
        assertFalse(CoreHttp.mayOpen("http://other.example/application/o/authorize/", place, null))
        assertFalse(CoreHttp.mayOpen(music.address.replace("http://", "http://me:secret@") + "/application/o/authorize/", place, null))
    }

    @Test
    fun `with a proxy set on the phone the proxy looks the name up, and only an address written as inside a network is refused`() {
        val proxy = standIn { _, reply -> reply.json("""{"through":"the proxy"}""") }
        CoreHttp.publicOnly = true
        // (As the phone's own setting gives one: a host and a port. Written as numbers it
        // is connected to without being looked up.)
        CoreHttp.dress = { it.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("127.0.0.1", proxy.port))) }
        // A name is handed to the proxy as it stands. The phone never looks it up (this
        // one could not be), so where it leads is the proxy's to say, not the app's.
        assertEquals("""{"through":"the proxy"}""", probe(Core.Probe(line = "musicbrainz", url = "http://musicbrainz.invalid/ws/2/release")).got().body)
        assertEquals(listOf("GET http://musicbrainz.invalid/ws/2/release"), proxy.asked.map { it.line })
        // An address written as one inside a network is still refused, before anything is sent.
        assertEquals(Outcome.Failed("It pointed at an address inside a private network."), probe(Core.Probe(line = "listenbrainz", url = "http://192.168.0.2/1/popularity")))
        assertEquals(1, proxy.asked.size)
        // Lidarr's line goes by the proxy too, as any request of the phone's does.
        probe(Core.Probe(url = "http://lidarr.invalid/api/v1/system/status")).got()
        assertEquals("GET http://lidarr.invalid/api/v1/system/status", proxy.asked.last().line)
    }

    @Test
    fun `a name a public service is asked at that turns out to be inside a network is refused as it is looked up`() {
        // (A name this machine has for itself that is not "localhost", which is refused as
        // written. Where it has none, there is nothing here to ask.)
        val name = runCatching { java.net.InetAddress.getLocalHost() }.getOrNull()
            ?.takeIf { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }?.hostName
            ?.takeIf { it != "localhost" && it.any(Char::isLetter) }
        org.junit.Assume.assumeTrue("this machine has no name of its own that leads inside", name != null)
        val server = standIn { _, reply -> reply.json("{}") }
        CoreHttp.publicOnly = true
        assertEquals(Outcome.Failed("It pointed at an address inside a private network."), probe(Core.Probe(line = "musicbrainz", url = "http://$name:${server.port}/ws/2/release")))
        assertTrue(server.asked.isEmpty())
        // (Lidarr is wherever the person says it is.)
        CoreHttp.publicOnly = true
        probe(Core.Probe(url = "http://$name:${server.port}/api/v1/system/status"))
    }

    @Test
    fun `an operation whose whole time is spent is allowed no further request`() {
        // (Each of its requests may take a second; the whole of it sixty milliseconds.)
        val doing = CoreHttp.Doing(1, changing = true, callMillis = 1_000, wholeMillis = 60)
        val first = doing.allowance()!!
        assertTrue("$first", first in 1..60)
        Thread.sleep(90)
        // Its time is spent: not one more request with a moment to live, none at all.
        val after = doing.allowance()!!
        assertTrue("allowed $after ms", after < 0)
    }

    @Test
    fun `once the whole time of finishing a sign-in is spent, a further request is not sent, and ends as one not answered in time`() {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val service = standIn { request, reply ->
            asked += request.line
            reply.json("{}")
        }
        CoreHttp.signInWholeMillis = 400
        try {
            // (As the library does it: one request after another, each under the operation.)
            val (first, second) = runBlocking {
                withTimeout(30_000) {
                    CoreHttp.finishing { operation ->
                        fun ask(what: String) = CoreHttp.send("""{"operation":$operation,"line":"lidarr","method":"GET","url":"${service.address}/$what"}""".encodeToByteArray())
                        val first = ask("token")
                        Thread.sleep(600)
                        first to ask("userinfo")
                    }
                }
            }
            assertEquals("the first was not answered: ${first.drop(1).toByteArray().decodeToString()}", 0, first[0].toInt())
            assertEquals(1, second[0].toInt())
            assertEquals("It didn't answer in time.", second.drop(1).toByteArray().decodeToString())
            Thread.sleep(200)
            assertEquals(listOf("GET /token"), asked.toList())
        } finally {
            CoreHttp.signInWholeMillis = 12_000
        }
    }

    @Test
    fun `a proxy known by a name is looked up as itself, wherever it is, and the services are still reached through it`() {
        val proxy = standIn { _, reply -> reply.json("""{"through":"the proxy"}""") }
        CoreHttp.publicOnly = true
        // (A proxy inside the network the phone is on, by name: the usual kind.)
        CoreHttp.dress = { it.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("localhost", proxy.port))) }
        assertEquals("""{"through":"the proxy"}""", probe(Core.Probe(line = "musicbrainz", url = "http://musicbrainz.invalid/ws/2/release")).got().body)
    }

    @Test
    fun `the public services are reached only at public addresses, and nothing of requests is ever sent to the music server`() {
        val music = standIn { _, reply -> reply.json("{}") }
        val place = Core.lidarrPlace(music.address)!!
        // A public service's own address inside a network: not asked at all.
        CoreHttp.publicOnly = true
        val inside = Outcome.Failed("It pointed at an address inside a private network.")
        assertEquals(inside, probe(Core.Probe(line = "musicbrainz", url = "${music.address}/ws/2/release")))
        assertEquals(inside, probe(Core.Probe(line = "listenbrainz", url = "http://localhost:${music.port}/1/popularity")))
        assertEquals(inside, probe(Core.Probe(line = "musicbrainz", url = "https://192.168.0.2/x")))
        assertTrue(music.asked.isEmpty())
        CoreHttp.publicOnly = false

        // The music server: whatever answers at Lidarr's address points there, and the look
        // the library takes at where a redirect points is not sent.
        val home = "It was for the music server's own address, which requests are never sent to."
        val lidarr = standIn { _, reply -> reply.send(302, "", "Location" to "${music.address}/rest/ping?u=someone") }
        assertEquals(Outcome.Failed(home), probe(Core.Probe(url = "${music.address}/rest/ping", home = place)))
        assertEquals(Outcome.Failed(home), probe(Core.Probe(line = "musicbrainz", url = "${music.address}/ws/2/release", home = place)))
        val found = runBlocking { Core.gateFind(lidarr.address, home = place) }
        assertTrue("$found", found is Outcome.Failed)
        // (So is the look taken when Lidarr itself is asked and answers with a sign-in.)
        val checked = runBlocking { Core.lidarrCheck(LidarrAt(lidarr.address, "the-key", home = place)) }
        assertTrue("$checked", (checked as Outcome.Got).value.doorWhy != null)
        assertTrue(music.asked.isEmpty())
        // The one exception is the person's own doing: Lidarr's address, as they typed it,
        // is on the music server's host and port (one proxy, two paths).
        val typedThere = runBlocking { Core.lidarrVersion(LidarrAt("${music.address}/lidarr", "the-key", home = place)) }
        assertEquals(1, music.asked.size)
        assertEquals("GET /lidarr/api/v1/system/status", music.asked.single().line)
        assertTrue("$typedThere", typedThere is Outcome.Failed)
    }

    // ---- limits ---------------------------------------------------------------------------------

    @Test
    fun `the library's own limit on a call is honoured, the body included`() {
        // Slow and hostile: the answer begins at once and then comes a letter at a time.
        val server = standIn { _, reply ->
            reply.raw("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n")
            while (true) {
                reply.raw("a")
                Thread.sleep(50)
            }
        }
        val took = elapsed {
            val answer = probe(Core.Probe(line = "listenbrainz", url = server.address, patience = 700)).got()
            assertEquals("It didn't answer in time.", answer.unread)
        }
        assertTrue("took $took ms", took in 600..5_000)
    }

    @Test
    fun `what carries a secret goes to the place the secret was given for, and nowhere else`() {
        val lidarr = standIn { _, reply -> reply.json("""{"version":"2.9.6"}""") }
        val other = standIn { _, reply -> reply.json("""{"version":"9"}""") }
        val place = Core.lidarrPlace(lidarr.address)!!
        assertEquals("http://127.0.0.1:${lidarr.port}", place)
        val refused = Outcome.Failed("The request would have carried a key to an address it wasn't given for, so it wasn't sent.")
        // A key, a pass, a body: each stays at home.
        assertEquals(refused, probe(Core.Probe(url = "${other.address}/api/v1/system/status", headers = listOf(listOf("X-Api-Key", "the-key")), bound = place)))
        assertEquals(refused, probe(Core.Probe(url = "${other.address}/x", headers = listOf(listOf("authorization", "Bearer a-pass")), bound = place)))
        assertEquals(refused, probe(Core.Probe(method = "POST", url = "${other.address}/application/o/token/", body = "refresh_token=r", bound = place), changing = true))
        // The same host by another scheme or on another port is another place.
        assertEquals(refused, probe(Core.Probe(url = lidarr.address.replace("http://", "https://"), headers = listOf(listOf("X-Api-Key", "the-key")), bound = place)))
        // And an address that reads as home to another reader and is elsewhere to the client.
        assertEquals(refused, probe(Core.Probe(url = "${other.address}\\@127.0.0.1:${lidarr.port}/api", headers = listOf(listOf("X-Api-Key", "the-key")), bound = place)))
        assertTrue(other.asked.isEmpty())
        assertTrue(lidarr.asked.isEmpty())
        // Bound to nowhere (a key kept without its place), it carries nothing anywhere.
        assertEquals(refused, probe(Core.Probe(url = lidarr.address, headers = listOf(listOf("X-Api-Key", "the-key")), bound = "")))
        // At home it goes, however the address is written.
        assertEquals(200, probe(Core.Probe(url = "${lidarr.address.replace("http://", "HTTP://")}/api/v1/system/status", headers = listOf(listOf("X-Api-Key", "the-key")), bound = place)).got().status)
        // What carries nothing may be asked of another place (a look at where a redirect
        // points), as before.
        assertEquals(200, probe(Core.Probe(url = "${other.address}/look", bound = place)).got().status)
        assertEquals(listOf("GET /look"), other.asked.map { it.line })
        // A connection made without saying where its key is for is bound to its own address.
        val shown = runBlocking { Core.lidarrVersion(LidarrAt(lidarr.address, "the-key")) }
        assertEquals(Outcome.Got("2.9.6"), shown)
        val elsewhere = runBlocking { Core.lidarrVersion(LidarrAt(other.address, "the-key", origin = place)) }
        assertEquals(Outcome.Failed("Couldn't reach Lidarr. The request would have carried a key to an address it wasn't given for, so it wasn't sent."), elsewhere)
        assertEquals(1, other.asked.size)
    }

    @Test
    fun `a change is sent once, whatever answer would have a client send it again`() {
        // Each of these answers lets an HTTP client repeat the request by itself: "busy, come
        // back at once", "this was sent to the wrong place", "you took too long to send it".
        // A change to Lidarr repeated may be a change made twice.
        for ((status, header) in listOf(503 to ("Retry-After" to "0"), 421 to null, 408 to null)) {
            for (method in listOf("POST", "PUT")) {
                val server = standIn { _, reply -> if (header != null) reply.send(status, "", header) else reply.send(status) }
                val answered = probe(Core.Probe(method = method, url = server.address, body = """{"wanted":true}"""), changing = true).got()
                assertEquals(status, answered.status)
                Thread.sleep(300)
                assertEquals("a $method answered $status was sent ${server.asked.size} times", 1, server.asked.size)
                assertEquals("""{"wanted":true}""", server.asked.single().body)
            }
        }
        // (And as part of something the library sees through.)
        val busy = standIn { _, reply -> reply.send(503, "", "Retry-After" to "0") }
        val begun = Core.wireProbeSeenThrough(Core.Probe(method = "POST", url = busy.address, body = "{}", keepGoing = "first"))
        assertEquals(503, runBlocking { withTimeout(30_000) { begun.done.await() } }.got().status)
        Thread.sleep(300)
        assertEquals(1, busy.asked.size)
    }

    @Test
    fun `nothing answering is said in the app's words, without the address`() {
        val gone = StandIn { _, _ -> }.also { it.close() }
        val failed = runBlocking { Core.lidarrVersion(LidarrAt(gone.address, "k")) }
        assertEquals(Outcome.Failed("Couldn't reach Lidarr. The connection was refused."), failed)
        val stalls = standIn { _, reply -> reply.hungUpWithin(20_000) }
        assertEquals(Outcome.Failed("It didn't answer in time."), probe(Core.Probe(url = stalls.address, patience = 400)))
    }

    // ---- stopping -------------------------------------------------------------------------------

    @Test
    fun `stopping something that only looks cuts its request off and sends no more`() = runBlocking {
        val cut = AtomicReference<Boolean?>(null)
        val server = standIn { asked, reply ->
            // The first page of what is on its way: never answered.
            if ("wanted/missing" in asked.target) cut.set(reply.hungUpWithin(10_000)) else reply.json("""{"records":[]}""")
        }
        val looking = launch(Dispatchers.Default) { Core.lidarrPending(LidarrAt(server.address, "k")) }
        assertTrue(server.awaitAsked { "wanted/missing" in it.target })
        val took = elapsed { runBlocking { looking.cancelAndJoin() } }
        // The caller is let go at once, the request in flight is cut off...
        assertTrue("took $took ms", took < 2_000)
        val until = System.nanoTime() + 5_000_000_000
        while (cut.get() == null && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(true, cut.get())
        // ...and the rest of what it would have asked is never asked.
        Thread.sleep(300)
        assertEquals(listOf("wanted/missing"), server.asked.map { it.target.substringAfter("/api/v1/").substringBefore("?") })
    }

    @Test
    fun `a wait is ended by a stop, and says so`() = runBlocking {
        val server = standIn { _, reply -> reply.json("{}") }
        // Not stopped: waited out.
        val waited = elapsed { assertEquals(true, probe(Core.Probe(url = server.address, pause = 300)).got().waited) }
        assertTrue("$waited ms", waited >= 300)
        // Stopped part-way through a long one: over at once, and the request after it is never made.
        val before = server.asked.size
        val waiting = async(Dispatchers.Default) { Core.wireProbe(Core.Probe(url = server.address, pause = 30_000)) }
        Thread.sleep(300)
        val took = elapsed { runBlocking { waiting.cancelAndJoin() } }
        assertTrue("took $took ms", took < 2_000)
        Thread.sleep(300)
        assertEquals(before, server.asked.size)
    }

    /** A stand-in Lidarr with one artist whose profile doesn't list singles, and one single
     *  of theirs: what asking for it changes, and what is put back. */
    private inner class LidarrWithOneArtist(val slowAfter: () -> Boolean = { false }) {
        @Volatile var newReleases = "all"
        @Volatile var profile = 1
        val wanted = AtomicBoolean(false)

        private fun artist() = """{"id":1,"foreignArtistId":"artist-1","artistName":"Quillon Vane","monitored":true,"metadataProfileId":$profile,"monitorNewItems":"$newReleases"}"""
        private val standard = """{"id":1,"name":"Standard","primaryAlbumTypes":[{"albumType":{"name":"Album"},"allowed":true},{"albumType":{"name":"EP"},"allowed":false},{"albumType":{"name":"Single"},"allowed":false}],""" +
            """"secondaryAlbumTypes":[{"albumType":{"name":"Studio"},"allowed":true}],"releaseStatuses":[{"releaseStatus":{"name":"Official"},"allowed":true}]}"""

        val server = standIn { asked, reply ->
            val path = asked.target.substringAfter("/api/v1/")
            if (slowAfter()) Thread.sleep(3_000)
            when {
                asked.method == "GET" && path == "artist" -> reply.json("[${artist()}]")
                asked.method == "GET" && path == "metadataprofile" -> reply.json("[$standard]")
                asked.method == "POST" && path == "metadataprofile" -> reply.json(asked.body.replaceFirst("{", """{"id":2,"""))
                asked.method == "GET" && path == "artist/1" -> reply.json(artist())
                asked.method == "PUT" && path == "artist/1" -> {
                    newReleases = Regex(""""monitorNewItems":"(\w+)"""").find(asked.body)!!.groupValues[1]
                    profile = Regex(""""metadataProfileId":(\d+)""").find(asked.body)!!.groupValues[1].toInt()
                    reply.json(artist())
                }
                asked.method == "GET" && path.startsWith("album?artistId=1") -> reply.json("""[{"id":7,"title":"One Song","foreignAlbumId":"single-1","albumType":"Single","monitored":${wanted.get()}}]""")
                asked.method == "GET" && path == "command" -> reply.json("[]")
                asked.method == "PUT" && path == "album/monitor" -> {
                    wanted.set(true)
                    reply.json("[]")
                }
                asked.method == "GET" && path == "album/7" -> reply.json("""{"id":7,"monitored":${wanted.get()}}""")
                asked.method == "POST" && path == "command" -> reply.json("{}")
                else -> reply.send(404)
            }
        }
        val at get() = LidarrAt(server.address, "k")
        /** (What must be put back is counted as written down: see `RequestsPagesTest`.) */
        val place = Core.placeOpen { _, _ -> true }
        val single = Offer(album = "single-1", artistId = "artist-1", title = "One Song", artist = "Quillon Vane", kind = "Single")
        fun turnedOff() = server.asked.any { it.method == "PUT" && it.target.endsWith("artist/1") && "\"monitorNewItems\":\"none\"" in it.body }
    }

    @Test
    fun `asking for a single, left alone, turns new releases off while Lidarr reads and back on after`() {
        val lidarr = LidarrWithOneArtist()
        assertEquals(Outcome.Got(true), runBlocking { withTimeout(60_000) { Core.lidarrRequest(lidarr.at, lidarr.place, lidarr.single) } })
        assertTrue(lidarr.turnedOff())
        assertEquals("all", lidarr.newReleases)
        assertEquals("POST command", lidarr.server.asked.last { it.method == "POST" }.let { "${it.method} ${it.target.substringAfter("/api/v1/")}" })
    }

    @Test
    fun `asking for a single, stopped once it has begun to change Lidarr, is seen through to the end`() = runBlocking {
        val lidarr = LidarrWithOneArtist()
        val asking = launch(Dispatchers.Default) { Core.lidarrRequest(lidarr.at, lidarr.place, lidarr.single) }
        // New releases have been turned off, and the library is waiting for Lidarr to begin.
        assertTrue(lidarr.server.awaitAsked { it.method == "PUT" && it.target.endsWith("artist/1") })
        assertEquals("none", lidarr.newReleases)
        val took = elapsed { runBlocking { asking.cancelAndJoin() } }
        // Whoever asked is let go at once. Behind them the library carries on as if nobody
        // had stopped it (it said "keep going" before its first change): the waits are
        // waited out, the album is marked and looked for, and new releases are put back.
        // Stopped half-way it would leave an album marked and never looked for.
        assertTrue("took $took ms", took < 2_000)
        assertTrue(lidarr.server.awaitAsked(30_000) { it.method == "POST" && it.target.endsWith("/command") })
        val until = System.nanoTime() + 20_000_000_000
        while (lidarr.newReleases != "all" && System.nanoTime() < until) Thread.sleep(20)
        assertEquals("all", lidarr.newReleases)
        assertTrue(lidarr.wanted.get())
    }

    @Test
    fun `asking for a single, stopped before it has changed anything, changes nothing`() = runBlocking {
        // Lidarr is slow to say which artists it has: the first thing asked, and only a look.
        val held = CountDownLatch(1)
        val lidarr = LidarrWithOneArtist(slowAfter = { held.await(5, TimeUnit.SECONDS); false })
        val asking = launch(Dispatchers.Default) { Core.lidarrRequest(lidarr.at, lidarr.place, lidarr.single) }
        assertTrue(lidarr.server.awaitAsked { it.method == "GET" && it.target.endsWith("/artist") })
        val took = elapsed { runBlocking { asking.cancelAndJoin() } }
        assertTrue("took $took ms", took < 2_000)
        held.countDown()
        Thread.sleep(1_500)
        // Nothing but looks ever reached Lidarr, and no more after the stop.
        val asked = lidarr.server.asked.map { "${it.method} ${it.target.substringAfter("/api/v1/")}" }
        assertEquals(listOf("GET artist"), asked)
        assertEquals("all", lidarr.newReleases)
        assertFalse(lidarr.wanted.get())
    }

    @Test
    fun `once the library has said to keep going, a stop ends no wait and refuses no request`() = runBlocking {
        val server = standIn { _, reply -> reply.json("{}") }
        val begun = Core.wireProbeSeenThrough(Core.Probe(method = "PUT", url = server.address, body = "{}", pause = 1_200, keepGoing = "first"))
        Thread.sleep(300)
        begun.stop()
        val done = withTimeout(20_000) { begun.done.await() }.got()
        assertEquals("the wait was cut short", true, done.waited)
        assertEquals(200, done.status)
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `what nobody can stop still ends, each request of a change has a limit of its own`() = runBlocking {
        // (Lidarr answers the change a letter at a time, or not at all: there are two
        // threads for changes, and after "keep going" no stop reaches this one.)
        val hungUp = CopyOnWriteArrayList<Boolean>()
        val server = standIn { _, reply ->
            reply.raw("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4000\r\n\r\n")
            hungUp += runCatching {
                repeat(40) {
                    reply.raw("{")
                    Thread.sleep(150)
                }
                false
            }.getOrDefault(true)
        }
        CoreHttp.changeMillis = 700
        var began = System.nanoTime()
        val seen = Core.wireProbeSeenThrough(Core.Probe(method = "PUT", url = server.address, body = "{}", keepGoing = "first", then = "text"))
        assertTrue(server.awaitAsked { it.method == "PUT" })
        seen.stop()
        val ended = withTimeout(20_000) { seen.done.await() }
        var took = (System.nanoTime() - began) / 1_000_000
        assertTrue("$ended after $took ms", took in 600..3_000)
        assertEquals("It didn't answer in time.", (ended as? Outcome.Got)?.value?.unread ?: (ended as? Outcome.Failed)?.why)
        // The same for a change in one step that nobody has stopped at all.
        began = System.nanoTime()
        val one = probe(Core.Probe(method = "PUT", url = server.address, body = "{}", then = "text"), changing = true)
        took = (System.nanoTime() - began) / 1_000_000
        assertTrue("$one after $took ms", took in 600..3_000)
        assertEquals("It didn't answer in time.", (one as? Outcome.Got)?.value?.unread ?: (one as? Outcome.Failed)?.why)
        // A look has no such limit: it has a screen that can be left.
        CoreHttp.changeMillis = 120_000
        withTimeout(10_000) { while (hungUp.size < 2) delay(50) }
        assertEquals(listOf(true, true), hungUp.toList())
    }

    @Test
    fun `a stop that came before the library said to keep going is still honoured`() = runBlocking {
        val server = standIn { _, reply -> reply.json("{}") }
        // Stopped during the wait; only then does the library say "keep going", too late:
        // the change it was about to make is never sent.
        val begun = Core.wireProbeSeenThrough(Core.Probe(method = "PUT", url = server.address, body = "{}", pause = 30_000, keepGoing = "after the wait"))
        Thread.sleep(300)
        begun.stop()
        assertEquals(Outcome.Stopped, withTimeout(20_000) { begun.done.await() })
        Thread.sleep(300)
        assertEquals(0, server.asked.size)
    }

    @Test
    fun `something that changes in one step and never says to keep going is let finish under a short limit, and after a while nothing is`() = runBlocking {
        // (Taking an album off Lidarr's list; finishing a sign-in, whose second request reads
        // the name the first one's token belongs to.) Stopped during a wait, the request
        // that follows is still let through...
        val server = standIn { _, reply -> reply.json("{}") }
        CoreHttp.afterStopMillis = 400
        CoreHttp.graceMillis = 1_000
        val changing = launch(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "PUT", url = server.address, body = "{}", pause = 800), changing = true) }
        Thread.sleep(200)
        changing.cancelAndJoin()
        assertTrue(server.awaitAsked(5_000) { it.method == "PUT" })
        // ...and one that would come after the grace is over is not.
        // (The library busy for three seconds, in a way no stop can cut short, before it asks.)
        val late = launch(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "PUT", url = server.address, body = "{}", sleep = 3_000), changing = true) }
        Thread.sleep(200)
        late.cancelAndJoin()
        Thread.sleep(4_000)
        assertEquals(1, server.asked.size)

        // And the one that is let through has only the short while: not answered in it, it
        // is cut off.
        val cut = CopyOnWriteArrayList<Boolean>()
        val slow = standIn { _, reply ->
            cut += reply.hungUpWithin(3_000)
            runCatching { reply.json("{}") }
        }
        val held = launch(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "PUT", url = slow.address, body = "{}", pause = 800), changing = true) }
        Thread.sleep(200)
        held.cancelAndJoin()
        assertTrue(slow.awaitAsked(5_000) { it.method == "PUT" })
        withTimeout(6_000) { while (cut.isEmpty()) delay(50) }
        assertEquals(listOf(true), cut.toList())
    }

    @Test
    fun `a request that changes something is not cut off at the stop, only given a short while`() = runBlocking {
        val cut = CopyOnWriteArrayList<Boolean>()
        val arrived = CountDownLatch(1)
        val server = standIn { _, reply ->
            arrived.countDown()
            // Still there 700 ms on? Then answer.
            cut += reply.hungUpWithin(700)
            runCatching { reply.json("{}") }
        }
        val changing = launch(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "PUT", url = server.address, body = "{}"), changing = true) }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        changing.cancelAndJoin()
        Thread.sleep(1_200)
        assertEquals(listOf(false), cut.toList())

        // With the short while shorter than the answer takes, it is cut off then.
        CoreHttp.afterStopMillis = 200
        val again = launch(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "PUT", url = server.address, body = "{}"), changing = true) }
        assertTrue(server.awaitAsked { server.asked.size == 2 })
        Thread.sleep(50)
        again.cancelAndJoin()
        Thread.sleep(1_200)
        assertEquals(listOf(false, true), cut.toList())
    }

    @Test
    fun `a request that is nobody's is never cut off by a stop, and has a limit of its own`() = runBlocking {
        val cut = CopyOnWriteArrayList<Boolean>()
        val server = standIn { _, reply ->
            cut += reply.hungUpWithin(600)
            runCatching { reply.json("{}") }
        }
        val asking = launch(Dispatchers.Default) { Core.wireProbe(Core.Probe(nobodys = true, method = "POST", url = server.address, body = "a=b")) }
        assertTrue(server.awaitAsked { true })
        asking.cancelAndJoin()
        Thread.sleep(1_000)
        assertEquals(listOf(false), cut.toList())

        CoreHttp.nobodysMillis = 300
        assertEquals(Outcome.Failed("It didn't answer in time."), Core.wireProbe(Core.Probe(nobodys = true, method = "POST", url = server.address, body = "a=b")))
    }

    // ---- a sign-in kept open ----------------------------------------------------------------------

    @Test
    fun `a sign-in's token is handed over to be put away each time the service replaces it`() = runBlocking {
        val refusing = AtomicBoolean(false)
        val service = standIn { asked, reply ->
            when {
                asked.target != "/application/o/token/" -> reply.send(404)
                refusing.get() -> reply.json("""{"error":"invalid_grant"}""", status = 400)
                "grant_type=refresh_token" in asked.body -> {
                    val had = asked.body.substringAfter("refresh_token=").substringBefore("&")
                    reply.json("""{"access_token":"access-of-$had","refresh_token":"$had-next","expires_in":300}""")
                }
                // (A pass that has run out already, so that each request renews.)
                else -> reply.json("""{"access_token":"pass-for-${asked.body.substringAfter("client_assertion=").substringBefore("&")}","expires_in":0}""")
            }
        }
        val lidarr = standIn { _, reply -> reply.json("""{"version":"2.9.6"}""") }
        val kept = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()
        val session = Core.gateOpen(SignInDoor(service.address, "lidarr-proxy"), "first") {
            kept += it
            threads += Thread.currentThread().name
        }
        val at = LidarrAt(lidarr.address, "the-key", session)

        assertEquals(Outcome.Got("2.9.6"), Core.lidarrVersion(at))
        assertEquals(listOf("first-next"), kept.toList())
        assertEquals("Bearer pass-for-access-of-first", lidarr.asked.last().header("Authorization"))
        assertEquals("the-key", lidarr.asked.last().header("X-Api-Key"))
        assertEquals("grant_type=refresh_token&refresh_token=first&client_id=nexus-music", service.asked.first().body)

        assertEquals(Outcome.Got("2.9.6"), Core.lidarrVersion(at))
        assertEquals(listOf("first-next", "first-next-next"), kept.toList())
        assertTrue("$threads", threads.all { it.startsWith("requests-look-") })

        // The service stops honouring it: there is nothing left to keep, and the library says so.
        refusing.set(true)
        assertEquals(Outcome.Failed("Your sign-in to Lidarr has ended. In Settings, under Requests, sign in with your browser again."), Core.lidarrVersion(at))
        assertEquals("", kept.last())
        // Closed, it hands back what it holds (nothing, now), and is no longer a way through.
        assertEquals("", Core.gateClose(session))
        assertEquals(Outcome.Failed("Lidarr is behind a sign-in. In Settings, under Requests, sign in with your browser."), Core.lidarrVersion(at))
    }

    @Test
    fun `a sign-in closed with a token hands back the newest`() = runBlocking {
        val service = standIn { asked, reply ->
            if ("grant_type=refresh_token" in asked.body) reply.json("""{"access_token":"a","refresh_token":"second","expires_in":300}""") else reply.json("""{"access_token":"p","expires_in":300}""")
        }
        val lidarr = standIn { _, reply -> reply.json("""{"version":"2.9.6"}""") }
        val door = SignInDoor(service.address, "lidarr-proxy")
        val session = Core.gateOpen(door, "first") {}
        Core.lidarrVersion(LidarrAt(lidarr.address, "k", session)).got()
        assertEquals("second", Core.gateClose(session))
        // Handing it back to the service, as the Windows app does at sign-out.
        Core.gateGiveUp(door, "second")
        val revoke = service.asked.last()
        assertEquals("POST /application/o/revoke/", revoke.line)
        assertEquals("token=second&client_id=nexus-music", revoke.body)
    }

    // ---- threads, and what going wrong looks like ---------------------------------------------------

    @Test
    fun `the library's calls run on threads kept for them, four at once for looking and two for changing`() = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        CoreHttp.unsent = {
            threads += Thread.currentThread().name
            null
        }
        val looks = standIn { _, reply ->
            Thread.sleep(400)
            reply.json("{}")
        }
        (1..10).map { async(Dispatchers.Default) { Core.wireProbe(Core.Probe(url = looks.address)) } }.awaitAll()
        // (The numbers themselves, as the design has them: a change to either is a decision.)
        assertEquals(4, CoreHttp.LOOKING)
        assertEquals(2, CoreHttp.CHANGING)
        assertEquals(CoreHttp.LOOKING, looks.mostAtOnce.get())
        val changes = standIn { _, reply ->
            Thread.sleep(400)
            reply.json("{}")
        }
        (1..6).map { async(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "POST", url = changes.address, body = "{}"), changing = true) } }.awaitAll()
        assertEquals(CoreHttp.CHANGING, changes.mostAtOnce.get())
        assertEquals(16, threads.size)
        assertTrue("$threads", threads.all { it.startsWith("requests-look-") || it.startsWith("requests-change-") })
    }

    @Test
    fun `more at once than there are threads and places in the queue is told the app is busy, at once`() = runBlocking {
        val go = java.util.concurrent.CountDownLatch(1)
        val server = standIn { _, reply ->
            go.await(30, TimeUnit.SECONDS)
            reply.json("""{"version":"2.9.6"}""")
        }
        val busy = Outcome.Failed("The app is busy with other requests. Try again in a moment.")
        fun look() = async(Dispatchers.Default) { Core.lidarrVersion(LidarrAt(server.address, "k")) }
        fun change() = async(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "PUT", url = server.address, body = "{}"), changing = true) }
        // Four are being answered...
        val looking = (1..CoreHttp.LOOKING).map { look() }
        assertTrue(server.awaitAsked { server.asked.size == 4 })
        // ...thirty-two more wait their turn: that is all there is room for.
        val waiting = (1..CoreHttp.LOOKING_WAITING).map { look() }
        delay(500)
        val began = System.nanoTime()
        assertEquals(busy, withTimeout(5_000) { Core.lidarrVersion(LidarrAt(server.address, "k")) })
        assertTrue((System.nanoTime() - began) / 1_000_000 < 1_000)
        // The same for changes: two under way, eight waiting.
        val changing = (1..CoreHttp.CHANGING).map { change() }
        assertTrue(server.awaitAsked { server.asked.size == 6 })
        val changes = (1..CoreHttp.CHANGING_WAITING).map { change() }
        delay(500)
        assertEquals(busy, withTimeout(5_000) { Core.wireProbe(Core.Probe(method = "PUT", url = server.address, body = "{}"), changing = true) })
        // Something begun and not waited for says so too, when its end is asked for.
        val begun = Core.wireProbeSeenThrough(Core.Probe(method = "PUT", url = server.address, body = "{}"))
        assertTrue(withTimeout(5_000) { runCatching { begun.done.await() }.exceptionOrNull() } is CoreHttp.Busy)
        // What had room is answered once there is someone to answer it.
        go.countDown()
        assertTrue((looking + waiting).awaitAll().all { it == Outcome.Got("2.9.6") })
        assertEquals(10, (changing + changes).awaitAll().count { it is Outcome.Got })
        // And then there is room again.
        assertEquals(Outcome.Got("2.9.6"), Core.lidarrVersion(LidarrAt(server.address, "k")))
    }

    @Test
    fun `something thrown on the phone's side of a request is the library's failure, and nothing is left behind`() {
        val server = standIn { _, reply -> reply.json("""{"version":"2.9.6"}""") }
        // Not an ordinary failure, which the phone's side answers in words itself: an error
        // that goes straight up through it, into the library.
        CoreHttp.unsent = { throw StackOverflowError("thrown on purpose, with the-key in its words") }
        val failed = runBlocking { Core.lidarrVersion(LidarrAt(server.address, "the-key")) }
        assertEquals(Outcome.Failed("Couldn't reach Lidarr. The app couldn't make the request."), failed)
        // Nothing was left waiting in the Java machine to be thrown at the next call.
        CoreHttp.unsent = { null }
        assertEquals(Outcome.Got("2.9.6"), runBlocking { Core.lidarrVersion(LidarrAt(server.address, "the-key")) })
        assertEquals("still here", Core.echo("still here"))
    }

    @Test
    fun `a panic in the library while it holds an answer is the one exception, and the answer is let go`() {
        val hungUp = AtomicReference<Boolean?>(null)
        val server = standIn { _, reply ->
            reply.raw("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\npart")
            hungUp.set(reply.hungUpWithin(5_000))
        }
        val thrown = assertThrows(CoreException::class.java) { probe(Core.Probe(url = server.address, then = "panic")) }
        assertEquals("The shared library stopped.", thrown.message)
        val until = System.nanoTime() + 5_000_000_000
        while (hungUp.get() == null && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(true, hungUp.get())
        assertEquals("still here", Core.echo("still here"))
    }

    @Test
    fun `a caller cancelled before its turn comes never starts`() = runBlocking {
        val server = standIn { _, reply ->
            Thread.sleep(500)
            reply.json("{}")
        }
        // Both changing threads busy, and a third call waiting its turn.
        val busy = (1..2).map { async(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "POST", url = "${server.address}/busy", body = "{}"), changing = true) } }
        assertTrue(server.awaitAsked { server.asked.size == 2 })
        val third = async(Dispatchers.Default) { Core.wireProbe(Core.Probe(method = "POST", url = "${server.address}/third", body = "{}"), changing = true) }
        Thread.sleep(100)
        third.cancel()
        busy.awaitAll()
        assertThrows(CancellationException::class.java) { runBlocking { third.await() } }
        Thread.sleep(300)
        assertTrue(server.asked.none { it.target == "/third" })
    }
}
