package org.medianexus.music.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
 * unless chosen). Mobile data is a network Android counts as metered (a VPN over mobile data
 * is, as the network under it is). With "Same as Wi-Fi" there is one limit, and every answer
 * here is what it was before there were two: nothing below about networks is even asked.
 *
 * **In doubt, the lower** (the rule that settles the races between Android's word on the
 * network and where a request really goes). The higher of the two forms is asked of the
 * server only when the app is sure the request goes out on a network that isn't metered:
 * Android's last word ([network], from its callbacks) and a look at the network this instant
 * ([route]) both say so, and the request is then **bound to that very network**
 * ([Choice.route]): if it has gone, or the default route has moved, by the time the
 * connection is made, the request fails rather than going out over mobile data, and nothing
 * of the song having arrived, it is chosen again. Anything else (a late callback, a network
 * coming or going, no network yet, the two looks disagreeing) is the lower of the two limits.
 */
class StreamQuality(
    private val settings: StateFlow<Settings>,
    private val network: StateFlow<NetworkNow> = MutableStateFlow(NetworkNow()),
    /** The phone's default network this instant, asked of Android there and then: null if
     *  there is none or it can't be had. Asked only when the two limits differ. */
    private val route: () -> Route? = { null },
    /** How long the network must have held before fetching ahead is started again in its
     *  form (see [movedWithNetwork]). */
    private val settleMillis: Long = SETTLE_MILLIS,
) {
    /** A network as Android describes it this instant: `handle` is the `android.net.Network`
     *  a request can be bound to. */
    data class Route(val handle: Any, val unmetered: Boolean)

    /** What a song is opened in: the form, the network a request for it must go out on
     *  (null: any), and whether it is a copy kept whole in the other network's form, to be
     *  read from the phone and never asked of the server until it has begun to play. */
    data class Choice(val form: StreamForm, val route: Any? = null, val keptOnly: Boolean = false)

    /** Where the phone is, as far as Android's last word goes. */
    enum class Net { WIFI, MOBILE, UNSURE }

    /** A stream opened now goes out over mobile data, as far as Android's last word goes. */
    fun onMobileData(): Boolean = netOf(network.value) == Net.MOBILE

    /** The limit for a stream opened now, by Android's last word on the network (the lower
     *  of the two with no network): what the format line says before the player has opened
     *  the song. */
    fun limit(): Int = limitFor(settings.value, netOf(network.value))

    /** [limit] as it changes, with the settings or the network. */
    val limits: Flow<Int> = combine(settings, network) { chosen, net -> limitFor(chosen, netOf(net)) }.distinctUntilChanged()

    /** The limit for a stream opened now has changed **because a setting was changed**,
     *  never because the network did: the song playing is opened again in the new form (as
     *  on Windows), whereas a song playing when the network changes plays on as it began.
     *  With "Same as Wi-Fi" this is exactly a change of the streaming quality. */
    val chosenAgain: Flow<Int> = flow {
        var before: Settings? = null
        settings.collect { now ->
            val was = before
            before = now
            val net = netOf(network.value)
            if (was != null && limitFor(was, net) != limitFor(now, net)) emit(limitFor(now, net))
        }
    }

    /**
     * The limit for a stream opened now has changed **because the network did** (the
     * settings as they are), and the new network has **held for [settleMillis]**: a network
     * that flaps (Wi-Fi at the edge of its reach) starts nothing again until it has settled,
     * and one that goes and comes back within that time changes nothing at all. Never with
     * "Same as Wi-Fi".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val movedWithNetwork: Flow<Int> = flow {
        var before: Net? = null
        network.map(::netOf).distinctUntilChanged().mapLatest { net ->
            // A newer network cancels this wait (mapLatest): only one that has held is told.
            delay(settleMillis)
            net
        }.collect { net ->
            val was = before
            before = net
            val chosen = settings.value
            if (was != null && limitFor(chosen, was) != limitFor(chosen, net)) emit(limitFor(chosen, net))
        }
    }

    /** The form a song is asked of the server in now, if nothing of it is kept (as
     *  [choose] decides it). `native`: the phone plays its file as it is (by its kind, and not
     *  known to fail on this phone). */
    fun formOf(native: Boolean, suffix: String, fileKbps: Int): StreamForm = choose("", native, suffix, fileKbps) { false }.form

    /** The form a song would be streamed in now, by its kind alone and Android's last word
     *  on the network: for the format line and for asking whether it is kept, with no
     *  player. */
    fun formOf(song: Song): StreamForm = streamFormOf(song, limit())

    /** The key the song is kept under in the form it would be streamed in now. */
    fun keyOf(song: Song): String = cacheKey(song.id, formOf(song))

    /** The two limits differ (a choice is made on mobile data, and it isn't the same as the
     *  Wi-Fi's). */
    fun twoLimits(): Boolean = settings.value.let { limitFor(it, Net.WIFI) != limitFor(it, Net.MOBILE) }

    /**
     * The keys a song can be played from with no server: kept whole under one of them, it
     * plays offline and is not fetched ahead. With one limit, the key of the form it would be
     * streamed in by its kind, as it always was. With two, the forms of both limits, worked
     * out as the player would open them: `decodes` false (this phone has been found not to
     * play the file as it is, or must have it converted) leaves the file as it is out.
     */
    fun keptKeysOf(song: Song, decodes: Boolean = true): List<String> {
        val suffix = song.suffix.lowercase()
        val byKind = suffix.isEmpty() || suffix in NATIVE_SUFFIXES
        if (!twoLimits()) return listOf(cacheKey(song.id, streamForm(byKind, limit(), suffix, song.bitRate)))
        val native = byKind && decodes
        val chosen = settings.value
        return listOf(Net.WIFI, Net.MOBILE).map { streamForm(native, limitFor(chosen, it), suffix, song.bitRate) }.distinct().map { cacheKey(song.id, it) }
    }

    /**
     * What a song is opened in now. With one limit, its form, as always. With two:
     *
     * - the form for the network the request will go out on: the Wi-Fi's only when sure of
     *   an unmetered network, and then bound to it; mobile data's when sure of mobile data;
     *   the lower of the two otherwise;
     * - unless it is kept whole only in the other form (fetched ahead, or played, on the
     *   other network): that copy, read from the phone ([Choice.keptOnly]), not fetched a
     *   second time.
     *
     * `keptWhole` says whether a key is kept whole and can be read now (false with no kept
     * songs to read from).
     */
    fun choose(songId: String, native: Boolean, suffix: String, fileKbps: Int, keptWhole: (String) -> Boolean): Choice {
        val chosen = settings.value
        val wifi = streamForm(native, limitFor(chosen, Net.WIFI), suffix, fileKbps)
        val mobile = streamForm(native, limitFor(chosen, Net.MOBILE), suffix, fileKbps)
        // One form for this song (one limit, or two that come to the same file): no question
        // of networks at all.
        if (wifi == mobile) return Choice(wifi)
        val lower = streamForm(native, limitFor(chosen, Net.UNSURE), suffix, fileKbps)
        val sure = sureOf()
        val own = when {
            sure is Net && sure == Net.MOBILE -> mobile
            sure is Route -> wifi
            else -> lower
        }
        val other = if (own == wifi) mobile else wifi
        val pick = when {
            keptWhole(cacheKey(songId, own)) -> own
            keptWhole(cacheKey(songId, other)) -> return Choice(other, keptOnly = true)
            else -> own
        }
        // A form above mobile data's goes out only on the network it was chosen for.
        return Choice(pick, route = if (pick != mobile && sure is Route) sure.handle else null)
    }

    /** Sure of an unmetered network: the network to bind to. Sure of mobile data:
     *  [Net.MOBILE]. Otherwise null. */
    private fun sureOf(): Any? {
        val said = netOf(network.value)
        if (said == Net.UNSURE) return null
        val now = runCatching(route).getOrNull() ?: return null
        return when {
            said == Net.WIFI && now.unmetered -> now
            said == Net.MOBILE && !now.unmetered -> Net.MOBILE
            else -> null
        }
    }

    /**
     * Where a song's bytes come from, as the playback service decides it when the song is
     * opened (`PlaybackService.resolve` finds what it needs: whether the phone plays the file,
     * what is kept whole, the download, and what this queue entry has already played in):
     * its download if there is one, whatever either limit says; else, **for an entry that
     * has begun to play, the form it began in** (`pinned`), on any network; else [choose].
     */
    fun whereNow(
        songId: String,
        song: Song?,
        native: Boolean,
        keptWhole: (String) -> Boolean,
        downloaded: java.io.File?,
        downloadedKbps: Int,
        pinned: StreamForm? = null,
        stream: (kbps: Int, offsetSecs: Int) -> String,
    ): SongWhere {
        val suffix = song?.suffix?.lowercase().orEmpty()
        val choice = if (pinned != null) Choice(pinned) else choose(songId, native, suffix, song?.bitRate ?: 0, keptWhole)
        val form = choice.form
        val least = if (form.converted && song != null) leastBytes(song.duration, form.bitrate, song.bitRate) else 0
        return SongWhere(form, cacheKey(songId, form), least, downloaded, downloadedKbps, route = choice.route, keptOnly = choice.keptOnly) { offset -> stream(form.bitrate, offset) }
    }

    companion object {
        const val SETTLE_MILLIS = 5_000L

        fun netOf(network: NetworkNow): Net = when {
            !network.connected -> Net.UNSURE
            network.unmetered -> Net.WIFI
            else -> Net.MOBILE
        }

        /** A network a stream goes out on counts as mobile data: one Android counts as
         *  metered. */
        fun mobile(network: NetworkNow): Boolean = netOf(network) == Net.MOBILE

        /** The limit the settings give a stream on that network: the lower of the two when
         *  it isn't known. (0 is no limit, the highest.) */
        fun limitFor(settings: Settings, net: Net): Int {
            val wifi = settings.bitrate
            val mobile = if (settings.mobileBitrate == SAME_AS_WIFI) wifi else settings.mobileBitrate
            return when (net) {
                Net.WIFI -> wifi
                Net.MOBILE -> mobile
                Net.UNSURE -> when {
                    wifi == 0 -> mobile
                    mobile == 0 -> wifi
                    else -> minOf(wifi, mobile)
                }
            }
        }
    }
}
