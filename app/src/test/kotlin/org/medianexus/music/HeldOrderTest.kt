package org.medianexus.music

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.Account
import org.medianexus.music.store.AccountDirs
import org.medianexus.music.store.Settings

/**
 * Offline mode on, a like, offline mode off, an unlike: the server must end with the song
 * unliked. On the account as the app builds it (its likes, its sender, its client), against
 * a stand-in server that notes what it was sent, in order.
 *
 * As reviewed, the like sat held while the unlike went straight to the server; the like
 * arrived after it, at the next round, and won.
 */
class HeldOrderTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val reach = MutableStateFlow(Reach())
    private lateinit var account: AccountGraph

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
                if (endpoint == "star" || endpoint == "unstar") asked += "$endpoint ${request.url.queryParameter("id")}"
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0"}}""").build()
            }
        }
        server.start()
        val api = Subsonic(server.url("/").toString(), "tester", "pass", OkHttpClient())
        account = AccountGraph(
            Account(api.server, api.username, "key", AccountDirs(folder.newFolder("data"), folder.newFolder("cache"))), api, Dispatchers.IO,
            AccountWiring(MutableStateFlow(Settings()), reach, suspect = {}),
        )
    }

    @After
    fun stop() {
        account.close()
        server.close()
    }

    @Test
    fun likedOfflineAndUnlikedOnceBackTheServerEndsUnliked() = runBlocking {
        val song = Song(id = "s1", title = "One")
        // Offline mode on, by hand: a like is held.
        reach.value = Reach(manual = true)
        account.likes.set(song, true)
        withTimeout(30_000) { account.held.waiting.first { it == 1 } }
        assertEquals(emptyList<String>(), asked.toList())
        // Offline mode off, and at once an unlike.
        reach.value = Reach()
        account.likes.set(song, false)
        // The unlike reaches the server...
        withTimeout(30_000) { while (asked.none { it.startsWith("unstar") }) kotlinx.coroutines.yield() }
        // ...and whatever is still held has its round, as the server coming back gives it.
        account.flush()
        withTimeout(30_000) { account.held.waiting.first { it == 0 } }
        assertEquals(listOf("star s1", "unstar s1"), asked.toList())
    }
}
