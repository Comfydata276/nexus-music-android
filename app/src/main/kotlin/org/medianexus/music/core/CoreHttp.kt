package org.medianexus.music.core

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.medianexus.music.BuildConfig
import org.medianexus.music.net.NetTrouble
import org.medianexus.music.net.netTrouble

/**
 * The library's requests: the one object the shared library calls up into when it wants
 * something from Lidarr, the sign-in service in front of it, MusicBrainz or ListenBrainz.
 * The library opens no connections of its own; the Rust side of this is `native/src/wire.rs`,
 * and docs/DESIGN.md, "The library's requests", says what was decided and why.
 *
 * **A client of its own.** None of these is the music server, so nothing of the music
 * server's is anywhere near them: no sign-in, no cookies, no interceptors, none of its
 * connections. The limits are the Windows app's for the same calls (eight seconds to
 * connect, thirty for an answer to begin, and whatever shorter limit the library puts on
 * one call), and so is the rule about redirects: never followed for Lidarr and its sign-in,
 * where a redirect is how a sign-in shows itself; followed for MusicBrainz and ListenBrainz.
 * It goes by the app's own name ([NAME]).
 *
 * **Nothing here is ever logged**: not a request, not an answer, not an exception's own
 * words (which can quote a header). What goes wrong is said in words written here, and they
 * never name an address.
 *
 * **Stopping** ([Doing]). Everything the library does for the app that makes requests is an
 * operation with a number, and the app can stop one. A stopped operation's waits end at
 * once. Beyond that there are two kinds:
 *
 * - one that only **looks** (a search, a page, a check of the address): the request in
 *   flight is cut off, and no more are sent;
 * - one that **changes something in several steps, which the library sees through**
 *   (asking Lidarr for an album, adding an artist). It looks before it changes, and until
 *   its first change a stop is honoured exactly as for one that only looks. Just before
 *   that first change the library says so ([keepGoing], its `Http::keep_going`), and **from
 *   then on a stop does nothing**: its waits are waited out and its requests are sent, to
 *   the end, behind the back of whoever asked. Stopped half-way it would leave an album
 *   marked and never looked for, or an artist no longer taking new releases. A stop that
 *   came first is still honoured: the change it was about to make is refused;
 * - one that **changes something in one step** and never says to keep going (taking an
 *   album off Lidarr's list, finishing a sign-in, handing a token back): the request in
 *   flight is not cut off at once, because the library would not know whether the change
 *   was made; it is given [afterStopMillis] to finish. A request it still makes (the name
 *   a new token belongs to) is let through under the same short limit, for [graceMillis]
 *   in all; then no more are sent.
 *
 * A request that is **nobody's** (the sign-in renewing its token, which replaces the token
 * that is kept) is never stopped and never cut off: it has [nobodysMillis] and no longer.
 *
 * **Threads.** The library's calls block, some of them for a minute and more. They run on
 * threads kept for them and for nothing else: [LOOKING] at once for looking and [CHANGING]
 * for changing, as the Windows app has, made when first wanted and let go when idle. None
 * is ever the main thread.
 */
internal object CoreHttp {
    /** What the client calls itself. The Windows app sends `NexusMusic/<version>`; this is
     *  the phone's, the same as its music client sends. */
    val NAME = "NexusMusic/${BuildConfig.VERSION_NAME} (Android)"

    private const val CONNECT_SECONDS = 8L
    private const val ANSWER_SECONDS = 30L

    /** The most of a body that is ever read, as the library has it (`BODY_LIMIT`). */
    private const val BODY_LIMIT = 10L * 1024 * 1024

    /** How long a request that is nobody's may take in all. (The Windows app puts no limit
     *  on the whole of one; there nothing waits behind it that cannot be stopped.) */
    @Volatile var nobodysMillis = 60_000L

    const val LOOKING = 4
    const val CHANGING = 2

    /** After an operation that changes something has been stopped: how long each of its
     *  requests may still take. */
    @Volatile var afterStopMillis = 10_000L

    /** And for how long after the stop its requests are still let through at all. */
    @Volatile var graceMillis = 30_000L

    /**
     * Whether requests may not be sent at all just now, and if so the words for why (the end
     * of the library's own sentence, as in "Couldn't reach Lidarr. Offline mode is on.").
     * Set by whoever owns the signed-in account; asked before every request.
     */
    @Volatile var unsent: () -> String? = { null }

    /** For the tests: something done to the client as it is built (a stand-in for a service
     *  whose address the library fixes). Nothing in the app sets it. */
    @Volatile var dress: (OkHttpClient.Builder) -> Unit = {}
        set(value) {
            field = value
            lines = null
        }

    // ---- the words for what goes wrong --------------------------------------------------------

    private const val NOT_MADE = "The app couldn't make the request."
    private const val BAD_ADDRESS = "That address isn't one the app can use."
    private const val TOO_LARGE = "The answer was larger than 10 MB."
    private const val GONE = "The answer is no longer there to read."
    private const val STOPPED_WORDS = "Stopped."

    /** Why a request failed on its way, to end the library's sentence with ("Couldn't reach
     *  Lidarr. …"). Never the exception's own words, which name the host and can quote more. */
    private fun words(error: IOException): String = when (netTrouble(error)) {
        NetTrouble.REDIRECT -> "It tried to send the request on somewhere it shouldn't go."
        NetTrouble.CERTIFICATE_FOR_ANOTHER -> "Its certificate is for a different address."
        NetTrouble.CERTIFICATE_NOT_TRUSTED -> "Its certificate isn't one this phone trusts."
        NetTrouble.NAME_NOT_FOUND -> "Its name couldn't be found."
        NetTrouble.REFUSED -> "The connection was refused."
        NetTrouble.TIMED_OUT -> "It didn't answer in time."
        NetTrouble.SECURE_CONNECTION -> "The secure connection failed."
        NetTrouble.NOT_ALLOWED -> "The phone didn't allow the connection."
        NetTrouble.NO_WAY -> "There's no way to it from this network."
        NetTrouble.DROPPED -> "The connection dropped."
        NetTrouble.NO_CONNECTION -> "Couldn't connect."
        NetTrouble.OTHER -> "The connection failed."
    }

    // ---- the client ---------------------------------------------------------------------------

    /** Each service's connections: kept as long as the Windows app keeps them, and no longer
     *  (MusicBrainz closes one that has been idle for fifteen seconds). */
    private class Lines(dress: (OkHttpClient.Builder) -> Unit) {
        private val base: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ANSWER_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(ANSWER_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .also(dress)
            .build()

        private fun line(idleSeconds: Long, follows: Boolean): Pair<OkHttpClient, OkHttpClient> {
            val looks = base.newBuilder()
                .connectionPool(ConnectionPool(4, idleSeconds, TimeUnit.SECONDS))
                .followRedirects(follows)
                .followSslRedirects(follows)
                .build()
            // A request that changes something is sent once: never again by the client on
            // its own, which could not know whether the first had been acted on.
            return looks to looks.newBuilder().retryOnConnectionFailure(false).build()
        }

        private val lidarr = line(300, follows = false)
        private val musicbrainz = line(12, follows = true)
        private val listenbrainz = line(50, follows = true)

        fun of(line: String, changes: Boolean): OkHttpClient? {
            val pair = when (line) {
                "lidarr" -> lidarr
                "musicbrainz" -> musicbrainz
                "listenbrainz" -> listenbrainz
                else -> return null
            }
            return if (changes) pair.second else pair.first
        }
    }

    @Volatile private var lines: Lines? = null

    private fun lines(): Lines = lines ?: synchronized(this) { lines ?: Lines(dress).also { lines = it } }

    // ---- operations ---------------------------------------------------------------------------

    /**
     * One thing the library is doing for the app that makes requests. [stop] is how the app
     * says it has no more use for it; what that does is in this file's own comment.
     */
    class Doing(val id: Long, val changing: Boolean) {
        private val lock = ReentrantLock()
        private val stoppedNow = lock.newCondition()
        private var stopped = false
        private var stoppedAt = 0L
        private val flying = HashSet<Call>()

        /** The library has said this is to be seen through, and it had not been stopped. */
        private var seenThrough = false

        val isStopped: Boolean get() = lock.withLock { stopped }

        /** The library's "keep going": honoured unless a stop came first. */
        fun keepGoing() {
            lock.withLock { if (!stopped) seenThrough = true }
        }

        fun stop() {
            val cut = lock.withLock {
                if (stopped || seenThrough) return
                stopped = true
                stoppedAt = System.nanoTime()
                stoppedNow.signalAll()
                flying.toList()
            }
            if (!changing) {
                cut.forEach { it.cancel() }
            } else if (cut.isNotEmpty()) {
                // (Rare: one short-lived thread, and nothing kept standing for it.)
                Thread({
                    try {
                        Thread.sleep(afterStopMillis)
                    } catch (_: InterruptedException) {
                    }
                    cut.forEach { it.cancel() }
                }, "requests-stop").apply { isDaemon = true }.start()
            }
        }

        /** Waits, as the library asks between requests. False if stopped, before or during. */
        fun pause(millis: Long): Boolean = lock.withLock {
            var left = TimeUnit.MILLISECONDS.toNanos(millis)
            while (!stopped && left > 0) left = stoppedNow.awaitNanos(left)
            !stopped
        }

        /**
         * Whether a request may be sent now, and under what limit of this operation's own:
         * null for none, a number of milliseconds once it has been stopped, and [REFUSED]
         * when no more are to be sent.
         */
        fun allowance(): Long? = lock.withLock {
            when {
                !stopped -> null
                !changing -> REFUSED
                System.nanoTime() - stoppedAt > TimeUnit.MILLISECONDS.toNanos(graceMillis) -> REFUSED
                else -> afterStopMillis
            }
        }

        /** Notes a request as in flight. False if it may no longer be sent. */
        fun enter(call: Call): Boolean = lock.withLock {
            if (stopped && !changing) return false
            flying += call
            true
        }

        fun leave(call: Call) {
            lock.withLock { flying -= call }
        }

        companion object {
            const val REFUSED = -1L
        }
    }

    private val doings = ConcurrentHashMap<Long, Doing>()
    private val nextDoing = AtomicLong(0)

    private fun begin(changing: Boolean): Doing = Doing(nextDoing.incrementAndGet(), changing).also { doings[it.id] = it }

    private fun end(doing: Doing) {
        doings.remove(doing.id)
        // (The library lets go of every answer it was given; this is for one it didn't.)
        bodies.entries.removeIf { (_, held) ->
            (held.doing === doing).also { if (it) closeQuietly(held) }
        }
    }

    private fun pool(name: String, threads: Int): Lazy<Executor> = lazy {
        val count = AtomicInteger(0)
        ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { work ->
            Thread(work, "requests-$name-${count.incrementAndGet()}").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    private val lookers by pool("look", LOOKING)
    private val changers by pool("change", CHANGING)

    /**
     * Runs something of the library's that only looks, on a thread kept for that, and waits
     * for it without holding the caller's thread. Cancelling the caller stops it: the caller
     * is let go at once, and what the library was doing ends behind it.
     */
    suspend fun <T> looking(act: (operation: Long) -> T): T = run(begin(changing = false), lookers, act)

    /** The same for something that changes Lidarr or the sign-in in one step: stopped, the
     *  request in flight is let finish, behind the caller's back. */
    suspend fun <T> changing(act: (operation: Long) -> T): T = run(begin(changing = true), changers, act)

    /**
     * The same for something the library sees through once it has begun to change Lidarr
     * (asking for an album, adding an artist): cancelling the caller stops it only if the
     * library has not yet said to keep going. After that it runs to its end behind the
     * caller, who is let go at once all the same, **and never hears how it ended**: whoever
     * must know (to write down what was asked for) uses [seeingThrough].
     */
    suspend fun <T> seenThrough(act: (operation: Long) -> T): T = run(begin(changing = false), changers, act)

    /** Something begun with [seeingThrough]: how it ended, whenever that is, and a way to
     *  stop it, which the library may no longer honour. */
    class Begun<T> internal constructor(val done: Deferred<T>, private val doing: Doing) {
        fun stop() = doing.stop()
    }

    /** Starts something the library sees through, without waiting for it. */
    fun <T> seeingThrough(act: (operation: Long) -> T): Begun<T> {
        val doing = begin(changing = false)
        val done = CompletableDeferred<T>()
        changers.execute {
            val outcome = if (doing.isStopped) Result.failure(CancellationException(STOPPED_WORDS)) else runCatching { act(doing.id) }
            end(doing)
            outcome.fold(done::complete, done::completeExceptionally)
        }
        return Begun(done, doing)
    }

    /** Runs something of the library's that makes no request an operation could own (opening
     *  and closing a sign-in, which can wait on a renewal) off the caller's thread. It is
     *  not stopped by cancelling the caller: it finishes behind it. */
    suspend fun <T> aside(act: () -> T): T = suspendCancellableCoroutine { waiting ->
        changers.execute { waiting.resumeWith(runCatching(act)) }
    }

    private suspend fun <T> run(doing: Doing, pool: Executor, act: (Long) -> T): T = suspendCancellableCoroutine { waiting ->
        waiting.invokeOnCancellation { doing.stop() }
        pool.execute {
            // (Stopped while it waited its turn: it never starts.)
            val outcome = if (doing.isStopped) Result.failure(CancellationException(STOPPED_WORDS)) else runCatching { act(doing.id) }
            end(doing)
            // (Ignored if the caller has been cancelled meanwhile: nothing is published
            // after a stop.)
            waiting.resumeWith(outcome)
        }
    }

    // ---- sign-ins that are open ----------------------------------------------------------------

    private val keepers = ConcurrentHashMap<Long, (String) -> Unit>()

    /** Says who puts away the token of the sign-in the library knows by this number, each
     *  time it changes ("" when the service has stopped honouring it). */
    fun keeping(session: Long, keep: (String) -> Unit) {
        keepers[session] = keep
    }

    fun forget(session: Long) {
        keepers.remove(session)
    }

    // ---- what the library calls ----------------------------------------------------------------
    //
    // (Each by its name, from `native/src/wire.rs`: they are kept under these names in a
    // release build by app/proguard-rules.pro. None throws: whatever goes wrong is answered
    // in the bytes, and the library has its own word for an answer that never came.)

    @Serializable
    private class Asked(
        val operation: Long, val line: String, val method: String, val url: String,
        val headers: List<List<String>> = emptyList(), val body: String? = null, val patience: Long? = null,
    )

    @Serializable
    private class Answered(val status: Int, val headers: List<List<String?>>, val body: Long, val length: Long?)

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private const val FINE: Byte = 0
    private const val FAILED: Byte = 1
    private const val STOPPED: Byte = 2

    private fun failed(why: String): ByteArray = byteArrayOf(FAILED) + why.encodeToByteArray()
    private val stopped: ByteArray get() = byteArrayOf(STOPPED)

    /** An answer whose body the library has not read yet, or is part-way through. */
    private class Held(val response: Response, val call: Call, val doing: Doing?) {
        var read = 0L
    }

    private val bodies = ConcurrentHashMap<Long, Held>()
    private val nextBody = AtomicLong(0)

    private fun closeQuietly(held: Held) {
        try {
            held.response.close()
        } catch (_: Exception) {
        }
        held.doing?.leave(held.call)
    }

    /** Makes one request. */
    fun send(frame: ByteArray): ByteArray = try {
        sent(json.decodeFromString(Asked.serializer(), frame.decodeToString()))
    } catch (_: Exception) {
        // (A header that can't be sent, say. Not the exception's words: they can quote it.)
        failed(NOT_MADE)
    }

    private fun sent(asked: Asked): ByteArray {
        unsent()?.let { return failed(it) }
        val doing: Doing? = if (asked.operation == 0L) null else doings[asked.operation] ?: return stopped
        val allowed = if (doing == null) nobodysMillis else doing.allowance()
        if (allowed == Doing.REFUSED) return stopped
        val limit = listOfNotNull(asked.patience, allowed).minOrNull()

        val url = asked.url.toHttpUrlOrNull() ?: return failed(BAD_ADDRESS)
        val body = asked.body.orEmpty().encodeToByteArray()
        val request = Request.Builder().url(url).apply {
            var named = false
            var accepts = false
            for (header in asked.headers) {
                addHeader(header[0], header[1])
                named = named || header[0].equals("User-Agent", ignoreCase = true)
                accepts = accepts || header[0].equals("Accept", ignoreCase = true)
            }
            if (!named) header("User-Agent", NAME)
            // (As the Windows app's agent sends where the library doesn't say.)
            if (!accepts) header("Accept", "*/*")
            when (asked.method) {
                "GET" -> get()
                // (No kind of its own: the library says what the text is, in a header.)
                "POST" -> post(body.toRequestBody(null))
                "PUT" -> put(body.toRequestBody(null))
                else -> return failed(NOT_MADE)
            }
        }.build()
        val client = lines().of(asked.line, changes = asked.method != "GET") ?: return failed(NOT_MADE)
        val call = client.newCall(request)
        if (limit != null) call.timeout().timeout(limit, TimeUnit.MILLISECONDS)
        if (doing != null && !doing.enter(call)) return stopped
        val response = try {
            call.execute()
        } catch (e: IOException) {
            doing?.leave(call)
            return if (doing?.isStopped == true) stopped else failed(words(e))
        }
        val id = nextBody.incrementAndGet()
        bodies[id] = Held(response, call, doing)
        val headers = response.headers.map { (name, value) -> listOf(name, value.takeIf(::isText)) }
        val length = response.body.contentLength().takeIf { it >= 0 }
        return byteArrayOf(FINE) + json.encodeToString(Answered.serializer(), Answered(response.code, headers, id, length)).encodeToByteArray()
    }

    /** Whether a header's value is text as the Windows app's agent has it: visible ASCII
     *  (and tabs) only. One that isn't is handed over as there but unreadable. */
    private fun isText(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

    /** The whole of an answer's body, which is then let go. */
    fun text(body: Long): ByteArray {
        val held = bodies.remove(body) ?: return failed(GONE)
        return try {
            val source = held.response.body.source()
            if (source.request(BODY_LIMIT + 1)) {
                failed(TOO_LARGE)
            } else {
                val buffer = source.buffer
                val whole = ByteArray(buffer.size.toInt() + 1)
                whole[0] = FINE
                var at = 1
                while (at < whole.size) at += buffer.read(whole, at, whole.size - at)
                whole
            }
        } catch (e: IOException) {
            failed(if (held.doing?.isStopped == true) STOPPED_WORDS else words(e))
        } catch (_: Exception) {
            failed(NOT_MADE)
        } finally {
            closeQuietly(held)
        }
    }

    /** The next piece of an answer's body, [most] bytes at the most; none at its end. */
    fun read(body: Long, most: Int): ByteArray {
        val held = bodies[body] ?: return failed(GONE)
        return try {
            val piece = Buffer()
            val count = held.response.body.source().read(piece, most.toLong())
            if (count > 0) held.read += count
            when {
                count <= 0 -> byteArrayOf(FINE)
                held.read > BODY_LIMIT -> failed(TOO_LARGE)
                else -> byteArrayOf(FINE) + piece.readByteArray()
            }
        } catch (e: IOException) {
            failed(if (held.doing?.isStopped == true) STOPPED_WORDS else words(e))
        } catch (_: Exception) {
            failed(NOT_MADE)
        }
    }

    /** Lets an answer go, read or not. */
    fun close(body: Long) {
        try {
            bodies.remove(body)?.let(::closeQuietly)
        } catch (_: Exception) {
        }
    }

    /** Waits for the library, which asks between rationed requests and while Lidarr works.
     *  False if the operation has been stopped, before or during. */
    fun pause(operation: Long, millis: Long): Boolean = try {
        if (operation == 0L) {
            Thread.sleep(millis)
            true
        } else {
            doings[operation]?.pause(millis) ?: false
        }
    } catch (_: Exception) {
        false
    }

    /** The library is about to change something it must see through: this operation is no
     *  longer stopped (see [Doing.keepGoing]). */
    fun keepGoing(operation: Long) {
        try {
            doings[operation]?.keepGoing()
        } catch (_: Exception) {
        }
    }

    /** A sign-in's token has changed: handed to whoever puts it away. */
    fun kept(session: Long, token: ByteArray) {
        try {
            keepers[session]?.invoke(token.decodeToString())
        } catch (_: Exception) {
        }
    }
}
