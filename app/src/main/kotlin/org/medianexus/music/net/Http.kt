package org.medianexus.music.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The one HTTP client: API calls, artwork and audio all go through it, so they share warm
 * connections (setting one up is the expensive part of a request).
 */
object Http {
    /** Longest an API call may take from start to finish. Audio has no such limit. */
    const val API_TIMEOUT_SECONDS = 45L

    fun client(userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Between one piece of an answer and the next. Nothing overall here: a stream runs as
        // long as the song does. API calls set their own overall limit (see `Subsonic`).
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        // Plenty of connections, kept for minutes rather than seconds: enough for artwork to
        // load many at a time without opening new ones.
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 24 })
        // A music login the music server has refused is never sent again, from anywhere: a
        // stream, a cover, a download included (see `MusicLogin`).
        .addInterceptor(MusicLogin.Gate)
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .addNetworkInterceptor(NoDowngrade)
        .build()

    /**
     * The client for audio: the same connections and settings, but a read that gets nothing
     * for [STREAM_STALL_SECONDS] is given up on. The app's 30 seconds between pieces of an
     * answer suits a server working on a slow request; for a song that has stalled it is 30
     * seconds of silence before the player even tries again. (The limit runs only while a
     * read is waiting for bytes, not while the player, with enough buffered, isn't reading.)
     */
    fun forStreams(client: OkHttpClient): OkHttpClient = client.newBuilder().readTimeout(STREAM_STALL_SECONDS, TimeUnit.SECONDS).build()

    const val STREAM_STALL_SECONDS = 10L

    /**
     * Refuses the redirects that would give the sign-in away. Every request carries a
     * sign-in token in its address, which a redirect carries on with it:
     *
     * - from HTTPS to plain HTTP, it would go on unencrypted;
     * - from plain HTTP to a different host, it would go to whoever the answer named, and
     *   over plain HTTP anyone on the way can write that answer.
     *
     * Other redirects are followed as usual: staying on HTTPS, an upgrade to HTTPS on the
     * same host, another path or port of the same host. A network interceptor sees each step
     * of a redirect before OkHttp follows it.
     */
    internal object NoDowngrade : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.isRedirect) {
                val target = response.header("Location")?.let { request.url.resolve(it) }
                if (target != null && request.isHttps && !target.isHttps) {
                    response.close()
                    throw InsecureRedirect("The server tried to send the request on without encryption, so it wasn't followed.")
                }
                if (target != null && !request.isHttps && !target.host.equals(request.url.host, ignoreCase = true)) {
                    response.close()
                    throw InsecureRedirect("The server tried to send the request on to another address, so it wasn't followed.")
                }
            }
            return response
        }
    }
}

/** The server tried to send a request on somewhere the sign-in token shouldn't go (see
 *  `Http.NoDowngrade`). The message never names an address. */
class InsecureRedirect(message: String) : IOException(message)

/**
 * Sends the call and hands its response to `read`, which runs on `io` and may block reading
 * the body. The call and its response are held throughout: however the caller is cancelled
 * (waiting for the answer, or part-way through reading it), the call is cancelled, which
 * stops a read that is blocked, and the response is closed, which gives back its connection.
 */
internal suspend fun <T> Call.fetch(io: CoroutineDispatcher, read: (Response) -> T): T {
    val response = await()
    // Nothing may suspend between the answer arriving and `use` taking it, or a cancellation
    // there would leave it open.
    return response.use {
        coroutineScope {
            val reading = async(io) {
                try {
                    read(it)
                } catch (e: Exception) {
                    // Cut off by the cancellation below: say so, not what the cut-off read saw.
                    ensureActive()
                    throw e
                }
            }
            try {
                reading.await()
            } catch (e: CancellationException) {
                cancel()
                throw e
            }
        }
    }
}

/** Runs the call without holding a thread while it waits, and cancels it if the caller is
 *  cancelled. The response's body still has to be read off the main thread: see `fetch`. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
