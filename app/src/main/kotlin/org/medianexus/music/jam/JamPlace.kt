package org.medianexus.music.jam

import android.content.res.Resources
import org.medianexus.music.R
import org.medianexus.music.net.Song

/** What [Jams] needs of the rest of the app, handed in so that it can be tested alone. */
interface JamPlace {
    val words: JamWords
    fun toast(text: String)

    /** Pauses this phone's own player (joining a jam: it is still there afterwards). */
    fun pauseOwn()

    /** The name this person goes by in jams: the one they chose, or else their account's. */
    fun jamName(): String

    /** Keeps the name typed in the jam page, if it's a new one. */
    fun setJamName(typed: String)

    /** Hosting is on and a host link is kept. */
    fun canHost(): Boolean

    /**
     * A song of a jam as a row for the phone's player: a song this phone's library has is
     * shown as it is anywhere else in the app (it can be liked, its artist opened); one it
     * doesn't is shown from what the host said of it, its cover from the relay
     * (`Steered.FOREIGN`).
     */
    fun rowOf(song: JamSong, relay: String, jam: String): Song

    /** Whether the phone holds a connection to the relay, and its notification, may have
     *  changed (see [Reasons]). */
    fun reasonsChanged()

    /** The screens' state changed (the notification follows it). */
    fun shown(ui: JamUi) {}
}

/** The words [Jams] says things in, from the app's resources. */
open class JamWords(private val res: Resources?) {
    private fun s(id: Int, fallback: String, vararg args: Any): String =
        res?.getString(id, *args) ?: if (args.isEmpty()) fallback else "$fallback ${args.joinToString(" ")}"

    open fun trouble(trouble: LinkTrouble): String = when (trouble) {
        LinkTrouble.NOT_A_LINK -> s(R.string.jam_link_not_a_link, "not a link")
        LinkTrouble.PLAIN_HTTP -> s(R.string.jam_link_plain_http, "plain http")
        LinkTrouble.NO_KEY -> s(R.string.jam_link_no_key, "no key")
        LinkTrouble.SHORT_KEY -> s(R.string.jam_link_short_key, "short key")
        LinkTrouble.NOT_A_JAM -> s(R.string.jam_link_not_a_jam, "not a jam link")
    }

    fun joining(joining: Joining): String = when (joining) {
        is Joining.In, Joining.NotAJam -> s(R.string.jam_not_a_jam, "not a jam")
        Joining.Ended -> s(R.string.jam_ended_ask, "ended")
        is Joining.Full -> joining.said ?: s(R.string.jam_full, "full")
        Joining.Unreachable -> s(R.string.jam_unreachable, "unreachable")
    }

    fun gone(why: Gone): String = when (why) {
        Gone.ENDED -> s(R.string.jam_gone_ended, "ended")
        Gone.REMOVED -> s(R.string.jam_gone_removed, "removed")
        Gone.RESTARTED -> s(R.string.jam_gone_restarted, "restarted")
    }

    fun couldntReach(device: String, why: String) = s(R.string.jam_couldnt_reach, "couldn't reach", device, why)
    fun deviceGone(device: String) = s(R.string.jam_device_gone, "device gone", device)
    val endHostingFirst get() = s(R.string.jam_end_hosting_first, "end hosting first")
    val leaveFirst get() = s(R.string.jam_leave_first, "leave first")
    val reconnectingJam get() = s(R.string.jam_reconnecting, "reconnecting")
    fun hostAway(host: String) = s(R.string.jam_host_away, "away", host)
    fun hostChooses(host: String) = s(R.string.jam_host_chooses, "host chooses", host)
    val didntWork get() = s(R.string.jam_didnt_work, "didn't work")
    val noConnection get() = s(R.string.jam_no_connection, "no connection")
    val noStations get() = s(R.string.jam_no_stations, "no stations")
    val jamsQueue get() = s(R.string.jam_the_jams_queue, "the jam's queue")
    fun queueOn(device: String) = s(R.string.jam_queue_on, "the queue on", device)
    val addedToJam get() = s(R.string.jam_added_to_jam, "added to the jam")
    val you get() = s(R.string.jam_you, "you")
    fun hostsJam(host: String) = s(R.string.jam_hosts_jam, "jam of", host)

    fun added(count: Int, cut: Boolean, where: String): String = when {
        count == 1 -> s(R.string.jam_added_to, "added to", where)
        cut -> s(R.string.jam_added_first, "added first", count, where)
        else -> s(R.string.jam_added_count, "added", count, where)
    }

    fun with(others: List<String>): String = when (others.size) {
        0 -> s(R.string.jam_with_none, "just you")
        1 -> s(R.string.jam_with_one, "with", others[0])
        2 -> s(R.string.jam_with_two, "with", others[0], others[1])
        else -> s(R.string.jam_with_more, "with", others[0], others[1], others.size - 2)
    }
}
