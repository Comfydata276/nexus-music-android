package org.medianexus.music.library

import org.medianexus.music.net.Song

/**
 * Songs from the library that would suit a playlist (Windows' `playlist_extras`): ones the
 * server reckons similar to a few of the songs in it, picked afresh each time, so asking again
 * gives different ones; if the server has nothing to go on (no Last.fm), songs of the kind the
 * playlist is mostly made of. Never one already in it, nor the same song from another album,
 * and no more than two by one artist.
 */
object Recommend {
    /** How many songs are suggested for a playlist (Windows' `SUGGESTED`). */
    const val SUGGESTED = 10

    /** What a list of suggestions is based on: what's in the playlist, or its kind of music. */
    enum class Basis { SIMILAR, GENRE }

    data class Picks(val songs: List<Song>, val basis: Basis)

    /** The songs of the playlist to ask about: three at random, never a radio station. */
    fun seeds(playlist: List<Song>, random: kotlin.random.Random = kotlin.random.Random.Default): List<Song> =
        playlist.filter { it.radioUrl.isEmpty() && it.id.isNotEmpty() }.shuffled(random).take(3)

    /** The kind of music most of the playlist is (ties to the first by name), or null. */
    fun genreOf(playlist: List<Song>): String? = playlist.filter { it.genre.isNotEmpty() }.groupingBy { it.genre }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key

    /**
     * Picks from the server's similar songs for each seed (`similar`, one list per seed, the most
     * alike first), a song from each in turn; then, if that is fewer than [SUGGESTED], from
     * `ofGenre` (random songs of the playlist's main genre). Null if there is nothing to suggest.
     */
    fun pick(playlist: List<Song>, similar: List<List<Song>>, ofGenre: List<Song>): Picks? {
        val listed = playlist.mapTo(HashSet()) { it.id }
        // The same song from another album is no suggestion either.
        val named = playlist.mapTo(HashSet()) { plain(it.title) to plain(it.artist) }
        val picks = ArrayList<Song>()
        fun take(song: Song) {
            val known = song.id in listed || (plain(song.title) to plain(song.artist)) in named || picks.any { it.id == song.id }
            val by = picks.count { it.artistId == song.artistId }
            if (!known && by < 2 && picks.size < SUGGESTED) picks += song
        }
        for (place in 0 until 30) for (list in similar) list.getOrNull(place)?.let(::take)
        val fromSimilar = picks.size
        if (picks.size < SUGGESTED) ofGenre.forEach(::take)
        if (picks.isEmpty()) return null
        return Picks(picks, if (fromSimilar == 0) Basis.GENRE else Basis.SIMILAR)
    }

    private fun plain(text: String): String = SearchRank.fold(text).split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
