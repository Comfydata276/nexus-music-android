package org.medianexus.music.net

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.FormBody
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **The music login's one check at the send** (`MusicLogin.Gate`; the backend design's 6.3 and
 * the review of P7, MUST 4 and 5): an unproven login has exactly one request on the wire however
 * slow its answer; a refusal inside an answer of any status, a stream's included, sets that pass
 * aside before the caller has it; a refusal is told with the pass it was asked with; the token is
 * found in a form's body too; and an account let go of sends nothing more.
 */
class MusicGateTest {
    private lateinit var server: MockWebServer
    private val onWire = AtomicInteger()
    private val mostAtOnce = AtomicInteger()
    private val arrived = AtomicInteger()
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var answer: () -> MockResponse = { ok() }
    private val http = Http.client("test")

    private fun ok() = MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()

    private fun refused() = MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""").build()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.incrementAndGet()
                val now = onWire.incrementAndGet()
                mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    hold?.await(20, TimeUnit.SECONDS)
                    return answer()
                } finally {
                    onWire.decrementAndGet()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        hold?.countDown()
        server.close()
    }

    private fun login(count: String = "1", salt: String = "s".repeat(64), proven: Boolean = false) =
        MusicLogin(Handed(salt, md5Hex("sesame$salt"), count), generation = "5", proven = proven)

    private fun url(login: MusicLogin, endpoint: String = "ping") =
        server.url("/rest/$endpoint").newBuilder().addQueryParameter("u", "ada").addQueryParameter("t", login.now.token).addQueryParameter("s", login.now.salt).build()

    private fun send(request: Request): Result<Int> = runCatching { http.newCall(request).execute().use { it.code } }

    @Test
    fun anUnprovenLoginHasOneRequestOnTheWireHoweverSlowItsAnswer() {
        val login = login()
        hold = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Result<Int>>()
        val threads = (1..6).map { n -> Thread { results += send(Request.Builder().url(url(login, if (n == 1) "ping" else "getAlbumList2")).build()) }.apply { start() } }
        // Every one of them asked; one on the wire, held for as long as it takes.
        Thread.sleep(1_500)
        assertEquals("more than one unproven request on the wire", 1, arrived.get())
        hold?.countDown()
        threads.forEach { it.join(20_000) }
        assertEquals(1, mostAtOnce.get().coerceAtMost(1))
        assertEquals("the healthy ones were let go once it was proven", 6, results.count { it.getOrNull() == 200 })
        assertTrue(login.isProven)
    }

    @Test
    fun aProvenLoginWaitsForNothing() {
        val login = login(proven = true)
        hold = CountDownLatch(1)
        val threads = (1..3).map { Thread { send(Request.Builder().url(url(login)).build()) }.apply { start() } }
        val until = System.currentTimeMillis() + 5_000
        while (arrived.get() < 3 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(3, arrived.get())
        hold?.countDown()
        threads.forEach { it.join(20_000) }
    }

    @Test
    fun aRefusalInsideAStreamsAnswerSetsThePassAsideBeforeTheCallerHasIt() {
        val login = login()
        val told = CopyOnWriteArrayList<Pair<String, String>>()
        login.onRefused = { told += it.handed.count to it.generation }
        // Navidrome says it as a success, where audio was asked for.
        answer = { refused() }
        val first = send(Request.Builder().url(url(login, "stream")).build())
        assertTrue(first.exceptionOrNull() is RefusedLogin)
        val sentBefore = arrived.get()
        val again = send(Request.Builder().url(url(login, "stream")).header("Range", "bytes=1000-").build())
        assertTrue(again.exceptionOrNull() is RefusedLogin)
        assertEquals("a refused pass was sent again", sentBefore, arrived.get())
        assertEquals(listOf("1" to "5"), told.toList())
    }

    @Test
    fun aRefusalIsToldWithThePassItWasAskedWithWhateverCameSince() {
        val login = login(count = "1")
        val told = CopyOnWriteArrayList<Pair<String, String>>()
        login.onRefused = { told += it.handed.count to it.generation }
        hold = CountDownLatch(1)
        answer = { refused() }
        val asked = url(login)
        val out = Thread { send(Request.Builder().url(asked).build()) }.apply { start() }
        while (arrived.get() < 1) Thread.sleep(5)
        // A new login is handed while the first is on its way.
        login.renew(Handed("t".repeat(64), md5Hex("sesame" + "t".repeat(64)), "2"), generation = "6")
        hold?.countDown()
        out.join(20_000)
        assertEquals("told under a later login's number", listOf("1" to "5"), told.toList())
        assertTrue("the new login was set aside by the old one's refusal", !login.isRefused)
        assertEquals("not a renewal after a refusal", 0L, login.renewals.value)
    }

    @Test
    fun aRenewalAfterARefusalIsWhatTheHeldWorkListensFor() {
        val login = login()
        answer = { refused() }
        send(Request.Builder().url(url(login)).build())
        assertTrue(login.isRefused)
        login.renew(Handed("u".repeat(64), md5Hex("sesame" + "u".repeat(64)), "2"), generation = "6")
        assertEquals(1L, login.renewals.value)
        assertTrue(!login.isRefused)
    }

    @Test
    fun theTokenInALongFormsBodyIsCheckedToo() {
        val login = login()
        login.refuseCount("1")
        val body = FormBody.Builder().add("u", "ada").add("t", login.now.token).add("s", login.now.salt).add("id", "1").build()
        val result = send(Request.Builder().url(server.url("/rest/createPlaylist")).post(body).build())
        assertTrue(result.exceptionOrNull() is RefusedLogin)
        assertEquals(0, arrived.get())
    }

    @Test
    fun anAccountLetGoOfSendsNothingMore() {
        val login = login(proven = true)
        login.retire()
        val result = send(Request.Builder().url(url(login)).build())
        assertTrue(result.exceptionOrNull() is RefusedLogin)
        assertEquals(0, arrived.get())
    }

    @Test
    fun requestsWithNoMusicLoginPassUntouched() {
        hold = null
        assertEquals(200, send(Request.Builder().url(server.url("/elsewhere")).build()).getOrThrow())
    }
}
