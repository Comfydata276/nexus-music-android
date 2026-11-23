package org.medianexus.music

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_800_000_000_000L

    @After
    fun stop() = scope.cancel()

    private fun open(most: Int = Diagnostics.MOST) = Diagnostics(File(folder.root, "diagnostics.txt"), scope, Dispatchers.IO, clock = { now }, most = most)

    private fun written(): List<String> = File(folder.root, "diagnostics.txt").takeIf { it.isFile }?.readLines().orEmpty()

    private suspend fun until(what: () -> Boolean) = withTimeout(10_000) { while (!what()) delay(10) }

    @Test
    fun theSameThingAgainIsCountedNotWrittenAgain() = runBlocking {
        val log = open()
        log.note("connection from an.app: refused")
        now += 5_000
        log.note("playback error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        now += 5_000
        repeat(40) { log.note("connection from an.app: refused") }
        val entries = log.entries()
        assertEquals(2, entries.size)
        // The one seen most lately is last.
        assertEquals("connection from an.app: refused", entries.last().text)
        assertEquals(41, entries.last().count)
        assertEquals(1_800_000_000_000L, entries.last().first)
        assertEquals(1_800_000_010_000L, entries.last().last)
    }

    @Test
    fun itIsBoundedInMemoryAndOnThePhone() = runBlocking {
        val log = open(most = 50)
        for (i in 1..500) log.note("thing number $i " + "x".repeat(1_000), now = true)
        assertEquals(50, log.entries().size)
        assertEquals("thing number 500", log.entries().last().text.substringBefore(" x"))
        assertTrue(log.entries().all { it.text.length <= Diagnostics.MOST_CHARS })
        until { written().size == 50 && written().last().contains("thing number 500") }
        assertTrue(File(folder.root, "diagnostics.txt").length() < 64 * 1024)
    }

    @Test
    fun whatWasNotedIsThereAfterTheAppStops() = runBlocking {
        val first = open()
        first.note("player started by a media button")
        first.note("foreground: entered after 180 ms", now = true)
        until { written().size == 2 }
        // The app is stopped and starts again; something is noted before the file is read.
        val second = open()
        second.note("player started by a media button")
        until { second.entries().size == 2 && second.entries().last().count == 2 }
        assertEquals(listOf("foreground: entered after 180 ms", "player started by a media button"), second.entries().map { it.text })
        until { written().any { it.endsWith("\t2\tplayer started by a media button") } }
    }

    @Test
    fun aLineIsOneLine() {
        val log = open()
        log.note("one\ntwo\tthree\r")
        assertEquals("one two three", log.entries().single().text)
        log.note("   ")
        assertEquals(1, log.entries().size)
    }

    @Test
    fun theReportSaysWhenAndHowOften() {
        val log = open()
        log.note("MP3 is decoded by c2.android.mp3.decoder")
        now += 61_000
        log.note("connection from a.car: admitted")
        log.note("connection from a.car: admitted")
        val report = log.report(listOf("Nexus Music 0.1.0 on Android 17", "Signed in: yes"))
        assertEquals(
            """
            Nexus Music 0.1.0 on Android 17
            Signed in: yes

            What the player has met (times in UTC):
              2027-01-15 08:00:00  MP3 is decoded by c2.android.mp3.decoder
              2027-01-15 08:01:01  connection from a.car: admitted  (2 times since 2027-01-15 08:01:01)
            """.trimIndent(),
            report,
        )
        assertFalse(report.contains('\t'))
    }
}
