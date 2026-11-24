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
import org.robolectric.RobolectricTestRunner

/**
 * What a file is, read from its bytes by Media3's own extractors: real audio made by ffmpeg
 * (`resources/audio`), and things that are not audio whatever they are called.
 */
@RunWith(RobolectricTestRunner::class)
class AudioCheckTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(bytes: ByteArray): File = folder.newFile().also { it.writeBytes(bytes) }

    @Test
    fun aFlacSaysWhatItIsAndHowLongItRuns() {
        val seen = Media3AudioCheck.inspect(file(GatedServer.fixture("three-seconds.flac")), "flac")
        assertEquals("flac", seen.container)
        assertTrue(seen.audio)
        assertEquals(3_000_000L, seen.durationUs)
        assertEquals(5_000_000L, Media3AudioCheck.inspect(file(GatedServer.fixture("five-seconds.flac")), "flac").durationUs)
    }

    @Test
    fun anMp3WithNoHeaderOfItsLengthRunsAsLongAsItsFramesDo() {
        // As the server streams a conversion: constant bit rate, a tag, and no Xing header.
        val whole = GatedServer.fixture("twenty-seconds.mp3")
        val seen = Media3AudioCheck.inspect(file(whole), "mp3")
        assertEquals("mp3", seen.container)
        assertTrue(seen.audio)
        assertTrue("it says ${seen.durationUs}", seen.durationUs in 20_000_000L..20_200_000L)
        // Cut short, it says so.
        val most = Media3AudioCheck.inspect(file(whole.copyOf(whole.size * 85 / 100)), "mp3")
        assertTrue("it says ${most.durationUs}", most.durationUs in 16_800_000L..17_200_000L)
    }

    @Test
    fun theNameItIsGivenCountsForNothing() {
        // An MP3 looked at as a FLAC is still found to be an MP3.
        assertEquals("mp3", Media3AudioCheck.inspect(file(GatedServer.fixture("twenty-seconds.mp3")), "flac").container)
        assertFalse(Media3AudioCheck.fits("flac", "mp3"))
        assertTrue(Media3AudioCheck.fits("opus", "ogg"))
        assertTrue(Media3AudioCheck.fits("m4a", "mp4"))
    }

    @Test
    fun whatIsNotAudioIsNotAudio() {
        // An error page, noise, a picture, nothing at all.
        val page = "<html><body><h1>502 Bad Gateway</h1></body></html>".repeat(200).toByteArray()
        assertNull(Media3AudioCheck.inspect(file(page), "flac").container)
        assertNull(Media3AudioCheck.inspect(file(ByteArray(50_000) { (it * 31 + 7).toByte() }), "mp3").container)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(4_000)
        Media3AudioCheck.inspect(file(png), "flac").let { assertTrue(it.container == null || !it.audio) }
        assertNull(Media3AudioCheck.inspect(file(ByteArray(0)), "flac").container)
    }
}
