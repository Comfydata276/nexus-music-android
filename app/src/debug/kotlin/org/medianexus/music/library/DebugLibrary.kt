package org.medianexus.music.library

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.medianexus.music.AccountGraph

/**
 * Debug builds only: what the copy of the library costs, for docs/TESTING.md's numbers
 * ("The library on the phone"). Driven by `DebugControl --es library <what>`.
 */
object DebugLibrary {
    /** Where the copy stands: how much of it there is, on the phone and in memory. */
    fun say(account: AccountGraph): String {
        val copy = account.copy
        val now = copy.state.value
        val dir = File(account.account.dirs.cache, "library")
        val onDisk = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return buildString {
            append(if (now == null) "no copy" else "${now.songs.size} songs, ${now.albums.size} albums, ${now.artists.size} artists, ${if (now.complete) "whole" else "still arriving"}")
            copy.fetching.value?.let { append("; fetching (${it.songs} so far${if (it.first) ", the first time" else ""})") }
            append("; ${onDisk / 1024} KB on the phone; search ${if (account.library.search.instant) "on the phone" else "by the server"}")
            append("; Java heap in use ${heapMb()} MB")
        }
    }

    /**
     * What one copy costs in memory: a second copy read from the same file, with the heap
     * measured (after collecting) before and after, then the same for its search index and
     * its newest-first order. The account's own copy is untouched.
     */
    fun memory(account: AccountGraph): String {
        val file = File(account.account.dirs.cache, "library/copy.bin")
        val before = heapBytes()
        val started = System.nanoTime()
        val copy = CopyFile.read(file) ?: return "no whole copy on the phone"
        val read = (System.nanoTime() - started) / 1_000_000
        val afterRead = heapBytes()
        val indexStarted = System.nanoTime()
        copy.index
        val indexed = (System.nanoTime() - indexStarted) / 1_000_000
        val afterIndex = heapBytes()
        val orderStarted = System.nanoTime()
        copy.newestFirst
        val ordered = (System.nanoTime() - orderStarted) / 1_000_000
        val afterOrder = heapBytes()
        val result = "${copy.songs.size} songs: read in $read ms (${file.length() / 1024} KB), ${mb(afterRead - before)} MB in memory; " +
            "search index made in $indexed ms, ${mb(afterIndex - afterRead)} MB; newest-first order in $ordered ms, ${mb(afterOrder - afterIndex)} MB"
        // (Kept alive to here, so nothing above is collected before it is counted.)
        check(copy.songs.size >= 0)
        return result
    }

    /** How long a search of the copy takes, word by word as if typed, each time over. */
    fun search(account: AccountGraph, queries: List<String>, times: Int = 5): String {
        val copy = account.copy.ready ?: return "no whole copy yet"
        val search = account.library.search
        val limits = SearchLimits(artists = 24, albums = 36, songs = 60)
        return queries.joinToString("\n") { query ->
            // As typed, a letter at a time: each step is a search of its own. Typed through
            // `times` times over, so each keystroke is timed as it comes after the one before
            // it (what a search may reuse from the last one, it reuses as it would for a
            // person typing; never the same words searched again straight away).
            val steps = (1..query.length).map { query.substring(0, it) }.filter { it.isNotBlank() }
            val runs = Array(steps.size) { LongArray(times) }
            var songs = 0
            for (time in 0 until times) {
                for ((n, step) in steps.withIndex()) {
                    val started = System.nanoTime()
                    val found = runBlocking(Dispatchers.Default) { search.find(step, limits) }
                    runs[n][time] = (System.nanoTime() - started) / 1_000
                    songs = found.songs.size
                }
            }
            val each = runs.map { it.sorted()[times / 2] }
            // The slowest keystroke by its median (as measured before), and the slowest single run.
            val slowest = each.max()
            val worst = runs.maxOf { it.max() }
            val median = each.sorted()[each.size / 2]
            "\"$query\": ${steps.size} keystrokes, median ${median / 1000.0} ms, slowest ${slowest / 1000.0} ms (worst single ${worst / 1000.0} ms); $songs songs for the whole words (copy of ${copy.songs.size})"
        }
    }

    /** Takes the copy off the phone, for timing a first fetch (the app is then stopped and
     *  opened again). */
    fun forget(account: AccountGraph): String {
        val dir = File(account.account.dirs.cache, "library")
        val had = dir.walkTopDown().filter { it.isFile }.count()
        dir.deleteRecursively()
        return "removed $had files; stop the app and open it again for a first fetch"
    }

    private fun heapBytes(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) {
            runtime.gc()
            System.runFinalization()
            Thread.sleep(100)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun heapMb(): String = mb(heapBytes())

    private fun mb(bytes: Long) = "%.1f".format(bytes / (1024.0 * 1024.0))
}
