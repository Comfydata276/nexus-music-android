package org.medianexus.music.jam

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.medianexus.music.net.await

/**
 * A connection to one relay (Windows' `Line`): its address, and the app's HTTP client made fit
 * for it. Nothing here is logged: a request carries a key or a jam's secret in its
 * `Authorization` header, and an answer may name the relay.
 *
 * **Asked once** (rule 6). Anything sent that changes something (a guest's request, the
 * host's answers, a guest removed, a jam ended) goes on a client that never sends it again:
 * no retry on a connection that turns out dead, no redirect followed, and a reply OkHttp
 * would follow up by itself (a 408, a 421, a 503 with `Retry-After: 0`) handed back as it is.
 * A request that gets no answer is an [Answer.Unreachable]: whoever sent it reads the jam
 * again and shows what it now is. The long waits for news are only reads, and go on a client
 * that may try a dead connection again.
 */
class RelayLine(base: String, http: OkHttpClient, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** The relay's address, with no `/` at its end. */
    val base: String = base.trimEnd('/')

    private val reads: OkHttpClient = http.newBuilder()
        .connectionPool(POOL)
        .followRedirects(false).followSslRedirects(false)
        .readTimeout(HOLD_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HOLD_SECONDS + 5, TimeUnit.SECONDS)
        .addNetworkInterceptor(AsItIs)
        .build()

    private val once: OkHttpClient = http.newBuilder()
        .connectionPool(POOL)
        .retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .readTimeout(SEND_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SEND_SECONDS, TimeUnit.SECONDS)
        .addNetworkInterceptor(AsItIs)
        .build()

    /** What came back: the status and the answer read as JSON (null for an answer that
     *  isn't: a proxy's error page, say), or that no answer came at all. */
    sealed interface Answer {
        data class Got(val status: Int, val body: JsonElement) : Answer
        data object Unreachable : Answer
    }

    /** A read: waits up to `waitSeconds` (the relay holds some requests open on purpose). */
    suspend fun get(path: String, pass: String, waitSeconds: Long = HOLD_SECONDS): Answer {
        val client = if (waitSeconds == HOLD_SECONDS) reads else reads.newBuilder().readTimeout(waitSeconds, TimeUnit.SECONDS).callTimeout(waitSeconds + 5, TimeUnit.SECONDS).build()
        return call(client, request(path, pass).get().build(), JSON_LIMIT)
    }

    /** Sends JSON, once. */
    suspend fun post(path: String, pass: String, body: JsonElement): Answer =
        send(path, pass, "application/json", body.toString().toByteArray(Charsets.UTF_8))

    /** Sends bytes of a kind, once. */
    suspend fun send(path: String, pass: String, kind: String, body: ByteArray, waitSeconds: Long = SEND_SECONDS): Answer {
        val client = if (waitSeconds == SEND_SECONDS) once else once.newBuilder().readTimeout(waitSeconds, TimeUnit.SECONDS).callTimeout(waitSeconds, TimeUnit.SECONDS).build()
        return call(client, request(path, pass).post(body.toRequestBody(kind.toMediaType())).build(), JSON_LIMIT)
    }

    /** A cover from the relay (a guest's): its bytes, at most [COVER_LIMIT], or null. */
    suspend fun cover(path: String): ByteArray? = withContext(io) {
        val request = request(path, "").get().build()
        try {
            reads.newCall(request).await().use { response ->
                if (response.code != 200) return@use null
                readBounded(response, COVER_LIMIT)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun request(path: String, pass: String): Request.Builder {
        val builder = Request.Builder().url("$base/$path")
        if (pass.isNotEmpty()) builder.header("Authorization", "Bearer $pass")
        return builder
    }

    private suspend fun call(client: OkHttpClient, request: Request, limit: Int): Answer = withContext(io) {
        try {
            client.newCall(request).await().use { response ->
                val bytes = readBounded(response, limit)
                // An answer that isn't JSON (a proxy's error page, say) is read as nothing.
                val body = bytes?.let { runCatching { Json.parseToJsonElement(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JsonNull
                Answer.Got(response.code, body)
            }
        } catch (_: IOException) {
            Answer.Unreachable
        } catch (_: IllegalArgumentException) {
            // An address OkHttp won't take.
            Answer.Unreachable
        }
    }

    companion object {
        /**
         * The relay's own connections (rule 1): not the app's pool, which keeps a connection
         * for minutes. One left open with nothing on it holds nothing awake, but nothing of the
         * relay should be open when the phone has no reason to be there: an idle one goes after
         * ten seconds, and all of them as soon as the phone lets the relay go ([letGo]).
         */
        private val POOL = okhttp3.ConnectionPool(4, 10, TimeUnit.SECONDS)

        /** Closes every connection to a relay not in use this moment. */
        fun letGo() = POOL.evictAll()

        /** How long a read may wait: the relay holds one for 20 seconds before saying
         *  "nothing new"; twice that is given (Windows gives 40). */
        const val HOLD_SECONDS = 40L
        const val SEND_SECONDS = 20L
        /** The most an answer may be: the relay takes 512 KiB of state from a host. */
        const val JSON_LIMIT = 768 * 1024
        /** The most a cover may weigh (the relay takes 1.5 MiB). */
        const val COVER_LIMIT = 1536 * 1024

        /** The body, read to at most `limit` bytes; null if it is longer. */
        internal fun readBounded(response: Response, limit: Int): ByteArray? {
            val length = response.body.contentLength()
            if (length > limit) return null
            val source = response.body.source()
            val out = okio.Buffer()
            while (out.size <= limit) {
                if (source.read(out, 8192) == -1L) return out.readByteArray()
            }
            return null
        }
    }

    /** Hands back a reply OkHttp would otherwise follow up with a second request of its own. */
    private object AsItIs : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            return when {
                response.code == 408 || response.code == 421 -> response.newBuilder().code(599).build()
                response.header("Retry-After") != null -> response.newBuilder().removeHeader("Retry-After").build()
                else -> response
            }
        }
    }
}
