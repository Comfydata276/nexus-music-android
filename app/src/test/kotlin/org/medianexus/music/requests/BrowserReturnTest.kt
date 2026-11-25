package org.medianexus.music.requests

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The door the browser comes back through ([BrowserReturn]), with other apps on the phone
 * knocking at it: any of them can connect to a port on the loopback address, and none of
 * them may hold the door, or the wait, or the real browser up.
 */
class BrowserReturnTest {
    @get:Rule
    val noLongerThan: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).build()

    private val state = "the-state-nobody-else-was-told"
    private val door = BrowserReturn.open(state)
    private val port = door.back.removePrefix("http://127.0.0.1:").removeSuffix("/callback").toInt()
    private val sockets = mutableListOf<Socket>()

    @After
    fun tidy() {
        door.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun knock(): Socket = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).also { sockets += it }

    /** Everything a knock is answered with, until the door hangs up (or `millis` pass: then
     *  what came so far, with "…" after it). */
    private fun answerTo(socket: Socket, millis: Int = 5_000): String {
        socket.soTimeout = millis
        val seen = java.io.ByteArrayOutputStream()
        return try {
            seen.write(socket.getInputStream().readBytes())
            seen.toString("UTF-8")
        } catch (_: SocketTimeoutException) {
            seen.toString("UTF-8") + "…"
        } catch (_: IOException) {
            seen.toString("UTF-8")
        }
    }

    private fun ask(text: String, millis: Int = 5_000): String = knock().let {
        it.getOutputStream().write(text.toByteArray())
        it.getOutputStream().flush()
        answerTo(it, millis)
    }

    /** Whether the door hung up on `socket` within `millis` (nothing more to read). */
    private fun hungUpWithin(socket: Socket, millis: Int): Boolean {
        socket.soTimeout = millis
        return try {
            socket.getInputStream().read() == -1
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    private fun waiting(millis: Long = 30_000): CompletableFuture<BrowserReturn.Came> = CompletableFuture.supplyAsync { door.await(millis) }

    private fun elapsed(since: Long) = (System.nanoTime() - since) / 1_000_000

    @Test
    fun `only a whole GET for the callback with the right state is the browser coming back`() {
        val came = waiting()
        // Another method, another path, another state, no state, not HTTP at all: each is
        // told there is nothing here, and the wait goes on.
        for (knock in listOf(
            "POST /callback?code=c&state=$state HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n",
            "PUT /callback?code=c&state=$state HTTP/1.1\r\n\r\n",
            "GET /?code=c&state=$state HTTP/1.1\r\n\r\n",
            "GET /callback?code=c&state=a-guess HTTP/1.1\r\n\r\n",
            "GET /callback?code=c HTTP/1.1\r\n\r\n",
            "GET /callback?state=$state HTTP/1.1\r\n\r\n",
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n",
            "GET /callback?code=c&state=$state\r\n\r\n",
            "\r\n\r\n",
        )) {
            val answer = ask(knock)
            assertTrue("$knock was answered: $answer", answer.startsWith("HTTP/1.1 404 "))
            assertTrue(answer, "Referrer-Policy: no-referrer" in answer)
        }
        Thread.sleep(200)
        assertFalse(came.isDone)
        assertFalse(door.arrived)
        // A line that never ends is not waited for, and is not the callback however it begins.
        val half = knock()
        half.getOutputStream().write("GET /callback?code=c&state=$state".toByteArray())
        half.getOutputStream().flush()
        half.shutdownOutput()
        answerTo(half)
        Thread.sleep(200)
        assertFalse(came.isDone)
    }

    @Test
    fun `whoever sends a line slowly is hung up on two seconds after connecting, and holds nobody up`() {
        val came = waiting()
        val slow = knock()
        val began = System.nanoTime()
        val dribbling = thread(isDaemon = true) {
            runCatching {
                for (letter in "GET /callback?code=mine&state=wrong-and-very-long-indeed-so-that-it-takes-its-time") {
                    slow.getOutputStream().write(letter.code)
                    slow.getOutputStream().flush()
                    Thread.sleep(300)
                }
            }
        }
        Thread.sleep(600)
        // The real browser comes back meanwhile, and is attended to at once.
        val real = knock()
        val asked = System.nanoTime()
        real.getOutputStream().write("GET /callback?code=the-code&state=$state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        real.getOutputStream().flush()
        val arrived = came.get(5, TimeUnit.SECONDS)
        assertTrue("the wait ended ${elapsed(asked)} ms after the browser came back", elapsed(asked) < 1_000)
        assertEquals("the-code", (arrived as BrowserReturn.Came.Code).code)
        // And the slow one is hung up on, two seconds after it connected: not two seconds
        // after its last letter.
        assertTrue(hungUpWithin(slow, 3_000))
        assertTrue("hung up after ${elapsed(began)} ms", elapsed(began) in 1_500..3_200)
        dribbling.join(5_000)
    }

    @Test
    fun `the wait ends when it is due, whoever is connected and however slowly they write`() {
        val slow = knock()
        slow.getOutputStream().write("GET /call".toByteArray())
        slow.getOutputStream().flush()
        val began = System.nanoTime()
        assertEquals(BrowserReturn.Came.Nobody, door.await(700))
        assertTrue("the wait took ${elapsed(began)} ms", elapsed(began) in 600..1_200)
    }

    @Test
    fun `shutting the door hangs up on everyone it had let in`() {
        val came = waiting()
        val idle = knock()
        val slow = knock()
        slow.getOutputStream().write("GET /callb".toByteArray())
        slow.getOutputStream().flush()
        Thread.sleep(300)
        door.close()
        assertEquals(BrowserReturn.Came.GivenUp, came.get(3, TimeUnit.SECONDS))
        assertTrue(hungUpWithin(idle, 1_000))
        assertTrue(hungUpWithin(slow, 1_000))
        assertTrue(runCatching { knock() }.isFailure)
    }

    @Test
    fun `the browser's page waits until it is known how the sign-in ended, and then says the truth`() {
        for (signedIn in listOf(true, false)) {
            val door = BrowserReturn.open(state)
            try {
                val came = CompletableFuture.supplyAsync { door.await(30_000) }
                val browser = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), door.back.removePrefix("http://127.0.0.1:").removeSuffix("/callback").toInt()).also { sockets += it }
                browser.getOutputStream().write("GET /callback?code=the-code&state=$state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                browser.getOutputStream().flush()
                val code = came.get(5, TimeUnit.SECONDS) as BrowserReturn.Came.Code
                assertTrue(door.arrived)
                // Nothing yet: the code has not been traded for a token.
                assertEquals("…", answerTo(browser, 700))
                // And the door takes no more knocks meanwhile.
                assertTrue(runCatching { Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), door.back.removePrefix("http://127.0.0.1:").removeSuffix("/callback").toInt()).use { it.getOutputStream().write(1); hungUpWithin(it, 500) } }.getOrDefault(true))
                code.say(signedIn)
                val page = answerTo(browser)
                assertTrue(page, page.startsWith("HTTP/1.1 200 OK\r\n"))
                assertTrue(page, (if (signedIn) "You're signed in" else "The sign-in didn't go through") in page)
                assertFalse(page, (if (signedIn) "The sign-in didn't go through" else "You're signed in") in page)
                assertTrue(page, "Referrer-Policy: no-referrer\r\n" in page && "Cache-Control: no-store\r\n" in page)
                // Said once.
                code.say(!signedIn)
            } finally {
                door.close()
            }
        }
    }

    @Test
    fun `a browser left waiting for its page is hung up on when the door is shut`() {
        val came = waiting()
        val browser = knock()
        browser.getOutputStream().write("GET /callback?code=the-code&state=$state HTTP/1.1\r\n\r\n".toByteArray())
        browser.getOutputStream().flush()
        assertTrue(came.get(5, TimeUnit.SECONDS) is BrowserReturn.Came.Code)
        door.close()
        assertEquals("", answerTo(browser, 1_000))
    }

    @Test
    fun `more knocks at once than the door attends to are turned away without waiting`() {
        val came = waiting()
        // Four that say nothing take all the attention there is, for two seconds.
        val silent = (1..4).map { knock() }
        Thread.sleep(300)
        val began = System.nanoTime()
        val fifth = knock()
        assertTrue("the fifth was still connected after ${elapsed(began)} ms", hungUpWithin(fifth, 700))
        // Once they have been hung up on, the browser gets in.
        silent.forEach { assertTrue(hungUpWithin(it, 3_000)) }
        val real = knock()
        real.getOutputStream().write("GET /callback?error=access_denied&error_description=No&state=$state HTTP/1.1\r\n\r\n".toByteArray())
        real.getOutputStream().flush()
        assertEquals("No", (came.get(5, TimeUnit.SECONDS) as BrowserReturn.Came.TurnedDown).why)
        assertTrue(answerTo(real).let { "The sign-in didn't go through" in it && "Referrer-Policy: no-referrer" in it })
    }
}
