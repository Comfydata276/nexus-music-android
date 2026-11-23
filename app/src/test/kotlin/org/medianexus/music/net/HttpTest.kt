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

    private companion object {
        val PASSWORD = "testing".toCharArray()
    }
}
