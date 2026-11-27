package org.medianexus.music.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
 *
 * **Two limits**: Settings' streaming quality, and the one "On mobile data" (Same as Wi-Fi
 * unless chosen). With "Same as Wi-Fi" there is one limit, every answer here is what it was
 * before there were two, and the network is not even looked at.
 *
 * **The lower limit is never exceeded over mobile data, and in doubt, the lower.** The Wi-Fi's
 * limit applies only when the app is sure the stream goes out on a network Android doesn't
 * count as metered (a VPN over mobile data is metered, as the network under it is): Android's
 * last word on the network ([network], from its callbacks) and a look at the default network
 * this instant ([route]) both say so. Anything else (mobile data, a late callback, a network
 * coming or going, no network) is the lower of the two limits. And a form above the lower
 * limit is asked of the server only while that is still so ([mayAsk]): the server is asked
 * nothing in it otherwise, and the read fails for the entry to be made ready again, in the
 * form chosen then (see `SongDataSource`).
 */
class StreamQuality(
    private val settings: StateFlow<Settings>,
    private val network: StateFlow<NetworkNow> = MutableStateFlow(NetworkNow()),
    /** Whether the phone's default network is unmetered this instant, asked of Android there
     *  and then; null if there is none or it can't be had. Asked only when two limits make
     *  two forms of a song. */
    private val unmeteredNow: () -> Boolean? = { null },
) {
    /** Where the phone is, as far as Android's last word goes. */
    enum class Net { WIFI, MOBILE, UNSURE }

    /** A stream opened now goes out over mobile data, as far as Android's last word goes. */
    fun onMobileData(): Boolean = netOf(network.value) == Net.MOBILE

    /** Sure that a stream asked for now goes out on an unmetered network: Android's last
     *  word and a look this instant agree. */
    fun sureUnmetered(): Boolean = netOf(network.value) == Net.WIFI && runCatching(unmeteredNow).getOrNull() == true

    /** The limit for a stream opened now, by Android's last word on the network: what the
     *  format line says before the player has opened the song. */
    fun limit(): Int = limitFor(settings.value, netOf(network.value))

    /** [limit] as it changes, with the settings or the network. */
    val limits: Flow<Int> = combine(settings, network) { chosen, net -> limitFor(chosen, netOf(net)) }.distinctUntilChanged()

    /** A setting has changed what either limit comes to: the song playing is opened again and
     *  chosen afresh (as on Windows). With "Same as Wi-Fi", exactly a change of the streaming
     *  quality. Never the network: a song playing when it changes plays on as it began. */
    val chosenAgain: Flow<Unit> = settings.map { limitFor(it, Net.WIFI) to limitFor(it, Net.MOBILE) }.distinctUntilChanged().drop(1).map { }

    /** The form a song is asked of the server in now, if nothing of it is kept. `native`: the
     *  phone plays its file as it is (by its kind, and not known to fail on this phone). */
    fun formOf(native: Boolean, suffix: String, fileKbps: Int): StreamForm = choose("", native, suffix, fileKbps, { false })

    /** The form a song would be streamed in now, by its kind alone and Android's last word
     *  on the network: for the format line and for asking whether it is kept, with no player. */
    fun formOf(song: Song): StreamForm = streamFormOf(song, limit())

    /** The key the song is kept under in the form it would be streamed in now. */
    fun keyOf(song: Song): String = cacheKey(song.id, formOf(song))

    /** The two limits differ. */
    fun twoLimits(): Boolean = settings.value.let { limitFor(it, Net.WIFI) != limitFor(it, Net.MOBILE) }

    /**
     * The keys under which a song kept whole plays with no server, as the player would open
     * it: the form for each limit, `native` saying whether this phone plays the file as it is.
     * One key with one limit.
     */
    fun keptKeysOf(song: Song, native: Boolean): List<String> {
        val suffix = song.suffix.lowercase()
        val chosen = settings.value
        return listOf(Net.WIFI, Net.MOBILE).map { streamForm(native, limitFor(chosen, it), suffix, song.bitRate) }.distinct().map { cacheKey(song.id, it) }
    }

    /**
     * The form a song is opened in now: its form, with one limit. With two that make two
     * forms of it: the Wi-Fi's only when [sureUnmetered], else the lower limit's; unless it is
     * kept whole only in the other form (fetched ahead, or played, on the other network), when
     * that copy is played from the phone as it was fetched. `keptWhole` says whether a key is
     * kept whole and can be read now. `lowerOnly`: the lower limit's form, whatever the
     * network says (an entry made ready again after a read of it failed as "moved", which so
     * can't fail so again).
     */
    fun choose(songId: String, native: Boolean, suffix: String, fileKbps: Int, keptWhole: (String) -> Boolean, lowerOnly: Boolean = false): StreamForm {
        val chosen = settings.value
        val wifi = streamForm(native, limitFor(chosen, Net.WIFI), suffix, fileKbps)
        val lower = streamForm(native, limitFor(chosen, Net.MOBILE), suffix, fileKbps)
        if (wifi == lower || lowerOnly) return lower
        val own = if (sureUnmetered()) wifi else lower
        val other = if (own == wifi) lower else wifi
        return if (!keptWhole(cacheKey(songId, own)) && keptWhole(cacheKey(songId, other))) other else own
    }

    /**
     * Whether the server may be asked for `form` of a song now: always with one limit (as
     * released); with two, the lower limit's form always, and any other only while
     * [sureUnmetered]. Asked at every request a read makes, and once more as its first bytes
     * arrive.
     */
    fun mayAsk(form: StreamForm, native: Boolean, suffix: String, fileKbps: Int): Boolean {
        if (!twoLimits()) return true
        if (form == streamForm(native, limitFor(settings.value, Net.MOBILE), suffix, fileKbps)) return true
        return sureUnmetered()
    }

    /**
     * Where a song's bytes come from, as the playback service decides it when the song is
     * opened (`PlaybackService.resolve` finds what it needs: whether the phone plays the file,
     * what is kept whole, the download): its download if there is one, whatever either limit
     * says; else [choose]'s form, kept under that form's key, asked for at that form's rate,
     * and only while [mayAsk].
     */
    fun whereNow(
        songId: String,
        song: Song?,
        native: Boolean,
        keptWhole: (String) -> Boolean,
        downloaded: java.io.File?,
        downloadedKbps: Int,
        lowerOnly: Boolean = false,
        stream: (kbps: Int, offsetSecs: Int) -> String,
    ): SongWhere {
        val suffix = song?.suffix?.lowercase().orEmpty()
        val fileKbps = song?.bitRate ?: 0
        val form = choose(songId, native, suffix, fileKbps, keptWhole, lowerOnly)
        val least = if (form.converted && song != null) leastBytes(song.duration, form.bitrate, song.bitRate) else 0
        return SongWhere(form, cacheKey(songId, form), least, downloaded, downloadedKbps, mayAsk = { mayAsk(form, native, suffix, fileKbps) }) { offset -> stream(form.bitrate, offset) }
    }

    companion object {
        fun netOf(network: NetworkNow): Net = when {
            !network.connected -> Net.UNSURE
            network.unmetered -> Net.WIFI
            else -> Net.MOBILE
        }

        /** A network a stream goes out on counts as mobile data: one Android counts as
         *  metered. */
        fun mobile(network: NetworkNow): Boolean = netOf(network) == Net.MOBILE

        /** The limit the settings give a stream: the Wi-Fi's on an unmetered network; on
         *  mobile data, or not knowing, the lower of the two (0 is no limit, the highest). */
        fun limitFor(settings: Settings, net: Net): Int {
            val wifi = settings.bitrate
            if (net == Net.WIFI) return wifi
            val mobile = if (settings.mobileBitrate == SAME_AS_WIFI) wifi else settings.mobileBitrate
            return when {
                wifi == 0 -> mobile
                mobile == 0 -> wifi
                else -> minOf(wifi, mobile)
            }
        }
    }
}
