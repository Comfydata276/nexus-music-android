package org.medianexus.music.ui.home

import java.time.Instant
import org.medianexus.music.R
import org.medianexus.music.net.Album

/** A shelf on Home: its name (a string resource), its albums, and the Albums sort its
 *  "Show all" opens (null for none). */
data class HomeShelf(val title: Int, val albums: List<Album>, val showAll: Int? = null)

/**
 * What Home shows, from the four album lists it asks for, as the Windows app's `home` in
 * src/pages.rs puts it together (less "Made for you" and "Artists you might like", which
 * come with later milestones). Plain Kotlin, so it can be tested on its own.
 */
object HomeModel {
    /** How many of each list Home asks for; more of the most played, to find old favourites
     *  among. */
    const val ROW = 12
    const val FREQUENT = 60

    /** The quick tiles at the top: what was played lately, topped up with the newest
     *  arrivals, eight at most, no album twice. */
    fun tiles(recent: List<Album>, newest: List<Album>): List<Album> {
        val tiles = mutableListOf<Album>()
        for (album in recent + newest) {
            if (tiles.size < 8 && tiles.none { it.id == album.id }) tiles += album
        }
        return tiles
    }

    /**
     * The shelves, in the Windows order: Recently added, Recently played, Most played, It's
     * been a while (albums played a lot, but not for 45 days; only with four or more), and
     * Something different. Empty ones are left out. `now` is in seconds since 1970.
     */
    fun shelves(recent: List<Album>, newest: List<Album>, frequent: List<Album>, random: List<Album>, now: Long): List<HomeShelf> {
        val neglected = frequent.filter { album ->
            val last = playedAt(album.played)
            last > 0 && now - last > 45L * 24 * 3600
        }.take(ROW)
        return listOfNotNull(
            HomeShelf(R.string.recently_added, newest, showAll = SORT_NEWEST),
            HomeShelf(R.string.recently_played, recent, showAll = SORT_RECENT),
            HomeShelf(R.string.most_played, frequent.take(ROW), showAll = SORT_FREQUENT),
            if (neglected.size >= 4) HomeShelf(R.string.been_a_while, neglected) else null,
            HomeShelf(R.string.something_different, random),
        ).filter { it.albums.isNotEmpty() }
    }

    /** "Good morning", "Good afternoon" or "Good evening", by the hour. */
    fun greeting(hour: Int): Int = when (hour) {
        in 5..11 -> R.string.good_morning
        in 12..17 -> R.string.good_afternoon
        else -> R.string.good_evening
    }

    /** When the server says something was last played, in seconds since 1970; 0 if it
     *  doesn't say, or says it in a form that can't be read. */
    fun playedAt(text: String): Long = if (text.isEmpty()) 0 else runCatching { Instant.parse(text).epochSecond }.getOrDefault(0)

    // Places in ALBUM_SORTS (see the Albums page).
    const val SORT_NEWEST = 0
    const val SORT_RECENT = 3
    const val SORT_FREQUENT = 4
}
