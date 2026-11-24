package org.medianexus.music.library

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What makes a keystroke's search fast on a real library's vocabulary (tens of thousands of
 * distinct words, each compared with every typed word at every keystroke) without changing
 * a single answer: nothing allocated per comparison, a length test before any, cancellation
 * looked at inside the loops; and a list's arrangement that has been superseded stops.
 */
class SearchSpeedTest {
    /** Made-up words, the same every time: a real library's spread of lengths and letters. */
    private fun vocabulary(count: Int, seed: Int = 7): List<String> {
        val random = Random(seed)
        val letters = "abcdefghijklmnopqrstuvwxyzéöå"
        val seen = HashSet<String>()
        val out = ArrayList<String>(count)
        while (out.size < count) {
            val word = String(CharArray(3 + random.nextInt(9)) { letters[random.nextInt(letters.length)] })
            if (seen.add(word)) out += word
        }
        return out
    }

    private fun index(words: List<String>): SearchIndex = SearchIndex.Builder().apply {
        // A song a word, credited to a few artists and albums: every word distinct.
        for ((i, word) in words.withIndex()) song(word, "artist ${i % 50}", "album ${i % 300}")
    }.build()

    private fun allocated(block: () -> Unit): Long {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        block()
        return threads.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun aSearchAllocatesNothingPerComparison() {
        val words = vocabulary(40_000)
        val index = index(words)
        // Once first, for what is made only once (classes loaded); then other words, so
        // nothing that was worked out for the first can be reused.
        index.search("zyxwvuts", { false }, 20, 20, 50)
        val bytes = allocated { index.search("abcdefgh mnopq", { false }, 20, 20, 50) }
        // What it must hold: a score for each typed word against each distinct word, and its
        // best in each distinct name (about 80,000 numbers each), and the answer. Three
        // arrays for each of tens of thousands of comparisons is many megabytes more.
        assertTrue("a search allocated $bytes bytes", bytes < 2_000_000)
    }

    @Test
    fun answersAreTheSameAsTheWordForWordPort() {
        // Every comparison against the plain port of Windows' likeness (below): the length
        // test and the reused rows change how fast, never what.
        val words = vocabulary(3_000, seed = 11)
        val random = Random(3)
        repeat(20_000) {
            val word = words[random.nextInt(words.size)]
            val typed = when (random.nextInt(4)) {
                0 -> word.take(1 + random.nextInt(word.length))
                1 -> word.replaceRange(random.nextInt(word.length).let { it..it }, "x")
                2 -> words[random.nextInt(words.size)]
                else -> word.drop(1) + "e"
            }
            assertEquals("$typed for $word", Reference.likeness(SearchRank.points(typed), SearchRank.points(word)), SearchRank.likeness(typed, word))
        }
    }

    @Test
    fun aCancelledSearchStopsPartWay() {
        val words = vocabulary(60_000, seed = 5)
        val index = index(words)
        var asked = 0
        index.comparisons = 0
        val found = index.search("abcdefgh", { false }, 20, 20, 50, cancelled = { ++asked > 1 })
        assertNull(found)
        // It looked between comparisons, not only after every word had been compared.
        assertTrue("compared ${index.comparisons} of ${words.size} words before stopping", index.comparisons < words.size / 2)
    }

    @Test
    fun anOfflineSearchStopsPartWayLikeAnOnlineOne() {
        // What plays offline, a large library of it: each keystroke's search used to run to
        // the end for nobody, and the next waited for it.
        val songs = vocabulary(20_000, seed = 9).mapIndexed { n, word -> org.medianexus.music.net.Song(id = "s$n", title = word, artist = "someone") }
        LocalSearch.find("warm", songs, SearchLimits()) // (the index made once, as when typing begins)
        var asked = 0
        val stopped = runCatching { LocalSearch.find("abcdefgh", songs, SearchLimits(), cancelled = { ++asked > 1 }) }.exceptionOrNull()
        assertTrue("not stopped: $stopped", stopped is kotlinx.coroutines.CancellationException)
        assertTrue(asked > 1)
    }

    @Test
    fun aSupersededArrangementStops() {
        var titles = 0
        val songs = object : SongColumns {
            override val size = 200_000
            override fun title(i: Int): String { titles++; return "song $i" }
            override fun artistLabel(i: Int) = "artist"
            override fun album(i: Int) = "album"
            override fun disc(i: Int) = 1
            override fun track(i: Int) = i
            override fun duration(i: Int) = 100
        }
        var asked = 0
        try {
            Arrange.arrange(songs, ListView(ListView.TITLE, filter = "song"), cancelled = { ++asked > 1 })
            fail("a superseded arrangement ran to the end")
        } catch (_: CancellationException) {
        }
        assertTrue("read $titles titles of ${songs.size}", titles < songs.size / 2)
    }

    /** Windows' `slips` and `likeness`, word for word (src/search.rs): what the fast one must
     *  agree with. */
    private object Reference {
        fun slips(a: IntArray, b: IntArray, limit: Int): Int {
            if (kotlin.math.abs(a.size - b.size) > limit) return limit + 1
            var before = IntArray(b.size + 1)
            var previous = IntArray(b.size + 1) { it }
            var current = IntArray(b.size + 1)
            for (i in 1..a.size) {
                current[0] = i
                var least = current[0]
                for (j in 1..b.size) {
                    val cost = if (a[i - 1] != b[j - 1]) 1 else 0
                    var best = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, before[j - 2] + 1)
                    current[j] = best
                    least = minOf(least, best)
                }
                if (least > limit) return limit + 1
                val swap = before; before = previous; previous = current; current = swap
            }
            return minOf(previous[b.size], limit + 1)
        }

        fun likeness(typed: IntArray, word: IntArray): Float {
            if (typed.contentEquals(word)) return 1f
            if (word.size >= typed.size && word.copyOf(typed.size).contentEquals(typed)) return 0.85f + 0.1f * typed.size / word.size
            val limit = when (typed.size) { in 0..3 -> return 0f; in 4..7 -> 1; else -> 2 }
            if (typed.size <= 5 && typed.firstOrNull() != word.firstOrNull()) return 0f
            val whole = slips(typed, word, limit)
            if (whole <= limit) return if (whole == 1) 0.75f else 0.6f
            var best = limit + 1
            for (length in (typed.size - 1).coerceAtLeast(0)..minOf(typed.size + 1, (word.size - 1).coerceAtLeast(0))) best = minOf(best, slips(typed, word.copyOf(length), limit))
            return when { best == 1 -> 0.4f; best == 2 && limit == 2 -> 0.3f; else -> 0f }
        }
    }
}
