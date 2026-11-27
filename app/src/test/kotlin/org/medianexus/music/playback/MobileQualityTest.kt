package org.medianexus.music.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
import org.medianexus.music.net.NetworkNow
import org.medianexus.music.net.Song
import org.medianexus.music.store.KeptIndex
import org.medianexus.music.store.QUALITIES
import org.medianexus.music.store.SAME_AS_WIFI
import org.medianexus.music.store.Settings

/**
 * A second streaming limit for mobile data, as the one place for what a song is streamed as
 * ([StreamQuality]) decides it: which network counts, what is asked for on each, what a kept
 * copy fetched on the other network does, and that with "Same as Wi-Fi" nothing is what it
 * was not before.
 */
class MobileQualityTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var catalogue: Catalogue
    private lateinit var kept: KeptIndex

    private val settings = MutableStateFlow(Settings())
    private val network = MutableStateFlow(WIFI)
    private val quality = StreamQuality(settings, network)

    private val flac = Song(id = "f", suffix = "flac", bitRate = 900, samplingRate = 48_000, bitDepth = 24, duration = 200)
    private val mp3Low = Song(id = "m1", suffix = "mp3", bitRate = 128, duration = 200)
    private val mp3High = Song(id = "m3", suffix = "mp3", bitRate = 320, duration = 200)
    private val aac = Song(id = "a", suffix = "m4a", bitRate = 256, duration = 200)
    private val wma = Song(id = "w", suffix = "wma", bitRate = 192, duration = 200)
    private val unknown = Song(id = "u", suffix = "", duration = 200)
    private val songs = listOf(flac, mp3Low, mp3High, aac, wma, unknown)

    @Before
    fun start() {
        catalogue = Catalogue(folder.newFolder("downloads"))
        kept = KeptIndex(File(folder.root, "kept.json"), scope, Dispatchers.Unconfined)
    }

    @After
    fun stop() = scope.cancel()

    private fun availability(keeping: Boolean = true) = Availability(catalogue, kept, quality, keeping = { keeping })

    @Test
    fun sameAsWifiIsTheDefaultAndChangesNothingOnMobileData() {
        assertEquals(SAME_AS_WIFI, Settings().mobileBitrate)
        for (cap in QUALITIES) {
            settings.value = Settings(bitrate = cap)
            val onWifi = songs.map { listOf(quality.formOf(it), quality.keyOf(it), quality.keysOf(it), formatLine(it, quality.limit(), false, LIVE)) }
            val natives = songs.map { quality.formOf(true, it.suffix, it.bitRate) to quality.formOf(false, it.suffix, it.bitRate) }
            for (net in listOf(MOBILE, VPN_OVER_MOBILE, NONE)) {
                network.value = net
                assertEquals(cap, quality.limit())
                assertEquals(onWifi, songs.map { listOf(quality.formOf(it), quality.keyOf(it), quality.keysOf(it), formatLine(it, quality.limit(), false, LIVE)) })
                assertEquals(natives, songs.map { quality.formOf(true, it.suffix, it.bitRate) to quality.formOf(false, it.suffix, it.bitRate) })
                // One form, one key: nothing kept is looked for under another name.
                for (song in songs) assertEquals(1, quality.keysOf(song).size)
            }
            network.value = WIFI
        }
    }

    @Test
    fun theLimitIsTheNetworksTheStreamIsOpenedOn() {
        settings.value = Settings(bitrate = 0, mobileBitrate = 160)
        network.value = WIFI
        assertFalse(quality.onMobileData())
        assertEquals(0, quality.limit())
        assertEquals(StreamForm(0, "flac"), quality.formOf(flac))
        // Mobile data: Android counts it as metered.
        network.value = MOBILE
        assertTrue(quality.onMobileData())
        assertEquals(160, quality.limit())
        assertEquals(StreamForm(160, "mp3"), quality.formOf(flac))
        assertEquals(StreamForm(0, "mp3"), quality.formOf(mp3Low))
        assertEquals(StreamForm(160, "mp3"), quality.formOf(mp3High))
        assertEquals("song:f:160", quality.keyOf(flac))
        // A VPN over mobile data is metered as the network under it is: mobile data.
        network.value = VPN_OVER_MOBILE
        assertEquals(160, quality.limit())
        // A Wi-Fi Android counts as metered (a phone's hotspot) is mobile data's too.
        network.value = METERED_WIFI
        assertEquals(160, quality.limit())
        // No network: nothing streams; the Wi-Fi limit is the one named.
        network.value = NONE
        assertFalse(quality.onMobileData())
        assertEquals(0, quality.limit())
        // And the other way about: lossless on mobile data, a limit at home.
        settings.value = Settings(bitrate = 96, mobileBitrate = 0)
        network.value = WIFI
        assertEquals(StreamForm(96, "mp3"), quality.formOf(flac))
        network.value = MOBILE
        assertEquals(StreamForm(0, "flac"), quality.formOf(flac))
    }

    @Test
    fun theTwoFormsHaveTwoKeysAndOneFormOneKey() {
        settings.value = Settings(bitrate = 0, mobileBitrate = 160)
        network.value = MOBILE
        assertEquals(listOf("song:f:160", "song:f:0"), quality.keysOf(flac))
        network.value = WIFI
        assertEquals(listOf("song:f:0", "song:f:160"), quality.keysOf(flac))
        // An MP3 within both limits is the same file either way: one key.
        assertEquals(listOf("song:m1:0"), quality.keysOf(mp3Low))
        // A file the phone can't play: 320 with no limit, 160 on mobile data.
        assertEquals(listOf("song:w:320", "song:w:160"), quality.keysOf(wma))
        // The same limit chosen twice is one form.
        settings.value = Settings(bitrate = 160, mobileBitrate = 160)
        assertEquals(listOf("song:f:160"), quality.keysOf(flac))
    }

    @Test
    fun aCopyKeptWholeOnTheOtherNetworkIsPlayedAsItWasFetched() {
        settings.value = Settings(bitrate = 0, mobileBitrate = 160)
        val whole = HashSet<String>()
        fun open(song: Song) = quality.formToOpen(song.id, true, song.suffix, song.bitRate, whole::contains)
        network.value = MOBILE
        // Nothing kept: mobile data's own form.
        assertEquals(StreamForm(160, "mp3"), open(flac))
        // Fetched ahead on the Wi-Fi as it is: played as it is, not fetched again at 160.
        whole += "song:f:0"
        assertEquals(StreamForm(0, "flac"), open(flac))
        // Both kept: the network's own.
        whole += "song:f:160"
        assertEquals(StreamForm(160, "mp3"), open(flac))
        network.value = WIFI
        assertEquals(StreamForm(0, "flac"), open(flac))
        // Kept only at 160 (fetched on mobile data), on the Wi-Fi: the 160 copy.
        whole -= "song:f:0"
        assertEquals(StreamForm(160, "mp3"), open(flac))
        // What the format line says of it, once opened, is that copy.
        assertEquals(FormatLine("MP3 · 160 kbps", false), formatLineOf(flac, SongCopy.Stream(open(flac)).kbps, LIVE))
        // With one limit, only its own form is ever opened, whatever is kept.
        settings.value = Settings(bitrate = 0)
        network.value = MOBILE
        assertEquals(StreamForm(0, "flac"), open(flac))
        whole.clear()
        whole += "song:f:160"
        assertEquals(StreamForm(0, "flac"), open(flac))
    }

    @Test
    fun aSongKeptWholeInEitherFormPlaysOfflineAndIsNotFetchedAhead() {
        settings.value = Settings(bitrate = 0, mobileBitrate = 160)
        kept.kept("song:f:0", flac)
        kept.kept("song:m3:160", mp3High)
        for (net in listOf(WIFI, MOBILE)) {
            network.value = net
            assertTrue(availability().isKept(flac))
            assertTrue(availability().plays(flac))
            assertTrue(availability().isKept(mp3High))
            assertFalse(availability().isKept(aac))
            assertFalse(availability(keeping = false).isKept(flac))
            assertEquals(setOf("f", "m3"), availability().playable().map { it.id }.toSet())
            val queue = Queue().apply { replace(listOf(wma, flac, mp3High, aac), 0, "Mix", "", null, emptyList(), false, false, kotlin.random.Random(1)) }
            val a = availability()
            assertEquals(listOf("a"), FetchAhead.upcoming(queue, 3) { a.isDownloaded(it.id) || a.isKept(it) })
        }
        // Kept at an old limit, neither of the two now: not kept.
        settings.value = Settings(bitrate = 96, mobileBitrate = 320)
        assertFalse(availability().isKept(flac))
    }

    @Test
    fun aDownloadIsUntouchedByEitherLimit() {
        File(catalogue.dir, "a.m4a").writeBytes(ByteArray(4))
        catalogue.add(Downloaded(aac, "a.m4a", 4, at = 1, suffix = "m4a"))
        for (mobileCap in listOf(SAME_AS_WIFI) + QUALITIES) for (net in listOf(WIFI, MOBILE, NONE)) {
            settings.value = Settings(bitrate = 0, mobileBitrate = mobileCap)
            network.value = net
            assertTrue(availability().plays(aac))
            assertTrue(availability().isDownloaded("a"))
        }
    }

    @Test
    fun theSongPlayingIsOpenedAgainForASettingNeverForTheNetwork() = runBlocking {
        settings.value = Settings(bitrate = 0)
        network.value = WIFI
        val again = mutableListOf<Int>()
        val moved = mutableListOf<Int>()
        val watching = launch(Dispatchers.Unconfined) { quality.chosenAgain.collect { again += it } }
        val watchingNetwork = launch(Dispatchers.Unconfined) { quality.movedWithNetwork.collect { moved += it } }
        yield()
        // "Same as Wi-Fi": the streaming quality changed is the song opened again, wherever.
        settings.value = Settings(bitrate = 160)
        network.value = MOBILE
        settings.value = Settings(bitrate = 320)
        assertEquals(listOf(160, 320), again)
        // ...and the network changing does nothing.
        network.value = WIFI
        assertEquals(emptyList<Int>(), moved)
        // On the Wi-Fi, the mobile data choice changes nothing playing.
        settings.value = Settings(bitrate = 320, mobileBitrate = 96)
        assertEquals(listOf(160, 320), again)
        // On mobile data it does; the streaming quality then doesn't.
        network.value = MOBILE
        assertEquals(listOf(96), moved)
        settings.value = Settings(bitrate = 320, mobileBitrate = 160)
        settings.value = Settings(bitrate = 0, mobileBitrate = 160)
        assertEquals(listOf(160, 320, 160), again)
        // Back to "Same as Wi-Fi" on mobile data: the limit goes from 160 to the Wi-Fi's.
        settings.value = Settings(bitrate = 0, mobileBitrate = SAME_AS_WIFI)
        assertEquals(listOf(160, 320, 160, 0), again)
        // The same limit by both: the network moving between them is nothing.
        settings.value = Settings(bitrate = 160, mobileBitrate = 160)
        network.value = WIFI
        network.value = MOBILE
        assertEquals(listOf(96), moved)
        watching.cancel()
        watchingNetwork.cancel()
    }

    @Test
    fun theFormatLineBeforeOpeningFollowsTheNetworkNow() = runBlocking {
        settings.value = Settings(bitrate = 0, mobileBitrate = 160)
        network.value = WIFI
        assertEquals(FormatLine("FLAC · 48 kHz · 24-bit", true), formatLine(flac, quality.limit(), false, LIVE))
        network.value = MOBILE
        assertEquals(FormatLine("MP3 · 160 kbps", false), formatLine(flac, quality.limit(), false, LIVE))
        val seen = mutableListOf<Int>()
        val watching = launch(Dispatchers.Unconfined) { quality.limits.collect { seen += it } }
        yield()
        network.value = WIFI
        network.value = VPN_OVER_MOBILE
        settings.value = Settings(bitrate = 0, mobileBitrate = SAME_AS_WIFI)
        assertEquals(listOf(160, 0, 160, 0), seen)
        watching.cancel()
    }

    private companion object {
        const val LIVE = "Live stream"
        val WIFI = NetworkNow(connected = true, unmetered = true)
        val MOBILE = NetworkNow(connected = true, unmetered = false)
        /** What Android reports of a VPN whose way out is mobile data: metered. */
        val VPN_OVER_MOBILE = NetworkNow(connected = true, unmetered = false)
        val METERED_WIFI = NetworkNow(connected = true, unmetered = false)
        val NONE = NetworkNow(connected = false, unmetered = false)
    }
}
