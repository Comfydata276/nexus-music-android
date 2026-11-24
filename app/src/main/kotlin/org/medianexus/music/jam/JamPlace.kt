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

    // ---- the person's devices, and hosting ----

    /** Someone is signed in, hosting is on, and a relay is set up (its key may still have to
     *  be opened: see [relay]). */
    fun hostingSetUp(): Boolean = canHost()

    /** "Link my devices" is on (and hosting is set up). */
    fun linkingOn(): Boolean = false

    /** The relay's address and its key, for the account signed in; null if either is missing.
     *  Opening the key takes the Keystore: called off the main thread. */
    suspend fun relay(): Pair<String, String>? = null

    /** The room's name and the word that proves a device is the person's (see
     *  [RoomSide.words]); null with no saved password. Called off the main thread. */
    suspend fun accountWords(): Pair<String, String>? = null

    /** This device's id and the name it goes by in the others' lists. */
    fun device(): Pair<String, String> = "" to ""

    /** "Guests can steer". */
    fun guestsSteer(): Boolean = true

    /** The phone's own player and library, for a session hosted here; null while nobody is
     *  signed in. */
    fun host(): HostPlace? = null

    /** The words a host answers in. */
    val answerWords: AnswerWords get() = DefaultAnswers
}

/** What a session hosted here needs of the phone. */
interface HostPlace {
    val player: HostPlayer
    val library: HostLibrary

    /** Holds the phone's own player while a session is open (`on`), or lets it go. */
    fun hold(on: Boolean) {}

    /** Plays a queue handed over (see [OwnPlayer.load]); null if it was, else why not, in words. */
    suspend fun load(songs: List<Song>, index: Int, positionMs: Long, play: Boolean, context: String, place: Long): String?

    /** A place in the order of choices, taken now. */
    fun takePlace(): Long

    /** What changes, for telling the relay: a value that is new whenever the player's state is. */
    val changes: kotlinx.coroutines.flow.Flow<Any?>

    /** A cover for guests, cut down as Windows cuts it: bytes and their kind, or null. */
    suspend fun cover(id: String, large: Boolean): Pair<ByteArray, String>? = null

    /** Songs a guest named that this library doesn't have in its copy, asked of the server. */
    suspend fun fetchSongs(ids: List<String>): List<Song> = emptyList()
}

/** Windows' words, for tests and anything without the app's resources. */
object DefaultAnswers : AnswerWords {
    override val ownOnly = "That's for your own devices."
    override val noneOfThose = "This device's library has none of those songs."
    override val hostChooses = "The host is choosing what plays. You can still add songs."
    override val notForGuests = "That isn't something a guest can do."
    override val nothingPlaying = "Nothing is playing."
    override val notInLibrary = "That song isn't in the host's library."
    override val already = "That's already in the queue."
    override val ownSongs = "You can take out the songs you added."
    override val gone = "That song is no longer in the queue."
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

    fun device(status: Device): Pair<String, Boolean> = when {
        status.steering != null -> s(R.string.jam_device_steering, "steering", status.steering) to (status.session != null)
        status.session == null -> s(R.string.jam_device_getting_ready, "getting ready") to false
        status.title.isEmpty() -> s(R.string.jam_device_nothing, "nothing playing") to true
        status.playing -> s(R.string.jam_device_playing, "playing", status.title) to true
        else -> s(R.string.jam_device_paused, "paused on", status.title) to true
    }

    val deviceNotReady get() = s(R.string.jam_device_not_ready, "not ready")
    val leaveBeforeMoving get() = s(R.string.jam_leave_before_moving, "leave first")
    val hostingNotSetUp get() = s(R.string.jam_hosting_not_set_up, "not set up")
    val relayRefusedLink get() = s(R.string.jam_relay_refused_link, "refused link")
    val relayOdd get() = s(R.string.jam_relay_answer_odd, "odd answer")
    val relayCouldntStart get() = s(R.string.jam_relay_couldnt_start, "couldn't start")
    val unreachable get() = s(R.string.jam_unreachable, "unreachable")
    val relayReconnecting get() = s(R.string.jam_relay_reconnecting, "reconnecting")
    val lost get() = s(R.string.jam_lost, "lost")
    fun joinedToast(names: List<String>) = if (names.size == 1) s(R.string.jam_joined_toast, "joined", names[0]) else s(R.string.jam_joined_many, "joined", names.size)
    fun guestAdded(guest: String, titles: List<String>) = if (titles.size == 1) s(R.string.jam_guest_added_one, "added", guest, titles[0]) else s(R.string.jam_guest_added_many, "added", guest, titles.size)

    fun with(others: List<String>): String = when (others.size) {
        0 -> s(R.string.jam_with_none, "just you")
        1 -> s(R.string.jam_with_one, "with", others[0])
        2 -> s(R.string.jam_with_two, "with", others[0], others[1])
        else -> s(R.string.jam_with_more, "with", others[0], others[1], others.size - 2)
    }
}
