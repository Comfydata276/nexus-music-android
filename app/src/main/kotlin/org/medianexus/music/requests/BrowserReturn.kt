package org.medianexus.music.requests

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import org.medianexus.music.core.Core
import org.medianexus.music.core.Words

/**
 * Where the browser comes back to when someone signs in to the sign-in in front of Lidarr:
 * **a door on the phone's own loopback address**, `http://127.0.0.1:<port>/callback`, open
 * only while a sign-in is under way. It is the redirect the Windows app uses, so the
 * sign-in service needs nothing set up for the phone that it doesn't already have for the
 * PC.
 *
 * Any app on the phone can knock on it, and so can a page in the browser. What it lets in
 * is one request for `/callback` that carries the [state] this sign-in was started with,
 * which nobody else was told: anything else is shown "Nothing here" and the wait goes on.
 * What comes in is a code that is no use by itself: collecting the token also takes the
 * secret that only this app holds (see `Core.gateFinish`), so a code that went to some
 * other app listening on this port later would be no use to it either.
 */
internal class BrowserReturn private constructor(private val server: ServerSocket, private val state: String) : Closeable {
    /** Where the service is to send the browser back to. */
    val back: String = "http://127.0.0.1:${server.localPort}/callback"

    /** How the wait ended. */
    sealed interface Came {
        /** With a code, to be traded for the token. */
        class Code(val code: String) : Came

        /** The service turned the sign-in down, and said this. */
        class TurnedDown(val why: String) : Came

        /** Nobody came back in time. */
        data object Nobody : Came

        /** The wait was given up here ([close]). */
        data object GivenUp : Came

        /** The door itself failed. */
        class Failed(val why: String) : Came
    }

    /** The browser has knocked with the right [state]: whatever follows, somebody came. */
    @Volatile var arrived = false
        private set

    /**
     * Waits for the browser to come back, `millis` at the longest. Blocks: for a thread
     * that may wait. [close], from anywhere, ends the wait.
     */
    fun await(millis: Long): Came {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return Came.Nobody
            val socket = try {
                server.soTimeout = left.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                server.accept()
            } catch (_: SocketTimeoutException) {
                return Came.Nobody
            } catch (e: IOException) {
                return if (server.isClosed) Came.GivenUp else Came.Failed(e.javaClass.simpleName)
            }
            try {
                socket.use { answer(it) }?.let { return it }
            } catch (_: IOException) {
                // (Whoever knocked went away part-way: the wait goes on.)
            }
        }
    }

    /** One knock: answered, and what it brought, or null if it was not the browser coming
     *  back from this sign-in. */
    private fun answer(socket: Socket): Came? {
        val target = firstLine(socket).split(' ').getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val theirs = Core.gateParam(query, "state")
        if (path != "/callback" || theirs == null || !MessageDigest.isEqual(theirs.toByteArray(), state.toByteArray())) {
            reply(socket, "404 Not Found", Words.pageNothingHere)
            return null
        }
        arrived = true
        Core.gateParam(query, "error")?.let { error ->
            val why = Core.gateParam(query, "error_description")?.takeIf { it.isNotEmpty() } ?: error
            reply(socket, "200 OK", Words.pageNotThrough)
            return Came.TurnedDown(why)
        }
        Core.gateParam(query, "code")?.takeIf { it.isNotEmpty() }?.let { code ->
            reply(socket, "200 OK", Words.pageSignedIn)
            return Came.Code(code)
        }
        arrived = false
        reply(socket, "400 Bad Request", Words.pageNothingHere)
        return null
    }

    /** The first line of what a browser sent, e.g. "GET /callback?code=… HTTP/1.1". */
    private fun firstLine(socket: Socket): String {
        socket.soTimeout = 2_000
        val input = socket.getInputStream()
        val seen = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        try {
            while (seen.size() < 16 * 1024 && !seen.toByteArray().let { bytes -> (1 until bytes.size).any { bytes[it - 1] == '\r'.code.toByte() && bytes[it] == '\n'.code.toByte() } }) {
                val count = input.read(buffer)
                if (count <= 0) break
                seen.write(buffer, 0, count)
            }
        } catch (_: IOException) {
        }
        return seen.toString(Charsets.UTF_8.name()).lineSequence().firstOrNull().orEmpty()
    }

    private fun reply(socket: Socket, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /** Shuts the door. A wait under way ends as given up. */
    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Opens the door, on a port of the phone's choosing, for a sign-in that will come
         *  back with `state`. Throws if the phone won't give one. */
        @Throws(IOException::class)
        fun open(state: String): BrowserReturn = BrowserReturn(ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), state)

        /** Letters nobody could guess, from the system's own source of randomness, in the
         *  form used in web addresses (as the Windows app makes them). */
        fun unguessable(bytes: Int): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) })
    }
}
