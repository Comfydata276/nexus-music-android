package org.medianexus.music

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The record itself. Its reading and writing run on a dispatcher the test holds, so nothing
 * here waits for a file to turn up: what was asked to be written is written when the test
 * lets it, at once or after the record's own short delay, on a clock that is the test's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private val io = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var now = 1_800_000_000_000L

    @After
    fun stop() = scope.cancel()

    private fun open(most: Int = Diagnostics.MOST) = Diagnostics(File(folder.root, "diagnostics.txt"), scope, io, clock = { now }, most = most)

    private fun written(): List<String> = File(folder.root, "diagnostics.txt").takeIf { it.isFile }?.readLines().orEmpty()

    /** Lets everything that was asked for be done: the file read, and whatever is waiting
     *  to be written, its delay included. */
    private fun settle() = scheduler.advanceUntilIdle()

    @Test
    fun theSameThingAgainIsCountedNotWrittenAgain() {
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
    fun itIsBoundedInMemoryAndOnThePhone() {
        val log = open(most = 50)
        for (i in 1..500) log.note("thing number $i " + "x".repeat(1_000), now = true)
        assertEquals(50, log.entries().size)
        assertEquals("thing number 500", log.entries().last().text.substringBefore(" x"))
        assertTrue(log.entries().all { it.text.length <= Diagnostics.MOST_CHARS })
        settle()
        assertEquals(50, written().size)
        assertTrue(written().last().contains("thing number 500"))
        assertTrue(File(folder.root, "diagnostics.txt").length() < 64 * 1024)
    }

    @Test
    fun whatIsNotedIsWrittenInAMomentAndAtOnceWhenItMayBeTheLastThing() {
        val log = open()
        settle()
        log.note("playback error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        // Not at once: something noted again and again is written once.
        scheduler.runCurrent()
        assertTrue(written().isEmpty())
        scheduler.advanceTimeBy(2_000)
        assertEquals(1, written().size)
        // What may be the last thing the app does is written without the wait.
        log.note("foreground: Android refused it", now = true)
        scheduler.runCurrent()
        assertEquals(2, written().size)
    }

    @Test
    fun whatWasNotedIsThereAfterTheAppStops() {
        val first = open()
        first.note("player started by a media button")
        first.note("foreground: entered after 180 ms", now = true)
        settle()
        assertEquals(2, written().size)
        // The app is stopped and starts again; something is noted before the file is read.
        val second = open()
        second.note("player started by a media button")
        assertEquals(1, second.entries().size)
        settle()
        assertEquals(listOf("foreground: entered after 180 ms", "player started by a media button"), second.entries().map { it.text })
        assertEquals(2, second.entries().last().count)
        assertTrue(written().any { it.endsWith("\t2\tplayer started by a media button") })
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
