package org.medianexus.music.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Answers kept as files (test resources, `subsonic/`) that pin how this app reads what the
 * server sends where it must match Windows: an absent section is empty, but a null where a
 * section, list or name should be makes the answer malformed, as serde makes it there.
 */
class DecodingTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun fixture(name: String): String =
        DecodingTest::class.java.getResourceAsStream("/subsonic/$name")!!.use { it.readBytes().decodeToString() }

    private fun answer(body: String) =
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build())

    private fun client() = Subsonic(server.url("/").toString(), "ada", "sesame", http)

    private suspend fun malformed(block: suspend () -> Unit) {
        try {
            block()
            fail("read as if it were fine")
        } catch (e: ApiError.NotSubsonic) {
            assertTrue(e.message, e.message!!.startsWith("That address didn't answer like a Navidrome server. Unexpected response from"))
        }
    }

    @Test
    fun anAbsentSectionIsEmptyButANullOneIsMalformed() = runBlocking {
        answer(fixture("section-absent.json"))
        assertTrue(client().albumList(AlbumList.Newest, 10, 0).isEmpty())
        answer(fixture("album-list-empty.json"))
        assertTrue(client().albumList(AlbumList.Newest, 10, 0).isEmpty())
        answer(fixture("album-list-null.json"))
        malformed { client().albumList(AlbumList.Newest, 10, 0) }
    }

    @Test
    fun aNullListOrNameInsideIsMalformed() = runBlocking {
        answer(fixture("album-songs-null.json"))
        malformed { client().album("al-1") }
        answer(fixture("album-name-null.json"))
        malformed { client().album("al-1") }
    }

    @Test
    fun fieldsThatMayBeAbsentTakeNull() = runBlocking {
        answer(fixture("album-nullable-fields-null.json"))
        val album = client().album("al-1")
        assertNull(album.starred)
        assertNull(album.songs[0].starred)
        assertNull(album.songs[0].replayGain)
    }

    @Test
    fun theSavedQueuesCurrentSongIsReadAsWindowsReadsIt() = runBlocking {
        val cases = Subsonic.json.parseToJsonElement(fixture("play-queue-current.json")).jsonObject["cases"]!!.jsonArray
        for (case in cases) {
            val current = case.jsonObject["current"]!!
            val reads = case.jsonObject["reads"]!!.jsonPrimitive.content
            answer("""{"subsonic-response":{"status":"ok","playQueue":${JsonObject(mapOf("current" to current))}}}""")
            assertEquals("for $current", reads, client().playQueue().current)
        }
    }
}
