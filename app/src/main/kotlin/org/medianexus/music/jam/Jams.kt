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
    /** What this phone is called in the person's other devices' lists. */
    val device: String = "",
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
        sayStatus(again = false)
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
        sayStatus(again = false)
        linkDevices()
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

    // ---- hosting: this phone's own session ------------------------------------------------

    /** A session this phone hosts: a jam when friends have its link (`public`), otherwise
     *  only there for the person's other devices to steer this phone through. */
    private inner class Hosting(
        val serial: Long,
        val line: RelayLine,
        val id: String,
        val secret: String,
        var public: Boolean,
        val host: HostPlace,
    ) {
        val link = "${line.base}/j/$id"
        val answers = Answers(host.player, host.library, place.answerWords)
        var guests: List<JamGuest> = emptyList()
        var trouble = false
        var job: Job? = null

        /** What the relay was last told: a fingerprint of it, when, and where the song was. */
        var told = 0
        var toldAt = 0L
        var toldPosition = 0.0
        var toldPlaying = false

        /** What waits to go to the relay, in order; only the newest state goes. */
        val out = kotlinx.coroutines.channels.Channel<Out>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    }

    private sealed interface Out {
        data class State(val state: JsonObject) : Out
        data class Reply(val ask: Long, val outcome: Answers.Outcome) : Out
        data class Remove(val guest: String) : Out
        data object End : Out
    }

    private var hosting: Hosting? = null
    private var opening = false
    private var tried = 0L
    private var goPublic = false

    private fun hostingPublic(): Boolean = hosting?.public == true

    /** "Start a jam" (Windows' `start_jam`): this phone's session, if it has one open, becomes
     *  the jam; otherwise one is opened. */
    fun start(name: String) {
        problem = null
        place.setJamName(name)
        if (busy) return publish()
        if (joined != null) return problem(place.words.leaveFirst)
        if (!place.hostingSetUp()) return problem(place.words.hostingNotSetUp)
        val session = hosting
        if (session != null) {
            session.public = true
            sync(session, force = true)
        } else {
            busy = true
            goPublic = true
            openSession()
        }
        place.reasonsChanged()
        publish()
    }

    /** Opens this phone's session at the relay (Windows' `open_session`). */
    private fun openSession() {
        if (opening || hosting != null) return
        val host = place.host() ?: return
        opening = true
        tried = SystemClock.elapsedRealtime()
        val mine = ++serial
        val name = place.jamName()
        scope.launch(io) {
            val relay = place.relay()
            val line = relay?.let { RelayLine(it.first, http, io) }
            val started = if (relay == null || line == null) null else HostSide.start(line, relay.second, name)
            withContext(main) { sessionOpened(mine, line, started, host) }
        }
    }

    private fun sessionOpened(mine: Long, line: RelayLine?, started: HostSide.Started?, host: HostPlace) {
        if (serial != mine) return
        opening = false
        busy = false
        val public = goPublic
        goPublic = false
        if (line == null || started !is HostSide.Started.Ok) {
            // Only someone who pressed "Start a jam" is waiting to hear why not.
            if (public) problem = when (started) {
                null -> place.words.hostingNotSetUp
                HostSide.Started.KeyRefused -> place.words.relayRefusedLink
                HostSide.Started.NotUnderstood -> place.words.relayOdd
                HostSide.Started.Unreachable -> place.words.unreachable
                is HostSide.Started.Refused -> started.said ?: place.words.relayCouldntStart
                is HostSide.Started.Ok -> null
            }
            place.reasonsChanged()
            return publish()
        }
        val session = Hosting(mine, line, started.jam, started.secret, public, host)
        hosting = session
        host.hold(true)
        session.job = scope.launch(io) {
            launch { sendLoop(session) }
            // What plays here, told to the relay as it changes (and the place in the song, as
            // a guest works it out, put right after a seek or a stall).
            launch {
                host.changes.collect {
                    withContext(main) {
                        if (hosting === session) sync(session, force = false)
                        // The person's other devices list what this phone has on.
                        sayStatus(again = false)
                    }
                }
            }
            launch {
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    withContext(main) { if (hosting === session) sync(session, force = false) }
                }
            }
            val lost = HostSide.serve(
                line, started.jam, started.secret,
                news = { news -> withContext(main) { hostNews(session, news) } },
                trouble = { on -> withContext(main) { if (hosting === session) { session.trouble = on; publish() } } },
            )
            if (lost) withContext(main) { hostLost(session) }
        }
        sync(session, force = true)
        sayStatus(again = true)
        place.reasonsChanged()
        publish()
    }

    /** Sends what the main thread hands over, in order; of several states waiting, the newest. */
    private suspend fun sendLoop(session: Hosting) {
        while (true) {
            val batch = arrayListOf(session.out.receive())
            while (true) batch += session.out.tryReceive().getOrNull() ?: break
            val newest = batch.indexOfLast { it is Out.State }
            for ((i, out) in batch.withIndex()) {
                when (out) {
                    is Out.State -> if (i == newest) HostSide.state(session.line, session.id, session.secret, out.state)
                    is Out.Reply -> HostSide.reply(session.line, session.id, session.secret, out.ask, out.outcome)
                    is Out.Remove -> HostSide.removeGuest(session.line, session.id, session.secret, out.guest)
                    Out.End -> {
                        HostSide.end(session.line, session.id, session.secret)
                        return
                    }
                }
            }
        }
    }

    /** Tells the relay what's playing and queued, if that has changed since it was last told
     *  (or with `force`), as Windows' `jam_sync`. */
    private fun sync(session: Hosting, force: Boolean) {
        val steer = place.guestsSteer()
        val state = session.answers.state(steer)
        val now = session.host.player.now()
        val print = JsonObject(state - "position").toString().hashCode()
        val since = (SystemClock.elapsedRealtime() - session.toldAt) / 1000.0
        val expected = session.toldPosition + if (session.toldPlaying) since else 0.0
        val moved = now.song != null && kotlin.math.abs(now.position - expected) > 2.0
        if (!(force || print != session.told || moved || since >= RETELL_SECONDS)) return
        session.told = print
        session.toldAt = SystemClock.elapsedRealtime()
        session.toldPosition = now.position
        session.toldPlaying = now.playing
        session.out.trySend(Out.State(state))
    }

    /** Guests have come or gone, or asked for something. */
    private fun hostNews(session: Hosting, news: HostSide.News) {
        if (hosting !== session) return
        if (news.guests != session.guests) {
            session.guests = news.guests
            publish()
        }
        // Outside a jam, the only ones who come and go are the person's own devices.
        if (session.public && news.arrivals.isNotEmpty()) place.toast(place.words.joinedToast(news.arrivals))
        for ((cover, large) in news.covers) scope.launch(io) {
            val found = session.host.cover(cover, large)
            HostSide.cover(session.line, session.id, session.secret, cover, large, found?.first, found?.second ?: "")
        }
        for (ask in news.asks) answer(session, ask)
    }

    /** One thing a guest (or another of the person's devices) asked for. Looking things up
     *  happens away from the main thread; changing what plays, on it. */
    private fun answer(session: Hosting, ask: Ask) {
        val host = session.host
        val place = host.takePlace().takeIf { ask.action == "load" }
        if (ask.action == "search") {
            scope.launch(io) {
                val songs = host.library.search(ask.data.at("q").str().orEmpty())
                val found = buildJsonObject { put("songs", kotlinx.serialization.json.JsonArray(songs.map(::songJson))) }
                session.out.trySend(Out.Reply(ask.id, Answers.Outcome.Done(found)))
            }
            return
        }
        scope.launch(io) {
            val found = if (ask.action == "add") {
                val ids = session.answers.wanted(ask.data)
                val known = ids.mapNotNull(host.library::song)
                val missing = ids.filter { id -> known.none { it.id == id } }
                (known + if (missing.isEmpty()) emptyList() else host.fetchSongs(missing)).sortedBy { ids.indexOf(it.id) }
            } else {
                emptyList()
            }
            withContext(main) {
                if (hosting !== session) return@withContext
                val word = presence?.word
                val outcome = session.answers.answer(ask, word, this@Jams.place.guestsSteer(), found)
                if (outcome is Answers.Outcome.Load) {
                    // Another device hands this phone its queue: played here as a choice made
                    // there, in its place in the order of choices (rule 3).
                    scope.launch(main) {
                        val why = host.load(outcome.songs, outcome.index, outcome.positionMs, outcome.playing, outcome.context, place ?: host.takePlace())
                        session.out.trySend(Out.Reply(ask.id, if (why == null) Answers.Outcome.Done() else Answers.Outcome.No(why)))
                        sync(session, force = true)
                    }
                    return@withContext
                }
                if (ask.action == "add" && outcome is Answers.Outcome.Done && ask.guest.id !in session.answers.owners) {
                    this@Jams.place.toast(this@Jams.place.words.guestAdded(ask.guest.name, found.map { it.title }))
                }
                session.out.trySend(Out.Reply(ask.id, outcome))
                sync(session, force = false)
                if (session.answers.owners.isNotEmpty()) publish()
            }
        }
    }

    /** The relay no longer knows the session (it restarted, or the jam ran out). */
    private fun hostLost(session: Hosting) {
        if (hosting !== session) return
        val public = session.public
        endHosting()
        // A session that was only there for the person's own devices is quietly opened again.
        if (public) place.toast(place.words.lost)
        linkDevices()
    }

    /** Ends the session hosted here (Windows' `end_jam`): the relay is told, and anything still
     *  on its way for it is for nobody now. */
    fun endHosting() {
        busy = false
        opening = false
        goPublic = false
        serial++
        val session = hosting ?: return publish()
        hosting = null
        session.out.trySend(Out.End)
        // The goodbye is given a moment to go before the rest of the session's work stops.
        scope.launch(io) {
            kotlinx.coroutines.delay(1_500)
            session.job?.cancel()
        }
        session.host.hold(false)
        place.reasonsChanged()
        publish()
    }

    /** "End jam": the jam ends; a fresh private session is opened for the person's devices,
     *  so the old link is of no further use. */
    fun endJam() {
        endHosting()
        linkDevices()
    }

    /** Takes a guest out of the jam. */
    fun removeGuest(id: String) {
        val session = hosting ?: return
        session.out.trySend(Out.Remove(id))
        session.guests = session.guests.filter { it.id != id }
        publish()
    }

    /** "Guests can steer" was changed: the jam's guests are told. */
    fun guestsSteerChanged() {
        hosting?.let { sync(it, force = true) }
    }

    // ---- the person's other devices ---------------------------------------------------------

    /** This phone's place in the room where the person's devices find each other. */
    private class Presence(val serial: Long, val line: RelayLine, val key: String, val room: String, val word: String) {
        var job: Job? = null
    }

    private var presence: Presence? = null
    private var devices: List<Device> = emptyList()
    private var said = 0
    private var saidAt = 0L
    /** There's no saved password to work the room's name out from: don't keep trying. */
    private var noWords = false
    private var finding = false

    private var inFront = false
    private var playing = false
    private var stoppedAt: Long? = null
    private var timer: Job? = null

    /** The app's screens came to the front, or went. */
    fun front(on: Boolean) {
        inFront = on
        linkDevices()
    }

    /** Music started or stopped on the phone. */
    fun playingChanged(on: Boolean) {
        if (on == playing) return
        playing = on
        stoppedAt = if (on) null else SystemClock.elapsedRealtime()
        linkDevices()
        sayStatus(again = false)
    }

    /** Whether the phone has a reason to hold a connection to the relay now (rule 1). */
    private fun reason(): Boolean = Reasons.open(inFront, playing, stoppedAt, SystemClock.elapsedRealtime(), joined != null || hostingPublic())

    /**
     * Keeps this phone's place among the person's devices, or lets it go (Windows'
     * `link_devices`, called here whenever something it depends on changes rather than a few
     * times a second): in the room, with a session open that they can steer it through, and
     * saying what it is playing; but only while there is a reason to (rule 1).
     */
    fun linkDevices() {
        val wanted = place.linkingOn() && reason()
        if (!wanted) {
            if (presence != null) stopLinking()
            // A private session goes too; a jam stays as long as it runs.
            if (hosting?.public == false) endHosting()
            scheduleRecheck()
            return publish()
        }
        if (presence == null && !finding && !noWords) {
            finding = true
            val mine = ++serial
            scope.launch(io) {
                val relay = place.relay()
                val words = place.accountWords()
                withContext(main) { found(mine, relay, words) }
            }
        }
        if (hosting == null && !opening && SystemClock.elapsedRealtime() - tried >= TRY_AGAIN_MILLIS) openSession()
        sayStatus(again = false)
        scheduleRecheck()
    }

    /** Looks again when the time after a pause runs out, and every half minute meanwhile
     *  (a lost session opened again, what's said renewed). Nothing while there's no reason. */
    private fun scheduleRecheck() {
        timer?.cancel()
        val stopped = stoppedAt
        val due = when {
            presence != null || hosting != null -> RETELL_SECONDS * 1000
            stopped != null && place.linkingOn() -> (Reasons.PAUSED_GRACE_MILLIS - (SystemClock.elapsedRealtime() - stopped)).coerceAtLeast(1_000)
            else -> return
        }
        timer = scope.launch(main) {
            kotlinx.coroutines.delay(due)
            linkDevices()
        }
    }

    private fun found(mine: Long, relay: Pair<String, String>?, words: Pair<String, String>?) {
        finding = false
        if (presence != null || !place.linkingOn() || !reason()) return
        if (relay == null) return
        if (words == null) {
            noWords = true
            return
        }
        val line = RelayLine(relay.first, http, io)
        val room = Presence(mine, line, relay.second, words.first, words.second)
        presence = room
        said = 0
        saidAt = 0
        sayStatus(again = true)
        val device = place.device().first
        room.job = scope.launch(io) {
            // Give the first hello a moment to land before asking who else is there.
            kotlinx.coroutines.delay(700)
            RoomSide.watch(
                line, relay.second, words.first, device,
                seen = { body -> withContext(main) { if (presence === room) { devices = Device.list(body).filter { it.id != device }; publish() } } },
                forgot = { withContext(main) { if (presence === room) sayStatus(again = true) } },
            )
        }
        publish()
    }

    /** Tells the room what this phone is doing, if that's changed (or with `again`). */
    private fun sayStatus(again: Boolean) {
        val room = presence ?: return
        val (id, name) = place.device()
        val session = joined
        // While this phone is steering another, or in a friend's jam, it isn't itself there
        // to be steered.
        val steering = session?.let { it.device?.second ?: place.words.hostsJam(it.host) }
        val now = place.host()?.player?.now()
        val status = RoomSide.status(
            session = hosting?.id?.takeIf { steering == null },
            title = now?.song?.title,
            artist = now?.song?.artistLabel,
            playing = now?.playing == true && now.song != null,
            steering = steering,
        )
        val print = (status.toString() + name).hashCode()
        val stale = SystemClock.elapsedRealtime() - saidAt >= RETELL_SECONDS * 1000
        if (!(again || stale || print != said)) return
        said = print
        saidAt = SystemClock.elapsedRealtime()
        scope.launch(io) { RoomSide.enter(room.line, room.key, room.room, id, name, status) }
    }

    /** Leaves the room: at sign-out, when linking is switched off, and when there's no longer
     *  a reason to be there. */
    fun stopLinking() {
        devices = emptyList()
        noWords = false
        finding = false
        tried = 0
        val room = presence ?: return
        presence = null
        room.job?.cancel()
        val id = place.device().first
        scope.launch(io) { RoomSide.leave(room.line, room.key, room.room, id) }
        publish()
    }

    /**
     * A device chosen under Connect (Windows' `pick_device`): this phone starts steering it,
     * and if music is playing here it carries on over there.
     */
    fun pick(id: String) {
        if (id.isEmpty()) return playHere()
        val session = joined
        if (session?.device?.first == id) return
        if ((session != null && session.friend) || hostingPublic()) return place.toast(place.words.leaveBeforeMoving)
        val target = devices.firstOrNull { it.id == id }
        val room = presence
        val sessionThere = target?.session
        if (target == null || room == null || sessionThere == null) return place.toast(place.words.deviceNotReady)
        // Music playing here moves over there; otherwise that device is steered as it is.
        val handover = if (session == null && playing) hosting?.answers?.handOver() ?: place.host()?.let { Answers(it.player, it.library, place.answerWords).handOver() } else null
        joinSession(room.line.base, sessionThere, place.device().second, Own(room.word, target.id to target.name, handover))
    }

    /** "Play here": what the device being steered was playing carries on on this phone, and
     *  this phone stops steering it (Windows' `play_here`). */
    fun playHere() {
        val session = joined ?: return
        if (session.friend) return
        request("queue", JsonObject(emptyMap())) { answer ->
            val queue = (answer as? Asked.Done)?.data
            if (queue == null || queue.at("playing").bool() != true) return@request leave()
            val host = place.host() ?: return@request leave()
            val (songs, index) = Answers(host.player, host.library, place.answerWords).handed(queue)
            // The other device stops as this one starts.
            leaveSaying("pause" to JsonObject(emptyMap()))
            val position = ((queue.at("position").f64() ?: 0.0) * 1000).toLong()
            val context = shownText(queue.at("context").str())
            val choice = host.takePlace()
            scope.launch(main) {
                host.load(songs, index, position, true, context, choice)?.let(place::toast)
            }
        }
    }

    /** Signed out: everything goes, and nothing of it is left for the next account. */
    fun signedOut() {
        leave()
        endHosting()
        stopLinking()
        noWords = false
        publish()
    }

    /** Another account (or none) was taken on: the room is worked out afresh. */
    fun accountChanged() {
        leave()
        endHosting()
        stopLinking()
        linkDevices()
    }

    // ---- what is shown ----------------------------------------------------------------------

    /** Whether the phone is in a jam or steering another device: its connection to the relay
     *  is then held, with a notification saying so (rule 1). */
    val steeringElsewhere: Boolean get() = joined != null || hostingPublic()

    private fun problem(text: String) {
        problem = text
        publish()
    }

    /** The notification's button: leave the jam, stop steering, or end the jam hosted here. */
    fun stopFromNotification() {
        leave()
        if (hostingPublic()) endJam()
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
            hosting = hosting?.takeIf { it.public }?.let { h -> HostingUi(h.link, h.guests.filter { it.id !in h.answers.owners }, if (h.trouble) place.words.relayReconnecting else null) },
            busy = busy,
            problem = problem,
            devices = devices.map { device ->
                val (about, ready) = place.words.device(device)
                DeviceUi(device.id, device.name, about, active = session?.device?.first == device.id, ready = ready)
            },
            linking = presence != null,
            device = place.device().second,
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

        /** The relay is told what's playing at least this often, changed or not (`RETELL`). */
        const val RETELL_SECONDS = 30L

        /** How long to leave it before trying again to open this phone's session (`TRY_AGAIN`). */
        const val TRY_AGAIN_MILLIS = 30_000L
    }
}
