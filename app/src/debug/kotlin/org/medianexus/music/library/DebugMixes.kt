package org.medianexus.music.library

import org.medianexus.music.AccountGraph
import org.medianexus.music.store.Plays

/**
 * Debug builds only: what working out the "Made for you" mixes and "Your stats" costs, for
 * docs/TESTING.md ("Your stats and the mixes"). Driven by `DebugControl --es library mixes`.
 *
 * Each is timed seven times over (the median and the slowest), on the account's own copy of
 * the library, with its own log of plays and with a full one (200,000 plays made up from the
 * library's songs over the last three years, a third of them from playlists: the most the
 * log keeps). What is allocated is the runtime's own count of bytes handed out (ART's
 * `art.gc.bytes-allocated`), read before and after each run with nothing else going on.
 */
object DebugMixes {
    fun run(account: AccountGraph): String {
        val copy = account.copy.ready ?: return "no whole copy yet"
        val table = copy.songs
        val now = System.currentTimeMillis() / 1000
        val liked = account.likes.liked.value
        val recent = account.recentlyPlayed.state.value.map { it.song.id to it.at }
        val rules = account.ruleMixes.state.value.ifEmpty {
            // Mixes of one's own, of each order, as if made.
            listOf(MixRules(id = 1, name = "a", order = 0), MixRules(id = 2, name = "b", genre = "rock", order = 1, size = 3), MixRules(id = 3, name = "c", order = 2, added = 3), MixRules(id = 4, name = "d", played = 3, order = 3, size = 2))
        }
        val from = Mixes.From(table, { it in liked }, recent, rules, now)
        val out = StringBuilder()
        var made = emptyList<Mix>()
        out.append(timed("all the mixes (${rules.size} of one's own)") { made = Mixes.all(from) })
        out.append(" -> ${made.size} mixes: ${made.joinToString { "${it.id} ${it.size}" }}\n")
        val zone = Stats.now()
        val own = account.playLog.state.value
        val full = fullLog(table, now)
        val playlists = runCatching { kotlinx.coroutines.runBlocking { account.pages.kept(account.library.playlists()) } }.getOrNull().orEmpty()
        for ((name, log) in listOf("its own log (${own.size} plays)" to own, "a full log (${full.size} plays)" to full)) {
            for (period in listOf(Stats.TODAY, Stats.WEEK, Stats.MONTH, Stats.ALL, Stats.MONTHS_12, zone.year - 1)) {
                out.append(timed("stats $period, $name") { Stats.page(period, table, copy.albums, copy.artists, copy.stamp.fetched, log, playlists, zone) }).append('\n')
            }
        }
        out.append("library: ${table.size} songs")
        return out.toString()
    }

    private fun fullLog(table: org.medianexus.music.store.SongTable, now: Long): Plays {
        val random = java.util.Random(1)
        val count = org.medianexus.music.store.PlayLog.LIMIT
        val span = 3L * 365 * 24 * 3600
        return Plays.of(List(count) { i ->
            val at = now - span + span * i / count
            val song = table.id(random.nextInt(table.size))
            Triple(song, at, if (i % 3 == 0) "playlist:p${random.nextInt(40)}" else "")
        })
    }

    private fun timed(what: String, work: () -> Unit): String {
        work()
        val runs = LongArray(7)
        val bytes = LongArray(7)
        for (n in runs.indices) {
            System.gc()
            val before = allocated()
            val started = System.nanoTime()
            work()
            runs[n] = System.nanoTime() - started
            bytes[n] = allocated() - before
        }
        runs.sort()
        bytes.sort()
        return "$what: median ${"%.1f".format(runs[3] / 1e6)} ms, slowest ${"%.1f".format(runs[6] / 1e6)} ms, allocating ${bytes[3] / 1024} KB"
    }

    private fun allocated(): Long = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0
}
