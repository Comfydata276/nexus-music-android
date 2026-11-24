package org.medianexus.music.library

import java.time.Instant
import java.time.ZoneId
import org.medianexus.music.net.Song
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.SongTable

/**
 * How a list of songs is shown (Windows' `TrackView`): in what order, and narrowed to what
 * was typed in its find box. A playlist, Liked songs and the Songs page each remember their
 * order (in [ListPrefs], under the list's key, with Windows' numbers, so the choice can go
 * between devices later); what was typed is forgotten when the page closes, as on Windows.
 */
data class ListView(val sort: Int = OWN, val descending: Boolean = false, val filter: String = "") {
    /** The list as it comes, nothing left out. */
    val isDefault: Boolean get() = sort == OWN && filter.isBlank()

    /** Picking the order already in use turns it round; another starts its own way (dates
     *  newest first), and the list's own order has no other way (Windows' `set_track_sort`). */
    fun sortedBy(choice: Int): ListView {
        val sort = choice.coerceIn(0, SORTS - 1)
        val descending = when {
            sort == OWN -> false
            sort == this.sort -> !this.descending
            else -> startsDescending(sort)
        }
        return copy(sort = sort, descending = descending)
    }

    companion object {
        // The orders, by Windows' numbers (`TRACK_SORTS`): the list's own, title, artist,
        // album, duration, date added.
        const val OWN = 0
        const val TITLE = 1
        const val ARTIST = 2
        const val ALBUM = 3
        const val DURATION = 4
        const val ADDED = 5
        const val SORTS = 6

        /** The most characters a find box takes, and so the most of them a list is narrowed
         *  by: on screen and in what is handed to the player alike. */
        const val MOST_FILTER = 200

        /** Whether an order starts out reversed when first chosen: dates do, newest first. */
        fun startsDescending(sort: Int): Boolean = sort == ADDED

        /**
         * The order a list opens in: the one it was last left in (Windows' `list_sort`). A
         * playlist or Liked songs nobody has sorted yet opens with the songs added most
         * recently at the top.
         */
        fun opening(key: String, prefs: ListPrefs?): ListView {
            val p = prefs ?: ListPrefs()
            if (!p.sortChosen && p.sort == 0 && (key.startsWith("playlist:") || key == "liked")) return ListView(ADDED, true)
            val sort = p.sort.coerceIn(0, SORTS - 1)
            return ListView(sort, sort != OWN && p.descending)
        }

        /** The prefs with this order remembered as chosen, at `at` (seconds): kept even when
         *  back to the usual, so other devices hear of the change. */
        fun remember(prefs: ListPrefs?, view: ListView, at: Long): ListPrefs =
            (prefs ?: ListPrefs()).copy(sort = view.sort, descending = view.descending, sortChosen = true, at = at)

        /**
         * How a queue started from a list follows it as it changes (Windows' `order_for`): in
         * the order the list remembers now, narrowed to what was typed in its find box when
         * the queue was started (`filter`). A song that joins the list and doesn't match it
         * doesn't join the queue.
         */
        fun following(key: String, prefs: ListPrefs?, filter: String): ListView =
            (if (findable(key)) opening(key, prefs) else ListView()).copy(filter = filter)

        /** Whether a list's key is one of the lists that can be found in and sorted. */
        fun findable(key: String): Boolean = key.startsWith("playlist:") || key == "liked" || key == Library.SONGS
    }
}

/**
 * One arrangement of a list as a screen shows it: which list (`list`, the very copy it was
 * worked out from), how (`view`), and the result (`order`, places in the list). A screen
 * keeps showing the last arrangement while a newer one is worked out off the main thread;
 * its rows are not to be tapped meanwhile ([tappable]), since what a tap hands the player is
 * this arrangement, and the person is already typing or choosing another.
 */
class Shown<L>(val list: L, val view: ListView, val order: IntArray) {
    /** Whether this is the arrangement of `list` as `view` now has it. */
    fun tappable(list: L, view: ListView): Boolean = this.list === list && this.view == view
}

/** What arranging a list needs of its songs, by place. */
interface SongColumns {
    val size: Int
    fun title(i: Int): String
    fun artistLabel(i: Int): String
    fun album(i: Int): String
    fun disc(i: Int): Int
    fun track(i: Int): Int
    fun duration(i: Int): Int
}

/** A list of songs as it is. */
fun List<Song>.columns(): SongColumns = object : SongColumns {
    override val size get() = this@columns.size
    override fun title(i: Int) = this@columns[i].title
    override fun artistLabel(i: Int) = this@columns[i].artistLabel
    override fun album(i: Int) = this@columns[i].album
    override fun disc(i: Int) = this@columns[i].discNumber
    override fun track(i: Int) = this@columns[i].track
    override fun duration(i: Int) = this@columns[i].duration
}

/** The phone's copy of the library, in the order of `order` (its places in the table). */
fun SongTable.columns(order: IntArray): SongColumns = object : SongColumns {
    override val size get() = order.size
    override fun title(i: Int) = this@columns.title(order[i])
    override fun artistLabel(i: Int) = this@columns.artistLabel(order[i])
    override fun album(i: Int) = this@columns.album(order[i])
    override fun disc(i: Int) = this@columns.disc(order[i])
    override fun track(i: Int) = this@columns.track(order[i])
    override fun duration(i: Int) = this@columns.duration(order[i])
}

object Arrange {
    /**
     * Which songs to show, and in what order, as places in the list (Windows'
     * `TrackView::arrange`). `added` says when the song at a place was added to the list, in
     * seconds (0 where that isn't known). What was typed is looked for in the title, the
     * artist and the album, whatever the case. A sort is stable, so songs that tie keep the
     * list's own order; reversed, the whole of it is reversed, ties too, as Windows does.
     * `cancelled` is asked every so often: once it says so, the arrangement stops with a
     * [kotlinx.coroutines.CancellationException].
     */
    fun arrange(songs: SongColumns, view: ListView, cancelled: () -> Boolean = { false }, added: (Int) -> Long = { 0 }): IntArray {
        // A newer find or order has taken this one's place: it stops where it is, inside each
        // pass, rather than running to the end of 50,000 songs for nobody.
        fun check(i: Int) {
            if (i and CHECK_EVERY == 0 && cancelled()) throw kotlinx.coroutines.CancellationException("a newer arrangement replaced it")
        }
        val needle = view.filter.trim()
        val shown = ArrayList<Int>(songs.size)
        for (i in 0 until songs.size) {
            check(i)
            if (needle.isEmpty() || songs.title(i).contains(needle, ignoreCase = true) ||
                songs.artistLabel(i).contains(needle, ignoreCase = true) || songs.album(i).contains(needle, ignoreCase = true)
            ) {
                shown += i
            }
        }
        if (view.sort != ListView.OWN) {
            // Each song's key worked out once (Windows' `sort_by_cached_key`).
            val words = arrayOfNulls<String>(songs.size)
            val numbers = LongArray(songs.size)
            for ((n, i) in shown.withIndex()) {
                check(n)
                when (view.sort) {
                    ListView.TITLE -> words[i] = songs.title(i).lowercase()
                    ListView.ARTIST -> words[i] = songs.artistLabel(i).lowercase()
                    ListView.ALBUM -> {
                        words[i] = songs.album(i).lowercase()
                        numbers[i] = songs.disc(i) * 1000L + songs.track(i)
                    }
                    ListView.DURATION -> numbers[i] = songs.duration(i).toLong()
                    else -> numbers[i] = added(i)
                }
            }
            var compared = 0
            shown.sortWith { a, b ->
                check(++compared)
                val byWord = (words[a] ?: "").compareTo(words[b] ?: "")
                if (byWord != 0) byWord else numbers[a].compareTo(numbers[b])
            }
            if (view.descending) shown.reverse()
        }
        return shown.toIntArray()
    }

    /** How often (a mask: every 1,024 songs or comparisons) an arrangement looks at whether
     *  it has been superseded. */
    private const val CHECK_EVERY = 1023

    /** The start of the day a time falls on, here (seconds; Windows' `day_start`): songs
     *  that joined the library on the same day count as equally old, so they keep the
     *  order the list has them in. */
    fun dayStart(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (seconds <= 0) return 0
        return Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
    }

    /** A time as the server writes it ("2024-03-09T10:15:00Z"), in seconds; 0 if it isn't one
     *  (Windows' `parse_time`). */
    fun parseTime(text: String?): Long = text?.takeIf { it.isNotEmpty() }?.let { SongTable.millisOf(it) }?.let { Math.floorDiv(it, 1000L) } ?: 0
}
