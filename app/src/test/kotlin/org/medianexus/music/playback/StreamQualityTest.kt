package org.medianexus.music.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.downloads.Catalogue
import org.medianexus.music.downloads.Downloaded
import org.medianexus.music.library.Availability
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.Settings

/**
 * What a song is streamed as, read in four places: where its bytes come from (the form the
 * player asks the server for, and the key it is kept under), whether a kept copy plays with
 * no server, which songs fetching ahead passes over, and the format line before the player
 * has opened the song. These are the answers the app gave with one streaming limit, before
 * the four were made to read it from one place: they must not change. (Only the adapters
 * at the top say how each place is reached.)
 */
class StreamQualityTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var kept: KeptIndex

    // ---- How each of the four places is reached ----------------------------------------

    /** The one place, with the streaming limit at `cap`. */
    private fun quality(cap: Int) = StreamQuality(MutableStateFlow(Settings(bitrate = cap)))

    /** Where the bytes come from: the form `PlaybackService.resolve` asks for. */
    private fun resolved(cap: Int, native: Boolean, suffix: String, fileKbps: Int): StreamForm =
        quality(cap).formOf(native, suffix, fileKbps)

    /** Whether a kept copy plays offline (and what fetching ahead passes over). */
    private fun availability(cap: Int, keeping: Boolean = true) =
        Availability(catalogue, kept, quality(cap), keeping = { keeping })

    /** The format line before the player has opened the song (`PlayerConnection.format`
     *  gives `formatLine` the limit as the one place has it). */
    private fun lineBeforeOpening(song: Song, cap: Int, converted: Boolean): FormatLine =
        formatLine(song, quality(cap).limit(), converted, LIVE)

    // ------------------------------------------------------------------------------------

    private val flac = Song(id = "f", suffix = "flac", bitRate = 900, samplingRate = 48_000, bitDepth = 24, duration = 200)
    private val mp3Low = Song(id = "m1", suffix = "mp3", bitRate = 128, duration = 200)
    private val mp3High = Song(id = "m3", suffix = "mp3", bitRate = 320, duration = 200)
    private val aac = Song(id = "a", suffix = "m4a", bitRate = 256, duration = 200)
    private val wma = Song(id = "w", suffix = "wma", bitRate = 192, duration = 200)
    private val unknown = Song(id = "u", suffix = "", duration = 200)

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
        kept = KeptIndex(File(folder.root, "kept.json"), scope, Dispatchers.Unconfined)
    }

    @After
    fun stop() = scope.cancel()

    @Test
    fun theFormAsked() {
        // No limit: the file as it is if the phone plays it, else MP3 at 320.
        assertEquals(StreamForm(0, "flac"), resolved(0, true, "flac", 900))
        assertEquals(StreamForm(0, "mp3"), resolved(0, true, "mp3", 320))
        assertEquals(StreamForm(0, ""), resolved(0, true, "", 0))
        assertEquals(StreamForm(320, "mp3"), resolved(0, false, "wma", 192))
        assertEquals(StreamForm(320, "mp3"), resolved(0, false, "m4a", 256))
        // A limit: MP3 at the limit, but an MP3 already within it as it is.
        assertEquals(StreamForm(192, "mp3"), resolved(192, true, "flac", 900))
        assertEquals(StreamForm(0, "mp3"), resolved(192, true, "mp3", 128))
        assertEquals(StreamForm(0, "mp3"), resolved(192, true, "mp3", 192))
        assertEquals(StreamForm(192, "mp3"), resolved(192, true, "mp3", 320))
        assertEquals(StreamForm(192, "mp3"), resolved(192, true, "mp3", 0))
        assertEquals(StreamForm(192, "mp3"), resolved(192, true, "m4a", 256))
        assertEquals(StreamForm(192, "mp3"), resolved(192, false, "wma", 192))
        assertEquals(StreamForm(192, "mp3"), resolved(192, true, "", 0))
        assertEquals(StreamForm(128, "mp3"), resolved(128, true, "opus", 96))
    }

    @Test
    fun theKeyItIsKeptUnder() {
        assertEquals("song:f:0", cacheKey("f", resolved(0, true, "flac", 900)))
        assertEquals("song:f:192", cacheKey("f", resolved(192, true, "flac", 900)))
        assertEquals("song:m1:0", cacheKey("m1", resolved(192, true, "mp3", 128)))
        assertEquals("song:w:320", cacheKey("w", resolved(0, false, "wma", 192)))
        // Availability names the same key, by the song's kind alone.
        assertEquals("song:f:0", availability(0).keyOf(flac))
        assertEquals("song:f:192", availability(192).keyOf(flac))
        assertEquals("song:m1:0", availability(192).keyOf(mp3Low))
        assertEquals("song:m3:192", availability(192).keyOf(mp3High))
        assertEquals("song:w:320", availability(0).keyOf(wma))
        assertEquals("song:w:128", availability(128).keyOf(wma))
        assertEquals("song:u:0", availability(0).keyOf(unknown))
        assertEquals("song:a:0", availability(0).keyOf(aac))
    }

    @Test
    fun aKeptCopyPlaysOfflineOnlyInTheFormItWouldBeStreamedIn() {
        kept.kept("song:f:0", flac)
        kept.kept("song:m3:192", mp3High)
        assertTrue(availability(0).isKept(flac))
        assertTrue(availability(0).plays(flac))
        assertFalse(availability(192).isKept(flac))
        assertFalse(availability(192).plays(flac))
        assertTrue(availability(192).isKept(mp3High))
        assertFalse(availability(0).isKept(mp3High))
        assertFalse(availability(320).isKept(mp3High))
        // Nothing is kept at all with a limit of nothing.
        assertFalse(availability(0, keeping = false).isKept(flac))
        assertEquals(listOf("f"), availability(0).playable().map { it.id })
        assertEquals(listOf("m3"), availability(192).playable().map { it.id })
    }

    @Test
    fun aDownloadPlaysWhateverTheLimit() {
        File(catalogue.dir, "a.m4a").writeBytes(ByteArray(4))
        catalogue.add(Downloaded(aac, "a.m4a", 4, at = 1, suffix = "m4a"))
        for (cap in listOf(0, 128, 192, 320)) {
            assertTrue(availability(cap).plays(aac))
            assertFalse(availability(cap).isKept(aac))
        }
    }

    @Test
    fun fetchingAheadPassesOverWhatIsKeptInTheFormNow() {
        kept.kept("song:f:0", flac)
        kept.kept("song:m1:0", mp3Low)
        val queue = Queue().apply { replace(listOf(wma, flac, mp3Low, mp3High), 0, "Mix", "", null, emptyList(), false, false, kotlin.random.Random(1)) }
        fun ahead(cap: Int) = availability(cap).let { a -> FetchAhead.upcoming(queue, 3) { a.isDownloaded(it.id) || a.isKept(it) } }
        assertEquals(listOf("m3"), ahead(0))
        // At 192 the FLAC is wanted again, in its new form; the MP3 within the limit is not.
        assertEquals(listOf("f", "m3"), ahead(192))
    }

    @Test
    fun theFormatLineBeforeTheSongIsOpened() {
        assertEquals(FormatLine("FLAC · 48 kHz · 24-bit", true), lineBeforeOpening(flac, 0, false))
        assertEquals(FormatLine("MP3 · 192 kbps", false), lineBeforeOpening(flac, 192, false))
        assertEquals(FormatLine("MP3 · 128 kbps", false), lineBeforeOpening(mp3Low, 192, false))
        assertEquals(FormatLine("MP3 · 192 kbps", false), lineBeforeOpening(mp3High, 192, false))
        assertEquals(FormatLine("MP3 · 320 kbps", false), lineBeforeOpening(mp3High, 0, false))
        assertEquals(FormatLine("MP3 · 320 kbps", false), lineBeforeOpening(wma, 0, false))
        assertEquals(FormatLine("MP3 · 320 kbps", false), lineBeforeOpening(aac, 0, true))
        assertEquals(FormatLine("M4A · 256 kbps", false), lineBeforeOpening(aac, 0, false))
        assertEquals(FormatLine(LIVE, false), lineBeforeOpening(Song(radioUrl = "http://radio.invalid"), 192, false))
    }

    private companion object {
        const val LIVE = "Live stream"
    }
}
