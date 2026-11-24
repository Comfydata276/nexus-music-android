package org.medianexus.music.library

import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Genre
import org.medianexus.music.net.Playlist
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

    /** One album as far as the phone has it: its songs among these, in disc and track
     *  order. Null if none of them is on it. */
    fun album(songs: List<Song>, id: String): Album? {
        val of = songs.filter { it.albumId == id }.sortedWith(compareBy({ it.discNumber }, { it.track }, { it.title.lowercase() }))
        val first = of.firstOrNull() ?: return null
        return Album(id = id, name = first.album, artist = first.artist, artistId = first.artistId, displayArtist = first.displayArtist, coverArt = first.coverArt, songCount = of.size, year = first.year, songs = of)
    }

    /** These songs' songs by one artist (credited on them, or theirs outright). */
    fun songsBy(songs: List<Song>, artistId: String): List<Song> = songs.filter { it.artistId == artistId || it.artists.any { a -> a.id == artistId } }

    /** One artist as far as the phone has them: who they are, and their albums with a song
     *  among these. Null if nothing of theirs is. */
    fun artist(songs: List<Song>, id: String, name: String = ""): Artist? {
        val theirs = songsBy(songs, id)
        if (theirs.isEmpty()) return null
        val called = name.ifEmpty { theirs.firstNotNullOfOrNull { song -> song.artists.firstOrNull { it.id == id }?.name?.ifEmpty { null } } ?: theirs.first().artist }
        val albums = albums(theirs)
        return Artist(id = id, name = called, albumCount = albums.size, albums = albums)
    }

    /**
     * The playlists to show with no server: **every playlist downloaded whole**, from the
     * downloads' own record (`downloaded`, with how many of its songs are on the phone),
     * whether or not the server's list of playlists is kept or has it; and of the kept list
     * (`kept`), those with something that plays (`plays`). In the kept list's order where it
     * is known, the downloaded ones it doesn't have first.
     */
    fun playlists(kept: List<Playlist>?, downloaded: List<Playlist>, plays: (Playlist) -> Boolean): List<Playlist> {
        val whole = downloaded.associateBy { it.id }
        val known = kept.orEmpty().mapNotNull { listed -> whole[listed.id]?.let { d -> listed.copy(songCount = d.songCount, coverArt = listed.coverArt.ifEmpty { d.coverArt }) } ?: listed.takeIf(plays) }
        val listed = known.mapTo(HashSet()) { it.id }
        return downloaded.filter { it.id !in listed } + known
    }

    /** The ids of the albums these songs are on. */
    fun albumIds(songs: List<Song>): Set<String> = songs.mapTo(HashSet()) { it.albumId }
}
