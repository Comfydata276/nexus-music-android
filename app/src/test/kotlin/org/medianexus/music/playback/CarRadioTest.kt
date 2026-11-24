package org.medianexus.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.library.Library
import org.medianexus.music.net.Subsonic
import org.medianexus.music.store.PageCache

/** A station chosen in the car is the station with that id, and nothing else (M3b fixes, C3). */
class CarRadioTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val web = MockWebServer()
    private val asked = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun start() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
                asked += endpoint
                val body = when (endpoint) {
                    "getInternetRadioStations" -> ""","internetRadioStations":{"internetRadioStation":[{"id":"st1","name":"Test FM","streamUrl":"http://127.0.0.1:1/a"},{"id":"st2","name":"Other FM","streamUrl":"http://127.0.0.1:1/b"}]}"""
                    else -> ""
                }
                return MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$body}}""").build()
            }
        }
        web.start()
    }

    @After
    fun stop() {
        scope.cancel()
        web.close()
    }

    private fun tree() = CarTree(Library(Subsonic(web.url("/").toString(), "ada", "sesame", OkHttpClient()), PageCache(folder.newFolder("pages"), scope, Dispatchers.IO)))

    @Test
    fun aStationIsChosenByItsOwnIdAndOneNoLongerThereIsNotFound() = runBlocking<Unit> {
        val tree = tree()
        // Chosen by id, whatever place the car had it at.
        val chosen = tree.play(CarNode.Track("radio:st2", ListRef.Radio, 0))!!
        assertEquals("radio:st2", chosen.songs[chosen.start].id)
        // Gone from the server: not found, and never asked for as a song.
        assertNull(tree.play(CarNode.Track("radio:st9", ListRef.Radio, 1)))
        assertEquals(0, asked.count { it == "getSong" })
    }

    @Test
    fun theRadioFolderIsTheStationsWithNothingToShuffle() = runBlocking<Unit> {
        // Found in the car: "Shuffle", "1 song" headed the folder of one station.
        assertEquals(listOf("Test FM", "Other FM"), tree().children(CarNode.Radio)!!.map { it.title })
    }
}
