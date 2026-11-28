package org.medianexus.music.nexus

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.concurrent.TaskRunner
import okio.Buffer

/**
 * **The backend's line** (`Nexus`): **HTTPS only; HTTP/1.1;
 * nothing followed (a redirect is an answer); nothing tried again; one request on the wire for
 * each ask**; its dispatcher on `calls`, its connection pool's upkeep on `runner`, both its own;
 * the connect on the caller's thread. Every request counted in `sent` as it goes out. (Each
 * ask's own time limit is the machine's: see `exchange`.)
 */
@OptIn(okhttp3.internal.OkHttpInternalApi::class)
internal fun nexusHttp(calls: ExecutorService, runner: TaskRunner, sent: AtomicInteger, sockets: javax.net.SocketFactory?): OkHttpClient {
    val builder = OkHttpClient.Builder()
        // HTTPS only (the design's R4): no plain HTTP, whatever the rest of the app allows.
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        // HTTP/1.1: nothing of HTTP/2's reading and writing on any scheduler.
        .protocols(listOf(Protocol.HTTP_1_1))
        // A redirect is an answer, never a place to go (7.1); nothing is tried again.
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        // The connect on this line's own thread, not handed to a scheduler.
        .fastFallback(false)
        .connectionPool(pool(runner))
        .dispatcher(Dispatcher(calls).apply { maxRequests = 1; maxRequestsPerHost = 1 })
        .connectTimeout(Nexus.CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Nexus.READ_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(Nexus.READ_SECONDS, TimeUnit.SECONDS)
        // **One ask is one request on the wire.** OkHttp sends a follow-up of its own for some
        // answers whatever is switched off (a 503 with "Retry-After: 0", a 408, a 421, a 401's
        // authenticator): the second attempt of one call is refused before it is sent.
        .addNetworkInterceptor { chain ->
            val attempts = chain.request().tag(AtomicInteger::class.java)
            if (attempts != null && attempts.incrementAndGet() > 1) throw IOException("One ask sends one request, and this would have been a second.")
            sent.incrementAndGet()
            chain.proceed(chain.request())
        }
    sockets?.let { builder.socketFactory(it) }
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

/** What came back from one ask of the backend. */
sealed interface Sent {
    /** An answer: its status and its body, read whole. Only a 200's body is read: no other
     *  status is an answer of the backend's, whatever it carries. */
    class Answer(val status: Int, val body: ByteArray) : Sent

    /** No answer, and how far it got, in the machine's own words (`client::How`): "name" (the
     *  name was not found), "connect" (no connection was ever opened: refused, timed out, or
     *  dropped by the system, which is what Android 17 does without the local-network
     *  permission), "secure" (opened, and its TLS failed: a certificate that is wrong is not a
     *  permission that is missing), "timeout" (secure, and no whole answer in time), "cut" (cut
     *  short, or longer than the ask allowed). */
    class Failed(val how: String) : Sent
}

/**
 * Sends one request (`post`: with `body`; otherwise a GET) to `url` with `authorization` (none if
 * empty) on `http`, on the caller's thread, giving up after `withinMs`, and reads the answer
 * whole, to `most` bytes (an answer longer is "cut": never cut to fit and read). `onCall` is
 * handed the call before it is sent, so that it can be cancelled from elsewhere.
 */
internal fun exchange(http: OkHttpClient, url: HttpUrl, post: Boolean, authorization: String, body: ByteArray, userAgent: String, withinMs: Long, most: Long, reading: AtomicInteger? = null, onCall: (Call) -> Unit = {}): Sent {
    val opened = java.util.concurrent.atomic.AtomicBoolean()
    val secure = java.util.concurrent.atomic.AtomicBoolean()
    val watching = http.newBuilder().eventListener(object : EventListener() {
        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) = opened.set(true)
        override fun secureConnectStart(call: Call) = opened.set(true)
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = secure.set(true)
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
            opened.set(true)
            secure.set(true)
        }
    }).callTimeout(withinMs, TimeUnit.MILLISECONDS).build()
    val request = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .apply { if (authorization.isNotEmpty()) header("Authorization", authorization) }
        .tag(AtomicInteger::class.java, AtomicInteger())
        .apply { if (post) post(body.toRequestBody(JSON)) else get() }
        .build()
    return try {
        val call = watching.newCall(request)
        onCall(call)
        call.execute().use { response ->
            if (response.code != 200) return Sent.Answer(response.code, ByteArray(0))
            reading?.incrementAndGet()
            try {
                val source = response.body.source()
                val got = Buffer()
                while (got.size <= most) {
                    if (source.read(got, most + 1 - got.size) == -1L) break
                }
                if (got.size > most) Sent.Failed("cut") else Sent.Answer(200, got.readByteArray())
            } finally {
                reading?.decrementAndGet()
            }
        }
    } catch (e: IOException) {
        Sent.Failed(
            when {
                secure.get() -> if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException) "timeout" else "cut"
                opened.get() -> "secure"
                e is java.net.UnknownHostException -> "name"
                else -> "connect"
            },
        )
    }
}

private val JSON = "application/json".toMediaType()
