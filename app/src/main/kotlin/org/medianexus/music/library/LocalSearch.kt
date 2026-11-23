package org.medianexus.music.library

import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Song

/**
 * Searching what is on the phone, for when the server can't be asked (offline, by hand or
 * because it is out of reach): the songs that play offline, and the albums and artists they
 * belong to, ranked by the same word matching as the rest of search ([SearchRank]). The car's
 * search and spoken requests use it offline.
 */
object LocalSearch {
    fun find(query: String, songs: List<Song>, limits: SearchLimits): Found {
        val typed = SearchRank.words(query)
        if (typed.isEmpty()) return Found()

        val songHits = songs.mapNotNull { song ->
            SearchRank.rate(typed, SearchRank.words(song.title), listOf(SearchRank.words(song.artistLabel) to 0.6f, SearchRank.words(song.album) to 0.5f))?.let { it.score to song }
        }.sortedByDescending { it.first }.take(limits.songs).map { it.second }

        val albums = songs.filter { it.albumId.isNotEmpty() }.groupBy { it.albumId }.map { (id, of) ->
            val first = of.first()
            Album(id = id, name = first.album, artist = first.artist, artistId = first.artistId, displayArtist = first.displayArtist, coverArt = first.coverArt, songCount = of.size, year = first.year, genre = first.genre)
        }
        val albumHits = albums.mapNotNull { album ->
            SearchRank.rate(typed, SearchRank.words(album.name), listOf(SearchRank.words(album.artistLabel) to 0.6f))?.let { it.score to album }
        }.sortedByDescending { it.first }.take(limits.albums).map { it.second }

        val artists = LinkedHashMap<String, Artist>()
        for (song in songs) {
            val credits = song.artists.filter { it.id.isNotEmpty() }.map { it.id to it.name }.ifEmpty { listOf(song.artistId to song.artist) }
            for ((id, name) in credits) if (id.isNotEmpty() && id !in artists) artists[id] = Artist(id = id, name = name)
        }
        val artistHits = artists.values.mapNotNull { artist ->
            SearchRank.rate(typed, SearchRank.words(artist.name))?.let { it.score to artist }
        }.sortedByDescending { it.first }.take(limits.artists).map { it.second }
        return Found(artists = artistHits, albums = albumHits, songs = songHits)
    }
}
