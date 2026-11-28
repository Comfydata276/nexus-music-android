package org.medianexus.music.nexus

import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.concurrent.TaskRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **The backend's line** (`nexus/Line.kt`; the review of P7, MUST 3 and 8): the machine is asked at
 * the send itself and an ask it no longer waits for is dropped with nothing written; no request is
 * ever sent on a connection left idle in a pool (a proxy closes idle ones); a follow-up OkHttp
 * would send by itself (a 503 with "Retry-After: 0") is never written, and the ask is given the
 * first answer; a request the server closed on after receiving it is never sent again.
 */
@OptIn(okhttp3.internal.OkHttpInternalApi::class)
class LineTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val calls = Executors.newFixedThreadPool(2)
    private val backend = TaskRunner.RealBackend { Thread(it).apply { isDaemon = true } }
    private val sent = AtomicInteger()

    @Before
    fun start() {
        val password = "testing".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { LineTest::class.java.getResourceAsStream("/localhost.p12")!!.use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        server = MockWebServer().apply { useHttps(context.socketFactory) }
        server.start()
        http = nexusHttp(calls, TaskRunner(backend), sent, null).newBuilder().sslSocketFactory(context.socketFactory, trust.trustManagers[0] as X509TrustManager).build()
    }

    @After
    fun stop() {
        server.close()
        calls.shutdownNow()
        backend.shutdown()
    }

    private val url: HttpUrl get() = server.url("/nexus/api/signin").newBuilder().host("localhost").build()

    private fun ask(stillWanted: () -> Boolean = { true }): Sent =
        exchange(http, url, post = true, authorization = "", body = "{}".toByteArray(), userAgent = "test", withinMs = 10_000, most = 4096, stillWanted = stillWanted)

    @Test
    fun anAskNoLongerWaitedForAtItsSendIsDroppedWithNothingWritten() {
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertTrue(ask(stillWanted = { false }) is Sent.Dropped)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFollowUpOkHttpWouldSendIsNeverWrittenAndTheFirstAnswerIsGiven() {
        server.enqueue(MockResponse.Builder().code(503).setHeader("Retry-After", "0").body("""{"busy":true}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val got = ask()
        assertTrue("$got", got is Sent.Answer)
        assertEquals(503, (got as Sent.Answer).status)
        assertEquals("""{"busy":true}""", String(got.body))
        assertEquals("a second request was written", 1, server.requestCount)
    }

    @Test
    fun noRequestGoesOnAConnectionLeftIdle() {
        server.enqueue(MockResponse.Builder().body("{}").build())
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertTrue(ask() is Sent.Answer)
        assertTrue(ask() is Sent.Answer)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNotEquals("the second ask was sent on the first one's connection", first.connectionIndex, second.connectionIndex)
    }

    @Test
    fun aServerThatClosesAfterEachAnswerIsAnsweredEveryTime() {
        repeat(3) { server.enqueue(MockResponse.Builder().setHeader("Connection", "close").body("{}").onResponseEnd(SocketEffect.CloseSocket()).build()) }
        repeat(3) { assertTrue(ask() is Sent.Answer) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aRequestTheServerClosedOnAfterReceivingItIsNeverSentAgain() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        server.enqueue(MockResponse.Builder().body("{}").build())
        val got = ask()
        assertTrue("$got", got is Sent.Failed)
        assertEquals("sent again", 1, server.requestCount)
    }
}
