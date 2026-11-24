package org.medianexus.music.library

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.store.Plays
import org.medianexus.music.store.SongTable

/** "Your stats": Windows' test of src/pages.rs (`stats_cover_the_right_stretch_of_time`),
 *  ported as it is, and the page it makes. */
class StatsTest {
    // A zone with summer time, so that "the day began" is the local midnight.
    private val zone = ZoneId.of("Australia/Sydney")

    private fun at(year: Int, month: Int, day: Int, hour: Int) = ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone)

    private fun stamp(year: Int, month: Int, day: Int, hour: Int) = at(year, month, day, hour).toEpochSecond()

    private fun table(songs: List<Song>) = SongTable.Builder().apply { songs.forEach { add(it) } }.build()

    @Test
    fun statsCoverTheRightStretchOfTime() {
        // A Wednesday afternoon.
        val now = at(2026, 11, 18, 15)
        assertEquals(stamp(2026, 11, 18, 0), Stats.span(Stats.TODAY, now)!!.first)
        // The week began on Monday; the month on the 1st.
        assertEquals(stamp(2026, 11, 16, 0), Stats.span(Stats.WEEK, now)!!.first)
        assertEquals(stamp(2026, 11, 1, 0), Stats.span(Stats.MONTH, now)!!.first)
        assertEquals(stamp(2026, 8, 18, 0), Stats.span(Stats.MONTHS_3, now)!!.first)
        assertEquals(stamp(2025, 1, 1, 0) to stamp(2026, 1, 1, 0), Stats.span(2025, now))
        assertEquals(null, Stats.span(Stats.ALL, now))

        // The copy of the library was fetched on Tuesday, when "a" had been played 5 times.
        val library = table(listOf(Song(id = "a", playCount = 5), Song(id = "b", playCount = 0)))
        val fetched = stamp(2026, 11, 17, 9)
        val log = listOf(
            Triple("a", stamp(2025, 12, 30, 10), ""),
            Triple("a", stamp(2026, 11, 2, 10), ""),
            Triple("b", stamp(2026, 11, 16, 8), "playlist:p"),
            Triple("a", stamp(2026, 11, 17, 20), ""),
            Triple("b", stamp(2026, 11, 18, 9), "playlist:p"),
            Triple("b", stamp(2026, 11, 18, 10), "playlist:q"),
        )
        val plays = Plays.of(log)
        fun counts(period: Int) = Stats.counts(period, library, fetched, plays, now).let { it.times.toSortedMap().toList() to it.lists.toSortedMap().toList() }
        assertEquals(listOf("b" to 2L) to listOf("playlist:p" to 1L, "playlist:q" to 1L), counts(Stats.TODAY))
        assertEquals(listOf("a" to 1L, "b" to 3L) to listOf("playlist:p" to 2L, "playlist:q" to 1L), counts(Stats.WEEK))
        assertEquals(listOf("a" to 2L, "b" to 3L), counts(Stats.MONTH).first)
        assertEquals(listOf("a" to 1L), counts(2025).first)
        // All time: the server's counts, and the plays made since they were fetched (which
        // a song nobody had played by then gets too).
        assertEquals(listOf("a" to 6L, "b" to 2L) to listOf("playlist:p" to 2L, "playlist:q" to 1L), counts(Stats.ALL))

        // The years are offered once the log reaches into more than one.
        fun names(log: List<Triple<String, Long, String>>) = Stats.filters(Plays.of(log), now).map { if (it.year) it.period.toString() else it.period.toString() }
        assertEquals(listOf("3", "4", "5", "6", "2026", "2025"), names(log))
        assertEquals(listOf("3", "4", "5", "6"), names(log.drop(1)))
    }

    @Test
    fun thePageIsWindowsOwn() {
        val now = at(2026, 11, 18, 15)
        val songs = listOf(
            Song(id = "a", title = "Alpha", artistId = "ar1", albumId = "al1", genre = "Rock", duration = 200, playCount = 5),
            Song(id = "b", title = "Bravo", artistId = "ar2", albumId = "al2", genre = "Jazz", duration = 100),
            Song(id = "c", title = "Charlie", artistId = "ar1", albumId = "al1", genre = "Rock", duration = 60),
            Song(id = "d", title = "Delta", artistId = "", albumId = "al3", genre = "", duration = 30),
        )
        val log = listOf(
            Triple("b", stamp(2026, 11, 18, 9), "playlist:p"),
            Triple("c", stamp(2026, 11, 18, 10), "playlist:p"),
            Triple("b", stamp(2026, 11, 18, 11), "playlist:gone"),
            Triple("d", stamp(2026, 11, 18, 12), ""),
            // A song no longer in the library counts for nothing.
            Triple("x", stamp(2026, 11, 18, 13), ""),
        )
        val page = Stats.page(
            Stats.TODAY, table(songs),
            albums = listOf(Album(id = "al1", name = "One", artist = "First"), Album(id = "al2", name = "Two", artist = "Second")),
            artists = listOf(Artist(id = "ar1", name = "First"), Artist(id = "ar2", name = "Second")),
            fetched = 0, log = Plays.of(log),
            playlists = listOf(Playlist(id = "p", name = "Morning mix")),
            now = now,
        )
        // Most played first, then by title.
        assertEquals(listOf("b", "c", "d"), page.songs.map { listOf("a", "b", "c", "d")[it] })
        assertEquals(listOf(2L, 1L, 1L), page.songTimes.toList())
        assertEquals(2 * 100L + 60 + 30, page.seconds)
        assertEquals(4L, page.total)
        assertEquals(3, page.songCount)
        // Artists counted are those with an id; shown, those the library has.
        assertEquals(2, page.artistCount)
        assertEquals(listOf("Second" to 2L, "First" to 1L), page.artists.map { it.what.name to it.times })
        // The album with no card in the library's list isn't shown.
        assertEquals(listOf("Two" to 2L, "One" to 1L), page.albums.map { it.what.name to it.times })
        assertEquals(listOf("Jazz" to 2L, "Rock" to 1L), page.genres.map { it.what to it.times })
        // A playlist the phone has no name for (gone, say) isn't shown.
        assertEquals(listOf("Morning mix" to 2L), page.playlists.map { it.what.name to it.times })
        // The log starts at nine this morning: today reaches back before it, and says so.
        assertEquals(Stats.Note.BEFORE_LOG, page.note)
        val longer = Plays.of(listOf(Triple("a", stamp(2026, 10, 1, 9), "")) + log)
        assertEquals(Stats.Note.NONE, Stats.page(Stats.WEEK, table(songs), emptyList(), emptyList(), 0, longer, emptyList(), now).note)

        // Nothing this week but plays before: empty, and All time still has the server's.
        val older = listOf(Triple("a", stamp(2026, 10, 1, 9), ""))
        assertTrue(Stats.page(Stats.WEEK, table(songs), emptyList(), emptyList(), 0, Plays.of(older), emptyList(), now).empty)
        val all = Stats.page(Stats.ALL, table(songs), emptyList(), emptyList(), stamp(2026, 11, 1, 0), Plays.of(older), emptyList(), now)
        assertEquals(Stats.Note.EVERYTHING, all.note)
        assertEquals(5L, all.total)
        // A year no longer offered is all time.
        assertEquals(Stats.ALL, Stats.page(2019, table(songs), emptyList(), emptyList(), 0, Plays.of(older), emptyList(), now).period)
        // A new install: nothing played, nothing counted.
        val none = Stats.page(Stats.ALL, table(songs.map { it.copy(playCount = 0) }), emptyList(), emptyList(), 0, Plays.EMPTY, emptyList(), now)
        assertTrue(none.empty)
    }

    @Test
    fun numbersAreGroupedAsWindowsGroupsThem() {
        assertEquals("0", Stats.grouped(0))
        assertEquals("999", Stats.grouped(999))
        assertEquals("5,210", Stats.grouped(5210))
        assertEquals("1,234,567", Stats.grouped(1_234_567))
    }
}
