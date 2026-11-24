package org.medianexus.music.downloads

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An HTTP server for tests in which the order of events is the test's to decide: an answer
 * can be sent as far as a given byte and then held at a gate until the test opens it, and can
 * then go on, or have its connection dropped. Nothing here waits on a clock.
 *
 * One request a connection, plain HTTP/1.1, which is all OkHttp needs of it.
 */
class GatedServer : Closeable {
    class Asked(val path: String, val headers: Map<String, String>) {
        /** The first byte asked for by a `Range` header, or null. */
        val from: Long? get() = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        fun query(name: String): String? = path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    class Answer(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        /** Sent with no length, as Navidrome streams a fresh conversion. */
        val chunked: Boolean = false,
        /** The length stated, if not the body's own (a server that says one thing and sends
         *  another). */
        val saysLength: Long? = null,
        /** Hold the answer once this many bytes of the body have gone (-1: don't). */
        val holdAfter: Int = -1,
        val gate: Gate? = null,
    )

    /** Where an answer is held: the test waits for it to get there, then says what next. */
    class Gate {
        private val there = CountDownLatch(1)
        private val open = CountDownLatch(1)
        @Volatile private var drop = false

        /** Waits until the answer has been sent as far as the gate. */
        fun awaitReached() = check(there.await(30, TimeUnit.SECONDS)) { "the answer never got as far as its gate" }

        /** Lets the rest of the answer go. */
        fun letThrough() = open.countDown()

        /** Drops the connection where it stands. */
        fun dropHere() {
            drop = true
            open.countDown()
        }

        internal fun reached() = there.countDown()

        /** Returns whether to carry on (false: drop). */
        internal fun pass(): Boolean {
            open.await()
            return !drop
        }
    }

    @Volatile var handler: (Asked) -> Answer = { Answer(404) }
    val asked: MutableList<Asked> = Collections.synchronizedList(mutableListOf())

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val workers = Executors.newCachedThreadPool { Thread(it, "GatedServer").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val open = Collections.synchronizedSet(HashSet<Socket>())

    val port: Int get() = socket.localPort
    fun url(path: String): String = "http://127.0.0.1:$port$path"

    init {
        workers.execute {
            while (!closed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                open += client
                workers.execute { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                val input = BufferedInputStream(client.getInputStream())
                val head = StringBuilder()
                // Up to the blank line that ends the request's head.
                while (!head.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte < 0) return
                    head.append(byte.toChar())
                }
                val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
                val path = lines.first().split(' ').getOrElse(1) { "/" }
                val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                val request = Asked(path, headers)
                asked += request
                val answer = handler(request)
                val out = client.getOutputStream()
                val text = StringBuilder("HTTP/1.1 ${answer.status} ${if (answer.status in 200..299) "OK" else "No"}\r\nConnection: close\r\n")
                answer.headers.forEach { (name, value) -> text.append("$name: $value\r\n") }
                if (answer.chunked) text.append("Transfer-Encoding: chunked\r\n") else text.append("Content-Length: ${answer.saysLength ?: answer.body.size}\r\n")
                text.append("\r\n")
                out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
                fun send(from: Int, to: Int) {
                    if (to <= from) return
                    if (answer.chunked) {
                        out.write("${Integer.toHexString(to - from)}\r\n".toByteArray())
                        out.write(answer.body, from, to - from)
                        out.write("\r\n".toByteArray())
                    } else {
                        out.write(answer.body, from, to - from)
                    }
                }
                var sent = 0
                if (answer.holdAfter >= 0 && answer.gate != null) {
                    sent = minOf(answer.holdAfter, answer.body.size)
                    send(0, sent)
                    out.flush()
                    answer.gate.reached()
                    if (!answer.gate.pass()) {
                        // The connection goes, mid-answer.
                        client.setSoLinger(true, 0)
                        return
                    }
                }
                send(sent, answer.body.size)
                if (answer.chunked) out.write("0\r\n\r\n".toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
            // The client went away: its business.
        } finally {
            open -= client
        }
    }

    /** The network is cut: nothing answers, and what was open is dropped. */
    override fun close() {
        closed = true
        runCatching { socket.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    companion object {
        /**
         * Answers for a file as Navidrome was seen to serve one as it is (24 November 2026):
         * its length, `Last-Modified` and no `ETag`; a range answered with exactly that range
         * if `If-Range` is absent or names this copy, the whole file if it names another;
         * "no such range" past the end. `conditional = false` is a server that takes no
         * notice of `If-Range`.
         */
        fun file(request: Asked, bytes: ByteArray, modified: String?, type: String = "audio/flac", conditional: Boolean = true, hold: Pair<Int, Gate>? = null, date: String? = now()): Answer {
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = type
            headers["Accept-Ranges"] = "bytes"
            if (modified != null) headers["Last-Modified"] = modified
            // The answer's own date, as every server sends one: what a modification date is
            // held against (see PartOf).
            if (date != null) headers["Date"] = date
            val from = request.from
            val same = !conditional || request.headers["if-range"].let { it == null || it == modified }
            if (from != null && from > 0 && same) {
                if (from >= bytes.size) return Answer(416, mapOf("Content-Range" to "bytes */${bytes.size}", "Content-Type" to "text/plain"), "no such range".toByteArray())
                headers["Content-Range"] = "bytes $from-${bytes.size - 1}/${bytes.size}"
                return Answer(206, headers, bytes.copyOfRange(from.toInt(), bytes.size), holdAfter = hold?.first ?: -1, gate = hold?.second)
            }
            return Answer(200, headers, bytes, holdAfter = hold?.first ?: -1, gate = hold?.second)
        }

        /** A fresh conversion, as Navidrome streams one: no length, no ranges, no validator. */
        fun conversion(bytes: ByteArray): Answer =
            Answer(200, mapOf("Content-Type" to "audio/mpeg", "Accept-Ranges" to "none"), bytes, chunked = true)

        /** The server's own answer to `getSong`. */
        fun song(id: String, suffix: String, size: Long, duration: Int): Answer = Answer(
            200, mapOf("Content-Type" to "application/json"),
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0","song":{"id":"$id","title":"${id.uppercase()}","suffix":"$suffix","size":$size,"duration":$duration}}}""".toByteArray(),
        )

        /** The time now, as an HTTP date. */
        fun now(): String = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))

        fun fixture(name: String): ByteArray = GatedServer::class.java.getResourceAsStream("/audio/$name")!!.use { it.readBytes() }
    }
}
