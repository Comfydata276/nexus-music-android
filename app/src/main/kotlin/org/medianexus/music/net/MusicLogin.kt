package org.medianexus.music.net

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * **A music login as the Nexus server hands it to this phone** (the backend design's 9.4): a
 * salt the server made for this device and the token for it (the MD5 of the music server's
 * password and that salt, which only the Nexus server can work out). The phone never has, or
 * makes, a music server's password: nothing here can make a token.
 *
 * It holds secrets: it says nothing of them as text.
 */
class Handed(
    val salt: String,
    val token: String,
    /** The number of the login it was made from (9.4), exactly as the shared core wrote it: a
     *  number of 64 bits without a sign, never turned into a Kotlin one. */
    val count: String = "0",
) {
    override fun equals(other: Any?): Boolean = other is Handed && other.salt == salt && other.token == token && other.count == count
    override fun hashCode(): Int = token.hashCode()
    override fun toString(): String = "Handed(..)"
}

/**
 * **One music login exactly as it was handed** (`Hand::Login`): the pair, the number and the
 * generation it came under, **never changed after**. Every request carries one, and what the music
 * server says of that request is said of this pass: a refusal is reported with ITS number and
 * generation, whatever has been handed since.
 */
class Pass internal constructor(val handed: Handed, val generation: String, internal val owner: MusicLogin, proven: Boolean) {
    /** Accepted by the music server at least once in this process: requests go without waiting. */
    @Volatile var proven: Boolean = proven
        internal set

    /** Refused by the music server (or set aside by the core): nothing more is sent with it. */
    @Volatile var refused: Boolean = false
        internal set

    /** Its account ended on the phone (signed out, another account): nothing more is sent with it. */
    @Volatile var retired: Boolean = false
        internal set

    /** The one request with this pass on the wire while it is not yet proven, if any. */
    internal var prover: Any? = null

    internal val lock = java.util.concurrent.locks.ReentrantLock()
    internal val changed: java.util.concurrent.locks.Condition = lock.newCondition()

    /** Runs `step` holding this pass's lock, and wakes whoever waits on it after. */
    internal inline fun <T> changing(step: () -> T): T {
        lock.lock()
        try {
            return step()
        } finally {
            changed.signalAll()
            lock.unlock()
        }
    }

    internal val told = AtomicBoolean()

    override fun toString(): String = "Pass(..)"
}

/**
 * The music login a client for the music server sends, **shared by the client and every copy of
 * it** (`Subsonic.sharing`, `over`, `once`), for ONE account on the phone: a new pass handed by the
 * Nexus server ([renew]) is what every request sends from then on, without the account's objects
 * being made again. Another account has a MusicLogin of its own; this one is [retire]d first.
 *
 * **The rules, held at the one place every request passes on its way to the wire ([Gate])**:
 *
 * - **A refused pass is never sent again** (6.3, R10). A request the music server turns away as
 *   "wrong name or password" (Subsonic's error 40, in an answer of any status: an API page, a
 *   stream, a range reopened, a song buffered ahead, a cover, a download) sets that pass aside the
 *   instant the answer is seen, before the caller is given anything ([RefusedLogin]); from then on
 *   every request carrying it fails before it is written. The shared core is told once, with the
 *   pass's own number and generation ([onRefused]).
 * - **Exactly one request with an unproven pass is on the wire** (Navidrome locks a login out
 *   after five refusals in twenty): a pass not yet accepted in this process lets the first request
 *   go and holds every other one, unsent, until that one is answered, for as long as it takes; a
 *   request held longer than [WAIT_MILLIS] fails unsent ([NotSentYet]). The first accepted answer
 *   releases them all at once; an answer that proves nothing (the network failed, a proxy's page)
 *   lets the next one go, alone. A pass already proven waits for nothing.
 * - **Checked at the send itself**, for every attempt OkHttp makes (a redirect, a retry on a fresh
 *   connection), wherever the pair is (the address, or the body of a long form POST).
 * - **What waited for a renewal is tried once more under it**: [renewals] moves when a pass
 *   replaces one that was refused, and what was held for it listens.
 */
class MusicLogin(first: Handed, generation: String = "0", proven: Boolean = false) {
    @Volatile private var current: Pass = Pass(first, generation, this, proven).also(Gate::register)

    /** The pass every request sends now. */
    val pass: Pass get() = current

    /** The generation of the Nexus server's machine the current pass was handed under. */
    val generation: String get() = current.generation

    /** What every request sends now. */
    val now: Handed get() = current.handed

    /** Told once for each pass the music server refuses (on the thread of the request that saw
     *  it), with that pass: its number and generation are what the core is told. */
    @Volatile var onRefused: ((Pass) -> Unit)? = null

    private val renewing = MutableStateFlow(0L)

    /** Moves each time a pass replaces one that was refused: what was held back for the renewal
     *  is tried once more. */
    val renewals: StateFlow<Long> = renewing.asStateFlow()

    /** Whether the pass every request would send now is set aside. */
    val isRefused: Boolean get() = current.refused || current.retired

    private val aside = MutableStateFlow(false)

    /** [isRefused], as it changes: at a refusal, a renewal, and when the account is let go of
     *  (what shows what is online, the car's tree above all, follows it). */
    val setAside: StateFlow<Boolean> = aside.asStateFlow()

    private fun moved() {
        aside.value = isRefused
    }

    /** Whether the current pass has been accepted in this process. */
    val isProven: Boolean get() = current.proven

    /**
     * The music server refused `sent` (a pass this login handed out, matched by its pair). True
     * the first time (and [onRefused] is told, if it is still the pass in use); false if it was
     * already refused, or is not this login's.
     */
    fun refuse(sent: Handed): Boolean {
        val pass = Gate.passOf(sent.token)?.takeIf { it.owner === this && it.handed == sent } ?: return false
        return refuse(pass)
    }

    internal fun refuse(pass: Pass): Boolean {
        val first = pass.changing {
            val was = pass.refused
            pass.refused = true
            !was
        }
        if (!first) return false
        pass.owner.moved()
        if (pass.told.compareAndSet(false, true)) onRefused?.invoke(pass)
        return true
    }

    /** The core says the login of this number, handed under this generation, is set aside
     *  (`Hand::Refused`): if it is the one in use, nothing more is sent with it. */
    fun refuseCount(count: String, generation: String? = null) {
        val now = current
        if (now.handed.count == count && (generation == null || generation == now.generation)) {
            now.told.set(true)
            refuse(now)
        }
    }

    /**
     * A new pass from the Nexus server (`Hand::Login`): sent from now on. **The machine decides
     * what replaces what**: it is taken whatever was refused before. A pass with the same pair as
     * the current one keeps what is known of it.
     */
    fun renew(next: Handed, generation: String = this.generation): Boolean {
        val was = current
        if (was.handed == next && !was.refused && !was.retired) {
            if (was.generation != generation) current = Pass(next, generation, this, was.proven).also(Gate::register)
            return true
        }
        current = Pass(next, generation, this, proven = false).also(Gate::register)
        moved()
        if (was.refused) renewing.value++
        return true
    }

    /** Its account ended on the phone: every pass it handed out is refused at the send from now,
     *  and anyone waiting on one is let go, unsent. */
    fun retire() {
        Gate.retire(this)
        moved()
    }

    /**
     * **The one check every request of the app's HTTP client makes at the send** (a network
     * interceptor: each attempt, after its connection is ready and before a byte of it is
     * written). Requests that carry no music login pass untouched.
     */
    object Gate : Interceptor {
        private val passes = ConcurrentHashMap<String, Pass>()

        internal fun register(pass: Pass) {
            passes[pass.handed.token] = pass
        }

        internal fun passOf(token: String): Pass? = passes[token]

        internal fun retire(login: MusicLogin) {
            for (pass in passes.values) {
                if (pass.owner !== login) continue
                pass.changing { pass.retired = true }
            }
        }

        /** Whether a pair with this token is set aside (refused, or its account ended). */
        fun refused(token: String): Boolean = passes[token]?.let { it.refused || it.retired } ?: false

        /** For the tests: nothing to forget (what is known is each pass's own). */
        internal fun forgetForTests() = Unit

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = request.url.queryParameter("t") ?: formToken(request)
            val pass = token?.let(passes::get) ?: return chain.proceed(request)
            val proving = admit(pass, chain)
            try {
                val response = chain.proceed(request)
                when (judge(response)) {
                    Judged.REFUSED -> {
                        response.close()
                        pass.owner.refuse(pass)
                        throw RefusedLogin()
                    }
                    Judged.ACCEPTED -> prove(pass)
                    Judged.NOTHING -> Unit
                }
                return response
            } finally {
                if (proving) release(pass)
            }
        }

        /** Lets the request go, now or once the pass is proven; true if it goes as the one
         *  request proving it. */
        private fun admit(pass: Pass, chain: Interceptor.Chain): Boolean {
            val until = System.currentTimeMillis() + WAIT_MILLIS
            pass.lock.lock()
            try {
                while (true) {
                    if (pass.refused || pass.retired) throw RefusedLogin()
                    if (pass.proven) return false
                    if (pass.prover == null) {
                        pass.prover = chain.call()
                        return true
                    }
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) throw NotSentYet()
                    pass.changed.await(minOf(left, 100L), java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } finally {
                pass.lock.unlock()
            }
        }

        private fun prove(pass: Pass) = pass.changing { pass.proven = true }

        private fun release(pass: Pass) = pass.changing { pass.prover = null }

        /** The token of a long Subsonic call sent as a form (`t=` in its body), if it is one. */
        private fun formToken(request: okhttp3.Request): String? {
            val body = request.body ?: return null
            val type = body.contentType() ?: return null
            if (type.subtype != "x-www-form-urlencoded") return null
            if (body.contentLength() > FORM_MAX) return null
            val buffer = Buffer()
            body.writeTo(buffer)
            return buffer.readUtf8().split('&').firstOrNull { it.startsWith("t=") }?.removePrefix("t=")
        }

        private enum class Judged { REFUSED, ACCEPTED, NOTHING }

        /**
         * What an answer says of the pass it was asked with: **one bounded look, for every kind of
         * answer**. Subsonic says "wrong name or password" as error 40 in a page of JSON or XML,
         * whatever the status (Navidrome sends it as a success, where audio or a picture was asked
         * for). Any other Subsonic answer, audio, a picture or a range of either means the pass was
         * taken; a proxy's error page or a status of its own proves nothing.
         */
        private fun judge(response: Response): Judged {
            val type = response.body.contentType()
            val textual = type == null || type.type == "text" || type.subtype.contains("json") || type.subtype.contains("xml")
            if (!textual) return if (response.isSuccessful) Judged.ACCEPTED else Judged.NOTHING
            val text = try {
                peek(response)
            } catch (_: IOException) {
                return Judged.NOTHING
            }
            val code = ERROR_CODE.find(text)?.groupValues?.let { it[1].ifEmpty { it[2] } }
            return when {
                code == "40" -> Judged.REFUSED
                // (10: a parameter missing, said before the login is looked at.)
                code != null && code != "10" -> Judged.ACCEPTED
                SUBSONIC_OK.containsMatchIn(text) -> Judged.ACCEPTED
                else -> Judged.NOTHING
            }
        }

        private fun peek(response: Response): String {
            val bytes = response.peekBody(PEEK_BYTES).bytes()
            val plain = if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
                val out = java.io.ByteArrayOutputStream()
                try {
                    GZIPInputStream(bytes.inputStream()).use { input ->
                        val chunk = ByteArray(4096)
                        while (out.size() < PEEK_BYTES) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            out.write(chunk, 0, n)
                        }
                    }
                } catch (_: java.io.EOFException) {
                    // (Only the start was peeked at: what came of it is enough.)
                }
                out.toByteArray()
            } else {
                bytes
            }
            return String(plain, Charsets.UTF_8)
        }

        /** How long a request waits for an unproven pass's first answer before it fails, unsent. */
        const val WAIT_MILLIS = 20_000L

        private const val PEEK_BYTES = 16_384L
        private const val FORM_MAX = 1L shl 20

        /** `"error":{"code":40` in JSON, `<error code="40"` in XML. */
        private val ERROR_CODE = Regex(""""error"\s*:\s*\{[^}]*?"code"\s*:\s*(\d+)|<error[^>]*\bcode\s*=\s*"(\d+)"""")
        private val SUBSONIC_OK = Regex(""""status"\s*:\s*"ok"|status\s*=\s*"ok"""")
    }
}

/** A request that would have carried a music login that is set aside (refused by the music
 *  server, or its account ended): not sent (see [MusicLogin]). Its message names nothing. */
class RefusedLogin : IOException("Your music sign-in needs renewing, so this wasn't sent.")

/** A request held while the music login's first answer was awaited, for longer than it may be:
 *  not sent (see [MusicLogin]). */
class NotSentYet : IOException("The music server hasn't answered yet, so this wasn't sent.")
