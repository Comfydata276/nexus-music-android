package org.medianexus.music.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for a service the library talks to, for the tests of the phone's side of its
 * requests: a server on this machine that hands each request, exactly as it arrived, to the
 * test, which answers in bytes of its own choosing. That is what the awkward answers need
 * (a body that never ends, a header that isn't text, an answer that stalls part-way), and
 * what was asked is kept to be looked at afterwards.
 *
 * One request to a connection: every answer closes it.
 */
internal class StandIn(private val answer: (Asked, Reply) -> Unit) : Closeable {
    /** One request, as it arrived. */
    class Asked(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: String) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val line: String get() = "$method $target"
        override fun toString(): String = line
    }

    /** The way back to whoever asked. */
    class Reply(private val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()

        /** A whole answer. */
        fun send(status: Int, body: String = "", vararg headers: Pair<String, String>) = send(status, body.encodeToByteArray(), *headers)

        fun send(status: Int, body: ByteArray, vararg headers: Pair<String, String>) {
            val head = StringBuilder("HTTP/1.1 $status Whatever\r\n")
            for ((name, value) in headers) head.append("$name: $value\r\n")
            head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            out.write(head.toString().encodeToByteArray())
            out.write(body)
            out.flush()
        }

        fun json(body: String, status: Int = 200) = send(status, body, "Content-Type" to "application/json")

        /** Bytes exactly as given. */
        fun raw(bytes: ByteArray) {
            out.write(bytes)
            out.flush()
        }

        fun raw(text: String) = raw(text.encodeToByteArray())

        /**
         * Whether whoever asked hangs up within `millis`: true if it closed (or cut) the
         * connection, false if it was still there at the end.
         */
        fun hungUpWithin(millis: Int): Boolean {
            socket.soTimeout = millis
            return try {
                socket.getInputStream().read() == -1
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: IOException) {
                true
            }
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val open = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort
    val address: String get() = "http://127.0.0.1:$port"

    /** Every request that arrived, in order. */
    val asked = CopyOnWriteArrayList<Asked>()

    /** How many requests were being answered at once, at the most. */
    val mostAtOnce = AtomicInteger(0)
    private val atOnce = AtomicInteger(0)

    init {
        Thread({
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                open += socket
                Thread({ serve(socket) }, "stand-in-answer").apply { isDaemon = true }.start()
            }
        }, "stand-in").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val request = read(socket) ?: return
                asked += request
                val now = atOnce.incrementAndGet()
                mostAtOnce.accumulateAndGet(now, ::maxOf)
                try {
                    answer(request, Reply(socket))
                } finally {
                    atOnce.decrementAndGet()
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            open -= socket
        }
    }

    private fun read(socket: Socket): Asked? {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next == -1) return null
            head.write(next)
            matched = if (next == "\r\n\r\n"[matched].code) matched + 1 else if (next == '\r'.code) 1 else 0
        }
        val lines = head.toString(Charsets.ISO_8859_1).trimEnd().split("\r\n")
        val first = lines.first().split(" ")
        val headers = lines.drop(1).map { it.substringBefore(":").trim() to it.substringAfter(":").trim() }
        val length = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var at = 0
        while (at < length) {
            val count = input.read(body, at, length - at)
            if (count == -1) break
            at += count
        }
        return Asked(first[0], first.getOrElse(1) { "" }, headers, body.decodeToString())
    }

    /** Waits until a request matching `which` has arrived; false if none did in time. */
    fun awaitAsked(millis: Long = 10_000, which: (Asked) -> Boolean): Boolean {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            if (asked.any(which)) return true
            Thread.sleep(10)
        }
        return false
    }

    override fun close() {
        server.close()
        open.forEach { runCatching { it.close() } }
    }
}
