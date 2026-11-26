package org.medianexus.music.requests

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.medianexus.music.core.Core
import org.medianexus.music.core.Words

/**
 * Where the browser comes back to when someone signs in to the sign-in in front of Lidarr:
 * **a door on the phone's own loopback address**, `http://127.0.0.1:<port>/callback`, open
 * only while a sign-in is under way. It is the redirect the Windows app uses, so the
 * sign-in service needs nothing set up for the phone that it doesn't already have for the
 * PC.
 *
 * **Any app on the phone can knock on it, and so can a page in the browser.** What it lets
 * in is one whole request, `GET /callback?…`, that carries the [state] this sign-in was
 * started with, which nobody else was told. Everything else is told "Nothing here" (404)
 * and the wait goes on. And nobody who knocks can hold the door, the wait or the real
 * browser up:
 *
 * - whoever connects has [READ_MILLIS] **in all** to send a whole first line, however they
 *   space it out, and is hung up on then;
 * - each knock is attended to apart from the others, [AT_ONCE] at a time; one more than
 *   that is hung up on at once, with no waiting;
 * - the wait ends when it is due whoever is connected, and shutting the door ([close])
 *   hangs up on everyone it had let in.
 *
 * What comes in is a code that is no use by itself: collecting the token also takes the
 * secret that only this app holds (see `Core.gateFinish`), so a code that went to some
 * other app listening on this port later would be no use to it either.
 *
 * **The page the browser is shown is the truth.** When the browser comes back with a code
 * it is kept waiting, a few seconds at the most, until the code has been traded for a
 * token or has failed to be, and is then shown "You're signed in" or "The sign-in didn't
 * go through" ([Came.Code.say]).
 *
 * Accepted, and written down: the address of the sign-in page is handed to whatever the
 * person has chosen as the phone's browser (that is what a browser is), with the `state`
 * and the check of the secret in it. A hostile app that has been made the phone's browser
 * sees them, and could come back through this door with a code of its choosing. It still
 * gets no token of the person's: the secret itself never leaves the app.
 */
internal class BrowserReturn private constructor(private val server: ServerSocket, private val state: String) : Closeable {
    /** Where the service is to send the browser back to. */
    val back: String = "http://127.0.0.1:${server.localPort}/callback"

    /** How the wait ended. */
    sealed interface Came {
        /**
         * With a code, to be traded for the token. **The browser is still waiting for its
         * page**: [say] shows it one, once it is known how the trade went. (Shutting the
         * door first hangs up on it instead.)
         */
        class Code internal constructor(val code: String, private val waiting: Socket, private val door: BrowserReturn) : Came {
            private val said = AtomicBoolean(false)

            /** Shows the browser its page: [Page.SIGNED_IN], [Page.NOT_THROUGH], or, when the
             *  app has not been brought forward to finish, [Page.GO_BACK]. Once. */
            fun say(page: Page) {
                if (!said.compareAndSet(false, true)) return
                door.reply(waiting, "200 OK", page.html())
                door.hangUp(waiting)
            }
        }

        /** The service turned the sign-in down, and said this. (The browser has been shown
         *  that it didn't go through.) */
        class TurnedDown(val why: String) : Came

        /** Nobody came back in time. */
        data object Nobody : Came

        /** The wait was given up here ([close]). */
        data object GivenUp : Came

        /** The door itself failed. */
        class Failed(val why: String) : Came
    }

    /** What the browser is shown once it has come back with a code. */
    enum class Page {
        /** The token is in the app's keeping. */
        SIGNED_IN,

        /** It is not, and will not be from this sign-in. */
        NOT_THROUGH,

        /** Nothing is known yet: the app finishes the sign-in when its own screen is in
         *  front, and Android has not brought it there. The person is to go back to it. */
        GO_BACK;

        fun html(): String = when (this) {
            SIGNED_IN -> Words.pageSignedIn
            NOT_THROUGH -> Words.pageNotThrough
            // (The library's own page, with the phone's words in it until the library has
            // a page for this: its next release does.)
            GO_BACK -> Words.pageSignedIn
                .replace(Regex("(<h1[^>]*>).*?(</h1>)"), "$1${RequestsWords.PAGE_GO_BACK_HEADING}$2")
                .replace(Regex("(<p[^>]*>).*?(</p>)"), "$1${RequestsWords.PAGE_GO_BACK_TEXT}$2")
        }
    }

    /** The browser has knocked with the right [state]: whatever follows, somebody came. */
    @Volatile var arrived = false
        private set

    /** Everyone let in and not yet hung up on. */
    private val inside = ConcurrentHashMap.newKeySet<Socket>()

    /** What the browser brought, once it has come. Only the first counts. */
    private val brought = LinkedBlockingQueue<Came>(1)
    private val taken = AtomicBoolean(false)

    @Volatile private var shut = false

    /** Whoever attends to the knocks: [AT_ONCE] at a time and nobody waiting for a turn. */
    private val attending = ThreadPoolExecutor(0, AT_ONCE, 5, TimeUnit.SECONDS, SynchronousQueue()) { work ->
        Thread(work, "requests-door-${threads.incrementAndGet()}").apply { isDaemon = true }
    }

    /**
     * Waits for the browser to come back, `millis` at the longest. Blocks: for a thread
     * that may wait. [close], from anywhere, ends the wait. Once the browser has come the
     * door takes no more knocks.
     */
    fun await(millis: Long): Came {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (true) {
            brought.poll()?.let { came ->
                stopListening()
                return came
            }
            if (shut) return Came.GivenUp
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return Came.Nobody
            val socket = try {
                // (In short turns, so that what a knock brought is noticed promptly.)
                server.soTimeout = left.coerceIn(1, LOOK_MILLIS).toInt()
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                brought.poll()?.let { return it }
                return if (shut || server.isClosed) Came.GivenUp else Came.Failed(e.javaClass.simpleName)
            }
            inside += socket
            if (shut) {
                hangUp(socket)
                continue
            }
            try {
                attending.execute { attend(socket) }
            } catch (_: RejectedExecutionException) {
                // (As many are being attended to as ever are: this one is not kept waiting.)
                hangUp(socket)
            }
        }
    }

    /** One knock, from connecting to being hung up on (unless it is the browser coming
     *  back with a code, which is kept for its page). */
    private fun attend(socket: Socket) {
        var keep = false
        try {
            val line = firstLine(socket)
            if (line == null) return
            val came = answer(socket, line)
            keep = came is Came.Code
        } catch (_: IOException) {
            // (Whoever knocked went away part-way: the wait goes on.)
        } catch (_: RuntimeException) {
        } finally {
            if (!keep) hangUp(socket)
        }
    }

    /** What a knock's first line brought: answered here unless it is a code, whose page
     *  waits. Null if it was not the browser coming back from this sign-in. */
    private fun answer(socket: Socket, line: String): Came? {
        // "GET /callback?code=… HTTP/1.1", and nothing else.
        val parts = line.split(' ')
        val target = parts.getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val theirs = if (parts.size == 3 && parts[0] == "GET" && parts[2].startsWith("HTTP/1.") && path == "/callback") Core.gateParam(query, "state") else null
        if (theirs == null || !MessageDigest.isEqual(theirs.toByteArray(), state.toByteArray())) {
            reply(socket, "404 Not Found", Words.pageNothingHere)
            return null
        }
        val error = Core.gateParam(query, "error")
        val code = Core.gateParam(query, "code")?.takeIf { it.isNotEmpty() }
        if (error == null && code == null) {
            reply(socket, "404 Not Found", Words.pageNothingHere)
            return null
        }
        // The browser has come back. Once: a second coming (a page reloaded) is nothing.
        if (!taken.compareAndSet(false, true)) {
            reply(socket, "404 Not Found", Words.pageNothingHere)
            return null
        }
        arrived = true
        val came = if (error != null) {
            reply(socket, "200 OK", Words.pageNotThrough)
            Came.TurnedDown(Core.gateParam(query, "error_description")?.takeIf { it.isNotEmpty() } ?: error)
        } else {
            // (Its page waits until it is known how the trade for a token went.)
            socket.soTimeout = 0
            Came.Code(code!!, socket, this)
        }
        brought.offer(came)
        return came
    }

    /**
     * The first line of what was sent, e.g. "GET /callback?code=… HTTP/1.1", if a whole one
     * (ended by a new line) arrives within [READ_MILLIS] of connecting **in all**, however
     * it is spaced out, and is no longer than a line may be. Null otherwise: whoever it was
     * has had their time.
     */
    private fun firstLine(socket: Socket): String? {
        val deadline = System.nanoTime() + READ_MILLIS * 1_000_000
        val input = socket.getInputStream()
        val seen = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0 || shut) return null
            socket.soTimeout = left.coerceAtLeast(1).toInt()
            val count = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                return null
            }
            if (count <= 0) return null
            for (at in 0 until count) {
                if (buffer[at] == '\n'.code.toByte()) {
                    seen.write(buffer, 0, at)
                    return seen.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
                }
            }
            seen.write(buffer, 0, count)
            if (seen.size() > LINE_LENGTH) {
                reply(socket, "404 Not Found", Words.pageNothingHere)
                return null
            }
        }
    }

    private fun reply(socket: Socket, status: String, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            // (A page that may hold a code in its address tells no other site where the
            // browser came from, and is kept nowhere.)
            val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            socket.soTimeout = WRITE_MILLIS
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
            // (So that whoever asked reads the answer to its end before the connection goes.)
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    private fun hangUp(socket: Socket) {
        inside -= socket
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /** No more knocks are taken; whoever is inside is seen out as usual. */
    private fun stopListening() {
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    /** Shuts the door, **and hangs up on everyone it had let in** (a browser still waiting
     *  for its page among them). A wait under way ends as given up. */
    override fun close() {
        shut = true
        stopListening()
        inside.toList().forEach(::hangUp)
        attending.shutdownNow()
    }

    companion object {
        /** How long whoever connects has to send a whole first line, in all. */
        const val READ_MILLIS = 2_000L

        /** How many knocks are attended to at once. One more is hung up on. */
        const val AT_ONCE = 4

        /** The longest a first line may be. (The browser's is a few hundred letters.) */
        private const val LINE_LENGTH = 8 * 1024
        private const val LOOK_MILLIS = 100L
        private const val WRITE_MILLIS = 2_000
        private val threads = AtomicInteger(0)

        /** Opens the door, on a port of the phone's choosing, for a sign-in that will come
         *  back with `state`. Throws if the phone won't give one. */
        @Throws(IOException::class)
        fun open(state: String): BrowserReturn = BrowserReturn(ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), state)

        /** Letters nobody could guess, from the system's own source of randomness, in the
         *  form used in web addresses (as the Windows app makes them). */
        fun unguessable(bytes: Int): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) })
    }
}
