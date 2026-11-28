package org.medianexus.music.nexus

import java.security.SecureRandom
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.internal.concurrent.TaskRunner
import org.medianexus.music.core.Core

/**
 * **The phone's side of the Nexus server, the backend of our own** (the Windows repository's
 * `docs/backend-design.md`): one device's dealings with it, which are **the shared core's
 * machine** (`nexus::client`). The phone drives it by its four calls in the order both apps make
 * them (5.5): **start** with what was kept, **happened** for whatever happens (a press, an
 * answer, a failure, the network, Offline, a tick, the music server refusing the login), **do**
 * each thing it gives back, and **stop**. Each thing to do is one of a closed few: **ask** the
 * backend this, **keep** or **forget** this where secrets are kept, **show** this, **hand** the
 * player this music login. Everything that decides is the machine's; the phone does the I/O.
 *
 * **Nothing that plays waits for it** (the design's section 6, R1): a cold start reads what sign-in
 * left on the phone and plays (`Session`) without it, the car is served, downloads run; this is
 * made after the app is already usable, by something off every one of those paths, and asks only
 * after that.
 *
 * - **Threads of its own, all of them:** the machine's ("nexus-worker": every call to the core,
 *   every write of what is kept, every thing done), the requests' ("nexus-calls", which also
 *   connect: OkHttp's fast fallback is off), and **its own OkHttp task runner** for its connection
 *   pool ("nexus-okhttp"), where the app's own client uses OkHttp's process-wide one. HTTP/1.1
 *   only. Shared with the rest of the app, and only these: the system's DNS and sockets, OkHttp's
 *   platform (TLS) and Okio's timeout watchdog.
 * - **HTTPS only, nothing followed, one request for each ask** (`nexusHttp`). The session goes to
 *   the backend alone (each ask is to the address the person typed, made by the machine); the
 *   music login goes to the player alone.
 * - **Nothing it does can take the app down:** every failure inside it is caught inside it.
 * - **Offline is honoured:** the machine is told, and asks nothing while it is on.
 */
class Nexus internal constructor(
    private val kept: NexusKept,
    private val side: Side,
    private val userAgent: String,
    private val appVersion: String,
    private val deviceName: String,
    /** Whether offline mode is switched on by hand, now. */
    private val offlineNow: () -> Boolean,
) {
    /** What the app does for the machine, beside the I/O. Called on the machine's thread. */
    interface Side {
        /** The player is to use this music login, in place of any other: a sign-in has completed,
         *  a start has read it, or it was renewed. `account` is who it is for, as kept;
         *  `generation` what a report about it carries back. */
        fun handLogin(music: KeptMusic, account: KeptAccount?, generation: String)

        /** What the machine said to keep could not be written to the disk. */
        fun notKept()

        /** The login of this number is set aside: nothing more is sent with it. */
        fun refused(count: String)

        /** The music login is forgotten (signed out, here or by the backend): the account goes,
         *  what is on the phone stays. */
        fun musicForgotten()

        /** Whether `host` leads inside the house on this network now (Android 17's local
         *  network), for an ask that never connected: null if it couldn't be judged. */
        fun inside(host: String): Boolean?

        /** Whether the local-network permission is held; null where the phone has none. */
        val permission: Boolean?
    }

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(named("nexus-worker"))
    private val calls: ExecutorService = Executors.newFixedThreadPool(CALLS, named("nexus-calls"))

    /** The backend of OkHttp's scheduler for this alone: shut down with it. */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private val backend = TaskRunner.RealBackend(named("nexus-okhttp"))

    /** OkHttp's scheduler for this alone (see the class). */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    internal val runner = TaskRunner(backend)

    @Volatile private var http: OkHttpClient? = null

    /** Its HTTP client, once made. (For the tests.) */
    internal val transport: OkHttpClient? get() = http

    /** The machine's number in the core; 0 until it is made. Only touched on [worker]. */
    private var machine = 0L

    /** What is kept, as last put in place. Only touched on [worker]. */
    private var parts = NexusKept.Parts.NONE

    private val stopped = AtomicBoolean()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Call>()
    private val random = SecureRandom()

    private val showing = MutableStateFlow<NexusShown?>(null)

    /** What the machine shows now; null before it has started. */
    val shown: StateFlow<NexusShown?> = showing

    /** Its own threads, for a test to hold and to see end. */
    internal val workers: List<ExecutorService> get() = listOf(worker, calls)

    /** How many requests it has put on the wire. */
    internal val sent = AtomicInteger()

    /** How many requests of its are under way now. */
    internal val calling = AtomicInteger()

    /** How many of its answers' bodies are being read now (their heads have come). */
    internal val reading = AtomicInteger()

    /** Whether it has been started, and the machine has taken what was kept. */
    @Volatile var started = false
        private set

    val isStopped: Boolean get() = stopped.get()

    /** Starts it: on its own thread, after the app is usable. Returns at once. */
    fun start() = onWorker {
        gate?.invoke()
        // (Waits for the shared library's load if another thread has begun it: only this
        // thread waits.)
        Core.warm()
        machine = Core.nexusMachine()
        parts = kept.load()
        val keptJson = buildJsonObject {
            put("device_secret", kept.deviceSecret() ?: JsonNull)
            put("saved", parts.saved ?: JsonNull)
            put("notes", JsonArray(parts.notes))
            put("offline", offlineNow())
            // (The phone has no Diagnostics switch yet: no choice made.)
            put("diagnostics", JsonNull)
            put("diag_schedule", kept.diagSchedule() ?: JsonNull)
        }
        call(buildJsonObject {
            put("start", buildJsonObject {
                put("app", buildJsonObject { put("version", appVersion); put("kind", "phone"); put("device_name", deviceName) })
                put("kept", keptJson)
                put("given", given())
            })
        })
        started = true
        worker.scheduleWithFixedDelay({ happened(buildJsonObject { put("tick", buildJsonObject {}) }) }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** The person pressed something: `press` as the machine reads it (`client::Press`, as
     *  `{"sign_out":{}}` and the rest). */
    fun press(press: JsonElement) = happened(buildJsonObject { put("pressed", press) })

    /** The phone's network went away or came back. */
    fun network(up: Boolean) = happened(buildJsonObject { put("network", buildJsonObject { put("up", up) }) })

    /** Offline mode was switched on or off. */
    fun offline(on: Boolean) = happened(buildJsonObject { put("offline", on) })

    /** The music server refused the login of this number, handed under this generation
     *  (Subsonic's error 40). Both as the machine wrote them. */
    fun musicRefused(count: String, generation: String) = happened(buildJsonObject {
        put("music_refused", buildJsonObject { put("count", number(count)); put("generation", number(generation)) })
    })

    /** Where the music server's address handed over leads, and whether the permission is held
     *  (6.4), for the login of this number and generation. */
    fun musicLooked(inside: Boolean?, permission: Boolean?, count: String, generation: String) = happened(buildJsonObject {
        put("music_looked", buildJsonObject {
            put("look", look(inside, permission))
            put("count", number(count))
            put("generation", number(generation))
        })
    })

    private fun happened(event: JsonElement) = onWorker {
        if (machine == 0L) return@onWorker
        call(buildJsonObject { put("happened", buildJsonObject { put("event", event); put("given", given()) }) })
    }

    /**
     * Lets it go: returns at once. What it is asking is cancelled, its threads interrupted, and
     * whatever it still brings back dropped.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val closer = {
            quiet { inFlight.values.forEach { it.cancel() } }
            quiet { http?.connectionPool?.evictAll() }
            quiet { worker.shutdownNow() }
            quiet { calls.shutdownNow() }
            @OptIn(okhttp3.internal.OkHttpInternalApi::class)
            quiet { backend.shutdown() }
            @OptIn(okhttp3.internal.OkHttpInternalApi::class)
            quiet { backend.executor.shutdownNow() }
        }
        try {
            named("nexus-closer").newThread(closer).start()
        } catch (e: Exception) {
            closer()
        } catch (e: Error) {
            closer()
        }
    }

    /** Runs `step` on the machine's thread; whatever fails in it ends there. */
    private fun onWorker(step: () -> Unit) {
        if (stopped.get()) return
        try {
            worker.execute {
                try {
                    if (!stopped.get()) step()
                } catch (e: Exception) {
                    // (A refusal of the library's, an interrupt while it is let go of: nothing it
                    // was doing is the app's to fail over.)
                } catch (e: LinkageError) {
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** One call to the machine, and everything it gives back done. On [worker]. */
    private fun call(envelope: JsonObject) {
        val out = Core.nexusCall(buildJsonObject { put("machine", machine); put("call", envelope) })
        val does = out["do"] as? JsonArray ?: return
        doAll(does)
    }

    /** **Whether the machine still waits for the answer to this ask**: asked on its own thread,
     *  immediately before the ask is sent, and the ask dropped unsent if not (C10's contract). */
    private fun waitsFor(id: JsonPrimitive): Boolean = try {
        worker.submit<Boolean> {
            if (machine == 0L || stopped.get()) return@submit false
            (Core.nexusCall(buildJsonObject { put("machine", machine); put("call", buildJsonObject { put("waits_for", buildJsonObject { put("ask", id) }) }) })["waits"] as? JsonPrimitive)?.booleanOrNull == true
        }.get()
    } catch (e: Exception) {
        false
    }

    private fun given(): JsonObject {
        val now = System.currentTimeMillis()
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return buildJsonObject {
            put("now_ms", now)
            put("local_offset_minutes", TimeZone.getDefault().getOffset(now) / 60_000)
            put("random", bytes.joinToString("") { "%02x".format(it) })
        }.also { bytes.fill(0) }
    }

    /**
     * Does what the machine said, in its order, with one difference that is the design's own:
     * **everything it said to keep or forget in one answer is put in place in one step**, before
     * anything that answer handed the player is acted on (section 11). A sign-in is then on the
     * disk, whole, before the account is published.
     */
    private fun doAll(does: JsonArray) {
        var parts = this.parts
        var changed = false
        val asks = ArrayList<JsonObject>()
        val hands = ArrayList<JsonObject>()
        val journal = ArrayList<JsonObject>()
        var forgot = false
        for (one in does) {
            val thing = one as? JsonObject ?: continue
            val (kind, value) = thing.entries.singleOrNull() ?: continue
            when (kind) {
                "ask" -> (value as? JsonObject)?.let(asks::add)
                "keep" -> {
                    val (part, kept) = (value as? JsonObject)?.entries?.singleOrNull() ?: continue
                    when (part) {
                        "device_secret" -> this.kept.keepDeviceSecret(kept)
                        "saved" -> (kept as? JsonObject)?.let { parts = parts.withSaved(it); changed = true }
                        "note" -> (kept as? JsonObject)?.let { parts = parts.withNote(it); changed = true }
                        "diag_schedule" -> if (!this.kept.keepDiagSchedule(kept)) side.notKept()
                    }
                }
                "forget" -> when {
                    value is JsonPrimitive && value.contentOrNull == "saved" -> {
                        parts = parts.withSaved(null)
                        changed = true
                        forgot = true
                    }
                    value is JsonPrimitive && value.contentOrNull == "device_secret" -> this.kept.forgetDeviceSecret()
                    value is JsonPrimitive && value.contentOrNull == "diag_schedule" -> this.kept.forgetDiagSchedule()
                    // (No Diagnostics choice is kept on the phone yet: nothing to forget.)
                    value is JsonPrimitive && value.contentOrNull == "diagnostics" -> Unit
                    value is JsonObject && value["note"] is JsonObject -> (value["note"] as JsonObject).let { note ->
                        val backend = (note["backend_id"] as? JsonPrimitive)?.content
                        val session = (note["session"] as? JsonPrimitive)?.content
                        if (backend != null && session != null) {
                            parts = parts.withoutNote(backend, session)
                            changed = true
                        }
                    }
                    value is JsonObject && value["unreadable_note"] is JsonObject ->
                        (((value["unreadable_note"] as JsonObject)["index"]) as? JsonPrimitive)?.content?.toIntOrNull()?.let {
                            parts = parts.withoutNoteAt(it)
                            changed = true
                        }
                }
                "show" -> (value as? JsonObject)?.let { showing.value = NexusShown.of(it) }
                "hand" -> (value as? JsonObject)?.let(hands::add)
                "journal" -> (value as? JsonObject)?.let(journal::add)
            }
        }
        // (Kept in memory as told, whether or not the disk took it: the machine goes on from what
        // it said. A sign-in that could not be written plays now and is asked for again at the
        // next start.)
        if (changed) {
            this.parts = parts
            if (!kept.save(parts)) side.notKept()
        }
        // Forgotten: the music login goes from the player too (downloads stay).
        if (forgot) side.musicForgotten()
        for (hand in hands) {
            val (kind, value) = hand.entries.singleOrNull() ?: continue
            val body = value as? JsonObject ?: continue
            val generation = (body["generation"] as? JsonPrimitive)?.content ?: continue
            when (kind) {
                "login" -> (body["login"] as? JsonObject)?.let(KeptMusic::of)?.let { side.handLogin(it, parts.account, generation) }
                "refused" -> (body["count"] as? JsonPrimitive)?.content?.let(side::refused)
                // Another account signed in with no music login: the old one is not the
                // player's any more (what is on the phone stays).
                "no_login" -> side.musicForgotten()
            }
        }
        for (one in journal) journal(one)
        for (ask in asks) send(ask)
    }

    /**
     * **The sending half's things to do** (`Do::Journal`). The phone has no journal yet (that is
     * its own slice): **every read is answered, with nothing waiting** (the machine's contract:
     * a read not answered stops that kind of sending until the next start), and what would
     * change the journal has nothing to change.
     */
    private fun journal(thing: JsonObject) {
        val (kind, value) = thing.entries.singleOrNull() ?: return
        val body = value as? JsonObject ?: return
        val read = body["read"] as? JsonPrimitive
        when (kind) {
            "read_events" -> if (read != null) happened(buildJsonObject {
                put("events_read", buildJsonObject {
                    put("read", read)
                    put("account", body["account"] ?: return)
                    put("life", body["life"] ?: return)
                    put("events", JsonArray(emptyList()))
                })
            })
            "read_diagnostics" -> if (read != null) happened(buildJsonObject {
                put("diagnostics_read", buildJsonObject { put("read", read); put("text", JsonNull) })
            })
            // taken, refused, life_moved, resend_all: no journal on the phone yet.
        }
    }

    /** Sends one ask, once, on the requests' threads, and tells the machine what came of it. */
    private fun send(ask: JsonObject) {
        val id = ask["id"] as? JsonPrimitive ?: return
        try {
            calls.execute {
                val result: JsonElement = try {
                    beforeAsk?.invoke(this)
                    // Immediately before it is sent: is it still waited for? If not, not sent.
                    if (!waitsFor(id)) return@execute
                    outcome(id, ask)
                } catch (e: Exception) {
                    failed(id, "connect", null)
                } catch (e: LinkageError) {
                    failed(id, "connect", null)
                }
                happened(result)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun outcome(id: JsonPrimitive, ask: JsonObject): JsonElement {
        val url = (ask["url"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()
        // (The machine only ever asks over HTTPS, at the address the person typed: anything else
        // is not sent.)
        if (url == null || !url.isHttps) return failed(id, "connect", null)
        val post = (ask["method"] as? JsonPrimitive)?.contentOrNull == "POST"
        val authorization = (ask["authorization"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val body = (ask["body"] as? JsonPrimitive)?.contentOrNull.orEmpty().toByteArray(Charsets.UTF_8)
        val within = withinForTests ?: (ask["within_ms"] as? JsonPrimitive)?.longOrNull ?: 15_000L
        val most = (ask["max_body"] as? JsonPrimitive)?.longOrNull ?: 4096L
        val client = http ?: synchronized(this) { http ?: nexusHttp(calls, runner, sent, socketsForTests).also { http = it } }
        calling.incrementAndGet()
        val key = id.content
        val sent = try {
            exchange(client, url, post, authorization, body, userAgent, within, most, reading) { call ->
                inFlight[key] = call
                if (stopped.get()) call.cancel()
            }
        } finally {
            inFlight.remove(key)
            calling.decrementAndGet()
            body.fill(0)
        }
        return when (sent) {
            is Sent.Answer -> buildJsonObject {
                put("answered", buildJsonObject {
                    put("ask", id)
                    put("status", sent.status)
                    // As text when it is UTF-8, as the core reads it; otherwise its bytes.
                    val text = utf8(sent.body)
                    if (text != null) put("body", text) else put("body_bytes", buildJsonArray { sent.body.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } })
                })
            }
            // **Where no connection was ever opened, where the address leads and whether the
            // permission is held** (6.4): the machine then says whether to ask for it.
            is Sent.Failed -> failed(id, sent.how, if (sent.how == "name" || sent.how == "connect") url.host else null)
        }
    }

    private fun failed(id: JsonPrimitive, how: String, host: String?): JsonElement = buildJsonObject {
        put("failed", buildJsonObject {
            put("ask", id)
            put("failure", buildJsonObject {
                put("how", how)
                put("inside", host?.let { side.inside(it) }?.let(::JsonPrimitive) ?: JsonNull)
                put("permission", if (host != null) side.permission?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            })
        })
    }

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    internal companion object {
        /** A number the machine wrote, handed back to it as a number. */
        fun number(text: String): JsonPrimitive = JsonPrimitive(text.toBigDecimal())

        /** Where an address leads, and whether the local-network permission is held (`Look`). */
        fun look(inside: Boolean?, permission: Boolean?): JsonObject = buildJsonObject {
            put("inside", inside?.let(::JsonPrimitive) ?: JsonNull)
            put("permission", permission?.let(::JsonPrimitive) ?: JsonNull)
        }

        /** How often the machine is told a minute has passed (`client::TICK_MS`). */
        const val TICK_MILLIS = 60_000L

        /** The requests that may be under way at once: the machine never has two of a kind. */
        const val CALLS = 4

        /** Failures that got past every catch inside it (to its threads' own handler): for the
         *  tests, which hold it to none. */
        val escaped = AtomicInteger()

        /** For the tests only: run first on the machine's thread, before it starts. */
        @Volatile var gate: (() -> Unit)? = null

        /** For the tests only: run on a request's thread before each request. */
        @Volatile var beforeAsk: ((Nexus) -> Unit)? = null

        /** For the tests only: how long an ask may take, in place of the machine's limit (a
         *  request the stand-in holds is then held for the whole test). */
        @Volatile var withinForTests: Long? = null

        /** For the tests only: the sockets its requests are made with. */
        @Volatile var socketsForTests: javax.net.SocketFactory? = null

        const val CONNECT_SECONDS = 8L
        const val READ_SECONDS = 10L
    }
}

/** Everything the machine shows (`client::Shown`), as the screens read it. */
data class NexusShown(
    /** "sign_in" or "signed_in". */
    val screen: String,
    val signIn: SignInShown,
    /** The one row under Settings: "Nexus server: connected", and the rest. */
    val row: String,
    /** Who is signed in: "Ada (ada@example.org)". */
    val who: String?,
    /** A sentence about the music login, when there is something to say (6.3). */
    val music: String?,
    /** What this server records (13.6), to be shown until seen. */
    val notice: String?,
    val devices: DevicesShown,
    val password: Said,
    /** Whether a music login is held. */
    val plays: Boolean,
    /** The backend's address may be out of reach for want of the local-network permission
     *  (any ask that never connected, whatever asked): the app offers to ask. */
    val askPermission: Boolean = false,
    /** The same for the music server's address handed over. */
    val musicAskPermission: Boolean = false,
    /** The short line for the car about what is not on the phone, when there is one. */
    val car: String? = null,
) {
    /** `waitS`: "come back in so many seconds", exactly: the form counts it down, its button
     *  disabled, and the person presses again. */
    data class SignInShown(val state: String, val words: String, val address: String, val email: String, val askPermission: Boolean, val waitS: Long? = null)
    data class DevicesShown(val state: String, val rows: List<DeviceRow>, val words: String)

    /** `id` is the device's number as the machine wrote it, handed back as it is. */
    data class DeviceRow(val id: JsonPrimitive, val name: String, val kind: String, val thisOne: Boolean, val seen: String, val canForceOut: Boolean, val working: Boolean)
    data class Said(val state: String, val words: String)

    companion object {
        fun of(shown: JsonObject): NexusShown {
            val signIn = shown["sign_in"] as? JsonObject ?: JsonObject(emptyMap())
            val devices = shown["devices"] as? JsonObject ?: JsonObject(emptyMap())
            val password = shown["password"] as? JsonObject ?: JsonObject(emptyMap())
            return NexusShown(
                screen = shown.text("screen").orEmpty(),
                signIn = SignInShown(signIn.text("state").orEmpty(), signIn.text("words").orEmpty(), signIn.text("address").orEmpty(), signIn.text("email").orEmpty(), signIn.flag("ask_permission"), (signIn["wait_s"] as? JsonPrimitive)?.longOrNull),
                row = shown.text("row").orEmpty(),
                who = shown.text("who"),
                music = shown.text("music"),
                notice = shown.text("notice"),
                devices = DevicesShown(
                    devices.text("state").orEmpty(),
                    (devices["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
                        val one = row as? JsonObject ?: return@mapNotNull null
                        DeviceRow((one["id"] as? JsonPrimitive) ?: return@mapNotNull null, one.text("name").orEmpty(), one.text("kind").orEmpty(), one.flag("this_one"), one.text("seen").orEmpty(), one.flag("can_force_out"), one.flag("working"))
                    },
                    devices.text("words").orEmpty(),
                ),
                password = Said(password.text("state").orEmpty(), password.text("words").orEmpty()),
                plays = shown.flag("plays"),
                askPermission = shown.flag("ask_permission"),
                musicAskPermission = shown.flag("music_ask_permission"),
                car = shown.text("car"),
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    }
}

/** Runs `step`, and whatever it throws ends there. */
private inline fun quiet(step: () -> Unit) {
    try {
        step()
    } catch (e: Exception) {
    } catch (e: LinkageError) {
    }
}

private val threadNumbers = AtomicInteger()

private fun named(name: String) = java.util.concurrent.ThreadFactory { work ->
    Thread(work, "$name-${threadNumbers.incrementAndGet()}").apply {
        isDaemon = true
        // (Should anything slip past every catch on one of its threads, it ends that thread,
        // not the app.)
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> Nexus.escaped.incrementAndGet() }
    }
}
