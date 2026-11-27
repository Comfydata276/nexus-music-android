package org.medianexus.music.playback

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * The review's findings that are decided in [StreamQuality] and [Availability] alone: in
 * doubt, the lower limit (1); a network that flaps changes nothing until it settles (5);
 * what is offered offline is what the player would play (7); and with "Same as Wi-Fi" the
 * network is never so much as asked.
 */
class MobileRacesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var kept: KeptIndex

    private val settings = MutableStateFlow(Settings(bitrate = 0, mobileBitrate = 96))
    private val network = MutableStateFlow(WIFI)
    @Volatile private var look: StreamQuality.Route? = StreamQuality.Route("wifi", true)
    private val looks = AtomicInteger()
    private val quality = StreamQuality(settings, network, route = { looks.incrementAndGet(); look })

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

    @Test
    fun inDoubtTheLower() {
        // Sure of the Wi-Fi (its last callback and a look this instant agree): the file as it
        // is, bound to that network.
        assertEquals(StreamQuality.Choice(StreamForm(0, "flac"), route = "wifi"), choose(flac))
        // The callback is late: the route is on mobile data already.
        look = StreamQuality.Route("mobile", false)
        assertEquals(StreamQuality.Choice(StreamForm(96, "mp3")), choose(flac))
        // No look to be had.
        look = null
        assertEquals(StreamQuality.Choice(StreamForm(96, "mp3")), choose(flac))
        // The callback says mobile data, the look says Wi-Fi (a network coming): in doubt.
        network.value = MOBILE
        look = StreamQuality.Route("wifi", true)
        assertEquals(StreamQuality.Choice(StreamForm(96, "mp3")), choose(flac))
        // No network yet.
        network.value = NONE
        assertEquals(StreamQuality.Choice(StreamForm(96, "mp3")), choose(flac))
        // Sure of mobile data.
        network.value = MOBILE
        look = StreamQuality.Route("mobile", false)
        assertEquals(StreamQuality.Choice(StreamForm(96, "mp3")), choose(flac))
        // The lower is the lower whichever it is: lossless on mobile data and 96 at home is
        // 96 in doubt, and the mobile data's lossless only when sure of mobile data.
        settings.value = Settings(bitrate = 96, mobileBitrate = 0)
        assertEquals(StreamQuality.Choice(StreamForm(0, "flac")), choose(flac))
        look = null
        assertEquals(StreamQuality.Choice(StreamForm(96, "mp3")), choose(flac))
        // A copy kept whole in the higher form is played from the phone, in doubt or not, and
        // only from the phone until it has begun.
        settings.value = Settings(bitrate = 0, mobileBitrate = 96)
        network.value = WIFI
        look = StreamQuality.Route("mobile", false)
        assertEquals(StreamQuality.Choice(StreamForm(0, "flac"), keptOnly = true), choose(flac, setOf("song:f:0")))
        // Sure of the Wi-Fi, the copy kept in its own form is read with any bytes the phone
        // no longer has asked for by the Wi-Fi alone.
        look = StreamQuality.Route("wifi", true)
        assertEquals(StreamQuality.Choice(StreamForm(0, "flac"), route = "wifi"), choose(flac, setOf("song:f:0")))
    }

    @Test
    fun sameAsWifiNeverAsksTheNetworkAndChoosesAsBefore() {
        for (cap in QUALITIES) {
            settings.value = Settings(bitrate = cap, mobileBitrate = SAME_AS_WIFI)
            for (net in listOf(WIFI, MOBILE, NONE)) for (seen in listOf(null, StreamQuality.Route("wifi", true), StreamQuality.Route("mobile", false))) {
                network.value = net
                look = seen
                for (song in listOf(flac, aac, Song(id = "m", suffix = "mp3", bitRate = 128), Song(id = "w", suffix = "wma"))) for (native in listOf(true, false)) {
                    val choice = quality.choose(song.id, native, song.suffix, song.bitRate) { true }
                    assertEquals(StreamQuality.Choice(streamForm(native, cap, song.suffix, song.bitRate)), choice)
                    assertEquals(listOf(cacheKey(song.id, streamFormOf(song, cap))), quality.keptKeysOf(song, decodes = false))
                }
                assertEquals(cap, quality.limit())
            }
        }
        assertEquals(0, looks.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aNetworkThatFlapsStartsNothingAgainUntilItHasSettled() = runTest {
        val settled = StreamQuality(settings, network, route = { look }, settleMillis = 5_000)
        val moved = mutableListOf<Int>()
        val watching = launch(StandardTestDispatcher(testScheduler)) { settled.movedWithNetwork.collect { moved += it } }
        runCurrent()
        advanceTimeBy(6_000)
        // The Wi-Fi at the edge of its reach: mobile data, Wi-Fi, mobile data, Wi-Fi, a
        // second apart.
        repeat(2) {
            network.value = MOBILE
            runCurrent()
            advanceTimeBy(1_000)
            network.value = WIFI
            runCurrent()
            advanceTimeBy(1_000)
        }
        advanceTimeBy(10_000)
        assertEquals(emptyList<Int>(), moved)
        // Mobile data that holds: once settled, told.
        network.value = MOBILE
        runCurrent()
        advanceTimeBy(4_000)
        assertEquals(emptyList<Int>(), moved)
        advanceTimeBy(2_000)
        assertEquals(listOf(96), moved)
        watching.cancel()
    }

    @Test
    fun offlineOffersOnlyWhatThePlayerWouldPlay() {
        // The phone has been found not to play this AAC as it is; its file as it is is kept
        // whole (fetched ahead on the Wi-Fi at Lossless before that was found).
        kept.kept("song:a:0", aac)
        var mustConvert = true
        val availability = Availability(catalogue, kept, quality, keeping = { true }, mustConvert = { mustConvert })
        // 96 on the Wi-Fi, Lossless on mobile data: the player would open a conversion at 96
        // or 320, never the file. Not offered.
        settings.value = Settings(bitrate = 96, mobileBitrate = 0)
        assertFalse(availability.plays(aac))
        assertEquals(emptyList<Song>(), availability.playable())
        // A conversion at 320 kept whole: that is what it would play.
        kept.kept("song:a:320", aac)
        assertTrue(availability.plays(aac))
        kept.gone("song:a:320")
        // A file it does play is offered as it is.
        mustConvert = false
        assertTrue(availability.plays(aac))
        // With one limit nothing is changed: the released answer, by the song's kind.
        mustConvert = true
        settings.value = Settings(bitrate = 0)
        assertTrue(availability.plays(aac))
    }

    private companion object {
        val WIFI = NetworkNow(connected = true, unmetered = true)
        val MOBILE = NetworkNow(connected = true, unmetered = false)
        val NONE = NetworkNow(connected = false, unmetered = false)
    }
}
