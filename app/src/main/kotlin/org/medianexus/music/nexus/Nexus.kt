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
        /** The player is to use this music login: a sign-in has completed, or a login was
         *  renewed. `account` is who it is for, as kept. */
        fun handLogin(music: KeptMusic, account: KeptAccount?)

        /** What the machine said to keep could not be written to the disk. */
        fun notKept()

        /** The login of this number is set aside: nothing more is sent with it. */
        fun refused(count: Long)

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
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, Call>()
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
            kept.deviceSecret()?.let { put("device_secret", it) }
            parts.all["session"]?.let { put("session", it) }
            parts.all["music"]?.let { put("music", it) }
            parts.all["account"]?.let { put("account", it) }
            parts.all["to_tell"]?.let { put("to_tell", it) }
            put("offline", offlineNow())
        }
        call(buildJsonObject {
            put("call", "start")
            put("app", buildJsonObject { put("version", appVersion); put("device_name", deviceName) })
            put("kept", keptJson)
        })
        started = true
        worker.scheduleWithFixedDelay({ happened(JsonPrimitive("tick")) }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** The person pressed something: `press` as the machine reads it (`client::Press`). */
    fun press(press: JsonElement) = happened(buildJsonObject { put("pressed", press) })

    /** The phone's network went away or came back. */
    fun network(up: Boolean) = happened(buildJsonObject { put("network", buildJsonObject { put("up", up) }) })

    /** Offline mode was switched on or off. */
    fun offline(on: Boolean) = happened(buildJsonObject { put("offline", on) })

    /** The music server refused the login of this number (Subsonic's error 40). */
    fun musicRefused(count: Long) = happened(buildJsonObject { put("music_refused", buildJsonObject { put("count", count) }) })

    private fun happened(event: JsonElement) = onWorker {
        if (machine == 0L) return@onWorker
        call(buildJsonObject { put("call", "happened"); put("event", event) })
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
    private fun call(input: JsonObject) {
        val full = JsonObject(input + ("machine" to JsonPrimitive(machine)) + ("given" to given()))
        val out = Core.nexusCall(full)
        val does = out["does"] as? JsonArray ?: return
        doAll(does)
    }

    private fun given(): JsonObject {
        val now = System.currentTimeMillis()
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return buildJsonObject {
            put("now_ms", now)
            put("local_offset_minutes", TimeZone.getDefault().getOffset(now) / 60_000)
            put("random", buildJsonArray { bytes.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } })
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
        var forgotMusic = false
        for (one in does) {
            val thing = one as? JsonObject ?: continue
            val (kind, value) = thing.entries.singleOrNull() ?: continue
            when (kind) {
                "ask" -> (value as? JsonObject)?.let(asks::add)
                "keep" -> {
                    val (part, kept) = (value as? JsonObject)?.entries?.singleOrNull() ?: continue
                    if (part == "device_secret") {
                        (kept as? JsonPrimitive)?.contentOrNull?.let { this.kept.keepDeviceSecret(it) }
                    } else {
                        parts = parts.with(part, kept)
                        changed = true
                    }
                }
                "forget" -> {
                    val part = (value as? JsonPrimitive)?.contentOrNull ?: continue
                    parts = parts.without(part)
                    changed = true
                    if (part == "music") forgotMusic = true
                }
                "show" -> (value as? JsonObject)?.let { showing.value = NexusShown.of(it) }
                "hand" -> (value as? JsonObject)?.let(hands::add)
            }
        }
        // (Kept in memory as told, whether or not the disk took it: the machine goes on from what
        // it said. A sign-in that could not be written plays now and is asked for again at the
        // next start.)
        if (changed) {
            this.parts = parts
            if (!kept.save(parts)) side.notKept()
        }
        if (forgotMusic) side.musicForgotten()
        for (hand in hands) {
            val (kind, value) = hand.entries.singleOrNull() ?: continue
            when (kind) {
                "login" -> (value as? JsonObject)?.let(KeptMusic::of)?.let { side.handLogin(it, parts.account) }
                "refused" -> ((value as? JsonObject)?.get("count") as? JsonPrimitive)?.longOrNull?.let(side::refused)
            }
        }
        for (ask in asks) send(ask)
    }

    /** Sends one ask, once, on the requests' threads, and tells the machine what came of it. */
    private fun send(ask: JsonObject) {
        val id = (ask["id"] as? JsonPrimitive)?.longOrNull ?: return
        try {
            calls.execute {
                val result: JsonElement = try {
                    beforeAsk?.invoke(this)
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

    private fun outcome(id: Long, ask: JsonObject): JsonElement {
        val url = (ask["url"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()
        // (The machine only ever asks over HTTPS, at the address the person typed: anything else
        // is not sent.)
        if (url == null || !url.isHttps) return failed(id, "connect", null)
        val post = (ask["method"] as? JsonPrimitive)?.contentOrNull == "POST"
        val authorization = (ask["authorization"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val body = (ask["body"] as? JsonPrimitive)?.contentOrNull.orEmpty().toByteArray(Charsets.UTF_8)
        val within = (ask["within_ms"] as? JsonPrimitive)?.longOrNull ?: 15_000L
        val most = (ask["max_body"] as? JsonPrimitive)?.longOrNull ?: 4096L
        val client = http ?: synchronized(this) { http ?: nexusHttp(calls, runner, sent, socketsForTests).also { http = it } }
        calling.incrementAndGet()
        val sent = try {
            exchange(client, url, post, authorization, body, userAgent, within, most, reading) { call ->
                inFlight[id] = call
                if (stopped.get()) call.cancel()
            }
        } finally {
            inFlight.remove(id)
            calling.decrementAndGet()
            body.fill(0)
        }
        return when (sent) {
            is Sent.Answer -> buildJsonObject {
                put("answered", buildJsonObject {
                    put("ask", id)
                    put("status", sent.status)
                    put("body", buildJsonArray { sent.body.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } })
                })
            }
            // **Where no connection was ever opened, where the address leads and whether the
            // permission is held** (6.4): the machine then says whether to ask for it.
            is Sent.Failed -> failed(id, sent.how, if (sent.how == "name" || sent.how == "connect") url.host else null)
        }
    }

    private fun failed(id: Long, how: String, host: String?): JsonElement = buildJsonObject {
        put("failed", buildJsonObject {
            put("ask", id)
            put("failure", buildJsonObject {
                put("how", how)
                put("inside", host?.let { side.inside(it) }?.let(::JsonPrimitive) ?: JsonNull)
                put("permission", if (host != null) side.permission?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            })
        })
    }

    internal companion object {
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

        /** For the tests only: the sockets its requests are made with. */
        @Volatile var socketsForTests: javax.net.SocketFactory? = null

        const val CONNECT_SECONDS = 8L
        const val READ_SECONDS = 10L
    }
}

/** Everything the machine shows (`client::Shown`), as the screens read it. */
data class NexusShown(
    /** "sign_in", "choose_password" or "signed_in". */
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
) {
    data class SignInShown(val state: String, val words: String, val address: String, val email: String, val askPermission: Boolean)
    data class DevicesShown(val state: String, val rows: List<DeviceRow>, val words: String)
    data class DeviceRow(val id: Long, val name: String, val kind: String, val thisOne: Boolean, val seen: String, val canForceOut: Boolean, val working: Boolean)
    data class Said(val state: String, val words: String)

    companion object {
        fun of(shown: JsonObject): NexusShown {
            val signIn = shown["sign_in"] as? JsonObject ?: JsonObject(emptyMap())
            val devices = shown["devices"] as? JsonObject ?: JsonObject(emptyMap())
            val password = shown["password"] as? JsonObject ?: JsonObject(emptyMap())
            return NexusShown(
                screen = shown.text("screen").orEmpty(),
                signIn = SignInShown(signIn.text("state").orEmpty(), signIn.text("words").orEmpty(), signIn.text("address").orEmpty(), signIn.text("email").orEmpty(), signIn.flag("ask_permission")),
                row = shown.text("row").orEmpty(),
                who = shown.text("who"),
                music = shown.text("music"),
                notice = shown.text("notice"),
                devices = DevicesShown(
                    devices.text("state").orEmpty(),
                    (devices["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
                        val one = row as? JsonObject ?: return@mapNotNull null
                        DeviceRow((one["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, one.text("name").orEmpty(), one.text("kind").orEmpty(), one.flag("this_one"), one.text("seen").orEmpty(), one.flag("can_force_out"), one.flag("working"))
                    },
                    devices.text("words").orEmpty(),
                ),
                password = Said(password.text("state").orEmpty(), password.text("words").orEmpty()),
                plays = shown.flag("plays"),
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
