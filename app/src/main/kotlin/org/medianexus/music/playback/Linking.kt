package org.medianexus.music.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.net.Song

/**
 * **Linking the person's devices, on the phone**: the half that calls the shared library
 * (`core/Core.kt`'s `link…`), on the player's thread, and makes the requests the library
 * asks for on a few threads kept for that. The contract is the Windows repository's
 * `docs/linking-design.md`, section 8.1; [LinkSide] is the half that knows the player.
 * Everything that decides is the library's.
 *
 * **Made only when linking exists** (`link/LinkBuild`): with it off there is none of this.
 *
 * **Nothing is told from the player's listeners.** Before every call into the library, what
 * the player holds is compared with what the library last knew of it ([LinkSide.known]);
 * if it differs, the library is told that something changed, before anything in that call
 * is judged. After every call it is noted again, so what the library's own commands did is
 * known, and their later echoes change none of it.
 *
 * **The person's own press and what it did are one change** ([chose]): the press is noted
 * where it is made, and said to the library, once and as the person's, at the next
 * comparison (which is before anything else is asked of the library, and at the latest as
 * soon as the press's own work on this thread is done). What that comparison finds changed
 * is the press's doing and is not said again as a change nobody chose.
 *
 * **A fault has no outside.** Everything that goes into the library and everything that
 * comes back, the comparison itself, and the start, are inside one catch ([into],
 * [start]). Whatever is thrown there is a fault: linking stops on this phone **until the
 * app's process next starts** ([faulted]: a playback service made again in the same
 * process does not start it again), everything it had is let go of by a clean-up no step
 * of which can stop another ([clean]), the person is told once in the library's words,
 * and nothing is called after it.
 *
 * **Everything asked for belongs to the sign-in and session it was asked under**
 * ([era]): a look, a stay, a send, a look-up of songs, the reading of the relay's key.
 * When that ends each is stopped, and what one still brings is for nobody: it is checked
 * against the era it began under before it is used.
 *
 * Main thread only, but for what the library calls from its own threads ([random],
 * [elapsed], [wake]) and the requests.
 */
internal class Linking(
    private val side: LinkSide,
    private val host: Host,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** What the player holds that the library is to know of ([LinkSide.known]). (Given,
     *  so that a test can have reading it fail.) */
    private val knows: () -> LinkSide.Known = side::known,
    /** The phone's player while it shows another's, and the list of the person's other
     *  devices the screens draw: the graph's. (Its own, where a test has no graph.) */
    private val steered: Steered = Steered(),
    private val linked: Linked = Linked(),
    /** Whether the phone is in a jam with other people. */
    private val inAJam: () -> Boolean = { false },
) : Core.LinkPlayer {
    /** What only the app knows. */
    interface Host {
        /** The phone's id among the person's devices (made once), and what it is called. */
        fun device(): Pair<String, String>

        /** The sign-in's name; null with nobody signed in. */
        fun account(): String?

        /** Signed in, a relay set up, and "Link my devices" on. */
        fun wanted(): Boolean

        /** The music server's sign-in, and that server's place (see `CoreHttp.linkMusic`). */
        fun server(): Pair<Core.LinkServer, String>?

        /** The relay and its host key, and the relay's place: **read as one thing, as one
         *  save put them**, never an address from one save with a key from another. Off
         *  the main thread. */
        fun relay(): Pair<Core.LinkRelay, String>?

        /** A number that is another whenever the relay or its key has been saved again.
         *  Any thread. */
        fun relayEdit(): Long

        /** The places a relay must not be at, because something else of the person's is
         *  there and would be sent the relay's key: the music server's, and Lidarr's and
         *  its sign-in's if requests are set up. Any thread. */
        fun elsewhere(): Set<String>

        /** The client the app keeps for the music server. */
        fun client(): okhttp3.OkHttpClient

        /** The devices seen before, as last kept ([remember]); an empty list for none. */
        fun remembered(): JsonElement
        fun remember(list: JsonElement)

        /** Something for the person, where they are looking. */
        fun say(text: String)

        /** The diagnostics log. */
        fun note(text: String)

        /** What the person is told if linking stops for a fault and the library's own
         *  words for it cannot be had: the phone's. */
        fun ownStoppedWords(): String

        /** Whatever is kept on disk of this playlist goes: it holds the secret. */
        fun forget(playlist: String)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val randomness = java.security.SecureRandom()

    // ---- What the screens are handed, and what they call ---------------------------------------

    private fun event(name: String) = buildJsonObject { put("event", name) }

    /** What this phone is called among the person's devices, as the library was told. */
    private var me = ""

    /**
     * The device being steered, in the phone's player. A press there is told to the
     * library, whose answer ends by saying whether it was asked of the device
     * (`{"act":"handled","by_another":…}`).
     */
    private val steer = LinkSteer(
        steered,
        side::song,
        press = { pressed -> happened(pressed)?.acts?.lastOrNull { it["act"]?.jsonPrimitive?.content == "handled" }?.get("by_another")?.jsonPrimitive?.booleanOrNull == true },
        // (Said from inside what the library answered: told once that is done with.)
        letGo = { main.post { guarded { if (started) happened(event("let_go")) } } },
        note = { host.note(it) },
    )

    /** What the list of devices calls ([Linked]). */
    private val doing = object : Linked.Doing {
        override fun choose(place: String) {
            happened(buildJsonObject {
                put("event", "choose")
                put("place", place)
                put("in_a_jam", inAJam())
            })
        }

        override fun pause(place: String) {
            happened(buildJsonObject {
                put("event", "pause")
                put("place", place)
            })
        }

        override fun playHere() {
            happened(event("play_here"))
        }

        override fun letGo() {
            happened(event("let_go"))
        }

        override fun reset() {
            happened(event("reset"))
        }

        override fun lookingUp(): Long? = happened(event("looking_up"))?.acts?.firstOrNull { it["act"]?.jsonPrimitive?.content == "ticket" }?.get("ticket")?.jsonPrimitive?.long

        override fun lookedUp(ticket: Long) {
            happened(buildJsonObject {
                put("event", "looked_up")
                put("ticket", ticket)
            })
        }
    }

    /** The number the library gave this linking: what every call into it names. */
    @Volatile private var link = 0L

    /** What the person is told if linking stops for a fault: the library's words, taken
     *  at the start, since a fault may be the library no longer answering. */
    private var stoppedWords = ""

    /** The number of the sign-in the library is at: what a copy of the library is read for. */
    @Volatile var signIn = 0L
        private set

    /** Linking has stopped for a fault, until the app next starts. */
    var stopped = false
        private set

    /** What the library last knew of the player. */
    private var known: LinkSide.Known? = null

    /** How deep in calls into the library this thread is. */
    private var inside = 0

    /** The person has pressed something here, and the library has yet to be told. */
    private var pressed = false

    /** The library is being told of the person's own press: a claim it asks for now is
     *  that press's own, which the service has made already. */
    private var pressing = false

    private var started = false

    /**
     * **What everything asked for is asked under**: moved on whenever all of it is ended
     * (the sign-in over, a fault, the service going). Whatever a request brings back to
     * this thread is used only if this is still what it began under. Main thread.
     */
    private var era = 0L

    // ---- The player, as the library calls it ------------------------------------------------

    private fun bytes(value: JsonElement): ByteArray = value.toString().encodeToByteArray()

    override fun brief(): ByteArray = bytes(side.brief()).also { insideForTests?.invoke("brief") }
    override fun full(): ByteArray = bytes(side.full())
    override fun handed(): ByteArray = bytes(side.handed())
    override fun queue(): Long = side.queueNumber().also { insideForTests?.invoke("queue") }
    override fun sounding(): ByteArray? = side.sounding()?.let(::bytes)
    override fun hasSong(): Boolean = side.hasSong()
    override fun ahead(): ByteArray = bytes(side.ahead())
    override fun toCome(): Int = side.toCome()

    override fun carryOut(asked: ByteArray): Int {
        val given = json.parseToJsonElement(asked.decodeToString()).jsonObject
        val command = given.getValue("command").jsonObject
        did += command.getValue("do").jsonPrimitive.content
        return side.carryOut(command, given.getValue("ready")).also { insideForTests?.invoke("carryOut") }
    }

    override fun pause(): Boolean = side.pause()
    override fun take(queue: ByteArray) {
        did += "take"
        side.take(json.parseToJsonElement(queue.decodeToString()).jsonObject)
    }

    /** What the library had the player do in the call under way, by name: for the
     *  diagnostics' line about a call that held the player's thread ([into]). */
    private val did = ArrayList<String>()

    override fun claim() {
        if (!pressing) side.claim()
        insideForTests?.invoke("claim")
    }

    override fun handedOn(): Boolean = side.handedOn().also { written ->
        if (!written) host.note("linking: handing the queue on could not be written down, so it is not handed on")
    }

    override fun handedBack() = side.handedBack()

    /**
     * Tests only: run inside a call into the player, once the player has answered it, with
     * the call's name. What an app's listener would do from there (say the sign-in is
     * over), or a failure of the call (it throws: a call into the player that fails is a
     * fault, and this is how one is made).
     */
    internal var insideForTests: ((String) -> Unit)? = null

    /** Tests only: an ask of the library's the test takes for itself (true), so that no
     *  request is made for it. */
    internal var asksForTests: ((JsonElement) -> Boolean)? = null

    /** Tests only: a call into the library made as this makes its own, with what goes
     *  before and after one. */
    internal fun forTests(call: (now: Long, today: Long) -> Core.LinkCame): Core.LinkCame? = into { call(clock(), today()) }

    override fun random(count: Int): ByteArray? = try {
        ByteArray(count).also(randomness::nextBytes)
    } catch (_: Exception) {
        null
    }

    override fun elapsed(): Long = clock()

    @Volatile private var woken = false

    override fun wake() {
        if (woken) return
        woken = true
        main.post(turn)
    }

    // ---- Calls into the library ----------------------------------------------------------------

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    private val turn = Runnable {
        woken = false
        into { Core.linkTurn(link, clock(), today()) }
    }

    /** The press's own work on this thread is done: it is said now, if nothing said it. */
    private val settle = Runnable { into(null) }

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            // (With nobody signed in there is no sign-in to name, and nothing to keep up:
            // the end of the last one was said as it ended.)
            val account = try {
                host.account()
            } catch (thrown: Throwable) {
                notOurs(thrown)
                return fault("who is signed in could not be read")
            }
            if (account != null) {
                // **The relay or its key saved again ends the session**: for this one tick
                // linking is not wanted, which ends the session and everything asked for
                // under it; at the next it is, and begins again with what is saved now.
                val edit = runCatching { host.relayEdit() }.getOrDefault(relayEdit)
                val edited = edit != relayEdit
                relayEdit = edit
                if (edited) host.note("linking: the relay or its key was saved again: the session ends and begins again")
                happened(buildJsonObject {
                    put("event", "tick")
                    put("account", account)
                    put("wanted", !edited && runCatching { host.wanted() }.getOrDefault(false))
                })
            }
            if (started) main.postDelayed(this, TICK_MILLIS)
        }
    }

    /**
     * Starts linking: once, on the player's thread. False, **with nothing left attached**,
     * if it could not be started, or if linking has stopped for a fault in this run of
     * the app.
     */
    fun start(): Boolean {
        if (started || stopped) return false
        if (faulted) {
            stopped = true
            host.note("linking: it stopped for a fault earlier in this run of the app, and is not started again")
            return false
        }
        try {
            // What the person is told if it stops: the phone's own words until the
            // library's are in hand, which are asked for before anything is started.
            stoppedWords = host.ownStoppedWords()
            stoppedWords = Core.linkStoppedWords()
            // (The library ties a linking to the thread that starts it.)
            check(Looper.myLooper() == main.looper) { "linking is started on the player's thread" }
            relayEdit = host.relayEdit()
            val (id, name) = host.device()
            me = name
            // The library first: until it has answered, nothing else is set up.
            val made = Core.linkNew(this, id, name, host.remembered())
            link = made.link
            signIn = made.signIn
            started = true
            known = knows()
            side.attach()
            side.beforeTake = steer::taking
            linked.doing = doing
            CoreHttp.linkMusic = { host.server()?.second }
            CoreHttp.linkRelay = { relayPlace?.takeUnless { it in host.elsewhere() } }
            CoreHttp.linkClient = { host.client() }
            org.medianexus.music.link.LinkShown.wanted = { started && runCatching { host.wanted() }.getOrDefault(false) }
            current = java.lang.ref.WeakReference(this)
            main.post(tick)
            return true
        } catch (thrown: Throwable) {
            notOurs(thrown)
            // **A start that fails is a fault like any other**: nothing is left attached
            // (the library's linking included, whose number may never have been read),
            // it is not tried again in this run of the app, and the person is told.
            stopped = true
            faulted = true
            clean(putRight = false)
            runCatching { Core.linkClose(0) }
            runCatching { host.note("linking: could not be started, and is off until the app is next started") }
            if (stoppedWords.isNotEmpty()) runCatching { host.say(stoppedWords) }
            return false
        }
    }

    /** [Host.relayEdit] as of the last tick. */
    private var relayEdit = 0L

    /** The relay's place, while a session has a stay there (the key is read off the main
     *  thread, and the place is set when it has been). */
    @Volatile private var relayPlace: String? = null

    /**
     * One call into the library (or none, for a comparison alone), with what goes before
     * and after it: the library is told first of the person's press, if there was one,
     * or else that something changed if the player is not as it last knew it; and what
     * the player holds is noted again afterwards. A call made from inside another (a
     * listener's) is only passed on. **Whatever is thrown in any of it is a fault.**
     */
    private fun into(call: (() -> Core.LinkCame)?): Core.LinkCame? {
        if (!started) return null
        if (inside > 0) return call?.let(::across)
        inside++
        did.clear()
        val began = System.nanoTime()
        try {
            val now = knows()
            if (pressed) {
                pressed = false
                known = now
                pressing = true
                try {
                    across { Core.linkHappened(link, CHANGED_HERE, clock(), today()) }
                } finally {
                    pressing = false
                }
            } else if (now != known) {
                // (Which of them: for the log, where a change nobody chose can be told from
                // one that should not have been found.)
                host.note("linking: the player is not as the library last knew it (${known?.let { LinkSide.differing(it, now) }})")
                known = now
                across { Core.linkHappened(link, CHANGED_BY_ITSELF, clock(), today()) }
            }
            val came = if (started && call != null) across(call) else null
            if (started) known = knows()
            return came
        } catch (thrown: Throwable) {
            // **Everything thrown that is linking's**, a library that is not there
            // included. (Not its words: they can quote what it was given.)
            notOurs(thrown)
            fault("a call into the shared library or the player failed")
            return null
        } finally {
            inside--
            // What a queue was before it was marked as handed on is kept for the call
            // that marked it, in case that call takes the mark back, and no longer.
            if (inside == 0) runCatching { side.callOver() }
            // (How long the player's thread was held: the longest is kept for the walk,
            // and one that would drop a frame is said in the diagnostics.)
            val took = (System.nanoTime() - began) / 1_000
            if (took > longestMicros) longestMicros = took
            if (took > SLOW_MICROS) runCatching { host.note("linking: the player's thread was held for ${took / 1_000} ms by one call into the shared library (${if (did.isEmpty()) "nothing asked of the player but what it holds" else "carried out: " + did.joinToString(", ")})") }
        }
    }

    /** The longest the player's thread has been held by one call, in microseconds, since
     *  this was last asked ([debug]). */
    private var longestMicros = 0L

    /**
     * What is thrown through linking that is not linking's to catch goes on as it would
     * without linking: a coroutine being cancelled, and the virtual machine's own errors
     * (no memory, a stack that is full). Everything else is a fault.
     */
    private fun notOurs(thrown: Throwable) {
        if (thrown is kotlin.coroutines.cancellation.CancellationException || thrown is VirtualMachineError || thrown is ThreadDeath) throw thrown
    }

    /** Something of linking's done on the player's thread outside a call into the
     *  library (what a request's thread posts back): inside the same boundary. */
    private fun guarded(what: () -> Unit) {
        try {
            what()
        } catch (thrown: Throwable) {
            notOurs(thrown)
            fault("something linking does on the player's thread failed")
        }
    }

    private fun across(call: () -> Core.LinkCame): Core.LinkCame? {
        val came = call()
        if (came.refused) {
            // (Called from inside one of its own calls with something that needs an
            // answer, or with something too large: nothing was done.)
            host.note("linking: a call into the shared library was refused")
            return null
        }
        signIn = came.signIn
        // What the library says is read here and nowhere else.
        for (act in came.acts) {
            if (!started) break
            act(act)
        }
        for (ask in came.asks) {
            if (!started) break
            ask(ask)
        }
        if (started && came.more) wake()
        return came
    }

    /** Something happened: told to the library. What it answered, for what needs it. */
    fun happened(event: JsonObject): Core.LinkCame? = into { Core.linkHappened(link, event, clock(), today()) }

    /**
     * **The person did something to the queue, or to playing it, on this phone** (the
     * service's `claimQueue`, the one place the person's ways in pass): noted here, at the
     * press, and said with what it did (see the top). Heard from inside a call into the
     * library, it is the library's own act coming round (a command from another device
     * being carried out through the same ways in), and is nothing.
     */
    fun chose() {
        if (!started || inside > 0) return
        pressed = true
        main.removeCallbacks(settle)
        main.post(settle)
    }

    /**
     * **The sign-in has ended** (a sign-out, another account, the service going): said
     * before the player is switched. Everything asked for under it is ended.
     */
    fun signInEnded() {
        if (!started) return
        pressed = false
        happened(buildJsonObject { put("event", "stop") })
        // (Nobody is signed in, so no tick will say what the list now shows: nothing.)
        steer.steering(null)
        if (linked.doing === doing) linked.nobody()
        if (started) endEverything()
    }

    /** The service is going. */
    fun close() {
        if (started) signInEnded()
        clean(putRight = false)
    }

    /**
     * Linking has stopped on this phone until the app next starts: the library will ask
     * nothing more of the player, and nothing more is asked of the library. Everything is
     * let go of, a queue left marked as handed on while the phone is the one playing is
     * the phone's again, and the person is told, once.
     */
    private fun fault(why: String) {
        if (stopped) return
        stopped = true
        faulted = true
        clean(putRight = true)
        runCatching { host.note("linking: stopped on this phone until the app is next started ($why)") }
        if (stoppedWords.isNotEmpty()) runCatching { host.say(stoppedWords) }
    }

    /**
     * Lets go of everything, at a fault and as the service goes: **no step of it can stop
     * another, and it may be run twice.**
     */
    private fun clean(putRight: Boolean) {
        started = false
        pressed = false
        runCatching {
            main.removeCallbacks(tick)
            main.removeCallbacks(turn)
            main.removeCallbacks(settle)
        }
        runCatching { endEverything() }
        if (putRight) runCatching { side.stopped() }
        runCatching { steer.steering(null) }
        runCatching { side.beforeTake = null }
        runCatching { if (linked.doing === doing) linked.over(stopped, stoppedWords) }
        runCatching { side.detach() }
        if (current.get() === this) {
            org.medianexus.music.link.LinkShown.wanted = { false }
            CoreHttp.linkMusic = { null }
            CoreHttp.linkRelay = { null }
            CoreHttp.linkClient = { null }
            current = java.lang.ref.WeakReference(null)
        }
        if (link != 0L) runCatching { Core.linkClose(link) }
        link = 0
    }

    // ---- What the library says to do -----------------------------------------------------------

    /** What the list last said, and the device being steered: for the debug walk. */
    var shown: JsonObject? = null
        private set
    var view: JsonElement? = null
        private set

    private fun act(act: JsonObject) {
        when (act.getValue("act").jsonPrimitive.content) {
            // **Each is read as what it is, or not at all**: one of these that does not
            // hold what it must is a fault, not something to pass over.
            "do" -> {
                val ask = act.getValue("ask").jsonObject
                ask["say"]?.let { host.say(it.jsonPrimitive.content) }
                ask["remember"]?.let { host.remember(it.jsonArray) }
                ask["forget"]?.let { host.forget(it.jsonObject.getValue("playlist").jsonPrimitive.content) }
                ask["steering"]?.let {
                    host.note("linking: the phone's player is " + (if (it is kotlinx.serialization.json.JsonNull) "its own again" else "another device's now"))
                    steer.steering(it.jsonPrimitive.contentOrNull)
                }
            }
            "shown" -> shown = act.getValue("shown").jsonObject.also { linked.shown(it, me) }
            "view" -> view = act.getValue("view").also(steer::view)
            "fault" -> fault("the shared library stopped, or a call into the player failed")
            else -> Unit
        }
    }

    /** One session's stay at the relay, and what is waiting to be sent under it. */
    private inner class Session(val number: Long, val relay: Core.LinkRelay, val room: String, @Volatile var since: Long) {
        private val link = this@Linking.link
        private val waiting = ArrayDeque<List<String>>()
        private var sending = false
        private var ended = false
        private var sender: CoreHttp.Doing? = null
        val stay: CoreHttp.Doing? = CoreHttp.linking("stay") { operation -> Core.linkStay(link, operation, relay, number, room) }

        init {
            if (stay == null) {
                host.note("linking: too much was already being asked for the stay at the relay to begin")
                Core.linkStayFailed(link, number)
            }
        }

        /** In the order asked, each sent once, by one worker at a time that is given back
         *  when nothing is waiting. Main thread. */
        fun send(token: String, to: String, frame: String) {
            synchronized(waiting) {
                if (ended) return
                if (waiting.size >= MOST_SENDS) return host.note("linking: more was waiting to be sent than is kept, and one thing was not sent")
                waiting += listOf(token, to, frame)
            }
            kick()
        }

        /** Sets a sender to work, if none is. Main thread. */
        private fun kick() {
            synchronized(waiting) {
                if (ended || sending || waiting.isEmpty()) return
                sending = true
            }
            sender = CoreHttp.linking("send") { operation ->
                try {
                    while (true) {
                        val next = synchronized(waiting) { if (ended) null else waiting.removeFirstOrNull() } ?: break
                        Core.linkSend(link, operation, relay, room, next[0], next[1], next[2], since, clock())
                    }
                } finally {
                    // **On every way out**: one that threw must not leave the session
                    // believing a sender is at work, with nothing sent ever again.
                    val more = synchronized(waiting) {
                        sending = false
                        !ended && waiting.isNotEmpty()
                    }
                    if (more) main.post { guarded { if (!synchronized(waiting) { ended }) kick() } }
                }
            }
            if (sender == null) {
                synchronized(waiting) {
                    sending = false
                    waiting.clear()
                }
                host.note("linking: too much was already being asked for anything to be sent, and what was waiting was not")
            }
        }

        fun end() {
            synchronized(waiting) {
                ended = true
                waiting.clear()
            }
            stay?.stop()
            sender?.stop()
        }
    }

    private var look: CoreHttp.Doing? = null
    private val sessions = HashMap<Long, Session>()

    /** Sessions whose relay key is being read: the ticket of that reading, and the reading
     *  itself, which is stopped with the session like everything else of it. */
    private val begins = HashMap<Long, Pair<Long, CoreHttp.Doing?>>()
    private var tickets = 0L
    private val finds = ArrayList<CoreHttp.Doing>()

    /** Songs a look-up on the music server brought for a command, by id: in hand before
     *  the library is told they were found. With the era they were found under: a
     *  look-up that ends after everything was ended adds nothing. */
    private val found = HashMap<String, Song>()
    private var foundEra = 0L

    /** A song a look-up brought, if one did. */
    fun foundSong(id: String): Song? = synchronized(found) { found[id] }

    private fun endEverything() {
        era++
        look?.stop()
        look = null
        begins.values.forEach { it.second?.stop() }
        begins.clear()
        sessions.values.forEach { it.end() }
        sessions.clear()
        relayPlace = null
        finds.forEach { it.stop() }
        finds.clear()
        synchronized(found) {
            foundEra = era
            found.clear()
        }
    }

    private fun ask(ask: JsonElement) {
        if (asksForTests?.invoke(ask) == true) return
        if (ask is JsonPrimitive) {
            if (ask.content == "stop_look") {
                look?.stop()
                look = null
            }
            return
        }
        val (name, with) = ask.jsonObject.entries.first()
        val link = link
        when (name) {
            "log" -> host.note("linking: ${with.jsonPrimitive.content}")
            "look" -> {
                val given = with.jsonObject
                val server = host.server()?.first ?: return
                val number = given.getValue("number").jsonPrimitive.long
                val used = (given["used"] as? JsonPrimitive)?.contentOrNull
                val reset = given["reset"]?.jsonPrimitive?.booleanOrNull == true
                val since = clock()
                look?.stop()
                look = CoreHttp.linking("look") { operation ->
                    try {
                        Core.linkLook(link, operation, server, number, used, reset, since)
                    } catch (_: Throwable) {
                        // (It could not be made at all: said as a look that failed.)
                        runCatching { Core.linkLookFailed(link, number, since, reset) }
                    }
                }
                if (look == null) {
                    host.note("linking: too much was already being asked for a look to begin")
                    Core.linkLookFailed(link, number, since, reset)
                }
            }
            "begin" -> {
                val given = with.jsonObject
                val number = given.getValue("session").jsonPrimitive.long
                val room = given.getValue("room").jsonPrimitive.content
                val since = given.getValue("since_ms").jsonPrimitive.long
                // The relay's key is read off the main thread, and the session begun here:
                // if this is still the reading that session is waiting for, under the
                // sign-in it was asked under.
                val under = era
                val ticket = ++tickets
                begins.put(number, ticket to null)?.second?.stop()
                val reading = CoreHttp.linking("relay") { _ ->
                    val relay = runCatching { host.relay() }.getOrNull()
                    val elsewhere = runCatching { host.elsewhere() }.getOrNull()
                    main.post(Runnable { guarded {
                        if (!started || era != under || begins[number]?.first != ticket) return@guarded
                        begins.remove(number)
                        when {
                            relay == null || elsewhere == null -> {
                                host.note("linking: the relay or its key could not be read")
                                Core.linkStayFailed(link, number)
                            }
                            // (Its key would go to whatever else is at that address.)
                            relay.second in elsewhere -> {
                                host.note("linking: the relay is set to the address of the music server or of Lidarr, which it cannot be: not used")
                                Core.linkStayFailed(link, number)
                            }
                            else -> {
                                relayPlace = relay.second
                                sessions.put(number, Session(number, relay.first, room, since))?.end()
                            }
                        }
                    } })
                }
                if (reading == null) {
                    begins.remove(number)
                    host.note("linking: too much was already being asked for a session to begin")
                    Core.linkStayFailed(link, number)
                } else if (begins[number]?.first == ticket) {
                    begins[number] = ticket to reading
                }
            }
            "renew" -> with.jsonObject.let { given -> sessions[given.getValue("session").jsonPrimitive.long]?.since = given.getValue("since_ms").jsonPrimitive.long }
            "end" -> {
                val number = with.jsonObject.getValue("session").jsonPrimitive.long
                begins.remove(number)?.second?.stop()
                sessions.remove(number)?.end()
                if (sessions.isEmpty()) relayPlace = null
            }
            "send" -> {
                val given = with.jsonObject
                sessions[given.getValue("session").jsonPrimitive.long]?.send(
                    given.getValue("token").jsonPrimitive.content, given.getValue("to").jsonPrimitive.content, given.getValue("frame").jsonPrimitive.content,
                )
            }
            "find" -> {
                val given = with.jsonObject
                val server = host.server()?.first ?: return
                val ticket = given.getValue("ticket").jsonPrimitive.long
                val ids = given.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
                val until = given.getValue("until_ms").jsonPrimitive.long
                val under = era
                finds.removeAll { it.isStopped }
                val find = CoreHttp.linking("find") { operation ->
                    val songs = try {
                        Core.linkFind(link, operation, server, ids, until).mapNotNull { runCatching { json.decodeFromJsonElement(Song.serializer(), it) }.getOrNull() }
                    } catch (_: Throwable) {
                        // (It could not be made at all: it found none.)
                        runCatching { Core.linkFindFailed(link, ticket) }
                        return@linking
                    }
                    // In hand first, if what it was found under has not ended; only then
                    // is the library told which were found.
                    val kept = synchronized(found) {
                        if (foundEra != under) return@linking
                        if (found.size > MOST_FOUND) found.clear()
                        songs.forEach { found[it.id] = it }
                        songs.map { it.id }
                    }
                    Core.linkFound(link, operation, ticket, kept)
                }
                if (find == null) {
                    host.note("linking: too much was already being asked for songs to be looked up")
                    Core.linkFindFailed(link, ticket)
                } else {
                    finds += find
                }
            }
            else -> Unit
        }
    }

    // ---- The phone's copy of the library ----------------------------------------------------------

    /** The phone's copy of the library, read for the sign-in whose number was [signIn] when
     *  the reading began. Any thread; **called once the app's own index of that copy is in
     *  place**, so that the library never counts as held a song the player cannot find. */
    fun library(readFor: Long, ids: List<String>) {
        val link = link
        if (link == 0L) return
        // (A linking that has ended meanwhile takes nothing, and says so by throwing.)
        runCatching { Core.linkLibrary(link, readFor, ids) }
    }

    companion object {
        const val TICK_MILLIS = 250L

        /** The most waiting to be sent under one session. */
        private const val MOST_SENDS = 256

        /** The most songs kept that look-ups brought. */
        private const val MOST_FOUND = 500

        /** A call into the library that holds the player's thread for longer than this is
         *  said in the diagnostics: a frame, at sixty a second. */
        private const val SLOW_MICROS = 16_000L

        private val CHANGED_BY_ITSELF = buildJsonObject {
            put("event", "changed")
            put("who", "nobody")
        }
        private val CHANGED_HERE = buildJsonObject {
            put("event", "changed")
            put("who", "here")
        }

        /**
         * **Linking stopped for a fault in this run of the app.** The process's, not a
         * service's: a playback service made again in the same process does not start
         * linking again. Nothing clears it but the tests.
         */
        @Volatile var faulted = false
            private set

        @androidx.annotation.VisibleForTesting
        internal fun forgetFaultForTests() {
            faulted = false
        }

        /** The one there is, if linking exists and has been started: for the debug walk. */
        @Volatile var current = java.lang.ref.WeakReference<Linking>(null)
            private set

        /**
         * What stands in for the screens, for the debug walk (`link/DebugLink`): the list,
         * a device picked, "Play here", "Stop steering", "Reset linking". On the main thread.
         */
        fun debug(what: String): String {
            val link = current.get() ?: return if (faulted) "linking: stopped for a fault in this run" else "linking: does not exist in this run"
            val words = what.trim().split(' ', limit = 2)
            val event = when (words[0]) {
                "say" -> null
                // (For measuring: a queue this long in the player, of the songs it has.)
                "fill" -> return "linking: the queue is ${link.side.fill(words.getOrNull(1)?.toIntOrNull() ?: 0)} long"
                "choose" -> buildJsonObject {
                    put("event", "choose")
                    put("place", words.getOrNull(1).orEmpty())
                    put("in_a_jam", false)
                }
                "play_here", "let_go", "reset" -> buildJsonObject { put("event", words[0]) }
                else -> return "linking: nothing is known as ${words[0]}"
            }
            val came = event?.let(link::happened)
            return buildJsonObject {
                put("sign_in", link.signIn)
                put("stopped", link.stopped)
                put("longest_ms", link.longestMicros / 1000.0)
                link.longestMicros = 0
                put("shown", link.shown ?: JsonObject(emptyMap()))
                put("view", link.view ?: JsonArray(emptyList()))
                came?.let { put("acts", JsonArray(it.acts)) }
            }.toString()
        }
    }
}

/** An empty list, for the first start. */
internal val NOTHING_REMEMBERED: JsonElement = JsonArray(emptyList())
