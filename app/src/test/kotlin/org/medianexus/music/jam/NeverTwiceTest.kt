package org.medianexus.music.jam

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.medianexus.music.net.Http

/**
 * Rule 6: anything asked of the relay that changes a jam is asked once. A request the relay
 * has received and then left unanswered, or answered in a way the HTTP client would follow up
 * by itself, is never sent again; it comes back as "no answer", and the jam is read again.
 */
class NeverTwiceTest {
    private lateinit var relay: MockWebServer

    @Before
    fun start() {
        relay = MockWebServer()
        relay.start()
    }

    @After
    fun stop() = relay.close()

    private fun line() = RelayLine(relay.url("/").toString(), Http.client("test"))

    /** Counts the asks that reach the stand-in, answering the first with `first`. */
    private fun asks(first: MockResponse): () -> Int {
        var count = 0
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/ask")) {
                    count++
                    if (count == 1) return first
                }
                return MockResponse.Builder().body("""{"ok": true, "data": {}}""").build()
            }
        }
        return { count }
    }

    private fun ask(line: RelayLine) = runBlocking { withTimeout(30_000) { GuestSide.ask(line, "jam1", "token", "skip", JsonObject(emptyMap()), owner = null) } }

    @Test
    fun anAskWhoseAnswerIsLostIsSentOnce() {
        val count = asks(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        val line = line()
        // A warm connection first, as the phone's waits for news leave one.
        runBlocking { line.get("api/jam/jam1/state?v=0", "token", 5) }
        assertEquals(Asked.NoAnswer, ask(line))
        assertEquals(1, count())
    }

    @Test
    fun anAskAnswered503WithRetryAfterZeroIsSentOnce() {
        val count = asks(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("""{"error": "busy"}""").build())
        assertEquals(Asked.Refused("busy"), ask(line()))
        assertEquals(1, count())
    }

    @Test
    fun anAskAnswered408IsSentOnceAndNobodyKnows() {
        val count = asks(MockResponse.Builder().code(408).build())
        assertEquals(Asked.NoAnswer, ask(line()))
        assertEquals(1, count())
    }

    @Test
    fun anAskAnsweredWithARedirectIsSentOnce() {
        val count = asks(MockResponse.Builder().code(307).addHeader("Location", relay.url("/api/jam/jam1/ask").toString()).build())
        ask(line())
        assertEquals(1, count())
    }

    @Test
    fun theRelaysOwnTimeOutIsNotKnowingEither() {
        // The relay's 504: the host's app didn't answer in time; the question may have been
        // carried out already, if the host had collected it.
        val count = asks(MockResponse.Builder().code(504).body("""{"error": "The host's app didn't answer. Try again."}""").build())
        assertEquals(Asked.NoAnswer, ask(line()))
        assertEquals(1, count())
    }

    @Test
    fun anAnswerTooLargeIsNoAnswerAtAll() {
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().body("[" + "1,".repeat(RelayLine.JSON_LIMIT) + "1]").build()
        }
        val answer = runBlocking { withTimeout(30_000) { line().get("api/jam/jam1/state?v=0", "token", 5) } }
        answer as RelayLine.Answer.Got
        assertEquals(kotlinx.serialization.json.JsonNull, answer.body)
    }

    @Test
    fun theKeyGoesOnlyInTheAuthorizationHeader() {
        relay.enqueue(MockResponse.Builder().body("{}").build())
        runBlocking { withTimeout(30_000) { line().post("api/room/r/enter", "the-key-itself-1234", JsonObject(emptyMap())) } }
        val request = relay.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer the-key-itself-1234", request.headers["Authorization"])
        assertEquals(false, "the-key-itself-1234" in request.url.toString())
    }
}
