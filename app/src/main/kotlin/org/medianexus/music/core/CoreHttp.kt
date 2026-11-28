package org.medianexus.music.core

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
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
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
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
 * one call). Redirects are never followed for Lidarr and its sign-in, where a redirect is
 * how a sign-in shows itself; for MusicBrainz and ListenBrainz they are followed by hand
 * and narrowly ([onward]): `https` to `https`, never into a private network or to the
 * music server, never for a `POST`, five times at the most.
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
 *   came first is still honoured: the change it was about to make is refused. Each of
 *   its requests has [changeMillis] in all, so that what nobody can stop still ends;
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

    /** How many more may wait their turn for a thread, of each kind. One more than that is
     *  told the app is busy ([Busy]) and is not left waiting. */
    const val LOOKING_WAITING = 32
    const val CHANGING_WAITING = 8

    /** What is said to whoever asks while every thread is taken and every place in the
     *  queue too. */
    const val BUSY = "The app is busy with other requests. Try again in a moment."

    /** There was no room for it (see [LOOKING_WAITING]): it was not begun. */
    class Busy : RuntimeException(BUSY)

    /** After an operation that changes something has been stopped: how long each of its
     *  requests may still take. */
    @Volatile var afterStopMillis = 10_000L

    /** And for how long after the stop its requests are still let through at all. */
    @Volatile var graceMillis = 30_000L

    /**
     * How long any one request of something that changes Lidarr or the sign-in may take in
     * all, its answer included, where the library sets no shorter limit of its own. There
     * are two threads for changes ([CHANGING]); without this, an answer that arrives a
     * letter at a time could hold one for as long as it liked, and once the library has
     * said to keep going nobody can stop it.
     */
    @Volatile var changeMillis = 120_000L

    /** How long each request of a sign-in being finished may take in all, its answer
     *  included: the browser is kept waiting for its page meanwhile (see [finishing]). */
    @Volatile var signInMillis = 8_000L

    /** How long finishing a sign-in may take **in all**, however many requests it makes. */
    @Volatile var signInWholeMillis = 12_000L

    /**
     * Whether requests may not be sent at all just now, and if so the words for why (the end
     * of the library's own sentence, as in "Couldn't reach Lidarr. Offline mode is on.").
     * Set by whoever owns the signed-in account; asked before every request.
     */
    @Volatile var unsent: () -> String? = { null }

    /** For the tests, which stand in for MusicBrainz and ListenBrainz on this machine:
     *  whether those two may be reached only at public addresses. Nothing in the app
     *  changes it. */
    @Volatile var publicOnly = true

    /** For the tests: something done to the client as it is built (a stand-in for a service
     *  whose address the library fixes). Nothing in the app sets it. */
    @Volatile var dress: (OkHttpClient.Builder) -> Unit = {}
        set(value) {
            field = value
            lines = null
        }

    // ---- the words for what goes wrong --------------------------------------------------------

    private const val NOT_MADE = "The app couldn't make the request."
    private const val IN_TIME = "It didn't answer in time."
    private const val BAD_ADDRESS = "That address isn't one the app can use."
    private const val TOO_LARGE = "The answer was larger than 10 MB."
    private const val GONE = "The answer is no longer there to read."
    private const val STOPPED_WORDS = "Stopped."
    private const val HOME = "It was for the music server's own address, which requests are never sent to."
    private const val INSIDE = "It pointed at an address inside a private network."
    private const val ELSEWHERE = "The request would have carried a key to an address it wasn't given for, so it wasn't sent."

    /** Why a request failed on its way, to end the library's sentence with ("Couldn't reach
     *  Lidarr. …"). Never the exception's own words, which name the host and can quote more. */
    private fun words(error: IOException): String = if (error is NotPublic) INSIDE else when (netTrouble(error)) {
        NetTrouble.REDIRECT -> "It tried to send the request on somewhere it shouldn't go."
        NetTrouble.CERTIFICATE_FOR_ANOTHER -> "Its certificate is for a different address."
        NetTrouble.CERTIFICATE_NOT_TRUSTED -> "Its certificate isn't one this phone trusts."
        NetTrouble.NAME_NOT_FOUND -> "Its name couldn't be found."
        NetTrouble.REFUSED -> "The connection was refused."
        NetTrouble.TIMED_OUT -> IN_TIME
        NetTrouble.SECURE_CONNECTION -> "The secure connection failed."
        NetTrouble.NOT_ALLOWED -> "The phone didn't allow the connection."
        NetTrouble.NO_WAY -> "There's no way to it from this network."
        NetTrouble.DROPPED -> "The connection dropped."
        NetTrouble.NO_CONNECTION -> "Couldn't connect."
        NetTrouble.OTHER -> "The connection failed."
    }

    // ---- what became of the last request to Lidarr or its sign-in -------------------------------

    /**
     * **What became of the last request on Lidarr's line** (Lidarr's own and its sign-in's),
     * for the diagnostics line about the last attempt to connect requests: in fixed words
     * ("timed out", "answered 302"), and the kind of address the connection was made to
     * ("IPv4", "IPv6"; null if none was made). Never an address, a header or a body. `at` is
     * [System.nanoTime] when it ended, so that whoever reads it can tell it is their own.
     */
    class LastOnLidarr(
        val at: Long, val ended: String, val family: String?,
        /** How far its connection got ([Way]), in counts and fixed words; null if it was
         *  never begun. */
        val way: String? = null,
        /** Whether its connection opened at all (TCP up, or one already open was used):
         *  false for a time-out or a failure while connecting. */
        val opened: Boolean = false,
    )

    @Volatile var lastOnLidarr: LastOnLidarr? = null
        private set

    /** The kind of address the request in flight on this thread was sent to, if it was sent. */
    private val family = ThreadLocal<String?>()

    private fun ended(asked: Asked, words: String) {
        if (asked.line == "lidarr") lastOnLidarr = LastOnLidarr(System.nanoTime(), words, family.get(), wayNow.get()?.words(), wayNow.get()?.opened == true)
    }

    /** [Way.words] of the request just made on this thread. */
    private val wayNow = ThreadLocal<Way?>()

    /** Each request on Lidarr's line in flight, and how far its connection has got. */
    private val ways = ConcurrentHashMap<Call, Way>()

    /**
     * **How far a request's connection got**, for the diagnostics line: how many addresses
     * of each kind its name gave, and for each kind tried, the furthest it got (connecting,
     * securing, connected, reused, asked, answering, or failed while one of those). So a
     * request that timed out says where: "the name gave 2 IPv4 and 2 IPv6, IPv6 securing,
     * IPv4 connected, IPv4 asked". Counts and fixed words only, never an address. (OkHttp
     * tries a second address beside the first when the first is slow to connect, on
     * threads of its own: hence the lock.)
     */
    private class Way : okhttp3.EventListener() {
        private val lock = Any()
        private var looked: String? = null
        private val stages = LinkedHashMap<String, String>()
        private var chosen: String? = null

        /** Some connection of its opened: TCP up, or one already open used. */
        @Volatile var opened = false
        private val trying = ThreadLocal<String?>()

        private fun kind(address: java.net.InetAddress?): String = if (address is java.net.Inet6Address) "IPv6" else "IPv4"

        private fun at(kind: String?, stage: String) {
            if (kind != null) synchronized(lock) { stages[kind] = stage }
        }

        override fun dnsStart(call: Call, domainName: String) {
            synchronized(lock) { looked = "looking up the name" }
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
            val six = inetAddressList.count { it is java.net.Inet6Address }
            synchronized(lock) { looked = "the name gave ${inetAddressList.size - six} IPv4 and $six IPv6" }
        }

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            val kind = kind(inetSocketAddress.address)
            trying.set(kind)
            at(kind, "connecting")
        }

        override fun secureConnectStart(call: Call) {
            opened = true
            at(trying.get(), "securing")
        }

        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
            opened = true
            at(kind(inetSocketAddress.address), "connected")
        }

        override fun connectFailed(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: IOException) {
            val kind = kind(inetSocketAddress.address)
            synchronized(lock) { stages[kind] = "failed while ${stages[kind] ?: "connecting"}" }
        }

        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
            opened = true
            val kind = kind(connection.route().socketAddress.address)
            synchronized(lock) {
                if (kind !in stages) stages[kind] = "reused"
                chosen = kind
            }
        }

        override fun requestHeadersEnd(call: Call, request: Request) = at(synchronized(lock) { chosen }, "asked")

        override fun responseHeadersStart(call: Call) = at(synchronized(lock) { chosen }, "answering")

        fun words(): String = synchronized(lock) { (listOfNotNull(looked) + stages.map { (kind, stage) -> "$kind $stage" }).joinToString(", ") }
    }

    /** The same as [words], as a fixed word for the diagnostics line. */
    private fun word(error: IOException): String = when (netTrouble(error)) {
        NetTrouble.REDIRECT -> "redirect refused"
        NetTrouble.CERTIFICATE_FOR_ANOTHER -> "certificate for another name"
        NetTrouble.CERTIFICATE_NOT_TRUSTED -> "certificate not trusted"
        NetTrouble.NAME_NOT_FOUND -> "name not found"
        NetTrouble.REFUSED -> "connection refused"
        NetTrouble.TIMED_OUT -> "timed out"
        NetTrouble.SECURE_CONNECTION -> "secure connection failed"
        NetTrouble.NOT_ALLOWED -> "not allowed by the phone"
        NetTrouble.NO_WAY -> "no route"
        NetTrouble.DROPPED -> "connection dropped"
        NetTrouble.NO_CONNECTION -> "couldn't connect"
        NetTrouble.OTHER -> "connection failed"
    }

    // ---- where a secret may go -----------------------------------------------------------------

    /**
     * **The place an address is, as this client takes it**: its scheme, host and port as
     * OkHttp reads them, which is where a request to that address really goes. Null for
     * what the client can't use as an address at all.
     *
     * A secret is kept together with this (see `requests/RequestsSecrets`), and a request
     * that carries one is sent only here ([sent]). It is this client's reading and nobody
     * else's on purpose: an address can be written so that another reader takes it for one
     * host while the client connects to another (`http://a.example\@b.example`), and what
     * matters is where the bytes go.
     */
    fun originOf(address: String): String? = address.trim().toHttpUrlOrNull()?.let(::origin)

    private fun origin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

    /**
     * Whether this client takes `address` for the place the library takes it for (its
     * scheme, host and port, as `lidarr::origin` read them), with no name or password in
     * it. An address the two would read differently is one neither may use.
     */
    fun readsAs(address: String, scheme: String, host: String, port: Int): Boolean {
        val url = address.trim().toHttpUrlOrNull() ?: return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.scheme != scheme || url.port != port) return false
        // (An IPv6 address can be written many ways, and each reader has its own: its
        // spelling is not compared.)
        return host.startsWith("[") || url.host == host
    }

    /** Whether an address has a name or a password written into it (`http://me:secret@host`). */
    fun namesSomeone(address: String): Boolean = address.trim().toHttpUrlOrNull()?.let { it.username.isNotEmpty() || it.password.isNotEmpty() } ?: false

    /**
     * Whether the browser may be opened at `link`, the page of a sign-in service whose
     * place is `service`, for an account whose music server is at `home`: **judged as any
     * request to the sign-in service is**. It is at that service's own place as this
     * client reads the address; it has no name or password written into it; and it is not
     * the music server's.
     */
    fun mayOpen(link: String, service: String?, home: String?): Boolean {
        val place = originOf(link) ?: return false
        return service != null && place == service && place != home && !namesSomeone(link)
    }

    /** Whether a request carries something secret: a key or a pass in its headers, or a body
     *  at all (the sign-in's code, its check and its token travel as bodies). */
    private fun carriesSecret(asked: Asked): Boolean =
        asked.method != "GET" || asked.headers.any { it[0].equals("X-Api-Key", ignoreCase = true) || it[0].equals("Authorization", ignoreCase = true) }

    // ---- where the public services may lead ----------------------------------------------------

    /** How many times a public service may send a request on. */
    private const val ONWARD = 5

    private class NotPublic : IOException("not a public address")

    /** Whether an address is inside some network rather than on the public internet: this
     *  phone itself, the link it is on, a private range, a carrier's shared one. */
    private fun isInside(address: InetAddress): Boolean {
        val bytes = address.address
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
            // (fc00::/7, the private range of IPv6; 100.64.0.0/10, shared by carriers.)
            (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) ||
            (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64)
    }

    /** The same for a host written as numbers (which is never looked up, so [Public] never
     *  sees it), and for the names every machine has for itself. */
    private fun isInside(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name == "localhost" || name.endsWith(".localhost")) return true
        if (!name.contains(':') && !name.all { it.isDigit() || it == '.' }) return false
        return try {
            isInside(InetAddress.getByName(host))
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Names are looked up as the phone looks them up, and one that turns out to be inside
     * a network is refused: for MusicBrainz and ListenBrainz, which are public services
     * and have no business there, wherever an answer of theirs points.
     *
     * **It is the name a request is for that is judged** ([asking]), not every name the
     * client looks up: with a proxy set on the phone the client looks up the proxy's name
     * and no other, and a proxy is usually inside the network the phone is on. The name the
     * request is for is then never looked up here at all: the proxy does that, and where
     * it leads is the proxy's to say (see docs/DESIGN.md, "With a proxy").
     */
    private object Public : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val found = Dns.SYSTEM.lookup(hostname)
            if (publicOnly && isAsked(hostname) && found.any(::isInside)) throw NotPublic()
            return found
        }
    }

    /** The names requests to the public services are for, each for as long as its request
     *  is being sent; and how many requests each. */
    private val publicNames = HashMap<String, Int>()

    private fun asking(name: String, by: Int) = synchronized(publicNames) {
        val now = (publicNames[name] ?: 0) + by
        if (now <= 0) publicNames.remove(name) else publicNames[name] = now
    }

    private fun isAsked(name: String): Boolean = synchronized(publicNames) { name.lowercase() in publicNames }

    /**
     * Where a public service's answer sends a request on to, **if it may be followed**; null
     * if the answer is to go back to the library as it came. Followed by hand, one step at
     * a time, so that each step is judged:
     *
     * - only for MusicBrainz and ListenBrainz (Lidarr and its sign-in are never followed: a
     *   redirect is how a sign-in shows itself, and the request carries Lidarr's key);
     * - only a `GET` (a `POST` sent on would carry its body with it);
     * - only from `https` to `https`;
     * - never to this phone, the link it is on or a private network, and never to the
     *   music server;
     * - [ONWARD] times at the most.
     */
    private fun onward(asked: Asked, from: HttpUrl, response: Response, steps: Int): HttpUrl? {
        if (asked.line == "lidarr" || asked.method != "GET" || steps >= ONWARD) return null
        if (response.code !in intArrayOf(301, 302, 303, 307, 308)) return null
        val to = response.header("Location")?.let(from::resolve) ?: return null
        if (!from.isHttps || !to.isHttps) return null
        if (publicOnly && isInside(to.host)) return null
        if (asked.home != null && origin(to) == asked.home) return null
        return to
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

        /** `public`: a service on the public internet, reached nowhere else ([Public]).
         *  No client here follows a redirect by itself: see [onward]. */
        private fun line(idleSeconds: Long, public: Boolean): Pair<OkHttpClient, OkHttpClient> {
            val looks = base.newBuilder()
                .connectionPool(ConnectionPool(4, idleSeconds, TimeUnit.SECONDS))
                .apply { if (public) dns(Public) }
                // (Lidarr's line: which kind of address the connection went to, for the
                // diagnostics line. It looks; it changes nothing.)
                .apply {
                    if (!public) eventListenerFactory { call -> Way().also { ways[call] = it } }
                    if (!public) addNetworkInterceptor { chain ->
                        chain.connection()?.socket()?.inetAddress?.let { family.set(if (it is java.net.Inet6Address) "IPv6" else "IPv4") }
                        chain.proceed(chain.request())
                    }
                }
                .build()
            // A request that changes something is sent once: never again by the client on
            // its own, which could not know whether the first had been acted on. (Not after
            // a connection that failed, here; and not after an answer that invites it: see
            // [Once].)
            return looks to looks.newBuilder().retryOnConnectionFailure(false).build()
        }

        private val lidarr = line(300, public = false)
        private val musicbrainz = line(12, public = true)
        private val listenbrainz = line(50, public = true)

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
    class Doing(
        val id: Long, val changing: Boolean,
        /** The longest any one request of this may take in all, if it has a limit of its own. */
        private val callMillis: Long? = null,
        /** The longest **the whole of it** may take, from when it was begun, however many
         *  requests it makes: what is left of that is the most its next request may take. */
        private val wholeMillis: Long? = null,
    ) {
        private val began = System.nanoTime()

        /** The limit a request of this goes under now, not having been stopped; [SPENT] if
         *  the whole of its time has gone, and then no request is made at all. */
        private fun limit(): Long? {
            val whole = wholeMillis ?: return callMillis
            val left = whole - (System.nanoTime() - began) / 1_000_000
            return if (left <= 0) SPENT else minOf(callMillis ?: Long.MAX_VALUE, left)
        }

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
         * null for none, a number of milliseconds once it has been stopped, [REFUSED] when
         * no more are to be sent because it was stopped, and [SPENT] when the whole of its
         * time has gone.
         */
        fun allowance(): Long? = lock.withLock {
            when {
                !stopped -> limit()
                !changing -> REFUSED
                System.nanoTime() - stoppedAt > TimeUnit.MILLISECONDS.toNanos(graceMillis) -> REFUSED
                // (Stopped, and its own time spent as well: stopped is what it is.)
                else -> when (val own = limit()) {
                    SPENT -> REFUSED
                    else -> minOf(afterStopMillis, own ?: Long.MAX_VALUE)
                }
            }
        }

        /**
         * Notes a request as in flight, **and says under what limit of this operation's it
         * goes** (as [allowance]: null for none), or [REFUSED] if it may no longer be sent.
         * Both at once, so that a stop cannot fall between being allowed and being in
         * flight and leave a request neither cut off nor limited.
         */
        fun enter(call: Call): Long? = lock.withLock {
            val allowed = allowance()
            if (allowed != REFUSED && allowed != SPENT) flying += call
            allowed
        }

        fun leave(call: Call) {
            lock.withLock { flying -= call }
        }

        companion object {
            const val REFUSED = -1L

            /** The whole of the operation's time has gone: nothing more is sent, and what
             *  would have been ends as a request that wasn't answered in time does. */
            const val SPENT = -2L
        }
    }

    private val doings = ConcurrentHashMap<Long, Doing>()
    private val nextDoing = AtomicLong(0)

    private fun begin(changing: Boolean, callMillis: Long? = null, wholeMillis: Long? = null): Doing =
        Doing(nextDoing.incrementAndGet(), changing, callMillis, wholeMillis).also { doings[it.id] = it }

    private fun end(doing: Doing) {
        doings.remove(doing.id)
        publishers.remove(doing.id)
        // (The library lets go of every answer it was given; this is for one it didn't.)
        bodies.entries.removeIf { (_, held) ->
            (held.doing === doing).also { if (it) closeQuietly(held) }
        }
    }

    private fun pool(name: String, threads: Int, waiting: Int): Lazy<Executor> = lazy {
        val count = AtomicInteger(0)
        // (A queue with an end to it: what it has no room for is refused, and told so.)
        ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(waiting)) { work ->
            Thread(work, "requests-$name-${count.incrementAndGet()}").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    private val lookers by pool("look", LOOKING, LOOKING_WAITING)
    private val changers by pool("change", CHANGING, CHANGING_WAITING)

    /**
     * Runs something of the library's that only looks, on a thread kept for that, and waits
     * for it without holding the caller's thread. Cancelling the caller stops it: the caller
     * is let go at once, and what the library was doing ends behind it.
     */
    suspend fun <T> looking(act: (operation: Long) -> T): T = run(begin(changing = false), lookers, act)

    /** The same, for something that hands over what it has found so far as it goes (a
     *  search): `published` is called with each, on the library's thread, and never once the
     *  operation has been stopped. */
    suspend fun <T> looking(published: (ByteArray) -> Unit, act: (operation: Long) -> T): T {
        val doing = begin(changing = false)
        publishers[doing.id] = published
        return run(doing, lookers, act)
    }

    private val publishers = ConcurrentHashMap<Long, (ByteArray) -> Unit>()

    /** The library has found something more for an operation that shows what it finds as it
     *  goes (see [looking]). Nothing is handed on after a stop. */
    fun published(operation: Long, found: ByteArray) {
        try {
            val doing = doings[operation] ?: return
            if (doing.isStopped) return
            publishers[operation]?.invoke(found)
        } catch (_: Exception) {
        }
    }

    /** The same for something that changes Lidarr or the sign-in in one step: stopped, the
     *  request in flight is let finish, behind the caller's back. */
    suspend fun <T> changing(act: (operation: Long) -> T): T = run(begin(changing = true, callMillis = changeMillis), changers, act)

    /** The same, begun and not waited for: for whoever must know how it ended though the
     *  screen that asked has gone (to write down what Lidarr accepted). Stopping it is
     *  as stopping [changing]. */
    fun <T> changingThrough(act: (operation: Long) -> T): Begun<T> = begun(begin(changing = true, callMillis = changeMillis), act)

    /** A thread of its own for each thing run on it: for what must never wait its turn. */
    private val apart = Executor { work -> Thread(work, "requests-sign-in").apply { isDaemon = true }.start() }

    /**
     * Finishing a sign-in: the browser has come back with a code, and is kept waiting for
     * its page until this is done. So it **never waits its turn** behind other changes (a
     * thread of its own), and **cannot take for ever**: each of its requests has
     * [signInMillis] in all, **and the whole of it [signInWholeMillis]** (eight seconds and
     * twelve), after which the library says in its own words that the service didn't
     * answer in time. Otherwise as [changing].
     */
    suspend fun <T> finishing(act: (operation: Long) -> T): T = run(begin(changing = true, callMillis = signInMillis, wholeMillis = signInWholeMillis), apart, act)

    /**
     * The same for something the library sees through once it has begun to change Lidarr
     * (asking for an album, adding an artist): cancelling the caller stops it only if the
     * library has not yet said to keep going. After that it runs to its end behind the
     * caller, who is let go at once all the same, **and never hears how it ended**: whoever
     * must know (to write down what was asked for) uses [seeingThrough].
     */
    suspend fun <T> seenThrough(act: (operation: Long) -> T): T = run(begin(changing = false, callMillis = changeMillis), changers, act)

    /** Something begun with [seeingThrough]: how it ended, whenever that is, and a way to
     *  stop it, which the library may no longer honour. */
    class Begun<T> internal constructor(val done: Deferred<T>, private val doing: Doing) {
        fun stop() = doing.stop()
    }

    /** Starts something the library sees through, without waiting for it. */
    fun <T> seeingThrough(act: (operation: Long) -> T): Begun<T> = begun(begin(changing = false, callMillis = changeMillis), act)

    private fun <T> begun(doing: Doing, act: (operation: Long) -> T, pool: Executor = changers): Begun<T> {
        val done = CompletableDeferred<T>()
        try {
            pool.execute {
                val outcome = if (doing.isStopped) Result.failure(CancellationException(STOPPED_WORDS)) else runCatching { act(doing.id) }
                end(doing)
                outcome.fold(done::complete, done::completeExceptionally)
            }
        } catch (_: RejectedExecutionException) {
            end(doing)
            done.completeExceptionally(Busy())
        }
        return Begun(done, doing)
    }

    /** Runs something of the library's that makes no request an operation could own (opening
     *  and closing a sign-in, which can wait on a renewal) off the caller's thread. It is
     *  not stopped by cancelling the caller: it finishes behind it. */
    suspend fun <T> aside(act: () -> T): T = suspendCancellableCoroutine { waiting ->
        val work = Runnable { waiting.resumeWith(runCatching(act)) }
        try {
            changers.execute(work)
        } catch (_: RejectedExecutionException) {
            // (Never refused: these are a sign-in being opened or let go of, which nobody
            // can be told to try again. A thread of its own, this once.)
            apart.execute(work)
        }
    }

    private suspend fun <T> run(doing: Doing, pool: Executor, act: (Long) -> T): T = suspendCancellableCoroutine { waiting ->
        waiting.invokeOnCancellation { doing.stop() }
        try {
            pool.execute {
                // (Stopped while it waited its turn: it never starts.)
                val outcome = if (doing.isStopped) Result.failure(CancellationException(STOPPED_WORDS)) else runCatching { act(doing.id) }
                end(doing)
                // (Ignored if the caller has been cancelled meanwhile: nothing is published
                // after a stop.)
                waiting.resumeWith(outcome)
            }
        } catch (_: RejectedExecutionException) {
            // (No thread and no place in the queue: said at once, not waited out.)
            end(doing)
            waiting.resumeWith(Result.failure(Busy()))
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

    // ---- the way back from a change to Lidarr ---------------------------------------------------

    private val desks = ConcurrentHashMap<Long, (ByteArray?, Boolean) -> Boolean>()

    /**
     * Says who writes down, for the place the library knows by this number, **what must be
     * put back on Lidarr** (the library's own record of it, as bytes; none: remove what is
     * written), and says whether it was written. `surely`: on the disk before it answers.
     * Called on the library's thread, never the main one.
     */
    fun wayBackKeptBy(place: Long, keep: (batch: ByteArray?, surely: Boolean) -> Boolean) {
        desks[place] = keep
    }

    fun wayBackForgotten(place: Long) {
        desks.remove(place)
    }

    /** A thread of its own for each thing run on it, as [apart]. */
    private val backing = Executor { work -> Thread(work, "requests-put-back").apply { isDaemon = true }.start() }

    /**
     * Putting back what an earlier run left changed on Lidarr. The library looks at Lidarr
     * every few seconds until it has finished with the artist, which can take minutes: so
     * it has **a thread of its own**, and is never in the way of what the person asks for.
     * Begun, and not waited for: stopping it ends the wait and the request in flight at
     * once, and **how it ended is known only when the library's worker has ended**
     * ([Begun.done]), which is what whoever must not start another beside it waits for.
     * What it had not put back is still written down, for the next time.
     */
    fun <T> puttingBack(act: (operation: Long) -> T): Begun<T> = begun(begin(changing = false, callMillis = changeMillis), act, backing)

    // ---- what the library calls ----------------------------------------------------------------
    //
    // (Each by its name, from `native/src/wire.rs`: they are kept under these names in a
    // release build by app/proguard-rules.pro. None throws: whatever goes wrong is answered
    // in the bytes, and the library has its own word for an answer that never came.)

    @Serializable
    private class Asked(
        val operation: Long, val line: String, val method: String, val url: String,
        val headers: List<List<String>> = emptyList(), val body: String? = null, val patience: Long? = null,
        /** Where alone this request may carry a secret to (`wire::Host::bound`): the place
         *  the secret was given for. Null for a way out that carries none. */
        val bound: String? = null,
        /**
         * **The music server's own place, for the account this request is made for** (its
         * scheme, host and port, as [originOf] has them). Nothing of requests is sent
         * there, by a redirect or otherwise: the music server has a client of its own, with
         * its sign-in, and nothing that answers at Lidarr's address, at a sign-in service's
         * or at a public service's may point this one at it. It comes with the request,
         * from whatever began the operation, and not from anything the app holds as a
         * whole: work that was begun outlives the account it was begun for.
         */
        val home: String? = null,
        /** This is a request to **Lidarr's own API at the address the person typed**
         *  (`wire::Host::typed`): the one thing that may go to [home], if that is where they
         *  said Lidarr is (one proxy, two paths). Not the sign-in's traffic, whatever
         *  service was found, and not a look at where a redirect points. */
        val typed: Boolean = false,
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
        family.set(null)
        wayNow.set(null)
        return sending(asked).also { out ->
            when (out.firstOrNull()) {
                FAILED -> if (lastOnLidarrNow == null) ended(asked, "not sent")
                STOPPED -> ended(asked, "stopped")
            }
            lastOnLidarrNow?.let { ended(asked, it) }
            lastOnLidarrNow = null
        }
    }

    /** What [sending] came to on this thread, in a fixed word, when it went on its way. */
    private var lastOnLidarrNow: String?
        get() = endedNow.get()
        set(value) = endedNow.set(value)

    private val endedNow = ThreadLocal<String?>()

    private fun sending(asked: Asked): ByteArray {
        unsent()?.let { return failed(it) }
        val doing: Doing? = if (asked.operation == 0L) null else doings[asked.operation] ?: return stopped
        when (doing?.allowance()) {
            Doing.REFUSED -> return stopped
            Doing.SPENT -> {
                lastOnLidarrNow = "out of time"
                return failed(IN_TIME)
            }
        }

        val url = asked.url.toHttpUrlOrNull() ?: return failed(BAD_ADDRESS)
        // A secret goes to the place it was given for and nowhere else, whatever address the
        // library was handed or made of it.
        if (asked.bound != null && carriesSecret(asked) && origin(url) != asked.bound) return failed(ELSEWHERE)
        // Nothing goes to the music server from here, but Lidarr's own API if that is where
        // the person said Lidarr is.
        if (asked.home != null && origin(url) == asked.home && !(asked.typed && asked.bound == asked.home)) return failed(HOME)
        if (asked.line != "lidarr" && publicOnly && isInside(url.host)) return failed(INSIDE)
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
                "POST" -> post(Once(body))
                "PUT" -> put(Once(body))
                else -> return failed(NOT_MADE)
            }
        }.build()
        val client = lines().of(asked.line, changes = asked.method != "GET") ?: return failed(NOT_MADE)
        var going = request
        var steps = 0
        while (true) {
            val call = client.newCall(going)
            val allowed = if (doing == null) nobodysMillis else doing.enter(call)
            if (allowed == Doing.REFUSED || allowed == Doing.SPENT) ways.remove(call)
            if (allowed == Doing.REFUSED) return stopped
            // (Its time was spent between the look above and now: not sent.)
            if (allowed == Doing.SPENT) {
                lastOnLidarrNow = "out of time"
                return failed(IN_TIME)
            }
            val limit = listOfNotNull(asked.patience, allowed).minOrNull()
            if (limit != null) call.timeout().timeout(limit, TimeUnit.MILLISECONDS)
            // (The name this is for, if a public service's: judged if it is looked up. See [Public].)
            val name = going.url.host.takeIf { asked.line != "lidarr" }
            name?.let { asking(it, +1) }
            val response = try {
                call.execute().also { wayNow.set(ways.remove(call)) }
            } catch (e: IOException) {
                wayNow.set(ways.remove(call))
                doing?.leave(call)
                if (doing?.isStopped != true) lastOnLidarrNow = word(e)
                return if (doing?.isStopped == true) stopped else failed(words(e))
            } finally {
                name?.let { asking(it, -1) }
                ways.remove(call)
            }
            // (Sent on by a public service: followed here, a step at a time, or not at all.)
            val next = onward(asked, going.url, response, steps)
            lastOnLidarrNow = "answered ${response.code}"
            if (next == null) return answered(response, call, doing)
            try {
                response.close()
            } catch (_: Exception) {
            }
            doing?.leave(call)
            steps++
            going = going.newBuilder().url(next).build()
        }
    }

    private fun answered(response: Response, call: Call, doing: Doing?): ByteArray {
        val id = nextBody.incrementAndGet()
        bodies[id] = Held(response, call, doing)
        val headers = response.headers.map { (name, value) -> listOf(name, value.takeIf(::isText)) }
        val length = response.body.contentLength().takeIf { it >= 0 }
        return byteArrayOf(FINE) + json.encodeToString(Answered.serializer(), Answered(response.code, headers, id, length)).encodeToByteArray()
    }

    /**
     * What a request that changes something carries: **sent once.** An HTTP client may send a
     * request again by itself when the answer is "busy, come back at once" (503 with
     * `Retry-After: 0`), "this came to the wrong place" (421) or "you took too long" (408),
     * and Lidarr may have acted on the first. One that says it can be sent only once is
     * never sent again by the client: the answer goes back to the library as it came.
     * (It has no kind of its own: the library says what the text is, in a header.)
     */
    private class Once(private val bytes: ByteArray) : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = bytes.size.toLong()

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
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

    /** The library has something to write down before it changes Lidarr, or to cross off
     *  after (see [wayBackKeptBy]). False if nobody keeps it for that place, or it couldn't
     *  be written: then, to the library, it is not written, and Lidarr is not changed. */
    fun wayBack(place: Long, batch: ByteArray?, surely: Boolean): Boolean = try {
        desks[place]?.invoke(batch, surely) ?: false
    } catch (_: Exception) {
        false
    }

    /** A sign-in's token has changed: handed to whoever puts it away. */
    fun kept(session: Long, token: ByteArray) {
        try {
            keepers[session]?.invoke(token.decodeToString())
        } catch (_: Exception) {
        }
    }
}
