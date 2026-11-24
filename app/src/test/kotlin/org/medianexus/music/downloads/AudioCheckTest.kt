package org.medianexus.music.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner

/**
 * What a file is, read from its bytes by Media3's own extractors: real audio made by ffmpeg
 * (`resources/audio`), and things that are not audio whatever they are called.
 */
@RunWith(RobolectricTestRunner::class)
class AudioCheckTest {
    @get:Rule val folder = TemporaryFolder()

    /** The decoders a phone has (and not one for ALAC, which Android has never had). */
    @get:Rule val decoders = phoneDecoders()

    private val check = Media3AudioCheck.system

    private fun file(bytes: ByteArray): File = folder.newFile().also { it.writeBytes(bytes) }

    @Test
    fun aFlacSaysWhatItIsAndHowLongItRuns() {
        val seen = check.inspect(file(GatedServer.fixture("three-seconds.flac")), "flac")
        assertEquals("flac", seen.container)
        assertTrue(seen.audio)
        assertEquals(3_000_000L, seen.durationUs)
        assertEquals(5_000_000L, check.inspect(file(GatedServer.fixture("five-seconds.flac")), "flac").durationUs)
    }

    @Test
    fun anMp3WithNoHeaderOfItsLengthRunsAsLongAsItsFramesDo() {
        // As the server streams a conversion: constant bit rate, a tag, and no Xing header.
        val whole = GatedServer.fixture("twenty-seconds.mp3")
        val seen = check.inspect(file(whole), "mp3")
        assertEquals("mp3", seen.container)
        assertTrue(seen.audio)
        assertTrue("it says ${seen.durationUs}", seen.durationUs in 20_000_000L..20_200_000L)
        // Cut short, it says so.
        val most = check.inspect(file(whole.copyOf(whole.size * 85 / 100)), "mp3")
        assertTrue("it says ${most.durationUs}", most.durationUs in 16_800_000L..17_200_000L)
    }

    @Test
    fun theNameItIsGivenCountsForNothing() {
        // An MP3 looked at as a FLAC is still found to be an MP3.
        assertEquals("mp3", check.inspect(file(GatedServer.fixture("twenty-seconds.mp3")), "flac").container)
        assertFalse(Media3AudioCheck.fits("flac", "mp3"))
        assertTrue(Media3AudioCheck.fits("opus", "ogg"))
        assertTrue(Media3AudioCheck.fits("m4a", "mp4"))
    }

    @Test
    fun whatIsNotAudioIsNotAudio() {
        // An error page, noise, a picture, nothing at all.
        val page = "<html><body><h1>502 Bad Gateway</h1></body></html>".repeat(200).toByteArray()
        assertNull(check.inspect(file(page), "flac").container)
        assertNull(check.inspect(file(ByteArray(50_000) { (it * 31 + 7).toByte() }), "mp3").container)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(4_000)
        check.inspect(file(png), "flac").let { assertTrue(it.container == null || !it.audio) }
        assertNull(check.inspect(file(ByteArray(0)), "flac").container)
    }

    // ---- Read through to the end (offline hardening 2, item 2) ------------------------------

    private val threeSeconds = listOf(
        "three-seconds.flac" to "flac", "three-seconds-vbr.mp3" to "mp3", "three-seconds.ogg" to "ogg",
        "three-seconds.opus" to "opus", "three-seconds.m4a" to "m4a", "three-seconds.wav" to "wav",
    )

    @Test
    fun everyKindIsReadToItsEndAndRunsAsLongAsItsSamplesDo() {
        for ((name, suffix) in threeSeconds) {
            val seen = check.inspect(file(GatedServer.fixture(name)), suffix)
            assertTrue("$name is $seen", seen.audio && seen.broken == null && !seen.partial && seen.decodable)
            // Where the last sample ends, not what the header says.
            assertTrue("$name runs ${seen.ranUs}", seen.ranUs in 2_900_000L..3_150_000L)
            assertNull("$name", SongFetch.faultOf(Song(id = "s", suffix = suffix, duration = 3), SavedForm(0, suffix), seen, wholeByLength = false))
        }
        val twenty = check.inspect(file(GatedServer.fixture("twenty-seconds.mp3")), "mp3")
        assertTrue("it runs ${twenty.ranUs}", twenty.broken == null && twenty.ranUs in 19_900_000L..20_200_000L)
    }

    @Test
    fun aFlacWithGoodHeadersAndBytesChangedInsideItsSoundIsDamaged() {
        val whole = GatedServer.fixture("five-seconds.flac")
        // Its headers, its length and every frame's own header are as they were: sixty-four
        // bytes inside one frame's sound are not.
        val hurt = whole.copyOf()
        for (i in whole.size / 2 until whole.size / 2 + 64) hurt[i] = (hurt[i] + 1).toByte()
        val seen = check.inspect(file(hurt), "flac")
        assertEquals("flac", seen.container)
        assertEquals(5_000_000L, seen.durationUs)
        assertEquals(Broken.DAMAGED, seen.broken)
        assertEquals(Fault.DAMAGED, SongFetch.faultOf(Song(id = "s", suffix = "flac", size = hurt.size.toLong(), duration = 5), SavedForm(0, "flac"), seen, wholeByLength = true))
        // And a stretch of it blanked, as a bad block leaves it.
        val blank = whole.copyOf().also { java.util.Arrays.fill(it, whole.size / 3, whole.size / 3 + 9_000, 0) }
        assertEquals(Broken.DAMAGED, check.inspect(file(blank), "flac").broken)
        // The whole file passes: every frame's checksum is right.
        assertNull(check.inspect(file(whole), "flac").broken)
    }

    @Test
    fun aFlacThatStopsBeforeItsHeaderSaysHasLostItsEnd() {
        val whole = GatedServer.fixture("five-seconds.flac")
        // Cut at four fifths: the header still says five seconds, and the frames stop at four.
        val seen = check.inspect(file(whole.copyOf(whole.size * 4 / 5)), "flac")
        assertEquals(5_000_000L, seen.durationUs)
        assertTrue("it is $seen", seen.broken != null || seen.ranUs < 4_500_000L)
        // (Where no length in bytes vouches for it, that is a download cut short.)
        assertTrue(SongFetch.faultOf(Song(id = "s", suffix = "flac"), SavedForm(0, "flac"), seen, wholeByLength = false) != null)
    }

    @Test
    fun anMp3WhoseSoundTheReaderCannotGetThroughIsDamaged() {
        val whole = GatedServer.fixture("twenty-seconds.mp3")
        // A stretch of it is not MP3 at all: longer than the reader will search for where
        // the frames pick up again.
        val junk = ByteArray(160 * 1024) { (it * 31 + 7).toByte() }
        val hurt = whole.copyOfRange(0, whole.size / 2) + junk + whole.copyOfRange(whole.size / 2, whole.size)
        val seen = check.inspect(file(hurt), "mp3")
        assertEquals("mp3", seen.container)
        // Media3 reports no error: it stops looking, and the song ends there, at ten seconds
        // of twenty. That it stopped looking is seen, and the song is not whole.
        assertNull(seen.broken)
        assertTrue(seen.gaveUp)
        assertTrue("it runs ${seen.ranUs}", seen.ranUs in 9_800_000L..10_200_000L)
        val song = Song(id = "s", suffix = "mp3", size = hurt.size.toLong(), duration = 20)
        assertEquals(Fault.DAMAGED, SongFetch.faultOf(song, SavedForm(0, "mp3"), seen, wholeByLength = true))
        // The same stretch after the sound is only something after the sound.
        val tagged = check.inspect(file(whole + junk), "mp3")
        assertTrue(tagged.gaveUp)
        assertNull(SongFetch.faultOf(song.copy(size = tagged.let { (whole.size + junk.size).toLong() }), SavedForm(0, "mp3"), tagged, wholeByLength = true))
    }

    @Test
    fun aStreamThatStopsInsideAFrameIsSeenToHave() {
        val whole = GatedServer.fixture("twenty-seconds.mp3")
        val seen = check.inspect(file(whole.copyOf(whole.size * 6 / 10 + 37)), "mp3")
        assertNull(seen.broken)
        assertTrue(seen.partial)
        assertFalse(check.inspect(file(whole), "mp3").partial)
        // With no length known for the song, that is all there is to tell a conversion that
        // ended early from a whole one.
        assertEquals(Fault.CUT_SHORT, SongFetch.faultOf(Song(id = "s", suffix = "ape"), SavedForm(160, "mp3"), seen, wholeByLength = false))
    }

    @Test
    fun anM4aCutShortIsNotASongOrEndsEarly() {
        val whole = GatedServer.fixture("three-seconds.m4a")
        val seen = check.inspect(file(whole.copyOf(whole.size * 6 / 10)), "m4a")
        assertTrue("it is $seen", SongFetch.faultOf(Song(id = "s", suffix = "m4a", duration = 3), SavedForm(0, "m4a"), seen, wholeByLength = true) != null)
    }

    @Test
    fun theSystemIsAskedWhetherItHasADecoder() {
        // An ALAC in an .m4a: an MP4 the reader reads perfectly, and no Android decodes it.
        val seen = check.inspect(file(GatedServer.fixture("three-seconds-alac.m4a")), "m4a")
        assertEquals("mp4", seen.container)
        assertTrue(seen.audio && seen.broken == null)
        assertFalse(seen.decodable)
        assertEquals(Fault.NO_DECODER, SongFetch.faultOf(Song(id = "s", suffix = "m4a", duration = 3), SavedForm(0, "m4a"), seen, wholeByLength = true))
        // The AAC beside it has one.
        assertTrue(check.inspect(file(GatedServer.fixture("three-seconds.m4a")), "m4a").decodable)
    }

    @Test
    fun anMp3sTagIsSteppedOverNotRead() {
        // A tag with a three-megabyte picture in it, before the sound.
        val picture = ByteArray(3 * 1024 * 1024) { (it * 13).toByte() }
        val frame = "APIC".toByteArray() + byteArrayOf((picture.size ushr 24).toByte(), (picture.size ushr 16).toByte(), (picture.size ushr 8).toByte(), picture.size.toByte(), 0, 0) + picture
        val size = frame.size
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, (size ushr 21 and 0x7F).toByte(), (size ushr 14 and 0x7F).toByte(), (size ushr 7 and 0x7F).toByte(), (size and 0x7F).toByte()) + frame
        val seen = check.inspect(file(tag + GatedServer.fixture("twenty-seconds.mp3")), "mp3")
        assertEquals("mp3", seen.container)
        assertTrue("it runs ${seen.ranUs}", seen.broken == null && seen.ranUs in 19_900_000L..20_200_000L)
    }

    @Test
    fun aLookCanBeCalledOff() {
        val song = file(GatedServer.fixture("twenty-seconds.mp3"))
        var asked = 0
        try {
            check.inspect(song, "mp3") { ++asked > 50 }
            org.junit.Assert.fail("it was called off")
        } catch (_: Cancelled) {
        }
        // Part-way through: it was asked as it went, not once at the end.
        assertTrue(asked in 51..60)
    }
}
