package org.medianexus.music.downloads

import java.io.File
import okhttp3.OkHttpClient
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
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner

/**
 * "Done" means the right song, whole, on disk: each way a resumed or cut-short answer could
 * once be marked complete, against real audio (made by ffmpeg, in `resources/audio`), read by
 * Media3's real extractors, from a server whose answers stop where the test says.
 *
 * Every test here failed on the code as it was reviewed (commit f84e4c4): the part file
 * recorded nothing about which copy of the file it came from, a resume sent no condition, a
 * `416` made a download of the part on the library's old size, a `206` nobody asked for was
 * taken for the file, a short answer that agreed with itself beat the library, a conversion
 * four fifths there passed, and nothing looked at the bytes.
 */
@RunWith(RobolectricTestRunner::class)
class WholeDownloadTest {
    @get:Rule val folder = TemporaryFolder()

    /** (The check asks the system for a decoder: a phone has these.) */
    @get:Rule val decoders = phoneDecoders()
    private val server = GatedServer()
    private val http = OkHttpClient()
    private lateinit var catalogue: Catalogue

    private val three = GatedServer.fixture("three-seconds.flac")
    private val five = GatedServer.fixture("five-seconds.flac")
    private val twenty = GatedServer.fixture("twenty-seconds.mp3")

    /** The server's library as it is now: what `getSong` would say. */
    private val library = HashMap<String, Song>()

    private fun asThree(id: String = "s1") = Song(id = id, title = "Song", suffix = "flac", size = three.size.toLong(), duration = 3)
    private fun asFive(id: String = "s1") = Song(id = id, title = "Song", suffix = "flac", size = five.size.toLong(), duration = 5)

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
    }

    @After
    fun stop() = server.close()

    private fun fetcher() = SongFetch(http, catalogue, refresh = { library[it] })

    private fun url(id: String = "s1") = server.url("/rest/stream?id=$id")

    private val flac = SavedForm(0, "flac")

    private fun part(name: String = "s1.flac") = File(catalogue.dir, SongFetch.partName(name))

    /** Fetches the three-second file and has the connection dropped once `bytes` of it have
     *  arrived: what the app stopping, or a dead spot, leaves on the disk. */
    private fun interruptedAfter(bytes: Int, modified: String?) {
        val gate = GatedServer.Gate()
        server.handler = { GatedServer.file(it, three, modified, hold = bytes to gate) }
        val fetch = fetcher()
        val worker = Thread {
            try {
                fetch.fetch(asThree(), flac, url())
                fail("it was interrupted")
            } catch (_: Cancelled) {
            } catch (_: DownloadFailure) {
            }
        }
        worker.start()
        gate.awaitReached()
        // Everything sent has been written before the fetch is stopped: the part holds it.
        while (part().length() < bytes) Thread.yield()
        fetch.cancel()
        gate.dropHere()
        worker.join()
        assertFalse(catalogue.has("s1"))
        assertEquals(bytes.toLong(), part().length())
    }

    @Test
    fun aPartIsNeverJoinedToADifferentFileUnderTheSameId() {
        interruptedAfter(8_192, MONDAY)
        // The file on the server is replaced: same id, another song.
        server.handler = { GatedServer.file(it, five, TUESDAY) }
        val entry = fetcher().fetch(asFive(), flac, url())
        // The rest was asked for only on condition that the copy was still Monday's.
        assertEquals(MONDAY, server.asked.last { it.from != null }.headers["if-range"])
        assertArrayEquals(five, File(catalogue.dir, entry.file).readBytes())
    }

    @Test
    fun aServerThatTakesNoNoticeOfTheConditionIsNotBelieved() {
        interruptedAfter(8_192, MONDAY)
        // It answers the range with the rest of whatever it has now.
        server.handler = { GatedServer.file(it, five, TUESDAY, conditional = false) }
        val entry = fetcher().fetch(asFive(), flac, url())
        assertArrayEquals(five, File(catalogue.dir, entry.file).readBytes())
        // The rest it sent was let go, and the song asked for again from nothing.
        assertNull(server.asked.last().from)
    }

    @Test
    fun withNothingToSayWhichCopyItIsAPartIsNotCarriedOnWith() {
        // No validator at all: the server can't say whether its copy is the same one.
        interruptedAfter(8_192, modified = null)
        server.handler = { GatedServer.file(it, three, null) }
        val entry = fetcher().fetch(asThree(), flac, url())
        assertNull("a range was asked of a server that can't vouch for it", server.asked.last().from)
        assertArrayEquals(three, File(catalogue.dir, entry.file).readBytes())
    }

    @Test
    fun noSuchRangeThrowsThePartAwayAndNeverMakesADownloadOfIt() {
        // A part as long as the library once said the song was, of a copy the server no
        // longer has: it answers "no such range".
        val old = ByteArray(five.size + 1_000) { 7 }
        part().writeBytes(old)
        File(catalogue.dir, SongFetch.metaName("s1.flac")).writeText("""{"modified":"$MONDAY","total":${old.size + 5_000},"synced":${old.size}}""")
        server.handler = { GatedServer.file(it, five, TUESDAY, conditional = false) }
        library["s1"] = asFive()
        // (As the app had it: the size the library gave before the file was replaced.)
        val entry = fetcher().fetch(asFive().copy(size = old.size.toLong()), flac, url())
        assertEquals(listOf(old.size.toLong(), null), server.asked.map { it.from })
        assertArrayEquals(five, File(catalogue.dir, entry.file).readBytes())
    }

    @Test
    fun partOfTheSongSentUnaskedIsNotTheSong() {
        // Something between the phone and the server answers a plain request with a range.
        server.handler = {
            GatedServer.Answer(206, mapOf("Content-Type" to "audio/flac", "Content-Range" to "bytes 0-9999/${three.size}", "Last-Modified" to MONDAY), three.copyOf(10_000))
        }
        try {
            fetcher().fetch(asThree(), flac, url())
            fail("part of a song is not a song")
        } catch (e: DownloadFailure) {
            assertTrue(e.again)
        }
        assertFalse(catalogue.has("s1"))
        assertTrue(catalogue.dir.listFiles()!!.none { it.name.contains("s1") })
    }

    /** A real library's figure for how long a song runs can be out (and an MP3 with no header
     *  to say is only estimated by the phone): a file that is exactly the length the server
     *  stated and the library gives is whole all the same, and must not be refused. */
    @Test
    fun aWholeOriginalIsNotRefusedBecauseTheLibraryHasItsRunningTimeWrong() {
        val wrong = asThree().copy(duration = 30)
        library["s1"] = wrong
        server.handler = { GatedServer.file(it, three, "v1") }
        fetcher().fetch(wrong, flac, url())
        assertArrayEquals(three, File(catalogue.dir, "s1.flac").readBytes())
        assertEquals(three.size.toLong(), catalogue.entry("s1")?.bytes)
    }

    @Test
    fun aShortAnswerThatAgreesWithItselfIsNotTheSongTheLibraryHas() {
        // The answer is 10,000 bytes and says so; the library says the song is longer, and
        // says so again when asked.
        library["s1"] = asThree()
        server.handler = { GatedServer.Answer(200, mapOf("Content-Type" to "audio/flac", "Last-Modified" to MONDAY), three.copyOf(10_000)) }
        try {
            fetcher().fetch(asThree(), flac, url())
            fail("cut short")
        } catch (e: DownloadFailure) {
            assertEquals("The download was cut short.", e.reason)
            assertTrue(e.again)
        }
        assertFalse(catalogue.has("s1"))
    }

    @Test
    fun whenTheLibraryHasMovedOnItsNewerWordIsGoneBy() {
        // The app still has the song as it was; the server has another file for it now.
        library["s1"] = asFive()
        server.handler = { GatedServer.file(it, five, TUESDAY) }
        val entry = fetcher().fetch(asThree(), flac, url())
        assertArrayEquals(five, File(catalogue.dir, entry.file).readBytes())
        // And it is entered as what it is.
        assertEquals(5, entry.song.duration)
        assertEquals(five.size.toLong(), entry.song.size)
    }

    @Test
    fun aConversionThatEndsEarlyIsNotWholeEvenMostOfTheWayThere() {
        // Seventeen of its twenty seconds, ended cleanly: more than the four fifths that
        // used to pass.
        val song = Song(id = "s2", title = "Converted", suffix = "ape", size = 900_000, duration = 20, bitRate = 900)
        library["s2"] = song
        val mp3 = SavedForm(16, "mp3")
        server.handler = { GatedServer.conversion(twenty.copyOf(twenty.size * 85 / 100)) }
        try {
            fetcher().fetch(song, mp3, url("s2"))
            fail("cut short")
        } catch (e: DownloadFailure) {
            assertEquals("The download was cut short.", e.reason)
        }
        assertFalse(catalogue.has("s2"))
        // All of it: a download.
        server.handler = { GatedServer.conversion(twenty) }
        val entry = fetcher().fetch(song, mp3, url("s2"))
        assertArrayEquals(twenty, File(catalogue.dir, entry.file).readBytes())
    }

    @Test
    fun whatIsNotAudioIsNotADownloadWhateverItIsCalled() {
        // The right size, called FLAC, and not a note in it.
        library["s1"] = asThree()
        val noise = ByteArray(three.size) { (it * 31 + 7).toByte() }
        server.handler = { GatedServer.file(it, noise, MONDAY) }
        try {
            fetcher().fetch(asThree(), flac, url())
            fail("not audio")
        } catch (e: DownloadFailure) {
            assertEquals("What the server sent isn't a song the phone can play.", e.reason)
        }
        // Nor one kind of audio called another.
        server.handler = { GatedServer.file(it, twenty, MONDAY) }
        try {
            fetcher().fetch(asThree().copy(size = twenty.size.toLong(), duration = 20), flac, url())
            fail("an MP3 is not a FLAC")
        } catch (_: DownloadFailure) {
        }
        assertFalse(catalogue.has("s1"))
        assertTrue(catalogue.dir.listFiles()!!.none { it.name.contains("s1") })
    }

    @Test
    fun aMarkedFileThatIsNoLongerItsLengthIsNotADownload() {
        server.handler = { GatedServer.file(it, three, MONDAY) }
        val entry = fetcher().fetch(asThree(), flac, url())
        val file = File(catalogue.dir, entry.file)
        // Something has cut it short since (a disk that lost its end, a restore that didn't
        // finish).
        file.writeBytes(three.copyOf(three.size - 100))
        val again = Catalogue(catalogue.dir)
        assertFalse(again.has("s1"))
        assertNull(again.wholeFileOf("s1"))
    }

    @Test
    fun afterThePowerWentOnlyWhatReachedTheDiskIsCarriedOnFrom() {
        // The part is longer than its record says reached the disk: what lies past that
        // can't be trusted (here it is plainly wrong).
        val sure = 6_000
        part().writeBytes(three.copyOf(sure) + ByteArray(4_000) { 0 })
        File(catalogue.dir, SongFetch.metaName("s1.flac")).writeText("""{"modified":"$MONDAY","total":${three.size},"synced":$sure}""")
        server.handler = { GatedServer.file(it, three, MONDAY) }
        val entry = fetcher().fetch(asThree(), flac, url())
        assertEquals(sure.toLong(), server.asked.single().from)
        assertArrayEquals(three, File(catalogue.dir, entry.file).readBytes())
    }

    private companion object {
        const val MONDAY = "Mon, 23 Nov 2026 07:57:08 GMT"
        const val TUESDAY = "Tue, 24 Nov 2026 09:00:00 GMT"
    }
}
