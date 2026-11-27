package org.medianexus.music.playback

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
import org.medianexus.music.library.Availability
import org.medianexus.music.net.NetworkNow
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.QUALITIES
import org.medianexus.music.store.SAME_AS_WIFI
import org.medianexus.music.store.Settings

/**
 * What [StreamQuality] and [Availability] decide alone: in doubt, the lower limit, and the
 * server asked for a form above it only while sure of an unmetered network (R1, R2); what
 * offline offers with two limits; and with "Same as Wi-Fi" the released answers, with the
 * network never so much as looked at (R3).
 */
class MobileRacesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var kept: KeptIndex

    private val settings = MutableStateFlow(Settings(bitrate = 0, mobileBitrate = 96))
    private val network = MutableStateFlow(WIFI)
    @Volatile private var look: Boolean? = true
    private val looks = AtomicInteger()
    private val quality = StreamQuality(settings, network, unmeteredNow = { looks.incrementAndGet(); look })

    private val flac = Song(id = "f", suffix = "flac", bitRate = 900, duration = 200)
    private val aac = Song(id = "a", suffix = "m4a", bitRate = 256, duration = 200)

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
        kept = KeptIndex(File(folder.root, "kept.json"), scope, Dispatchers.Unconfined)
    }

    @After
    fun stop() = scope.cancel()

    private fun choose(song: Song, whole: Set<String> = emptySet()) = quality.choose(song.id, true, song.suffix, song.bitRate, whole::contains)
    private fun mayAsk(form: StreamForm, song: Song = flac) = quality.mayAsk(form, true, song.suffix, song.bitRate)

    @Test
    fun inDoubtTheLower() {
        // Sure of the Wi-Fi (its last callback and a look this instant agree): the file as it is.
        assertEquals(StreamForm(0, "flac"), choose(flac))
        assertTrue(mayAsk(StreamForm(0, "flac")))
        // The callback is late: the route is on mobile data already.
        look = false
        assertEquals(StreamForm(96, "mp3"), choose(flac))
        assertFalse(mayAsk(StreamForm(0, "flac")))
        assertTrue(mayAsk(StreamForm(96, "mp3")))
        // No look to be had.
        look = null
        assertEquals(StreamForm(96, "mp3"), choose(flac))
        // The callback says mobile data, the look says Wi-Fi (a network coming): in doubt.
        network.value = MOBILE
        look = true
        assertEquals(StreamForm(96, "mp3"), choose(flac))
        assertFalse(mayAsk(StreamForm(0, "flac")))
        // No network yet.
        network.value = NONE
        assertEquals(StreamForm(96, "mp3"), choose(flac))
        // Lossless chosen for mobile data and 96 at home: never more than the lower over
        // mobile data, so 96 there too.
        settings.value = Settings(bitrate = 96, mobileBitrate = 0)
        network.value = MOBILE
        look = false
        assertEquals(StreamForm(96, "mp3"), choose(flac))
        // A copy kept whole in the higher form is chosen, in doubt or not: it is read from the
        // phone (the server is asked nothing in that form: mayAsk is false).
        settings.value = Settings(bitrate = 0, mobileBitrate = 96)
        assertEquals(StreamForm(0, "flac"), choose(flac, setOf("song:f:0")))
        assertEquals(StreamForm(96, "mp3"), choose(flac, setOf("song:f:0", "song:f:96")))
    }

    @Test
    fun sameAsWifiNeverAsksTheNetworkAndChoosesAsReleased() {
        for (cap in QUALITIES) {
            settings.value = Settings(bitrate = cap, mobileBitrate = SAME_AS_WIFI)
            for (net in listOf(WIFI, MOBILE, NONE)) for (seen in listOf(null, true, false)) {
                network.value = net
                look = seen
                for (song in listOf(flac, aac, Song(id = "m", suffix = "mp3", bitRate = 128), Song(id = "w", suffix = "wma"))) for (native in listOf(true, false)) {
                    val form = streamForm(native, cap, song.suffix, song.bitRate)
                    assertEquals(form, quality.choose(song.id, native, song.suffix, song.bitRate) { true })
                    assertTrue(quality.mayAsk(form, native, song.suffix, song.bitRate))
                    val where = quality.whereNow(song.id, song, native, { true }, null, 0) { kbps, offset -> "$kbps/$offset" }
                    assertEquals(cacheKey(song.id, form), where.key)
                    assertTrue(where.mayAsk())
                }
                assertEquals(cap, quality.limit())
            }
        }
        assertEquals(0, looks.get())
    }

    @Test
    fun withTwoLimitsOfflineOffersOnlyWhatThePlayerWouldPlay() {
        // The phone has been found not to play this AAC as it is; its file as it is is kept
        // whole (fetched ahead on the Wi-Fi at Lossless before that was found).
        kept.kept("song:a:0", aac)
        var mustConvert = true
        val availability = Availability(catalogue, kept, quality, keeping = { true }, mustConvert = { mustConvert })
        // Lossless on the Wi-Fi, 96 on mobile data: the player would open a conversion at 320
        // or 96, never the file. Not offered.
        assertFalse(availability.plays(aac))
        assertEquals(emptyList<Song>(), availability.playable())
        // A conversion at 320 kept whole: that is what it would play.
        kept.kept("song:a:320", aac)
        assertTrue(availability.plays(aac))
        kept.gone("song:a:320")
        // A file it does play is offered as it is.
        mustConvert = false
        assertTrue(availability.plays(aac))
    }

    private companion object {
        val WIFI = NetworkNow(connected = true, unmetered = true)
        val MOBILE = NetworkNow(connected = true, unmetered = false)
        val NONE = NetworkNow(connected = false, unmetered = false)
    }
}
