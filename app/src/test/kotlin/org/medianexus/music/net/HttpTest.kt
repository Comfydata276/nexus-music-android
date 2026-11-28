package org.medianexus.music.net

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The shared client against a stand-in server that speaks HTTPS, with a certificate made
 *  for "localhost" (test resources, `localhost.p12`) that only these tests trust. */
class HttpTest {
    private lateinit var secure: MockWebServer
    private lateinit var plain: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun start() {
        val store = KeyStore.getInstance("PKCS12").apply {
            HttpTest::class.java.getResourceAsStream("/localhost.p12")!!.use { load(it, PASSWORD) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        secure = MockWebServer().apply { useHttps(context.socketFactory) }
        secure.start()
        plain = MockWebServer()
        plain.start()
        http = Http.client("test").newBuilder()
            .sslSocketFactory(context.socketFactory, trust.trustManagers[0] as X509TrustManager)
            .build()
    }

    @After
    fun stop() {
        secure.close()
        plain.close()
    }

    /** The server's address by the name its certificate is for. */
    private fun MockWebServer.named(path: String): HttpUrl = url(path).newBuilder().host("localhost").build()

    @Test
    fun aRedirectFromHttpsToPlainHttpIsNotFollowed() {
        val leak = plain.named("/rest/ping?t=token&s=salt")
        secure.enqueue(MockResponse.Builder().code(302).setHeader("Location", leak.toString()).build())
        try {
            http.newCall(Request.Builder().url(secure.named("/rest/ping?t=token&s=salt")).build()).execute().close()
            fail("the redirect was followed")
        } catch (e: InsecureRedirect) {
            assertFalse(e.message!!, "localhost" in e.message!!)
        }
        // Nothing reached the plain server: the token never went unencrypted.
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun otherRedirectsAreStillFollowed() {
        // HTTPS to HTTPS.
        secure.enqueue(MockResponse.Builder().code(301).setHeader("Location", "/rest/ping2").build())
        secure.enqueue(MockResponse.Builder().body("there").build())
        http.newCall(Request.Builder().url(secure.named("/rest/ping")).build()).execute().use {
            assertEquals("there", it.body.string())
        }
        // Plain HTTP to HTTPS: an upgrade.
        plain.enqueue(MockResponse.Builder().code(302).setHeader("Location", secure.named("/up").toString()).build())
        secure.enqueue(MockResponse.Builder().body("secure").build())
        http.newCall(Request.Builder().url(plain.named("/rest/ping")).build()).execute().use {
            assertEquals("secure", it.body.string())
            assertTrue(it.request.url.isHttps)
        }
    }

    @Test
    fun aPlainRedirectToAnotherHostIsNotFollowed() {
        // Over plain HTTP anyone on the way can answer "go there instead", and the sign-in
        // token in the address would go with the request.
        val elsewhere = plain.url("/rest/ping?t=token&s=salt").newBuilder().host("127.0.0.1").build()
        plain.enqueue(MockResponse.Builder().code(302).setHeader("Location", elsewhere.toString()).build())
        try {
            http.newCall(Request.Builder().url(plain.named("/rest/ping?t=token&s=salt")).build()).execute().close()
            fail("the redirect was followed")
        } catch (e: InsecureRedirect) {
            assertFalse(e.message!!, "127.0.0.1" in e.message!! || "localhost" in e.message!!)
        }
        // Only the first request was made.
        assertEquals(1, plain.requestCount)
        // The same host, another path: followed.
        plain.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/rest/elsewhere").build())
        plain.enqueue(MockResponse.Builder().body("same host").build())
        http.newCall(Request.Builder().url(plain.named("/rest/ping")).build()).execute().use {
            assertEquals("same host", it.body.string())
        }
    }

    @Test
    fun aStalledSongIsGivenUpOnSoonerThanAStalledRequest() {
        val streams = Http.forStreams(http)
        assertEquals(30_000, http.readTimeoutMillis)
        assertEquals(10_000, streams.readTimeoutMillis)
        // The same connections, so a song starts on one that is already open.
        assertTrue(streams.connectionPool === http.connectionPool)
        assertTrue(streams.dispatcher === http.dispatcher)
        // And it is the limit that is felt: a server that sends the start of a song and then
        // nothing is given up on by the stall limit, not the app's 30 seconds.
        plain.enqueue(MockResponse.Builder().body("start of a song").bodyDelay(1, java.util.concurrent.TimeUnit.SECONDS).build())
        val quick = streams.newBuilder().readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val began = System.nanoTime()
        try {
            quick.newCall(Request.Builder().url(plain.named("/rest/stream")).build()).execute().use { it.body.string() }
            fail("the stalled read was waited out")
        } catch (e: java.io.IOException) {
            assertTrue((System.nanoTime() - began) / 1_000_000 < 900)
        }
    }

    @Test
    fun theClientSaysWhyItStopped() = runBlocking {
        val leak = plain.named("/rest/ping")
        secure.enqueue(MockResponse.Builder().code(307).setHeader("Location", leak.toString()).build())
        val api = Subsonic(secure.named("/").toString(), "ada", "sesame", http)
        try {
            api.ping()
            fail("expected an error")
        } catch (e: ApiError.Network) {
            assertEquals("Can't reach the server. The server tried to send the request on without encryption, so it wasn't followed.", e.message)
        }
        assertEquals(0, plain.requestCount)
    }

    /** Nothing private in an error's words: not the host, the token or its salt. */
    private fun assertNothingPrivate(error: Throwable, vararg also: String) {
        val text = error.toString()
        for (secret in listOf("localhost", "127.0.0.1", SALT, md5Hex("sesame$SALT"), "CN=", "sha256/") + also) {
            assertFalse("\"$secret\" in: $text", text.contains(secret, ignoreCase = true))
        }
        assertNull(error.cause)
    }

    @Test
    fun aCertificateForAnotherNameIsReportedWithoutItsDetails() = runBlocking {
        // The certificate is for "localhost"; the address is 127.0.0.1.
        val api = Subsonic(secure.url("/").newBuilder().host("127.0.0.1").build().toString(), "ada", "sesame", http, salt = SALT)
        val error = try {
            api.ping()
            null
        } catch (e: ApiError.Network) {
            e
        }!!
        assertEquals("Can't reach the server. The server's certificate is for a different address. Check the address.", error.message)
        assertNothingPrivate(error)
    }

    @Test
    fun anUntrustedCertificateIsReportedWithoutItsDetails() = runBlocking {
        // A client that doesn't trust the test certificate.
        val api = Subsonic(secure.named("/").toString(), "ada", "sesame", Http.client("test"), salt = SALT)
        val error = try {
            api.ping()
            null
        } catch (e: ApiError.Network) {
            e
        }!!
        assertEquals("Can't reach the server. The server's certificate isn't one this phone trusts.", error.message)
        assertNothingPrivate(error)
    }

    @Test
    fun aCertificateTurnedDownMeansTheServerAnswered() = runBlocking {
        val api = Subsonic(secure.named("/").toString(), "ada", "sesame", Http.client("test"), salt = SALT)
        val heard = mutableListOf<Boolean>()
        api.onReached = { heard += it }
        val error = try {
            api.ping()
            null
        } catch (e: ApiError.Network) {
            e
        }!!
        // It is there: not "out of reach", and nothing for the local-network permission to
        // be blamed for.
        assertTrue(error.answered)
        assertEquals(listOf(true), heard)
        // Nothing listening at all is out of reach.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val nobody = Subsonic("http://127.0.0.1:$port", "ada", "sesame", Http.client("test"), salt = SALT)
        nobody.onReached = { heard += it }
        val none = try {
            nobody.ping()
            null
        } catch (e: ApiError.Network) {
            e
        }!!
        assertFalse(none.answered)
        assertEquals(listOf(true, false), heard)
    }

    private companion object {
        val PASSWORD = "testing".toCharArray()
        const val SALT = "abcdefghijklmnop"
    }
}
