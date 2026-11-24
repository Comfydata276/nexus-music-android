package org.medianexus.music.library

import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Song

/**
 * Songs, albums and artists held as lists, made ready for searching with the same index and
 * scores as the phone's copy of the library ([SearchIndex]).
 */
class ListIndex(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>) {
    private val index: SearchIndex = SearchIndex.Builder().apply {
        for (song in songs) song(song.title, song.artistLabel, song.album)
        for (album in albums) album(album.name, album.artistLabel)
        for (artist in artists) artist(artist.name)
    }.build()

    /** What matches `query`, best first (null if `cancelled` said stop part-way). */
    fun find(query: String, limits: SearchLimits, liked: (String) -> Boolean = { false }, cancelled: () -> Boolean = { false }): Found? {
        val hits = index.search(query, { liked(songs[it].id) }, limits.artists, limits.albums, limits.songs, cancelled = cancelled) ?: return null
        return Found(
            artists = hits.artists.map { artists[it] },
            albums = hits.albums.map { albums[it] },
            songs = hits.songs.map { songs[it] },
            artistFirst = hits.artistFirst,
        )
    }
}

/**
 * Searching what is on the phone, for when the server can't be asked (offline, by hand or
 * because it is out of reach): the songs that play offline, and the albums and artists they
 * belong to, found and ranked exactly as the phone's copy of the library is searched
 * (Windows' search, [SearchIndex]), limited to what can be played. The Search tab, the car's
 * search and spoken requests use it offline.
 */
object LocalSearch {
    fun find(query: String, songs: List<Song>, limits: SearchLimits, liked: (String) -> Boolean = { false }): Found {
        if (SearchRank.words(query).isEmpty()) return Found()
        return indexOf(songs).find(query, limits, liked) ?: Found()
    }

    /** The last index made, and the songs it was made of: the songs that play change only
     *  when something is downloaded or kept, and every keystroke searches them. */
    @Volatile private var last: Pair<List<String>, ListIndex>? = null

    private fun indexOf(songs: List<Song>): ListIndex {
        val ids = songs.map { it.id }
        last?.let { (had, index) -> if (had == ids) return index }
        return ListIndex(songs, OnThePhone.albums(songs), OnThePhone.artists(songs)).also { last = ids to it }
    }

    /** Forgets the index kept (an account has gone: its songs are not the next one's). */
    fun forget() {
        last = null
    }
}
