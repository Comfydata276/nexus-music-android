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
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .addNetworkInterceptor(NoDowngrade)
        .build()

    /**
     * Refuses a redirect from HTTPS to plain HTTP. Every request carries a sign-in token in
     * its address, and a redirect would send it on unencrypted. (Redirects the other way, or
     * staying on one scheme, are followed as usual.) A network interceptor sees each step of
     * a redirect before OkHttp follows it.
     */
    internal object NoDowngrade : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.isHttps && response.isRedirect) {
                val target = response.header("Location")?.let { request.url.resolve(it) }
                if (target != null && !target.isHttps) {
                    response.close()
                    throw InsecureRedirect()
                }
            }
            return response
        }
    }
}

/** The server tried to send a request on from HTTPS to plain HTTP (see `Http.NoDowngrade`). */
class InsecureRedirect : IOException("The server tried to send the request on without encryption, so it wasn't followed.")

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
