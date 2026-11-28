package org.medianexus.music.nexus

import android.app.Application
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.internal.platform.Platform
import okio.Buffer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.SessionState
import org.medianexus.music.graph

/**
 * The music server's origin as the phone meets it once the Nexus server has been set up beside
 * it (docs/backend-design.md, 7.1): **one HTTPS origin**, the music server's own paths answered
 * as Navidrome answers them (enough to browse, stream and download, and to refuse a music login
 * that is no longer good), and everything under `/nexus/` either **a backend that is there**
 * ([backendThere]: `hello`, signing in, the session's check, the music login, signing out, each
 * answered with a body the shared core's own encoder wrote) or broken in one of the ways of the
 * design's 5.3.
 *
 * HTTPS, with the certificate made for "localhost" (`localhost.p12`, test resources), because
 * the backend's line is HTTPS only (R5): a phone signed in over plain HTTP never asks it
 * anything, and a test of that would prove nothing. Every OkHttp client made while [trust] is
 * in force (the app's own, and any other) trusts that certificate as well as the system's.
 */
class BackendStandIn(val broken: Broken, private val https: Boolean = true) {
    /** How the backend's side is broken. The names are the design's (5.3). */
    enum class Broken {
        /** Run first, as ABSENT, and not held to any time: the first pass through a test in a
         *  fresh process is the slow one, whatever the app. */
        WARM_UP,

        /** No backend: the music server is left to answer `/nexus/`, and answers as Navidrome
         *  0.64.2 was measured to (E1): a redirect to its web app for a GET, "method not
         *  allowed" for anything else. */
        ABSENT,

        /** What is sent is swallowed: the request arrives, and nothing ever comes back on
         *  that connection, not a byte, not a close, and nothing more is read from it. (At
         *  one origin, behind one proxy, this is what a black-holed container looks like from
         *  the phone until the proxy gives up: the phone's own connection is to the proxy,
         *  which answers the music server's paths as ever.) */
        BLACK_HOLE,

        /** The connection is taken and the request read, and the answer never comes. */
        STALLED,

        /** Every answer is "busy", slowly: the request held 8 seconds (under the client's
         *  10-second read limit), then 503 with "Retry-After: 0" (which OkHttp would follow up
         *  by itself), its body a byte a second. */
        SATURATED,

        /** Every answer is "not signed in" (401). */
        SESSION_ENDED,

        /** The backend's `hello` begins (200, JSON, the length of a whole one) and stops half
         *  way through its body, the connection held open: a side left half done. (Stage 1
         *  of the phone has no signing in to be killed between the halves of.) */
        HALF_CONNECTED,

        /** Stalled, and every thread of the phone's client for the backend (its launcher, its
         *  worker, its HTTP dispatcher's) held by the test in work that never ends. */
        WORKERS_HELD,

        /** Stalled, and where the client would keep its files (`files/nexus`) is a pipe that
         *  nothing reads: a write there hangs. (The client keeps no file at this stage.) */
        DISK_BLOCKED,

        /** Stalled, and where the client would keep its files is taken by a file: a write
         *  there fails, as on a full disk. */
        DISK_FULL,
    }

    private val server = MockWebServer()

    /** Every request, as "METHOD path", in order. */
    val asked = CopyOnWriteArrayList<String>()

    /** The connection (by the server's count) each request under `/nexus/` came on, and each
     *  of the music server's. A connection that carried both would be the app's client and
     *  the backend's sharing a pool. */
    val nexusConnections: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val musicConnections: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The requests under `/nexus/` being held **now**: arrived, and not answered, for as long
     *  as the stand-in is holding each (until the test ends, or SATURATED's eight seconds). */
    val nexusActive = java.util.concurrent.atomic.AtomicInteger()

    /** The requests under `/nexus/` that have arrived, ever (at this stand-in, one per test). */
    val nexusArrived = java.util.concurrent.atomic.AtomicInteger()

    /** Endpoints of the music server's answered by closing the connection, for as long as
     *  they are in here (a report that can't be made until the test lets it). */
    val failing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Streams cut after their first bytes, once each, by song id (a download cut off). */
    val cutOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val release = CountDownLatch(1)

    /** Set, it answers everything under `/nexus/` instead of [broken]: in some way a test
     *  wants. */
    @Volatile var answer: ((RecordedRequest) -> MockResponse)? = null

    /** While set, a backend that is there answers under `/nexus/` (the design's own calls), as
     *  while a test signs in; otherwise it is as [broken] says. */
    @Volatile var backendThere = false

    /** The backend says to every session that it has ended (`not_signed_in`): a device forced
     *  out, the account switched off. Only while [backendThere]. */
    @Volatile var sessionEnded = false

    /** The music login the backend hands over now: its number, and the password its token is
     *  made from (a new number is a new password, as when a device is forced out). */
    @Volatile var musicCount = 1L
    private fun musicPassword(count: Long = musicCount) = "music password $count"

    /** The music logins the music server takes, by token: every one made from the password of
     *  the latest number. Any other is refused, as Navidrome refuses a wrong password (40). */
    private fun takes(token: String?, salt: String?): Boolean = token != null && salt != null && token == org.medianexus.music.net.md5Hex(musicPassword() + salt)

    /** The backend turns a sign-in away as a wrong email or password. */
    @Volatile var refuseSignIn = false

    /** Whether the music server checks the login at all (off: the tests that are not about it). */
    @Volatile var checksLogins = true

    /** Requests the music server refused for a wrong login, ever. */
    val refusedLogins = java.util.concurrent.atomic.AtomicInteger()

    /** The calls a backend that was there answered, by path. */
    val backendAnswered = CopyOnWriteArrayList<String>()

    private var signedInAs = "tester"

    /** This stand-in's own salts (a backend makes a fresh one for each login it hands out): one
     *  test's refused login is never another's. */
    private val saltBase = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)

    /** Songs, by id, that the server has; and the album they are all on. */
    val songs = listOf("s1", "s2", "s3")

    private val audio: ByteArray by lazy { BackendStandIn::class.java.getResourceAsStream("/audio/three-seconds.flac")!!.use { it.readBytes() } }

    fun start() {
        if (https) server.useHttps(tls.socketFactory)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                asked += "${request.method} $path"
                return if (path.startsWith("/nexus/") || path == "/nexus") {
                    nexusConnections += request.connectionIndex
                    nexusArrived.incrementAndGet()
                    nexus(request)
                } else {
                    musicConnections += request.connectionIndex
                    music(request)
                }
            }
        }
        server.start()
    }

    /** Lets every held request go (each is then dropped) and closes the server. */
    fun stop() {
        release.countDown()
        // (MockWebServer waits five seconds for its connections' threads, and fails if a
        // client still holds one open: after every test's own checks, so not a test's fault.)
        try {
            server.close()
        } catch (e: AssertionError) {
            System.err.println("BackendStandIn: ${e.message}")
        }
    }

    /** The origin, by the name the certificate is for, as a person types it. */
    val address: String get() = "${if (https) "https" else "http"}://localhost:${server.port}"

    /** The address shown in the Settings row: the origin's host and port. */
    val shown: String get() = "localhost:${server.port}"

    /** Holds the request here until the test ends (or `millis` pass), counted in [nexusActive]
     *  while it is. */
    private fun hold(millis: Long = Long.MAX_VALUE) {
        nexusActive.incrementAndGet()
        try {
            release.await(millis, TimeUnit.MILLISECONDS)
        } finally {
            nexusActive.decrementAndGet()
        }
    }

    private fun nexus(request: RecordedRequest): MockResponse = answer?.invoke(request) ?: if (backendThere) there(request) else when (broken) {
        Broken.ABSENT, Broken.WARM_UP ->
            if (request.method == "GET") {
                MockResponse.Builder().code(302).setHeader("Location", "/app/").setHeader("Content-Type", "text/html; charset=utf-8").body("<a href=\"/app/\">Found</a>.\n\n").build()
            } else {
                MockResponse.Builder().code(405).setHeader("Content-Type", "text/plain; charset=utf-8").body("Method Not Allowed\n").build()
            }
        Broken.BLACK_HOLE -> {
            hold()
            MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()
        }
        Broken.STALLED, Broken.WORKERS_HELD, Broken.DISK_BLOCKED, Broken.DISK_FULL -> {
            hold()
            MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }
        Broken.SATURATED -> {
            hold(8_000)
            MockResponse.Builder().code(503).setHeader("Retry-After", "0").setHeader("Content-Type", "application/json")
                .throttleBody(1, 1, TimeUnit.SECONDS).body("""{"error":"busy"}""").build()
        }
        Broken.SESSION_ENDED ->
            MockResponse.Builder().code(401).setHeader("Content-Type", "application/json").body("""{"error":"not signed in"}""").build()
        Broken.HALF_CONNECTED -> {
            val whole = HELLO.toByteArray()
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                // The first half at once; the rest an hour later, which no test waits for.
                .throttleBody(whole.size / 2L, 1, TimeUnit.HOURS)
                .body(Buffer().write(whole)).build()
        }
    }

    /** A backend that is there: each of the design's calls, answered by the core's own words. */
    internal fun there(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        backendAnswered += path
        val base = buildJsonObject {
            put("backend", BACKEND_ID)
            put("email", "${signedInAs}@example.org")
            put("name", signedInAs)
            put("device", 1)
            put("count", musicCount)
        }
        val salt = saltBase.dropLast(musicCount.toString().length) + musicCount
        val music = buildJsonObject {
            put("address", address)
            put("name", signedInAs)
            put("salt", salt)
            put("token", org.medianexus.music.net.md5Hex(musicPassword() + salt))
            put("count", musicCount)
        }
        val kind = when {
            path == "/nexus/api/hello" -> return answered(org.medianexus.music.core.Core.nexusStandIn(JsonObject(base + ("kind" to JsonPrimitive("hello")))))
            path == "/nexus/api/signout-note" || path == "/nexus/api/signout" && request.headers["Authorization"] == null -> "signed_out"
            path == "/nexus/api/signin" && refuseSignIn -> "wrong_login"
            path == "/nexus/api/signin" -> "signed_in"
            sessionEnded -> "session_ended"
            path == "/nexus/api/devices" -> "devices"
            path == "/nexus/api/music-login" -> "music_login"
            path == "/nexus/api/signout" -> "signed_out"
            path == "/nexus/api/force-out" -> "forced_out"
            path == "/nexus/api/change-password" -> "password_changed"
            else -> return MockResponse.Builder().code(404).build()
        }
        val asked = JsonObject(base + ("kind" to JsonPrimitive(kind)) + ("session" to JsonPrimitive("cd".repeat(32))) + ("music" to music))
        return answered(org.medianexus.music.core.Core.nexusStandIn(asked))
    }

    internal fun feature(kind: String, life: Long = 1): MockResponse =
        answered(org.medianexus.music.core.Core.nexusStandIn(buildJsonObject {
            put("backend", BACKEND_ID)
            put("count", musicCount)
            put("life", life)
            put("kind", kind)
        }))

    private fun answered(out: org.medianexus.music.core.Core.StandInOut) = MockResponse.Builder().code(out.status).setHeader("Content-Type", "application/json").body(Buffer().write(out.bytes)).build()

    private fun music(request: RecordedRequest): MockResponse {
        val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
        if (!request.url.encodedPath.startsWith("/rest/")) return MockResponse.Builder().code(404).build()
        // A music login that is no longer good: refused as Navidrome refuses it, with a success
        // status and a small body (the design's 6.3).
        if (checksLogins && !takes(request.url.queryParameter("t"), request.url.queryParameter("s"))) {
            refusedLogins.incrementAndGet()
            return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true,"error":{"code":40,"message":"Wrong username or password"}}}""").build()
        }
        if (endpoint in failing) return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        if ((endpoint == "stream" || endpoint == "download") && cutOnce.remove(request.url.queryParameter("id"))) {
            // (Its length the whole file's, a third of it sent, and the connection closed.)
            return MockResponse.Builder().code(200).setHeader("Content-Type", "audio/flac")
                .body(Buffer().write(audio, 0, audio.size / 3)).setHeader("Content-Length", audio.size)
                .onResponseEnd(SocketEffect.CloseSocket()).build()
        }
        if (endpoint == "stream" || endpoint == "download") {
            return MockResponse.Builder().code(200).setHeader("Content-Type", "audio/flac").setHeader("Accept-Ranges", "none").body(Buffer().write(audio)).build()
        }
        val more = when (endpoint) {
            "getAlbum" -> ""","album":{"id":"al-1","name":"First Light","artist":"Paper Lanterns","artistId":"ar-1","songCount":3,"song":[${songs.joinToString(",") { song(it) }}]}"""
            "getSong" -> ""","song":${song(request.url.queryParameter("id") ?: "s1")}"""
            "search3" -> ""","searchResult3":{"song":[${songs.joinToString(",") { song(it) }}]}"""
            "getAlbumList2" -> ""","albumList2":{"album":[{"id":"al-1","name":"First Light","artist":"Paper Lanterns","artistId":"ar-1","songCount":3}]}"""
            else -> ""
        }
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true$more}}"""
        return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
    }

    private fun song(id: String) =
        """{"id":"$id","title":"Song ${id.removePrefix("s")}","album":"First Light","albumId":"al-1","artist":"Paper Lanterns","artistId":"ar-1","track":${id.removePrefix("s")},"duration":3,"suffix":"flac","contentType":"audio/flac","size":${audio.size},"isDir":false}"""

    /**
     * **Signs the app in to this origin through the Nexus server**, as a person does at the
     * sign-in screen: the machine asks `hello`, then signs in, and is handed the music login;
     * the account is published. A backend that is there answers while it does, and the case's
     * own way of being broken holds again once the account's objects are there (other threads'
     * work, waited for before a test begins).
     */
    fun signIn(app: Application, username: String = "tester") {
        val graph = app.graph
        signedInAs = username
        backendThere = true
        try {
            graph.started()
            runBlocking {
                withTimeout(30_000) {
                    while (graph.nexus?.started != true) kotlinx.coroutines.delay(10)
                    graph.nexus!!.press(buildJsonObject {
                        put("sign_in", buildJsonObject { put("address", address); put("email", "$username@example.org"); put("password", "a password long enough") })
                    })
                    val signedIn = graph.session.state.first { it is SessionState.SignedIn && it.account.username == username } as SessionState.SignedIn
                    graph.accounts.state.first { it?.api === signedIn.api }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // (What was asked and what is shown, said when a sign-in does not come through.)
            System.err.println("stand-in sign-in timed out: asked ${asked.toList()}; session ${graph.session.state.value.javaClass.simpleName} ${graph.session.signIn.value}; shown ${graph.nexus?.shown?.value?.let { it.screen + "/" + it.signIn.state + "/" + it.signIn.words + "/plays=" + it.plays }}; failure ${Nexus.lastFailure?.let { it.javaClass.name + ": " + it.message + " @ " + it.stackTrace.take(6).joinToString(" < ") }}")
            throw e
        } finally {
            backendThere = false
        }
    }

    companion object {
        /** The backend, there: its `hello`. */
        fun hello(): MockResponse = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(HELLO).build()

        /** A valid `hello` (the shared library's own example, design 12.1). */
        const val HELLO = """{"nexus":"0.1.0","id":"0123456789abcdef0123456789abcdef","speaks":[1,1],"has":["plays"]}"""

        /** The stand-in backend's id. */
        const val BACKEND_ID = "0123456789abcdef0123456789abcdef"

        private const val PASSWORD = "testing"

        private val store: KeyStore by lazy {
            KeyStore.getInstance("PKCS12").apply { BackendStandIn::class.java.getResourceAsStream("/localhost.p12")!!.use { load(it, PASSWORD.toCharArray()) } }
        }

        /** The stand-in's own side of TLS. */
        val tls: SSLContext by lazy {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD.toCharArray()) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        }

        private val trusting: X509TrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers[0] as X509TrustManager
        }

        /**
         * Every OkHttp client made from now until [untrust] trusts the stand-in's certificate
         * (OkHttp asks its platform for the trust of a client that names none, as the app's
         * clients don't). Nothing else about the platform changes.
         */
        @OptIn(okhttp3.internal.OkHttpInternalApi::class)
        fun trust() {
            Platform.resetForTests(object : Platform() {
                override fun platformTrustManager(): X509TrustManager = trusting
                override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory =
                    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
            })
        }

        @OptIn(okhttp3.internal.OkHttpInternalApi::class)
        fun untrust() = Platform.resetForTests()
    }
}
