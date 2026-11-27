package org.medianexus.music.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.medianexus.music.net.NetworkNow
import org.medianexus.music.net.Song
import org.medianexus.music.store.SAME_AS_WIFI
import org.medianexus.music.store.Settings

/**
 * What a song is streamed as: **the one place the streaming limit is read.** Four things
 * ask it, and must never disagree: where a song's bytes come from (`PlaybackService.resolve`:
 * the form the server is asked for, and the key it is kept under), whether a kept copy plays
 * with no server (`library.Availability`), which songs fetching ahead passes over (the same),
 * and the format line before the player has opened the song (`PlayerConnection.format`).
 * The song playing is opened again, and fetching ahead starts afresh, when it says so.
 *
 * **Two limits**: Settings' streaming quality, and the one "On mobile data" (Same as Wi-Fi
 * unless chosen). Which applies is the network a stream is opened on: **mobile data is a
 * network Android counts as metered** ([onMobileData]), which takes in a VPN over mobile
 * data (Android gives a VPN the meteredness of the networks under it) and a phone's hotspot
 * that says it is one. The network is the phone's default one, as [NetworkNow] follows it,
 * because that is the one the app's HTTP client goes out on. With "Same as Wi-Fi" there is
 * one limit, and every answer here is what it was before there were two.
 */
class StreamQuality(
    private val settings: StateFlow<Settings>,
    private val network: StateFlow<NetworkNow> = MutableStateFlow(NetworkNow()),
) {
    /** A stream opened now goes out over mobile data. */
    fun onMobileData(): Boolean = mobile(network.value)

    /** The limit for a stream opened now, in kbps; 0 for the files as they are. */
    fun limit(): Int = limitOf(settings.value, onMobileData())

    /** The limit for a stream opened now, as it changes (with the settings or the network):
     *  what the format line says before the player has opened the song. */
    val limits: Flow<Int> = combine(settings, network) { chosen, net -> limitOf(chosen, mobile(net)) }.distinctUntilChanged()

    /** The limit for a stream opened now has changed **because a setting was changed**,
     *  never because the network did: the song playing is opened again in the new form (as
     *  on Windows), whereas a song playing when the network changes plays on as it began.
     *  With "Same as Wi-Fi" this is exactly a change of the streaming quality. */
    val chosenAgain: Flow<Int> = flow {
        var before: Settings? = null
        settings.collect { now ->
            val was = before
            before = now
            val mobile = onMobileData()
            if (was != null && limitOf(was, mobile) != limitOf(now, mobile)) emit(limitOf(now, mobile))
        }
    }

    /** The limit for a stream opened now has changed **because the network did** (the
     *  settings as they are): what is being fetched ahead is let go and fetched afresh, in
     *  the new network's form. Never with "Same as Wi-Fi". */
    val movedWithNetwork: Flow<Int> = flow {
        var before: Boolean? = null
        network.map(::mobile).distinctUntilChanged().collect { now ->
            val was = before
            before = now
            val chosen = settings.value
            if (was != null && limitOf(chosen, was) != limitOf(chosen, now)) emit(limitOf(chosen, now))
        }
    }

    /** The form a song is streamed in now. `native`: the phone plays its file as it is (by
     *  its kind, and not known to fail on this phone). */
    fun formOf(native: Boolean, suffix: String, fileKbps: Int): StreamForm = streamForm(native, limit(), suffix, fileKbps)

    /** The form a song would be streamed in now, by its kind alone: for asking whether it
     *  is kept, with no player. */
    fun formOf(song: Song): StreamForm = streamFormOf(song, limit())

    /** The key the song is kept under in the form it would be streamed in now. */
    fun keyOf(song: Song): String = cacheKey(song.id, formOf(song))

    /** The forms a song is streamed in by the two limits: the one for a stream opened now
     *  first, then the other network's, if that is another form. One with "Same as Wi-Fi". */
    fun formsOf(native: Boolean, suffix: String, fileKbps: Int): List<StreamForm> {
        val chosen = settings.value
        val mobile = onMobileData()
        val own = streamForm(native, limitOf(chosen, mobile), suffix, fileKbps)
        val other = streamForm(native, limitOf(chosen, !mobile), suffix, fileKbps)
        return if (other == own) listOf(own) else listOf(own, other)
    }

    /** The keys a song kept whole under plays in place of a stream, by its kind alone (see
     *  [formToOpen]). */
    fun keysOf(song: Song): List<String> {
        val suffix = song.suffix.lowercase()
        return formsOf(suffix.isEmpty() || suffix in NATIVE_SUFFIXES, suffix, song.bitRate).map { cacheKey(song.id, it) }
    }

    /**
     * The form a song is opened in now: the network's own, unless the song is kept whole
     * only in the other network's form (fetched ahead, or played, on the other network). That
     * copy is played as it was fetched, and the format line says so: it is never fetched a
     * second time because the phone has moved between Wi-Fi and mobile data.
     */
    fun formToOpen(songId: String, native: Boolean, suffix: String, fileKbps: Int, keptWhole: (String) -> Boolean): StreamForm {
        val forms = formsOf(native, suffix, fileKbps)
        if (forms.size == 1) return forms[0]
        return forms.firstOrNull { keptWhole(cacheKey(songId, it)) } ?: forms[0]
    }

    /**
     * Where a song's bytes come from, as the playback service decides it when the song is
     * opened (`PlaybackService.resolve` finds what it needs: whether the phone plays the file,
     * what is kept whole, the download): its download if there is one, whatever either limit
     * says; else the server, through the kept songs, in [formToOpen]'s form, kept under that
     * form's key and asked for at that form's rate.
     */
    fun whereNow(
        songId: String,
        song: Song?,
        native: Boolean,
        keptWhole: (String) -> Boolean,
        downloaded: java.io.File?,
        downloadedKbps: Int,
        stream: (kbps: Int, offsetSecs: Int) -> String,
    ): SongWhere {
        val suffix = song?.suffix?.lowercase().orEmpty()
        val form = formToOpen(songId, native, suffix, song?.bitRate ?: 0, keptWhole)
        val least = if (form.converted && song != null) leastBytes(song.duration, form.bitrate, song.bitRate) else 0
        return SongWhere(form, cacheKey(songId, form), least, downloaded, downloadedKbps) { offset -> stream(form.bitrate, offset) }
    }

    companion object {
        /** A network a stream goes out on counts as mobile data: one Android counts as
         *  metered. With no network at all nothing is streamed, and the Wi-Fi limit is the
         *  one named. */
        fun mobile(network: NetworkNow): Boolean = network.connected && !network.unmetered

        /** The limit the settings give a stream on mobile data, or on anything else. */
        fun limitOf(settings: Settings, mobile: Boolean): Int =
            if (mobile && settings.mobileBitrate != SAME_AS_WIFI) settings.mobileBitrate else settings.bitrate
    }
}
