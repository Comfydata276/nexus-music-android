package org.medianexus.music.library

import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Genre
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Voice

/**
 * The library as far as what is on the phone goes, for when the server can't be asked: the
 * albums, artists and genres of the songs that play offline (`Availability.playable`), made
 * from those songs alone, with no page needed. The car's offline tabs and the phone's offline
 * Library and Home show the same.
 */
object OnThePhone {
    /** The albums of these songs, by name. */
    fun albums(songs: List<Song>): List<Album> = songs.filter { it.albumId.isNotEmpty() }.groupBy { it.albumId }.map { (id, of) ->
        val first = of.first()
        Album(id = id, name = first.album, artist = first.artist, artistId = first.artistId, displayArtist = first.displayArtist, coverArt = first.coverArt, songCount = of.size, year = first.year)
    }.sortedBy { Voice.plain(it.name) }

    /** Everyone credited on these songs, by name. */
    fun artists(songs: List<Song>): List<Artist> {
        val seen = LinkedHashMap<String, Artist>()
        for (song in songs) {
            val credits = song.artists.filter { it.id.isNotEmpty() }.map { it.id to it.name }.ifEmpty { listOf(song.artistId to song.artist) }
            for ((id, name) in credits) if (id.isNotEmpty()) seen.getOrPut(id) { Artist(id = id, name = name) }
        }
        return seen.values.sortedBy { Voice.plain(it.name) }
    }

    /** The genres of these songs, with how many albums and songs of each, by name. */
    fun genres(songs: List<Song>): List<Genre> = songs.filter { it.genre.isNotEmpty() }.groupBy { it.genre }.map { (name, of) ->
        Genre(name = name, albumCount = of.mapTo(HashSet()) { it.albumId }.size, songCount = of.size)
    }.sortedBy { it.name.lowercase() }

    /** The ids of the albums these songs are on. */
    fun albumIds(songs: List<Song>): Set<String> = songs.mapTo(HashSet()) { it.albumId }
}
