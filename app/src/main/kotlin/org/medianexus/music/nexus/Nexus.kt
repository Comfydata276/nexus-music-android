package org.medianexus.music.nexus

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.medianexus.music.AccountGraph
import org.medianexus.music.core.Core
import org.medianexus.music.core.CoreException

/**
 * The phone's side of the Nexus server, the backend of our own beside the music server
 * (docs/backend-design.md), **at its very smallest** (slice P1): it asks the backend's `hello`
 * once when an account is taken on and every hour after, and says what it found, for one row
 * under Settings. It reads, sends and keeps nothing else: no sign-in, no plays, no file.
 *
 * **Nothing waits for it** (the design's 5.1, and its rule R1). It is not made inside the
 * account's objects, nor by anything `Accounts.restored` waits for: [NexusLauncher] makes one
 * for each account **after** the account has been published, on a thread of its own. It has
 * its own HTTP client, connection pool, dispatcher, threads and limits, so none of the music
 * server's connections or the app's workers is ever one of its. It is reached through one
 * seam, [NexusLauncher.found], which the row reads. When the account changes it is let go of
 * at once, without being waited for, and stops by itself afterwards; whatever it still brings
 * back is dropped.
 */
sealed interface NexusFound {
    /** Nothing found: not asked yet, or what answered at `/nexus/` is not the backend. */
    data object None : NexusFound

    /** The backend answered `hello` at the music server's own origin; `address` is how that
     *  origin is shown (its host, and its port if it is not HTTPS's own). */
    data class At(val address: String) : NexusFound
}

/**
 * Makes the [NexusClient] for whoever is signed in, after they have been published, and lets
 * it go when they change. Made with the app's objects (`Graph`), it does its work on a thread
 * of its own: taking an account on is never held up by it, and nothing it does is in the way
 * of anything else.
 */
class NexusLauncher(
    accounts: StateFlow<AccountGraph?>,
    /** The app's work: the launcher ends with it. */
    parent: CoroutineScope,
    private val userAgent: String,
    /** How often `hello` is asked again. */
    private val every: Long = HOURLY_MILLIS,
) {
    private val thread: ExecutorService = Executors.newSingleThreadExecutor(named("nexus-launcher"))
    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext[Job]) + thread.asCoroutineDispatcher())
    private val lock = Any()
    private val shown = MutableStateFlow<NexusFound>(NexusFound.None)

    /** What the row under Settings says, for the account signed in now. */
    val found: StateFlow<NexusFound> = shown.asStateFlow()

    /** The launcher's own thread, for a test to hold. */
    internal val workers: List<ExecutorService> get() = listOf(thread)

    /** The component of the account signed in now; null with nobody. (For the tests.) */
    @Volatile internal var current: NexusClient? = null
        private set

    init {
        scope.launch {
            // (An account published is a new one only if it is another set of the account's
            // objects: StateFlow's own sameness is by equality.)
            accounts.distinctUntilChanged { a, b -> a === b }.collect { account -> taken(account) }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            synchronized(lock) { current?.stop(); current = null }
            thread.shutdown()
        }
    }

    private fun taken(account: AccountGraph?) {
        val made = account?.let { NexusClient(it.account.server, userAgent, every) }
        synchronized(lock) {
            // Let go of at once, never waited for.
            current?.stop()
            current = made
            shown.value = NexusFound.None
        }
        made?.start { found -> synchronized(lock) { if (current === made) shown.value = found } }
    }

    companion object {
        const val HOURLY_MILLIS = 60 * 60 * 1000L
    }
}

/**
 * The backend's client for one account: one thread for its work, one for its HTTP client's
 * dispatcher, a connection pool of one, and limits of its own. [stop] lets it go: it returns
 * at once, whatever the client is in the middle of, and the threads end by themselves.
 */
class NexusClient internal constructor(
    /** The account's music server, as the person typed it. */
    private val server: String,
    private val userAgent: String,
    private val every: Long,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(named("nexus-worker"))
    private val calls: ExecutorService = Executors.newSingleThreadExecutor(named("nexus-calls"))
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    @Volatile private var http: OkHttpClient? = null
    @Volatile private var call: Call? = null
    @Volatile private var stopped = false

    /** Every thread of its own, for a test to hold and to see end. */
    internal val workers: List<ExecutorService> get() = listOf(worker, calls)

    /** How many times `hello` has been asked and answered, or failed, and what was found said.
     *  (For the tests.) */
    internal val asked = AtomicInteger()

    /** Whether it has been let go of. */
    val isStopped: Boolean get() = stopped

    internal fun start(told: (NexusFound) -> Unit) {
        scope.launch {
            while (isActive) {
                val found = ask()
                if (!stopped) told(found)
                asked.incrementAndGet()
                delay(every)
            }
        }
    }

    /** Asks `hello`, and reads what answers. Runs on this client's own thread, and may block
     *  it for as long as its limits allow. */
    private fun ask(): NexusFound {
        val origin = origin(server) ?: return NexusFound.None
        val client = http ?: build().also { http = it }
        val request = Request.Builder().url(origin.newBuilder().encodedPath(HELLO).build()).header("User-Agent", userAgent).get().build()
        return try {
            val made = client.newCall(request)
            call = made
            if (stopped) made.cancel()
            made.execute().use { response ->
                val (body, whole) = read(response.body.source())
                if (Core.nexusHello(response.code, body, whole).backend) NexusFound.At(shown(origin)) else NexusFound.None
            }
        } catch (_: IOException) {
            NexusFound.None
        } catch (_: CoreException) {
            NexusFound.None
        } finally {
            call = null
        }
    }

    /**
     * Lets it go: returns at once. What it is asking is cancelled (its socket closed, never a
     * TLS goodbye that could wait on the other end), its threads are interrupted and end, and
     * whatever it still brings back is dropped.
     */
    fun stop() {
        stopped = true
        scope.cancel()
        call?.cancel()
        http?.dispatcher?.cancelAll()
        worker.shutdownNow()
        calls.shutdownNow()
    }

    private fun build(): OkHttpClient = OkHttpClient.Builder()
        // HTTPS only (the design's R5): no plain HTTP, whatever the phone otherwise allows.
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        // A redirect is an answer, never a place to go (6.1).
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        // Its own: none of the music server's connections or the app's workers is ever this.
        .connectionPool(ConnectionPool(1, 1, TimeUnit.MINUTES))
        .dispatcher(Dispatcher(calls).apply { maxRequests = 1; maxRequestsPerHost = 1 })
        .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
        .build()

    internal companion object {
        /** `nexus::proto::HELLO`: the backend is at the root of the music server's origin,
         *  whatever path the music server is under, always with the slash after `nexus`. */
        const val HELLO = "/nexus/api/hello"

        const val CONNECT_SECONDS = 8L
        const val READ_SECONDS = 10L
        const val CALL_SECONDS = 20L

        /** The origin of the account's music server, if it is HTTPS; otherwise there is no
         *  backend to ask (the backend's line is HTTPS only). */
        fun origin(server: String): HttpUrl? {
            val url = server.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            return HttpUrl.Builder().scheme("https").host(url.host).port(url.port).build()
        }

        /** How the origin is shown in the row: its host, and its port if not 443. */
        fun shown(origin: HttpUrl): String = if (origin.port == 443) origin.host else "${origin.host}:${origin.port}"

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

private fun named(name: String) = java.util.concurrent.ThreadFactory { work -> Thread(work, name).apply { isDaemon = true } }
