package org.medianexus.music.nexus

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.concurrent.TaskRunner
import okio.Buffer
import org.medianexus.music.AccountGraph
import org.medianexus.music.core.Core

/**
 * The phone's side of the Nexus server, the backend of our own beside the music server
 * (docs/backend-design.md), **at its very smallest** (slice P1): it asks the backend's `hello`
 * while the app is in use, and says what it found, for one row under Settings. It reads, sends
 * and keeps nothing else: no sign-in, no plays, no file.
 *
 * **Nothing waits for it** (the design's 5.1, and its rule R1):
 *
 * - **Not on the app's start-up path.** Nothing of it is made, loaded or started while the
 *   app's objects are made: `Graph` waits, off the main thread, for the first account to be
 *   published, and only then makes the [NexusLauncher] (and catches anything that fails in the
 *   making). Nothing `Accounts.restored` waits for makes it or waits on it.
 * - **Its own workers, all of them:** the launcher's thread; each client's worker (which also
 *   connects: OkHttp's fast fallback, which would hand the connect to a shared scheduler, is
 *   off); its HTTP dispatcher's thread; and **its own OkHttp task runner**, for the connection
 *   pool's upkeep, where OkHttp's process-wide one (`TaskRunner.INSTANCE`) serves the app's
 *   streams and downloads. HTTP/1.1 only, so no HTTP/2 reader or writer runs anywhere. Shared
 *   with the rest of the app, and only these: the system's DNS and sockets, OkHttp's platform
 *   (TLS) and Okio's timeout watchdog.
 * - **Never the shared library's load:** it asks the core only once the core is loaded
 *   ([Core.isReady], which takes no lock), and otherwise says "none found" and asks nothing.
 * - **Nothing it does can take the app down:** every failure inside it is caught inside it.
 * - **Bound to its account:** what it finds carries the account it was found for ([NexusShown]),
 *   and is shown only while that account is the one published; a client whose account is no
 *   longer published stops by itself, whether or not the launcher has got to it. The launcher
 *   lets it go when it sees the change, without waiting for anything.
 * - **One probe is one request:** no retry, no redirect, no follow-up of any kind.
 * - **Only while it may:** not with offline mode switched on by hand, not while the phone may
 *   not reach the home network, and only while the app is in use (its screens in front, or
 *   music playing): at most once an hour.
 */
data class NexusShown(
    /** The generation of the client that found it: one more for each account taken on, so that
     *  what a later account's client found is never overwritten by an earlier one's. */
    val generation: Long,
    /** The account it was found for: its API client, the account's identity in `Accounts` and
     *  in the session's state. */
    val owner: Any,
    /** How the backend's origin is shown (its host, and its port if not HTTPS's own); null for
     *  "none found". */
    val address: String?,
)

/**
 * Makes the [NexusClient] for whoever is signed in, after they have been published, and lets
 * the old one go when they change. Its work is on a thread of its own.
 */
class NexusLauncher internal constructor(
    private val accounts: StateFlow<AccountGraph?>,
    /** The app's work: the launcher ends with it. */
    parent: CoroutineScope,
    private val userAgent: String,
    /** Whether a `hello` may be asked now (see [NexusClient]). */
    private val mayAsk: () -> Boolean,
    private val every: Long = period,
) {
    /** The thread it was made on (for the tests: never the main thread). */
    internal val madeOn: Thread = Thread.currentThread()

    private val thread: ExecutorService = Executors.newSingleThreadExecutor(named("nexus-launcher"))
    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext[Job]) + thread.asCoroutineDispatcher() + quietly)
    private val lock = Any()

    private val shown = MutableStateFlow<NexusShown?>(null)

    /** What the client of the latest account found, with its generation and owner. The row
     *  shows its address only if its owner is the account the row is drawn for. */
    val found: StateFlow<NexusShown?> = shown

    /** The launcher's own thread, for a test to hold. */
    internal val workers: List<ExecutorService> get() = listOf(thread)

    /** The client of the account signed in now; null with nobody. (For the tests.) */
    @Volatile internal var current: NexusClient? = null
        private set

    init {
        scope.launch {
            gate?.invoke()
            // (Another set of the account's objects is another account, even if equal.)
            accounts.distinctUntilChanged { a, b -> a === b }.collect { account -> taken(account) }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            synchronized(lock) { current?.stop(); current = null }
            thread.shutdown()
        }
    }

    private fun taken(account: AccountGraph?) {
        val made = account?.let {
            try {
                NexusClient(generations.incrementAndGet(), it.api, it.account.server, userAgent, every, live = { accounts.value?.api === it.api }, mayAsk = mayAsk, shown = shown)
            } catch (e: Exception) {
                null
            } catch (e: LinkageError) {
                null
            }
        }
        val old = synchronized(lock) {
            val before = current
            current = made
            before
        }
        // The new account's first: nothing of the old one's is waited for (its letting go
        // returns at once, and its cleanup runs on a thread of its own).
        made?.start()
        old?.stop()
    }

    internal companion object {
        const val HOURLY_MILLIS = 60 * 60 * 1000L

        /** Each client's generation: one more for every account taken on, in the process. */
        private val generations = java.util.concurrent.atomic.AtomicLong()

        /** The last generation given out. (For the tests.) */
        fun lastGeneration(): Long = generations.get()

        /** How often a client asks `hello` again: an hour, but in tests. */
        @Volatile var period: Long = HOURLY_MILLIS

        /** For the tests only: run first on the launcher's thread, before it looks at any account. */
        @Volatile var gate: (() -> Unit)? = null
    }
}

/**
 * The backend's client for one account. Every thread it uses is its own (see the file's
 * comment). [stop] lets it go: it returns at once, whatever the client is in the middle of,
 * and the threads end by themselves.
 */
class NexusClient internal constructor(
    /** Its generation (see [NexusShown.generation]); its threads are named with it. */
    internal val generation: Long,
    /** The account's identity in `Accounts` (its API client). */
    private val owner: Any,
    /** The account's music server, as the person typed it. */
    private val server: String,
    private val userAgent: String,
    private val every: Long,
    /** Whether the account is still the one published. */
    private val live: () -> Boolean,
    private val mayAsk: () -> Boolean,
    private val shown: MutableStateFlow<NexusShown?>,
) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(named("nexus-worker-$generation"))
    private val calls: ExecutorService = Executors.newSingleThreadExecutor(named("nexus-calls-$generation"))
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher() + quietly)

    /** The backend of OkHttp's scheduler for this client alone: shut down with the client. */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private val backend = TaskRunner.RealBackend(named("nexus-okhttp-$generation"))

    /** OkHttp's scheduler for this client alone (see the file's comment). */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    internal val runner = TaskRunner(backend)

    @Volatile private var http: OkHttpClient? = null

    /** Its HTTP client, once made. (For the tests.) */
    internal val transport: OkHttpClient? get() = http
    @Volatile private var call: Call? = null
    @Volatile private var stopped = false
    private val closing = java.util.concurrent.atomic.AtomicBoolean()

    /** Its own threads (the task runner's are made only when it has work), for a test to
     *  hold and to see end. */
    internal val workers: List<ExecutorService> get() = listOf(worker, calls)

    /** How many times it has come round to `hello` (asked or not), its word given. */
    internal val asked = AtomicInteger()

    /** How many requests it has put on the wire. */
    internal val sent = AtomicInteger()

    /** Whether a request of its is under way now. */
    @Volatile internal var calling = false
        private set

    /** Whether it is reading an answer's body now (its head has come). */
    @Volatile internal var reading = false
        private set

    val isStopped: Boolean get() = stopped

    internal fun start() {
        try {
            scope.launch {
                try {
                    var last = 0L
                    while (isActive) {
                        if (!live()) break
                        val now = System.nanoTime()
                        if (last == 0L || now - last >= every * 1_000_000) {
                            if (mayAsk()) {
                                last = now
                                beforeAsk?.invoke(this@NexusClient)
                                val found = ask()
                                // Only for the account it was asked for, only while that account is
                                // the one published, and never over a later account's client's word.
                                if (!stopped && live()) {
                                    beforePublish?.invoke(this@NexusClient)
                                    shown.update { now -> if (now == null || now.generation <= generation) NexusShown(generation, owner, found) else now }
                                }
                            }
                        }
                        asked.incrementAndGet()
                        delay(minOf(every, LOOK_MILLIS))
                    }
                } finally {
                    // Every way out (its account gone, let go of, a failure): its threads end.
                    stop()
                }
            }
        } catch (e: Exception) {
            stop()
        }
    }

    /** Asks `hello` once, and reads what answers: the address it was found at, or null. Runs
     *  on this client's own thread, and may hold it for as long as its limits allow. */
    private fun ask(): String? {
        return try {
            val origin = origin(server) ?: return null
            // Never the shared library's load: not loaded, there is no answer to be had.
            if (!Core.isReady) return null
            val client = http ?: build().also { http = it }
            val request = Request.Builder().url(origin.newBuilder().encodedPath(HELLO).build())
                .header("User-Agent", userAgent).tag(AtomicInteger::class.java, AtomicInteger()).get().build()
            val made = client.newCall(request)
            call = made
            if (stopped) made.cancel()
            calling = true
            made.execute().use { response ->
                reading = true
                // (The core reads no body but a 200's: none is waited for.)
                val (body, whole) = if (response.code == 200) read(response.body.source()) else ByteArray(0) to true
                if (Core.nexusHelloIfReady(response.code, body, whole)?.backend == true) shown(origin) else null
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // (Its coroutine is to end: not a "none found".)
            throw e
        } catch (e: Exception) {
            // (Whatever it is: an I/O failure, a refusal of the library's, a thread interrupted
            // while connecting by the client being let go of. The probe found nothing; the app
            // carries on.)
            null
        } catch (e: LinkageError) {
            null
        } finally {
            calling = false
            reading = false
            call = null
        }
    }

    /**
     * Lets it go: returns at once. What it is asking is cancelled (its socket closed, never a
     * TLS goodbye that could wait on the other end), its idle connection closed, its threads
     * interrupted, and whatever it still brings back dropped.
     */
    fun stop() {
        stopped = true
        if (!closing.compareAndSet(false, true)) return
        quiet { scope.cancel() }
        // The rest on a thread of its own: closing a socket or the pool's upkeep (which takes
        // its scheduler's lock) is never anyone else's wait.
        val closer = { close() }
        try {
            named("nexus-closer-$generation").newThread(closer).start()
        } catch (e: Exception) {
            close()
        } catch (e: Error) {
            close()
        }
    }

    /** What letting go does, every step whatever the one before it did; the executors shut
     *  down whatever happens. */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private fun close() {
        try {
            quiet { call?.cancel() }
            quiet { http?.dispatcher?.cancelAll() }
            quiet { http?.connectionPool?.evictAll() }
        } finally {
            quiet { worker.shutdownNow() }
            quiet { calls.shutdownNow() }
            quiet { backend.shutdown() }
            // (And its thread interrupted where it waits for the pool's next upkeep, which
            // shutting down alone leaves to wait out.)
            quiet { backend.executor.shutdownNow() }
        }
    }

    /** How many threads its own OkHttp scheduler has now (OkHttp names them after the task they
     *  run, so they are counted here, not by name). For the tests. */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    internal val schedulerThreads: Int get() = backend.executor.poolSize

    /** Its threads, the scheduler's too, for a test to see end (and to count). */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    internal val executors: List<ExecutorService> get() = listOf(worker, calls, backend.executor)

    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private fun build(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // HTTPS only (the design's R5): no plain HTTP, whatever the rest of the app allows.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            // HTTP/1.1: nothing of HTTP/2's reading and writing on any scheduler.
            .protocols(listOf(Protocol.HTTP_1_1))
            // A redirect is an answer, never a place to go (6.1); nothing is tried again.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // The connect on this client's own thread, not handed to a scheduler.
            .fastFallback(false)
            .connectionPool(pool(runner))
            .dispatcher(Dispatcher(calls).apply { maxRequests = 1; maxRequestsPerHost = 1 })
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
            // **One probe is one request on the wire.** OkHttp sends a follow-up of its own
            // for some answers whatever is switched off (a 503 with "Retry-After: 0", a 408, a
            // 421): the second attempt of one call is refused before it is sent, and the call
            // fails, which is "none found".
            .addNetworkInterceptor { chain ->
                val attempts = chain.request().tag(AtomicInteger::class.java)
                if (attempts != null && attempts.incrementAndGet() > 1) throw IOException("A probe sends one request, and this would have been a second.")
                sent.incrementAndGet()
                chain.proceed(chain.request())
            }
        socketsForTests?.let { builder.socketFactory(it) }
        // (OkHttp keeps the setter of a client's task runner to itself: see proguard-rules.pro.)
        OkHttpClient.Builder::class.java.getMethod("taskRunner\$okhttp", TaskRunner::class.java).invoke(builder, runner)
        return builder.build()
    }

    /** A pool of one connection, kept a minute, its upkeep on `runner`. (OkHttp keeps this
     *  constructor to itself: see proguard-rules.pro.) */
    @OptIn(okhttp3.internal.OkHttpInternalApi::class)
    private fun pool(runner: TaskRunner): ConnectionPool {
        val listener = Class.forName("okhttp3.internal.connection.ConnectionListener")
        val none = listener.getDeclaredField("Companion").get(null).let { companion -> companion.javaClass.getMethod("getNONE").invoke(companion) }
        return ConnectionPool::class.java
            .getConstructor(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, TimeUnit::class.java, TaskRunner::class.java, listener)
            .newInstance(1, 1L, TimeUnit.MINUTES, runner, none)
    }

    internal companion object {
        /** `nexus::proto::HELLO`: the backend is at the root of the music server's origin,
         *  whatever path the music server is under, always with the slash after `nexus`. */
        const val HELLO = "/nexus/api/hello"

        const val CONNECT_SECONDS = 8L
        const val READ_SECONDS = 10L
        const val CALL_SECONDS = 20L

        /** How often it looks whether a `hello` is due and may be asked. */
        const val LOOK_MILLIS = 60 * 1000L

        /** Failures that got past every catch inside the component (to its threads' own
         *  handler, or its coroutines'): for the tests, which hold it to none. */
        val escaped = AtomicInteger()

        /** For the tests only: run on the worker before each `hello`. */
        @Volatile var beforeAsk: ((NexusClient) -> Unit)? = null

        /** For the tests only: run on the worker after its checks, before it publishes. */
        @Volatile var beforePublish: ((NexusClient) -> Unit)? = null

        /** For the tests only: the sockets a client is made with (one whose connect never
         *  completes). */
        @Volatile var socketsForTests: javax.net.SocketFactory? = null

        /** The origin of the account's music server, if it is HTTPS; otherwise there is no
         *  backend to ask (the backend's line is HTTPS only). */
        fun origin(server: String): HttpUrl? {
            val url = server.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            return HttpUrl.Builder().scheme("https").host(url.host).port(url.port).build()
        }

        /** How the origin is shown in the row: its host, and its port if not 443 (an IPv6
         *  address in brackets when a port follows it). */
        fun shown(origin: HttpUrl): String {
            val host = if (':' in origin.host) "[${origin.host}]" else origin.host
            return if (origin.port == 443) origin.host else "$host:${origin.port}"
        }

        /**
         * Reads a body as the core asks for it ([Core.nexusHello]): to its end, or to one byte
         * past the most a `hello` may be, and whether reading failed before either.
         */
        fun read(source: okio.BufferedSource): Pair<ByteArray, Boolean> {
            val most = Core.HELLO_MAX_BYTES + 1L
            val got = Buffer()
            val whole = try {
                while (got.size < most) {
                    if (source.read(got, most - got.size) == -1L) break
                }
                true
            } catch (_: IOException) {
                false
            }
            return got.readByteArray() to whole
        }
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

/** A failure in the component's own coroutines ends there: never Android's handler for an
 *  uncaught exception, which would end the app. (Counted, for the tests: every failure is
 *  meant to be caught before it gets here.) */
private val quietly = CoroutineExceptionHandler { _, _ -> NexusClient.escaped.incrementAndGet() }

private fun named(name: String) = java.util.concurrent.ThreadFactory { work ->
    Thread(work, name).apply {
        isDaemon = true
        // (And should anything slip past every catch on one of its threads, it ends that
        // thread, not the app.)
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> NexusClient.escaped.incrementAndGet() }
    }
}
