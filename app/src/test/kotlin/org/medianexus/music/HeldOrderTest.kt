package org.medianexus.music

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.medianexus.music.net.Found
import org.medianexus.music.net.OfflineSwitch
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.Pending
import org.medianexus.music.store.Settings

/**
 * Offline mode on, a like, offline mode off, an unlike: the server must end with the song
 * unliked, having been sent the like and then the unlike. On the account as the app builds
 * it (its likes, its sender, its client and that client's offline switch, the watch on the
 * server), against a stand-in server that remembers what is liked, as a server does, and
 * notes what it was asked to change, in order.
 *
 * As first reviewed, the like sat held while the unlike went straight to the server; the
 * like arrived after it, at the next round, and won.
 *
 * **Nothing here waits on time, and nothing looks again and again to see whether something
 * has happened yet.** Each step waits for the thing itself: the phone's copy of Liked songs
 * changing (which is the like acknowledged, as held or as taken), a request arriving at the
 * server, the queue reaching a length it then stays at. Where the order of two things
 * matters, one of them stands at a gate until the test has seen the other. (This test used
 * to take "in the queue" for "held", flip the switch at once, and look for the unlike in a
 * loop with thirty seconds to find it: it failed about one run in three, on a fault of the
 * sender's that it had never been written to look for. See HeldChangesTest,
 * `aChangeStillBeingWrittenIsNotGivenUpWhenAnotherPassEnds`.)
 */
class HeldOrderTest {
    @get:Rule val folder = TemporaryFolder()

    /** Only so that a test gone wrong at one of its gates fails, and does not sit there for
     *  ever: no test here passes or fails by how long anything took. */
    @get:Rule val notForEver: Timeout = Timeout.seconds(120)

    /**
     * The stand-in server. What it is asked to change it notes as the request arrives
     * ([asked], and [changes] for whoever is waiting to hear of it), and does as it answers.
     */
    private inner class Server : Dispatcher() {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val changes = Channel<String>(Channel.UNLIMITED)
        private val liked: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

        /** A like that arrives while this is set is not answered until it is opened. */
        @Volatile var gate: Gate? = null

        /** How many of the next changes are answered by something in the server's place (a
         *  proxy's error page), and not done. */
        val pagesInItsPlace = java.util.concurrent.atomic.AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            val id = request.url.queryParameter("id").orEmpty()
            var body = ""
            when (endpoint) {
                "star", "unstar" -> {
                    asked += "$endpoint $id"
                    changes.trySend("$endpoint $id")
                    if (endpoint == "star") gate?.let {
                        it.reached.complete(Unit)
                        it.open.await()
                    }
                    if (pagesInItsPlace.getAndUpdate { maxOf(it - 1, 0) } > 0) {
                        return MockResponse.Builder().code(502).setHeader("Content-Type", "text/html").body("<html><body>502 Bad Gateway</body></html>").build()
                    }
                    if (endpoint == "star") liked += id else liked -= id
                }
                "getStarred2" -> {
                    val songs = synchronized(liked) { liked.joinToString(",") { """{"id":"$it","title":"A song","starred":"2026-01-01T00:00:00Z"}""" } }
                    body = ""","starred2":{"song":[$songs]}"""
                }
            }
            return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0"$body}}""").build()
        }
    }

    private class Gate {
        val reached = CompletableDeferred<Unit>()
        val open = CountDownLatch(1)
    }

    private val web = MockWebServer()
    private val server = Server()
    private val song = Song(id = "s1", title = "One")
    private val another = Song(id = "s2", title = "Two")

    /** Offline mode, as the app's client has it: while on, nothing is sent at all. */
    private val switch = OfflineSwitch(initially = true)

    /** The app's watch on the server, with a check of the server that the test answers. */
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checks = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val watch = ServerWatch(
        watching,
        ping = {
            val answer = CompletableDeferred<Unit>()
            checks.send(answer)
            answer.await()
        },
        cameOnline = {},
        signInGone = {},
    )

    /** The account's clock for trying again: each wait asked for, for the test to end. */
    private val pauses = Channel<Pair<Long, CompletableDeferred<Unit>>>(Channel.UNLIMITED)

    private lateinit var account: AccountGraph

    @Before
    fun start() {
        web.dispatcher = server
        web.start()
        // Offline mode is on before the account is taken on, as when the app is started with
        // it on: nothing of the account's start-up goes to the server.
        watch.manual(true)
        val api = Subsonic(web.url("/").toString(), "tester", "pass", OkHttpClient(), switch)
        account = AccountGraph(
            Account(api.server, api.username, "key", AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))), api, Dispatchers.IO,
            AccountWiring(
                kotlinx.coroutines.flow.MutableStateFlow(Settings()), watch.state, suspect = { watch.check() },
                pause = { millis ->
                    val over = CompletableDeferred<Unit>()
                    pauses.send(millis to over)
                    over.await()
                },
            ),
        )
    }

    @After
    fun stop() {
        server.gate?.open?.countDown()
        account.close()
        watching.cancel()
        web.close()
    }

    private val heldFile get() = File(account.account.dirs.data, "held.json")

    /**
     * Likes or unlikes the song, and returns once that has been acknowledged to whoever made
     * it (the server has it, or it is held on the phone): which is when the phone's own copy
     * of Liked songs gains or loses the song. Listening for that begins before the change is
     * made.
     */
    private suspend fun setAndBeAcknowledged(liked: Boolean, song: Song = this.song) = coroutineScope {
        val acknowledged = async(start = CoroutineStart.UNDISPATCHED) {
            account.pages.changes.first { (key, value) -> key == "liked" && (value as Found).songs.any { it.id == song.id } == liked }
        }
        account.likes.set(song, liked)
        acknowledged.await()
    }

    /** Offline mode on, and a like: held on the phone, and the server asked nothing. */
    private suspend fun likedInOfflineMode() {
        // The phone has a copy of Liked songs from when it was last online, with nothing in it.
        account.pages.put(account.library.liked(), Found())
        setAndBeAcknowledged(true)
        assertTrue(account.likes.isLiked(song.id))
        assertEquals(listOf<Pending>(Pending.Star("song", song.id, true)), account.held.all())
        assertTrue("held, it is on the disk", "\"starred\":true" in heldFile.readText())
        assertEquals("nothing is asked of the server in offline mode", 0, web.requestCount)
    }

    /** Offline mode switched off in Settings: the client may send again, and the watch asks
     *  the server whether it is there. Returns that question, for the test to answer. */
    private suspend fun offlineModeOff(): CompletableDeferred<Unit> {
        switch.on = false
        watch.manual(false)
        return checks.receive()
    }

    /** The sender has nothing left, on the phone or in hand, and the server ended with the
     *  like and then the unlike, each once. */
    private suspend fun assertSentInOrderAndNothingLeft() {
        assertEquals("star s1", server.changes.receive())
        assertEquals("unstar s1", server.changes.receive())
        account.held.waiting.first { it == 0 }
        // (A pass of its own comes after whichever pass sent them, and so after that pass
        // has taken them off the disk.)
        account.flush()
        assertEquals("[]", heldFile.readText())
        assertEquals(listOf("star s1", "unstar s1"), server.asked.toList())
        assertFalse(account.likes.isLiked(song.id))
    }

    @Test
    fun likedOfflineAndUnlikedOnceBackTheServerEndsUnliked() = runBlocking {
        likedInOfflineMode()
        // Offline mode off, the server found there, and at once an unlike.
        offlineModeOff().complete(Unit)
        account.likes.set(song, false)
        assertSentInOrderAndNothingLeft()
    }

    @Test
    fun unlikedWhileTheLikeIsWithTheServerTheUnlikeWaitsItsTurn() = runBlocking {
        likedInOfflineMode()
        // The server will be slow to answer the like.
        val gate = Gate().also { server.gate = it }
        offlineModeOff().complete(Unit)
        // The like has reached the server and not been answered; now the unlike is made. It
        // joins the queue behind the like (two waiting, and so it stays until the gate opens).
        gate.reached.await()
        account.likes.set(song, false)
        account.held.waiting.first { it == 2 }
        assertEquals("the unlike is not sent past a like still on its way", listOf("star s1"), server.asked.toList())
        gate.open.countDown()
        assertSentInOrderAndNothingLeft()
    }

    @Test
    fun unlikedBetweenTheSwitchAndTheServerAnsweringBothGoOnceItHasAnswered() = runBlocking {
        likedInOfflineMode()
        // Offline mode off: the server has been asked whether it is there, and not answered.
        val check = offlineModeOff()
        assertTrue("switched off is not online yet", watch.state.value.offline)
        // An unlike made now is held behind the like: nothing is sent on the strength of a
        // switch.
        setAndBeAcknowledged(false)
        assertEquals(2, account.held.waiting.value)
        assertEquals(0, web.requestCount)
        // The server answers. Both go, in order, for that reason and no other: nothing here
        // makes another change, asks for a round, or waits for a timer.
        check.complete(Unit)
        assertSentInOrderAndNothingLeft()
    }

    @Test
    fun aChangeTheServerCouldNotBeAskedForIsTriedAgainWithNothingElseHappening() = runBlocking {
        // Online, and nothing waiting: a like held in offline mode has gone, and the pass
        // that the app coming back online starts is over.
        likedInOfflineMode()
        offlineModeOff().complete(Unit)
        assertEquals("star s1", server.changes.receive())
        account.held.waiting.first { it == 0 }
        account.flush()
        // Another song is liked, and something answers that one request in the server's
        // place (as a phone moving from Wi-Fi to mobile data loses the request it was in the
        // middle of): the like is held.
        server.pagesInItsPlace.set(1)
        setAndBeAcknowledged(true, another)
        assertEquals("star s2", server.changes.receive())
        assertEquals(listOf<Pending>(Pending.Star("song", "s2", true)), account.held.all())
        // Having lost touch, the app asks the server whether it is there. It is: the app was
        // never offline, and so there is no coming back online to send what is held.
        checks.receive().complete(Unit)
        assertFalse(watch.state.value.offline)
        // Nothing else happens: no other change is made, no network comes or goes. The
        // sender waits half a minute, by the account's clock, and sends it again.
        val (millis, over) = pauses.receive()
        assertEquals(30_000L, millis)
        assertEquals(listOf("star s1", "star s2"), server.asked.toList())
        over.complete(Unit)
        assertEquals("star s2", server.changes.receive())
        account.held.waiting.first { it == 0 }
        account.flush()
        assertEquals("[]", heldFile.readText())
        assertEquals(listOf("star s1", "star s2", "star s2"), server.asked.toList())
        assertTrue(account.likes.isLiked("s2"))
    }
}
