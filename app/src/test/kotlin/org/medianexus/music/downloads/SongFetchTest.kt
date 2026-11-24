package org.medianexus.music.downloads

import java.io.File
import java.util.Collections
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song

/**
 * A download is a whole file or nothing: fetched to a clean end, checked, put in place, and
 * only then entered in the catalogue (the mark). The server here is a stand-in shaped like
 * Navidrome: the file as it is comes with its length and takes byte ranges (unless told not
 * to); a conversion comes with no length and ignores ranges.
 */
class SongFetchTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var catalogue: Catalogue
    private val http = OkHttpClient()
    private val asked: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** What the server has, and how it answers. */
    private var file = ByteArray(300_000) { (it % 251).toByte() }
    private var ranges = true
    private var converted = false
    private var type = "audio/flac"
    private var status = 200
    private var cutAfterHead = false

    private val song = Song(id = "tr-1", title = "One", suffix = "flac", size = 300_000, duration = 10, bitRate = 900)

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request
                if (status != 200) return MockResponse.Builder().code(status).setHeader("Content-Type", "application/json").body("{}").build()
                val range = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
                val builder = MockResponse.Builder().setHeader("Content-Type", type)
                // What says which copy of the file this is: without it nothing is carried on.
                if (!converted) builder.setHeader("Last-Modified", "Mon, 23 Nov 2026 07:57:08 GMT")
                if (cutAfterHead) builder.onResponseBody(SocketEffect.ShutdownConnection)
                return if (!converted && ranges && range != null && range > 0) {
                    if (range >= file.size) return MockResponse.Builder().code(416).build()
                    builder.code(206).setHeader("Accept-Ranges", "bytes").setHeader("Content-Range", "bytes $range-${file.size - 1}/${file.size}")
                        .body(Buffer().write(file, range.toInt(), file.size - range.toInt())).build()
                } else {
                    if (!converted) builder.setHeader("Accept-Ranges", "bytes")
                    // A conversion is sent with no length (chunked), as Navidrome streams one.
                    if (converted) builder.chunkedBody(Buffer().write(file), 8192).build() else builder.body(Buffer().write(file)).build()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun url() = server.url("/rest/stream?id=${song.id}").toString()

    /** The bytes here are not audio: what is and isn't a song is WholeDownloadTest's. Here a
     *  stand-in says each file is the kind it was asked for as, an MP3 running as long as its
     *  bytes come to at 128 kbps. */
    private fun fetcher() = SongFetch(http, catalogue, check = { file, suffix ->
        if (suffix == "mp3") Inspected("mp3", true, file.length() * 1_000_000 / 16_000) else Inspected("flac", true, -1)
    })

    private fun files() = catalogue.dir.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()

    @Test
    fun theFileAsItIsArrivesWholeAndOnlyThenIsADownload() {
        var told = 0L
        val entry = fetcher().fetch(song, SavedForm(0, "flac"), url()) { got, total ->
            // While it arrives, it isn't one.
            assertFalse(catalogue.has(song.id))
            assertEquals(file.size.toLong(), total)
            told = got
        }
        assertEquals(file.size.toLong(), told)
        assertTrue(catalogue.has(song.id))
        assertEquals("tr-1.flac", entry.file)
        assertArrayEquals(file, File(catalogue.dir, entry.file).readBytes())
        // No part left lying.
        assertTrue(files().none { it.endsWith(".part") })
        // And it is still one after the app is started again.
        assertTrue(Catalogue(catalogue.dir).has(song.id))
    }

    @Test
    fun stoppedPartWayItIsPickedUpWhereItStoppedByARange() {
        val first = fetcher()
        try {
            first.fetch(song, SavedForm(0, "flac"), url()) { got, _ -> if (got >= 128 * 1024) first.cancel() }
            fail("cancelled")
        } catch (_: Cancelled) {
        }
        assertFalse(catalogue.has(song.id))
        val part = File(catalogue.dir, SongFetch.partName("tr-1.flac"))
        val had = part.length()
        assertTrue(had in 1 until file.size)
        // Again: only the rest is asked for, and the two make the file.
        fetcher().fetch(song, SavedForm(0, "flac"), url())
        assertEquals("bytes=$had-", asked.last().headers["Range"])
        assertArrayEquals(file, catalogue.fileOf(song.id)!!.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun aServerThatTakesNoRangeSendsItAllAgainAndNothingIsPiecedTogether() {
        ranges = false
        val first = fetcher()
        try {
            first.fetch(song, SavedForm(0, "flac"), url()) { got, _ -> if (got >= 128 * 1024) first.cancel() }
            fail("cancelled")
        } catch (_: Cancelled) {
        }
        fetcher().fetch(song, SavedForm(0, "flac"), url())
        assertArrayEquals(file, catalogue.fileOf(song.id)!!.readBytes())
    }

    @Test
    fun aConversionStartsAgainAndMustComeToAWholeOne() {
        converted = true
        val mp3 = song.copy(id = "tr-2", duration = 10)
        // 128 kbps for ten seconds: a whole one is at least four fifths of 160,000 bytes.
        file = ByteArray(120_000) { 1 }
        try {
            fetcher().fetch(mp3, SavedForm(128, "mp3"), server.url("/rest/stream?id=tr-2").toString())
            fail("too short to be whole")
        } catch (e: DownloadFailure) {
            assertEquals("The download was cut short.", e.reason)
        }
        assertFalse(catalogue.has("tr-2"))
        assertTrue(files().none { it.contains("tr-2") })
        file = ByteArray(160_000) { 2 }
        // A part from an earlier go is never joined onto: no range is asked for.
        File(catalogue.dir, SongFetch.partName("tr-2.mp3")).writeBytes(ByteArray(5_000))
        fetcher().fetch(mp3, SavedForm(128, "mp3"), server.url("/rest/stream?id=tr-2").toString())
        assertNull(asked.last().headers["Range"])
        assertArrayEquals(file, catalogue.fileOf("tr-2")!!.readBytes())
    }

    @Test
    fun aConnectionLostPartWayIsAFailureOfTheNetworkAndNotADownload() {
        cutAfterHead = true
        try {
            fetcher().fetch(song, SavedForm(0, "flac"), url())
            fail("the connection went")
        } catch (e: DownloadFailure) {
            assertTrue(e.network)
        }
        assertFalse(catalogue.has(song.id))
        assertFalse(File(catalogue.dir, "tr-1.flac").exists())
    }

    @Test
    fun whatTheServerSaysInsteadOfASongIsNeverSaved() {
        type = "text/html"
        try {
            fetcher().fetch(song, SavedForm(0, "flac"), url())
            fail("an error page")
        } catch (e: DownloadFailure) {
            assertEquals("The server didn't send the song.", e.reason)
        }
        type = "audio/flac"
        status = 404
        try {
            fetcher().fetch(song, SavedForm(0, "flac"), url())
            fail("refused")
        } catch (e: DownloadFailure) {
            assertEquals("The server refused it (HTTP 404).", e.reason)
            assertFalse(e.network)
        }
        assertFalse(catalogue.has(song.id))
    }

    @Test
    fun aFileLeftWithoutItsEntryIsNotTrusted() {
        // The app stopped between putting the file in place and entering it: there is a file
        // of the right name, and no mark. It is fetched again, not taken as whole.
        File(catalogue.dir, "tr-1.flac").writeBytes(ByteArray(10))
        assertFalse(Catalogue(catalogue.dir).has(song.id))
        fetcher().fetch(song, SavedForm(0, "flac"), url())
        assertArrayEquals(file, catalogue.fileOf(song.id)!!.readBytes())
    }

    @Test
    fun theFormIsWindowsChoice() {
        // The original, at Original quality and a file the phone plays.
        assertEquals(SavedForm(0, "flac"), savedForm(song, 0))
        // At a limit: MP3 at that rate, unless it is an MP3 within it already.
        assertEquals(SavedForm(160, "mp3"), savedForm(song, 160))
        assertEquals(SavedForm(0, "mp3"), savedForm(song.copy(suffix = "mp3", bitRate = 128), 160))
        // A file the phone can't play: converted, at 320 for Original.
        assertEquals(SavedForm(320, "mp3"), savedForm(song.copy(suffix = "ape"), 0))
        // What the free-space check expects of each.
        assertEquals(300_000L, expectedBytes(song, 0))
        assertEquals(10L * 160 * 125, expectedBytes(song, 160))
    }
}
