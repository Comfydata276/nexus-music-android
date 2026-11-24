package org.medianexus.music.store

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayLogTest {
    @get:Rule val folder = TemporaryFolder()

    private val io = Dispatchers.IO

    /** The log open now: closed before another is opened for its file, as an account's is
     *  when it is let go. */
    private var last: PlayLog? = null

    private fun log(file: File, recent: List<Pair<String, Long>> = emptyList()): PlayLog {
        last?.let { runBlocking { it.close().join() } }
        return PlayLog(file, io).also { runBlocking { it.open { recent } } }.also { last = it }
    }

    private fun PlayLog.settle() = runBlocking { flushed() }

    /** Windows' `keeps_plays_between_runs` (src/plays.rs), ported as it is, but for the log
     *  from before 0.26 (one list in one file), which the phone never wrote. */
    @Test
    fun keepsPlaysBetweenRuns() {
        val path = File(folder.root, "plays.log")
        path.writeText("[\"a\",100]\n[\"b\",200]\n")
        var log = log(path)
        log.add("c", 300, "")
        log.settle()
        // Adding a play adds a line and leaves the rest of the file as it was.
        assertEquals("[\"a\",100]\n[\"b\",200]\n[\"c\",300]\n", path.readText())
        // A line cut short (the app was killed part-way through writing it) is passed over.
        path.appendText("[\"d\",4")
        log = log(path)
        assertEquals(listOf("a" to 100L, "b" to 200L, "c" to 300L), log.shared())
        path.delete()

        // Plays from another device are taken in once each, oldest first.
        log = log(path, listOf("c" to 300L))
        val elsewhere = listOf("z" to 350L, "c" to 300L, "y" to 320L, "z" to 350L)
        val from = listOf(Triple("y", 320L, "playlist:p"))
        assertEquals(2, runBlocking { log.absorb(elsewhere, from) })
        assertEquals(0, runBlocking { log.absorb(elsewhere, from) })
        assertEquals(listOf("c" to 300L, "y" to 320L, "z" to 350L), log.shared())
        // A play made from a playlist says so, here and after the log is read again.
        log.add("w", 400, "playlist:q")
        assertEquals(listOf(Triple("w", 400L, "playlist:q"), Triple("y", 320L, "playlist:p")), log.sources().sortedBy { it.first })
        log.settle()
        assertTrue(path.readText().endsWith("[\"y\",320,\"playlist:p\"]\n[\"z\",350]\n[\"w\",400,\"playlist:q\"]\n"))
        log = log(path)
        assertEquals(2, log.sources().size)
        assertEquals(4, log.shared().size)
        path.delete()

        // No log at all: it starts from what Recently played has, which is newest first.
        log = log(path, listOf("y" to 20L, "x" to 10L))
        assertEquals(listOf("x" to 10L, "y" to 20L), log.shared())
        log.settle()
        assertEquals("[\"x\",10]\n[\"y\",20]\n", path.readText())
    }

    @Test
    fun aLineCutShortIsCutOffBeforeTheNextIsAdded() {
        val path = File(folder.root, "plays.log")
        path.writeText("[\"a\",100]\n[\"b\",2")
        val log = log(path)
        log.add("c", 300)
        log.settle()
        // Windows would have joined the next play to the fragment, and lost both.
        assertEquals("[\"a\",100]\n[\"c\",300]\n", path.readText())
        assertEquals(listOf("a" to 100L, "c" to 300L), log(path).shared())
    }

    @Test
    fun playsNotedBeforeTheFileIsReadComeAfterIt() {
        val path = File(folder.root, "plays.log")
        path.writeText("[\"a\",100]\n")
        val log = PlayLog(path, io).also { last = it }
        log.add("early", 150, "playlist:p")
        // Nothing is written before the file has been read: it would be written over.
        assertEquals("[\"a\",100]\n", path.readText())
        runBlocking { log.open() }
        log.settle()
        assertEquals(listOf("a" to 100L, "early" to 150L), log.shared())
        assertEquals("[\"a\",100]\n[\"early\",150,\"playlist:p\"]\n", path.readText())
    }

    @Test
    fun theOldestGoOnceTheLogIsWellPastItsLimit() {
        val path = File(folder.root, "plays.log")
        val text = StringBuilder()
        for (i in 0 until PlayLog.LIMIT + PlayLog.SLACK) text.append(PlayLog.line("s${i % 500}", i.toLong(), if (i == 0) "playlist:p" else ""))
        path.writeText(text.toString())
        val log = log(path)
        assertEquals(PlayLog.LIMIT + PlayLog.SLACK, log.state.value.size)
        // One more and it is past: back to the limit, the oldest gone, the file written whole.
        log.add("new", 1_000_000)
        assertEquals(PlayLog.LIMIT, log.state.value.size)
        assertEquals((PlayLog.SLACK + 1).toLong(), log.state.value.at(0))
        assertTrue(log.sources().isEmpty())
        log.settle()
        val lines = path.readLines()
        assertEquals(PlayLog.LIMIT, lines.size)
        assertEquals("[\"new\",1000000]", lines.last())
        // And it carries on from there, a line at a time.
        log.add("next", 1_000_001)
        log.settle()
        assertEquals(PlayLog.LIMIT + 1, path.readLines().size)
    }

    @Test
    fun aFileThatCantBeReadIsNeverWrittenOver() {
        // A folder where the file should be: it is there, and can't be read as one.
        val path = File(folder.root, "plays.log").also { it.mkdirs() }
        val notes = mutableListOf<String>()
        val log = PlayLog(path, io) { notes += it }
        runBlocking { log.open { listOf("x" to 1L) } }
        log.add("a", 100)
        // Said, not hidden.
        assertTrue(runCatching { log.settle() }.exceptionOrNull() is java.io.IOException)
        assertTrue(path.isDirectory)
        assertEquals(listOf("a" to 100L), log.shared())
        assertFalse(notes.isEmpty())
    }

    @Test
    fun linesAreWindowsOwn() {
        assertEquals("[\"a\",100]\n", PlayLog.line("a", 100, ""))
        assertEquals("[\"a\",100,\"playlist:p\"]\n", PlayLog.line("a", 100, "playlist:p"))
        assertEquals(Triple("a", 100L, ""), PlayLog.parse("[\"a\",100]"))
        assertEquals(Triple("a", 100L, "playlist:p"), PlayLog.parse("[\"a\",100,\"playlist:p\"]"))
        // As serde reads a tuple: the wrong kinds, or too many, aren't a play.
        assertEquals(null, PlayLog.parse("[\"a\",\"100\"]"))
        assertEquals(null, PlayLog.parse("[\"a\",100,7]"))
        assertEquals(null, PlayLog.parse("[\"a\",100,\"p\",\"q\"]"))
        assertEquals(null, PlayLog.parse("[\"a\",4"))
        assertEquals(null, PlayLog.parse(""))
    }

    // ---- the M5b fixes, items 1 and 2 ---------------------------------------------------------

    /** Signing out and straight back in to the same account: the new log reads the file only
     *  once the old one has closed, with its last play written; and a closed log never writes
     *  again. (It used to read the file as it was, and the last of the two to write threw the
     *  other's play away.) */
    @Test
    fun oneOwnerOfTheFileAtATime() = runBlocking {
        val path = File(folder.root, "plays.log")
        val old = PlayLog(path, io)
        old.open()
        // The old log's write of its last play is held at a gate.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val atGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        old.beforeWrite = { atGate.complete(Unit); gate.await() }
        old.add("a1", 100)
        atGate.await()
        val closed = old.close()
        // The account comes straight back: its new log opens while the old write waits.
        val new = PlayLog(path, io)
        val opening = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { new.open() }
        kotlinx.coroutines.delay(300)
        assertFalse("opened before the old log let the file go", opening.isCompleted)
        gate.complete(Unit)
        closed.join()
        opening.await()
        assertEquals(listOf("a1" to 100L), new.shared())
        // The old log is closed: nothing it is told now is kept or written.
        old.add("late", 150)
        new.add("b1", 200)
        new.flushed()
        assertEquals("[\"a1\",100]\n[\"b1\",200]\n", path.readText())
        new.close().join()
        assertEquals(listOf("a1" to 100L, "b1" to 200L), log(path).shared())
    }

    /** A write that failed part-way (the disk full) is taken back before the next: no play is
     *  in the file twice. (It used to be appended again after what it had left.) And the
     *  failure is said by `flushed`. */
    @Test
    fun aFailedWriteIsTakenBackBeforeTheNext() = runBlocking {
        val path = File(folder.root, "plays.log")
        path.writeText("[\"a\",100]\n")
        val log = log(path)
        // Half the line goes in, then the disk is full.
        log.appendBytes = { raf, bytes ->
            raf.write(bytes, 0, bytes.size / 2)
            throw java.io.IOException("No space left on device")
        }
        log.add("b", 200)
        assertTrue(runCatching { log.flushed() }.exceptionOrNull() is java.io.IOException)
        // The whole line written, and then its sync fails.
        log.appendBytes = { raf, bytes ->
            raf.write(bytes)
            throw java.io.IOException("sync failed")
        }
        assertTrue(runCatching { log.flushed() }.exceptionOrNull() is java.io.IOException)
        log.appendBytes = { raf, bytes -> raf.write(bytes) }
        log.add("c", 300)
        log.flushed()
        assertEquals("[\"a\",100]\n[\"b\",200]\n[\"c\",300]\n", path.readText())
        assertEquals(listOf("a" to 100L, "b" to 200L, "c" to 300L), log(path).shared())
    }

    /** Opening and appending agree on where the last record ends: a last line with no newline
     *  that reads as a play is one (as Windows reads it), and is given its newline before the
     *  next; one that doesn't read is cut off. */
    @Test
    fun theLastRecordEndsInOnePlaceForReadingAndWriting() = runBlocking {
        val path = File(folder.root, "plays.log")
        path.writeText("[\"a\",100]\n[\"b\",200]")
        var log = log(path)
        assertEquals(listOf("a" to 100L, "b" to 200L), log.shared())
        log.add("c", 300)
        log.flushed()
        assertEquals("[\"a\",100]\n[\"b\",200]\n[\"c\",300]\n", path.readText())
        path.appendText("[\"d\",4")
        log = log(path)
        log.add("e", 500)
        log.flushed()
        assertEquals("[\"a\",100]\n[\"b\",200]\n[\"c\",300]\n[\"e\",500]\n", path.readText())
    }
}
