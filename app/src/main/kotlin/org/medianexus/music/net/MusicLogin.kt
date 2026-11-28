package org.medianexus.music.net

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

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
 * The music login a client for the music server sends, **shared by the client and every copy of
 * it** (`Subsonic.sharing`, `over`, `once`), so that a new one handed by the Nexus server ([renew])
 * is what every request sends from then on, without the account's objects being made again.
 *
 * **The rule of a refused music login** (the backend design's 6.3, and R10): a request the music
 * server turned away as "wrong name or password" is never sent again as it was. The pair it
 * carried is marked refused ([refuse]), and from then on **every request that would carry it
 * fails before it is sent**: one check, made at each request ([isRefused], and [Gate] for the
 * requests made outside the client: streams, covers, downloads). Nothing counts or waits; the
 * shared core's rule is told once ([onRefused], with the login's number), and the Nexus server's
 * part of the app hands over a new login when there is one ([renew]), or says the number is set
 * aside ([refuseCount]).
 *
 * (The phone sets a refused login aside at the instant it is refused, before the core has said
 * so: waiting for the core's answer would let a request begun meanwhile on another thread carry
 * it. The core's `Hand::Refused` then confirms it.)
 */
class MusicLogin(first: Handed, generation: String = "0") {
    @Volatile private var current: Handed = first

    /** The generation of the Nexus server's machine it was handed under (`Hand::Login`), as the
     *  core wrote it: what a report about it carries back (`MusicRefused`, `MusicLooked`). */
    @Volatile var generation: String = generation
        private set

    /** What every request sends now. */
    val now: Handed get() = current

    /** Told once for each pair the music server refuses (on the thread of the request that was
     *  refused): the Nexus server's client then asks for the current login. */
    @Volatile var onRefused: ((Handed) -> Unit)? = null

    /** Whether the pair every request would send now has been refused. */
    val isRefused: Boolean get() = Gate.refused(current.token)

    /**
     * The music server refused `sent`: it is never sent again. True the first time (and
     * [onRefused] is told); false if it was already refused, or is no longer the one in use (a
     * new one came while the request was out: that one stands).
     */
    fun refuse(sent: Handed): Boolean {
        if (!Gate.refuse(sent.token)) return false
        if (current == sent) onRefused?.invoke(sent)
        return true
    }

    /** The core says the login of this number is set aside (`Hand::Refused`): if it is the one
     *  in use, nothing more is sent with it. */
    fun refuseCount(count: String) {
        val now = current
        if (now.count == count) Gate.refuse(now.token)
    }

    /** A new pair from the Nexus server: sent from now on. A pair already refused is not taken
     *  back into use (it is refused for good). False if it was. */
    fun renew(next: Handed, generation: String = this.generation): Boolean {
        this.generation = generation
        if (Gate.refused(next.token)) return false
        current = next
        return true
    }

    /**
     * Every pair refused in this process, by its token, and the check every request of the app's
     * own HTTP client makes before it is sent (`Http.client`): a request whose address carries a
     * refused token fails as [RefusedLogin], unsent. Tokens are kept for the life of the process
     * (a 32-character text each, one for each refusal: nothing to bound).
     */
    object Gate : Interceptor {
        private val tokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun refused(token: String): Boolean = token in tokens

        internal fun refuse(token: String): Boolean = tokens.add(token)

        /** For the tests: forgets every refusal. */
        internal fun forgetForTests() = tokens.clear()

        override fun intercept(chain: Interceptor.Chain): Response {
            val token = chain.request().url.queryParameter("t")
            if (token != null && refused(token)) throw RefusedLogin()
            return chain.proceed(chain.request())
        }
    }
}

/** A request that would have carried a music login the music server has refused: not sent (see
 *  [MusicLogin]). Its message names nothing. */
class RefusedLogin : IOException("Your music sign-in needs renewing, so this wasn't sent.")
