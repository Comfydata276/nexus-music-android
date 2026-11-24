package org.medianexus.music.library

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.store.Plays
import org.medianexus.music.store.SongTable

/**
 * "Your stats": what's been played over a stretch of time, how much listening it comes to,
 * and the songs, artists, albums, kinds of music and playlists played most. A port of the
 * Windows app's `stats` in src/pages.rs (`stats_span`, `stats_counts`, `stats_filters` and the
 * page they make, with their test), rule for rule. Plain Kotlin, worked out off the main
 * thread from the copy of the library on the phone, the log of plays and the list of
 * playlists the phone keeps.
 */
object Stats {
    // The periods: the chips across the top are the first four; the finer choices under "All
    // time" follow; from STATS_YEAR up the number is a calendar year (Windows' numbers, which
    // its route carries).
    const val TODAY = 0
    const val WEEK = 1
    const val MONTH = 2
    /** Everything there's a count of. */
    const val ALL = 3
    const val MONTHS_3 = 4
    const val MONTHS_6 = 5
    const val MONTHS_12 = 6
    const val YEAR = 1000

    /**
     * The stretch of time a period covers, as seconds since 1970: from, and up to but not
     * including. Null for all time. Days, weeks (which start on Monday) and months go by the
     * phone's clock.
     */
    fun span(period: Int, now: ZonedDateTime): Pair<Long, Long>? {
        val zone = now.zone
        val today = now.toLocalDate()
        val ever = Long.MAX_VALUE
        fun began(day: LocalDate) = day.atStartOfDay(zone).toEpochSecond()
        return when {
            period == TODAY -> began(today) to ever
            period == WEEK -> began(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) to ever
            period == MONTH -> began(today.withDayOfMonth(1)) to ever
            period == MONTHS_3 -> began(today.minusMonths(3)) to ever
            period == MONTHS_6 -> began(today.minusMonths(6)) to ever
            period == MONTHS_12 -> began(today.minusMonths(12)) to ever
            period >= YEAR -> began(LocalDate.of(period, 1, 1)) to began(LocalDate.of(period + 1, 1, 1))
            else -> null
        }
    }

    /** How many times each song was played over a period (by id), and how many of those
     *  plays were made from each playlist (by its route key): Windows' `stats_counts`, as
     *  maps, for its test. The page counts the same way into arrays ([tally]). */
    class Counts(val times: Map<String, Long>, val lists: Map<String, Long>)

    fun counts(period: Int, table: SongTable, fetched: Long, log: Plays, now: ZonedDateTime): Counts {
        val tally = tally(period, table, fetched, log, now)
        val times = HashMap<String, Long>()
        for (i in 0 until table.size) if (tally.times[i] > 0) times[table.id(i)] = tally.times[i]
        // (Windows counts songs the library no longer has too; nothing shows them.)
        for ((ref, n) in tally.elsewhere.withIndex()) if (n > 0) times[log.name(ref)] = n
        val lists = HashMap<String, Long>()
        for ((ref, n) in tally.lists.withIndex()) if (n > 0) lists[log.name(ref)] = n
        return Counts(times, lists)
    }

    /** The counts as arrays: plays by place in the table; plays of songs the table doesn't
     *  have, and plays from each playlist, by the log's number for the name. */
    private class Tally(val times: LongArray, val elsewhere: LongArray, val lists: LongArray)

    /**
     * Windows' `stats_counts`. A stretch of time goes by the log of plays, since the server
     * doesn't say when anything was played. "All time" goes by the server's count of each
     * song's plays, which takes in every app and device; to that are added the plays logged
     * since the phone's copy of the library was fetched (`fetched`, seconds), which that copy
     * can't know of yet.
     */
    private fun tally(period: Int, table: SongTable, fetched: Long, log: Plays, now: ZonedDateTime): Tally {
        val places = placesOf(table)
        // Each name the log uses, once: where it is in the table (-1: not a song of it).
        val placeOfRef = IntArray(log.names) { ref -> log.name(ref).let { if (it.isEmpty()) -1 else places[it] ?: -1 } }
        val times = LongArray(table.size)
        val elsewhere = LongArray(log.names)
        fun count(i: Int) {
            val ref = log.songRef(i)
            val place = placeOfRef[ref]
            if (place >= 0) times[place]++ else elsewhere[ref]++
        }
        val span = span(period, now)
        val from: Long
        val until: Long
        if (span == null) {
            for (i in 0 until table.size) times[i] = table.playCount(i).coerceAtLeast(0).toLong()
            for (i in 0 until log.size) if (log.at(i) > fetched) count(i)
            from = Long.MIN_VALUE
            until = Long.MAX_VALUE
        } else {
            from = span.first
            until = span.second
            for (i in 0 until log.size) log.at(i).let { if (it >= from && it < until) count(i) }
        }
        val lists = LongArray(log.names)
        for (i in 0 until log.size) {
            val list = log.fromRef(i)
            if (list < 0) continue
            val at = log.at(i)
            if (at >= from && at < until) lists[list]++
        }
        return Tally(times, elsewhere, lists)
    }

    /** Where each song is in a table, by id: made once per copy of the library. */
    private fun placesOf(table: SongTable): HashMap<String, Int> {
        cached?.let { (of, places) -> if (of === table) return places }
        val places = HashMap<String, Int>(table.size * 2)
        for (i in 0 until table.size) places[table.id(i)] = i
        cached = table to places
        return places
    }

    @Volatile private var cached: Pair<SongTable, HashMap<String, Int>>? = null

    /** A finer choice under "All time": its name (null for a year: the year is its name) and
     *  its number. */
    data class Filter(val period: Int, val year: Boolean)

    /** The finer choices under "All time" (Windows' `stats_filters`): everything, the last
     *  few months, and each calendar year the log of plays reaches into (when it reaches into
     *  more than one), newest first. */
    fun filters(log: Plays, now: ZonedDateTime): List<Filter> {
        val filters = mutableListOf(Filter(ALL, false), Filter(MONTHS_3, false), Filter(MONTHS_6, false), Filter(MONTHS_12, false))
        val first = log.first()?.let { java.time.Instant.ofEpochSecond(it).atZone(now.zone).year }
        if (first != null && first < now.year && first >= YEAR) for (year in now.year downTo first) filters += Filter(year, true)
        return filters
    }

    /** What the page says about where its numbers come from. */
    enum class Note { NONE, EVERYTHING, BEFORE_LOG }

    /** A count of something with what it is: an album, an artist, a genre, a playlist. */
    data class Ranked<T>(val what: T, val times: Long)

    /** The page (Windows' `stats`): `period` as it was taken (a year no longer offered is all
     *  time), the filters under All time, and either what was played or nothing. */
    class Page(
        val period: Int,
        val filters: List<Filter>,
        val note: Note,
        /** When the log starts (seconds), for the note. */
        val since: Long,
        /** Seconds of listening; how many plays; how many different songs; how many artists. */
        val seconds: Long,
        val total: Long,
        val songCount: Int,
        val artistCount: Int,
        /** The top 25 songs, as places in the table, with how often each was played. */
        val songs: IntArray,
        val songTimes: LongArray,
        val artists: List<Ranked<Artist>>,
        val albums: List<Ranked<Album>>,
        val genres: List<Ranked<String>>,
        val playlists: List<Ranked<org.medianexus.music.net.Playlist>>,
    ) {
        val empty: Boolean get() = songs.isEmpty()
    }

    /** The page, from the copy of the library, the log and the account's list of playlists
     *  as the phone has it. */
    fun page(
        period: Int,
        table: SongTable,
        albums: List<Album>,
        artists: List<Artist>,
        fetched: Long,
        log: Plays,
        playlists: List<org.medianexus.music.net.Playlist>,
        now: ZonedDateTime,
    ): Page {
        val allTime = period >= ALL
        val filters = if (allTime) filters(log, now) else emptyList()
        // A year that's no longer offered (or a number that never was) is all time.
        val taken = if (period < ALL || filters.any { it.period == period }) period else ALL
        val tally = tally(taken, table, fetched, log, now)
        val times = tally.times
        // The songs of the library that were played, most first, then by title.
        val found = IntList()
        for (i in 0 until table.size) if (times[i] > 0) found.add(i)
        val played = found.toIntArray()
        played.sortStably { a, b -> if (times[a] != times[b]) times[b].compareTo(times[a]) else Mixes.rustOrder(table.title(a), table.title(b)) }
        // The log only knows what's happened since it was started.
        val started = log.first() ?: now.toEpochSecond()
        val note = when (val span = span(taken, now)) {
            null -> Note.EVERYTHING
            // Only worth saying when the stretch of time reaches back before the log does.
            else -> if (span.first < started) Note.BEFORE_LOG else Note.NONE
        }
        if (played.isEmpty()) {
            return Page(taken, filters, if (allTime) note else Note.NONE, started, 0, 0, 0, 0, IntArray(0), LongArray(0), emptyList(), emptyList(), emptyList(), emptyList())
        }
        // The same plays, added up by artist, by album and by kind of music (one count per
        // name, not a boxed number per play).
        fun sums(key: PlaceText): List<Pair<String, Long>> {
            val sums = HashMap<String, LongArray>()
            for (i in played) {
                val k = key.of(i)
                if (k.isNotEmpty()) sums.getOrPut(k) { LongArray(1) }[0] += times[i]
            }
            return sums.entries.map { it.key to it.value[0] }.sortedWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else Mixes.rustOrder(a.first, b.first) }
        }
        val byArtist = sums { table.artistId(it) }
        val byAlbum = sums { table.albumId(it) }
        val byGenre = sums { table.genre(it) }
        var seconds = 0L
        var total = 0L
        for (i in played) {
            seconds += table.duration(i).toLong() * times[i]
            total += times[i]
        }
        val artistsById = artists.associateBy { it.id }
        val albumsById = albums.associateBy { it.id }
        val topArtists = byArtist.asSequence().mapNotNull { (id, n) -> artistsById[id]?.let { Ranked(it, n) } }.take(10).toList()
        val topAlbums = byAlbum.asSequence().mapNotNull { (id, n) -> albumsById[id]?.let { Ranked(it, n) } }.take(10).toList()
        val topGenres = byGenre.take(10).map { (name, n) -> Ranked(name, n) }
        // The playlists most played from, by the names the phone has for them.
        val counted = ArrayList<Pair<String, Long>>()
        for (ref in tally.lists.indices) {
            val n = tally.lists[ref]
            if (n > 0) log.name(ref).takeIf { it.startsWith("playlist:") }?.let { counted += it.removePrefix("playlist:") to n }
        }
        val lists = counted.sortedWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else Mixes.rustOrder(a.first, b.first) }
        val known = playlists.associateBy { it.id }
        val topLists = lists.mapNotNull { (id, n) -> known[id]?.let { Ranked(it, n) } }.take(10)
        val top = played.copyOf(minOf(25, played.size))
        return Page(
            taken, filters, note, started, seconds, total, played.size, byArtist.size,
            top, LongArray(top.size) { times[top[it]] },
            topArtists, topAlbums, topGenres, topLists,
        )
    }

    /** Text about a place in the table, read without boxing the place. */
    fun interface PlaceText {
        fun of(place: Int): String
    }

    /** A number with its thousands marked off, as Windows writes it: 5210 is "5,210". */
    fun grouped(number: Long): String {
        val digits = number.toString()
        val out = StringBuilder()
        for ((place, digit) in digits.withIndex()) {
            if (place > 0 && (digits.length - place) % 3 == 0) out.append(',')
            out.append(digit)
        }
        return out.toString()
    }

    /** The phone's clock, now. */
    fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
}
