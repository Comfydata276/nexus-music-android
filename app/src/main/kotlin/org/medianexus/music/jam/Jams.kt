package org.medianexus.music.jam

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.medianexus.music.net.Song
import org.medianexus.music.playback.AppCommands
import org.medianexus.music.playback.Steered
import org.medianexus.music.playback.UpNext

/** What the screens show of jams and the person's devices (see [Jams]). */
data class JamUi(
    /** A friend's jam this phone is in. */
    val joined: JoinedUi? = null,
    /** Another of the person's devices being steered from here: its name. */
    val linked: String? = null,
    /** A jam this phone is hosting (friends have its link). */
    val hosting: HostingUi? = null,
    /** A jam is being joined or started. */
    val busy: Boolean = false,
    /** What went wrong with the last join or start, in words. */
    val problem: String? = null,
    /** The person's other devices, as the room lists them. */
    val devices: List<DeviceUi> = emptyList(),
    /** This phone is in the room where the person's devices find each other. */
    val linking: Boolean = false,
    /** Hosting is on and a host link is kept. */
    val canHost: Boolean = false,
    /** The name this person goes by in jams. */
    val name: String = "",
)

data class JoinedUi(
    val host: String,
    /** Who else is here, in words. */
    val with: String,
    /** "Reconnecting…", or the host's app being away; null when all is well. */
    val notice: String?,
    /** Guests may steer. */
    val control: Boolean,
    /** Songs found for what's typed in the jam's search; null when nothing is typed. */
    val results: List<Found>?,
)

/** A song found in the host's library: whether it can be added (1), is being (2), or is
 *  already waiting in the queue (3), as Windows marks it. */
data class Found(val song: Song, val state: Int)

data class HostingUi(
    val link: String,
    val guests: List<JamGuest>,
    /** Why the relay can't be reached just now; null when it can. */
    val trouble: String?,
)

data class DeviceUi(val id: String, val name: String, val about: String, val active: Boolean, val ready: Boolean)

/**
 * Jams and the person's other devices (M6), for the whole app: a port of the Windows app's
 * src/jam.rs. Everything here is changed on the main thread (`main`), as Windows does it on its
 * UI thread; the waits on the relay run beside it and hand what they hear back there.
 *
 * What it holds at most: one jam joined (a friend's, or one of the person's own devices being
 * steered), one session hosted (made a jam when friends have its link; otherwise only there for
 * the person's other devices to steer this phone through), and this phone's place in the room
 * where the person's devices find each other. When the phone has a connection to the relay at
 * all is [Reasons]' to say (rule 1).
 */
class Jams(
    private val place: JamPlace,
    private val steered: Steered,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private val uiNow = MutableStateFlow(JamUi(canHost = place.canHost(), name = place.jamName()))
    val ui: StateFlow<JamUi> = uiNow.asStateFlow()

    /** Counts jams joined or started, to tell a late answer for an old one from news of this. */
    private var serial = 0L
    private var busy = false
    private var problem: String? = null
    private var joined: Joined? = null

    /** A jam this phone is in. */
    private inner class Joined(
        val serial: Long,
        val line: RelayLine,
        val id: String,
        val token: String,
        val me: String,
        var host: String,
        /** Set when what's joined is one of the person's own devices: the word that proves
         *  this phone is theirs, and that device's id and name. */
        val owner: String?,
        val device: Pair<String, String>?,
    ) {
        var job: Job? = null
        var heard = Heard()
        var heardAt = SystemClock.elapsedRealtime()
        var trouble = false
        var results: List<JamSong>? = null
        var searches = 0L
        val adding = HashSet<String>()

        val friend: Boolean get() = owner == null

        fun position(): Double = Steering.position(heard, (SystemClock.elapsedRealtime() - heardAt) / 1000.0)
    }

    // ---- joining --------------------------------------------------------------------------

    /** Joins the jam a link leads to, under `name` (Windows' `join_jam`). */
    fun join(link: String, name: String) {
        problem = null
        if (busy) return publish()
        if (hostingPublic()) return problem(place.words.endHostingFirst)
        val read = Links.jamLink(link)
        if (read is Links.Read.Wrong) return problem(place.words.trouble(read.trouble))
        val jam = (read as Links.Read.Ok).value
        place.setJamName(name)
        joinSession(jam.address, jam.jam, place.jamName(), own = null)
    }

    private class Own(val word: String, val device: Pair<String, String>, val handover: JsonObject?)

    /** Joins a session at a relay: a friend's jam, or (with `own`) one of the person's other
     *  devices, to steer it (Windows' `join_session`). */
    private fun joinSession(address: String, id: String, name: String, own: Own?) {
        leave()
        busy = own == null
        val mine = ++serial
        publish()
        scope.launch(io) {
            val line = RelayLine(address, http, io)
            val joining = GuestSide.join(line, id, name)
            withContext(main) { joinedAt(mine, line, id, joining, own) }
        }
    }

    private fun joinedAt(mine: Long, line: RelayLine, id: String, joining: Joining, own: Own?) {
        if (serial != mine) return
        busy = false
        val inside = when (joining) {
            is Joining.In -> joining
            else -> {
                val why = place.words.joining(joining)
                if (own != null) place.toast(place.words.couldntReach(own.device.second, why)) else problem(why)
                return publish()
            }
        }
        val session = Joined(mine, line, id, inside.token, inside.me, inside.host, own?.word, own?.device)
        joined = session
        // From here the player and the queue sheet are the other side's. What was playing on
        // the phone pauses, and is still there, where it was, afterwards.
        place.pauseOwn()
        session.job = scope.launch(io) {
            val gone = GuestSide.watch(
                line, id, inside.token,
                heard = { body -> withContext(main) { heard(session, body) } },
                trouble = { on -> withContext(main) { if (joined === session) { session.trouble = on; publish() } } },
            )
            if (gone != null) withContext(main) { gone(session, gone) }
        }
        place.reasonsChanged()
        publish()
        if (own != null) {
            // Say who's asking, so the other device gives this one the run of it; and if
            // this one was playing, hand it what was playing here to carry on with.
            if (own.handover != null) simply("load", own.handover) else simply("hello", JsonObject(emptyMap()))
        }
    }

    /** Leaves the jam this phone is in, if any. The player goes back to the phone's own. */
    fun leave() = leaveSaying(null)

    /** Leaves, first asking one last thing of the other side; the two go one after the other,
     *  so the goodbye can't overtake it. */
    private fun leaveSaying(last: Pair<String, JsonObject>?) {
        val session = joined ?: return
        joined = null
        serial++
        session.job?.cancel()
        scope.launch(io) {
            if (last != null) GuestSide.ask(session.line, session.id, session.token, last.first, last.second, session.owner)
            GuestSide.leave(session.line, session.id, session.token)
        }
        place.reasonsChanged()
        publish()
    }

    private fun gone(session: Joined, why: Gone) {
        if (joined !== session) return
        leave()
        val device = session.device
        place.toast(if (device != null) place.words.deviceGone(device.second) else place.words.gone(why))
    }

    private fun heard(session: Joined, body: JsonElement) {
        if (joined !== session) return
        val heard = Heard.read(body)
        session.heard = if (heard.host.isEmpty()) heard.copy(host = session.host) else heard
        if (heard.host.isNotEmpty()) session.host = heard.host
        session.heardAt = SystemClock.elapsedRealtime()
        session.trouble = false
        publish()
    }

    /** Reads the jam again, after something the phone assumed turned out not to be so. */
    private fun refresh(session: Joined) {
        scope.launch(io) {
            val body = GuestSide.look(session.line, session.id, session.token) ?: return@launch
            withContext(main) { heard(session, body) }
        }
    }

    /**
     * Asks the other side for something, and hands its answer back on the main thread, if the
     * phone is still in the same jam by then. Asked once: with no answer, the jam is read
     * again and shown as it is, and the person does it again if they still want it (rule 6).
     */
    private fun request(action: String, data: JsonObject, done: (Asked) -> Unit) {
        val session = joined ?: return
        scope.launch(io) {
            val answer = GuestSide.ask(session.line, session.id, session.token, action, data, session.owner)
            withContext(main) {
                if (joined !== session) return@withContext
                if (answer is Asked.NoAnswer) refresh(session)
                done(answer)
            }
        }
    }

    /** Asks for something with nothing to show for it but a complaint if it fails, and a
     *  fresh look at the jam, since the player showed it as done. */
    private fun simply(action: String, data: JsonObject) {
        request(action, data) { answer ->
            when (answer) {
                is Asked.Done -> Unit
                is Asked.Refused -> {
                    place.toast(answer.said ?: place.words.didntWork)
                    joined?.let(::refresh)
                }
                Asked.NoAnswer -> place.toast(place.words.noConnection)
            }
        }
    }

    /** Something done in the player while it shows the jam (Windows' `remote`). */
    fun steer(what: Remote) {
        val session = joined ?: return
        when (val steer = Steering.steer(session.heard.state, session.position(), what, session.me, !session.friend)) {
            Steer.Here, Steer.Already -> return
            Steer.NotAllowed -> return place.toast(place.words.hostChooses(session.host))
            is Steer.Ask -> {
                // The player shows what was asked for straight away; the other side's word
                // on it follows a moment later.
                session.heard = session.heard.copy(state = steer.shown, age = 0.0)
                session.heardAt = SystemClock.elapsedRealtime()
                publish()
                simply(steer.action, steer.data)
            }
        }
    }

    /** Songs queued anywhere in the app while the player shows another (Windows' `remote_add`). */
    fun add(songs: List<Song>, playNext: Boolean) {
        val session = joined ?: return
        val where = session.device?.let { place.words.queueOn(it.second) } ?: place.words.jamsQueue
        val ids = songs.asSequence().filter { it.radioUrl.isEmpty() && plainId(it.id) }.map { it.id }.take(MOST_ADDED).toList()
        if (ids.isEmpty()) return place.toast(place.words.noStations)
        val cut = songs.size > ids.size
        request("add", buildJsonObject { put("ids", idsJson(ids)); put("next", playNext) }) { answer ->
            when (answer) {
                is Asked.Done -> {
                    val count = answer.data.at("added").u64()?.toInt() ?: 1
                    place.toast(place.words.added(count, cut, where))
                }
                is Asked.Refused -> place.toast(answer.said ?: place.words.didntWork)
                Asked.NoAnswer -> place.toast(place.words.noConnection)
            }
        }
    }

    /**
     * A list played anywhere in the app while the player shows another (Windows'
     * `remote_play`): on one of the person's own devices it plays there as it would here, in
     * place of its queue; in a friend's jam the song picked, or the whole list when the list
     * itself was played, is queued.
     */
    fun play(songs: List<Song>, start: Int, context: String, whole: Boolean) {
        val session = joined ?: return
        if (session.friend) {
            val picked = if (whole) songs.take(MOST_ADDED) else listOfNotNull(songs.getOrNull(start))
            return add(picked, playNext = false)
        }
        // A very long list is handed over from the song picked onwards.
        val from = if (songs.size > MOST_HANDED) start else 0
        val handed = buildList { for (i in from until minOf(songs.size, from + MOST_HANDED)) add(buildJsonObject { put("id", songs[i].id) }) }
        simply("load", buildJsonObject {
            put("songs", kotlinx.serialization.json.JsonArray(handed))
            put("index", start - from)
            put("position", 0.0)
            put("playing", true)
            put("context", context)
        })
    }

    /** Something typed in the jam's search: looked up in the host's library once typing
     *  pauses (the screen waits the quarter second). */
    fun search(text: String) {
        val session = joined ?: return
        val query = text.trim()
        val count = ++session.searches
        if (query.isEmpty()) {
            session.results = null
            return publish()
        }
        request("search", buildJsonObject { put("q", query.take(100)) }) { answer ->
            if (session.searches != count) return@request
            when (answer) {
                is Asked.Done -> {
                    session.results = answer.data.at("songs").list().asSequence().take(FOUND).map(JamSong::read).filter { it.id.isNotEmpty() }.toList()
                    publish()
                }
                is Asked.Refused -> place.toast(answer.said ?: place.words.didntWork)
                Asked.NoAnswer -> place.toast(place.words.noConnection)
            }
        }
    }

    /** The + beside a song found in the host's library. */
    fun addFound(id: String) {
        val session = joined ?: return
        if (!plainId(id) || !session.adding.add(id)) return
        publish()
        request("add", buildJsonObject { put("id", id) }) { answer ->
            session.adding.remove(id)
            publish()
            when (answer) {
                is Asked.Done -> place.toast(place.words.addedToJam)
                is Asked.Refused -> place.toast(answer.said ?: place.words.didntWork)
                Asked.NoAnswer -> place.toast(place.words.noConnection)
            }
        }
    }

    // ---- hosting (placeholders until the host's side is built) -----------------------------

    private fun hostingPublic(): Boolean = false

    /** "Start a jam". */
    fun start(name: String) {
        problem = null
        place.setJamName(name)
        if (joined != null) return problem(place.words.leaveFirst)
        publish()
    }

    /** A device chosen under Connect. */
    fun pick(id: String) {
    }

    /** "Play here": what the device being steered was playing carries on on this phone. */
    fun playHere() {
        leave()
    }

    // ---- what is shown ----------------------------------------------------------------------

    /** Whether the phone is in a jam or steering another device: its connection to the relay
     *  is then held, with a notification saying so (rule 1). */
    val steeringElsewhere: Boolean get() = joined != null

    private fun problem(text: String) {
        problem = text
        publish()
    }

    /** The notification's button: leave the jam, stop steering, or end the jam hosted here. */
    fun stopFromNotification() {
        leave()
        place.reasonsChanged()
    }

    /** Clears what was said about the last join or start (the jam page opened afresh). */
    fun opened() {
        problem = null
        publish()
    }

    private fun publish() {
        val session = joined
        uiNow.value = JamUi(
            joined = session?.takeIf { it.friend }?.let(::joinedUi),
            linked = session?.device?.second,
            hosting = null,
            busy = busy,
            problem = problem,
            canHost = place.canHost(),
            name = place.jamName(),
        )
        showPlayer(session)
        place.shown(uiNow.value)
    }

    private fun joinedUi(session: Joined): JoinedUi {
        val heard = session.heard
        val others = heard.guests.filter { it.id != session.me }.map { it.name }
        val state = heard.state
        val coming = HashSet<String>().apply {
            state.queue.filter { it.queued }.forEach { add(it.id) }
            state.now?.let { add(it.id) }
        }
        return JoinedUi(
            host = session.host,
            with = place.words.with(others),
            notice = when {
                session.trouble -> place.words.reconnectingJam
                heard.away -> place.words.hostAway(session.host)
                else -> null
            },
            control = state.control,
            results = session.results?.map { found ->
                Found(place.rowOf(found, session.line.base, session.id), when (found.id) { in session.adding -> 2; in coming -> 3; else -> 1 })
            },
        )
    }

    /** Puts the jam in the phone's player in place of its own, or takes it out. */
    private fun showPlayer(session: Joined?) {
        if (session == null) return steered.clear()
        val heard = session.heard
        val state = heard.state
        val rows = state.queue.map { place.rowOf(it, session.line.base, session.id) }
        val view = Steered.Shown(
            song = state.now?.let { place.rowOf(it, session.line.base, session.id) },
            playing = state.playing && !heard.away,
            positionMs = (session.position() * 1000).toLong(),
            at = SystemClock.elapsedRealtime(),
            durationMs = (state.now?.duration ?: 0) * 1000L,
            queue = UpNext(
                songs = rows,
                queuedAhead = state.queuedAhead,
                context = state.context,
                total = rows.size + state.more,
                // A row is named by its place among those shown: the numbers are only
                // places, made anew with every state heard.
                uids = List(rows.size) { ROW + it },
                by = state.queue.map { song -> if (song.by.isEmpty()) "" else if (song.byId == session.me) place.words.you else song.by },
            ),
            shuffle = state.shuffle,
            repeat = state.repeat,
            volume = if (session.friend) null else state.volume?.toFloat(),
            where = session.device?.second ?: place.words.hostsJam(session.host),
        )
        steered.show(view, hands)
    }

    /** The player's buttons while it shows the jam. */
    private val hands = object : Steered.Hands {
        override fun toggle() = steer(Remote.Toggle)
        override fun next() = steer(Remote.Next)
        override fun previous() = steer(Remote.Previous)
        override fun seek(positionMs: Long) = steer(Remote.Seek(positionMs / 1000.0))
        override fun remove(uid: Long) { place(uid)?.let { steer(Remote.Remove(it)) } }
        override fun move(uid: Long, after: Long?, queued: Boolean) {
            val from = place(uid) ?: return
            // "Straight after `after`" as a place in the queue once the row has left its own.
            val to = if (after == null || after == AppCommands.NONE) 0 else (place(after) ?: return).let { if (it < from) it + 1 else it }
            steer(Remote.Move(from, to, queued))
        }
        override fun clear(queued: Boolean) = steer(Remote.Clear(queued))
        override fun jump(uid: Long) { place(uid)?.let { steer(Remote.Jump(it)) } }
        override fun shuffle() = steer(Remote.Shuffle)
        override fun repeat() = steer(Remote.Repeat)
        override fun volume(to: Float) = steer(Remote.Volume(to.toDouble().coerceIn(0.0, 1.0)))
        override fun add(songs: List<Song>, playNext: Boolean) = this@Jams.add(songs, playNext)
        override fun play(songs: List<Song>, start: Int, context: String, whole: Boolean) = this@Jams.play(songs, start, context, whole)

        private fun place(uid: Long): Int? = (uid - ROW).takeIf { it in 0 until SHOWN }?.toInt()
    }

    companion object {
        /** Where the numbers of a jam's rows begin: far from any entry of the phone's own. */
        const val ROW = Long.MIN_VALUE / 2
    }
}
