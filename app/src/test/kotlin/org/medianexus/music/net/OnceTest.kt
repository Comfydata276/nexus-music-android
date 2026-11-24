package org.medianexus.music.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A change to a playlist is sent once (M3b fixes, A2): a request the server has received and
 * then left unanswered (the connection dropped as the answer was due) is never sent again by
 * the client, and is told apart from one that never left the phone.
 */
class OnceTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun api() = Subsonic(server.url("/").toString(), "tester", "pass", Http.client("test"))

    private val ok = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

    /** One answer that leaves a connection open to be used again (as the app's earlier
     *  requests do), then every request's answer cut off once the server has it. */
    private fun dropEveryAnswerOnAWarmConnection() {
        server.enqueue(MockResponse.Builder().body(ok).build())
        repeat(4) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }
    }

    @Test
    fun aChangeWhoseAnswerIsLostReachesTheServerOnceAndNobodyKnowsWhetherItWasDone() = runBlocking {
        dropEveryAnswerOnAWarmConnection()
        val api = api()
        api.ping()
        try {
            api.once().removeManyFromPlaylist("p1", listOf(1))
            fail("no answer came")
        } catch (e: ApiError.Network) {
            assertFalse("it may have been done", e.unsent)
        }
        assertEquals("the change sent once", 2, server.requestCount)
    }

    @Test
    fun theAppsOrdinaryClientSendsSuchARequestAgainByItself() = runBlocking {
        // Why the playlist changes need their own way out: the ordinary client, which reads
        // are sent by, tries a dropped request again on its own.
        dropEveryAnswerOnAWarmConnection()
        val api = api()
        api.ping()
        try {
            api.removeManyFromPlaylist("p1", listOf(1))
        } catch (_: ApiError.Network) {
        }
        assertTrue("the change sent ${server.requestCount - 1} times", server.requestCount - 1 > 1)
    }

    @Test
    fun aRequestThatCouldNotConnectNeverLeft() = runBlocking {
        val address = server.url("/").toString()
        server.close()
        try {
            Subsonic(address, "tester", "pass", Http.client("test")).once().removeManyFromPlaylist("p1", listOf(1))
            fail("nobody there")
        } catch (e: ApiError.Network) {
            assertTrue(e.unsent)
        }
    }

    /**
     * A stand-in that carries out a removal from a playlist holding two copies of a song each
     * time the request reaches it, then answers with `answer` (M3b fixes 2, A5): the first
     * time only; after that, "ok".
     */
    private fun removalAnsweredWith(answer: MockResponse): MutableList<String> {
        val playlist = java.util.Collections.synchronizedList(mutableListOf("a", "a"))
        var first = true
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                synchronized(playlist) { if (playlist.isNotEmpty()) playlist.removeAt(0) }
                if (first) {
                    first = false
                    return answer
                }
                return MockResponse.Builder().body(ok).build()
            }
        }
        return playlist
    }

    @Test
    fun aRemovalAnswered503WithRetryAfterZeroIsSentOnce() = runBlocking {
        val playlist = removalAnsweredWith(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        try {
            api().once().removeManyFromPlaylist("p1", listOf(0))
            fail("not the server's ok")
        } catch (_: ApiError) {
        }
        assertEquals("one request", 1, server.requestCount)
        assertEquals("the other copy of the song still there", listOf("a"), playlist.toList())
    }

    @Test
    fun aRemovalAnsweredWithARedirectIsSentOnce() = runBlocking {
        val playlist = removalAnsweredWith(MockResponse.Builder().code(307).addHeader("Location", server.url("/rest/updatePlaylist.view").toString()).build())
        try {
            api().once().removeManyFromPlaylist("p1", listOf(0))
            fail("not the server's ok")
        } catch (_: ApiError) {
        }
        assertEquals("one request", 1, server.requestCount)
        assertEquals("the other copy of the song still there", listOf("a"), playlist.toList())
    }
}
