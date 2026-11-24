package org.medianexus.music.downloads

import org.medianexus.music.net.Album
import org.medianexus.music.net.Found
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/** An album as the downloads have it: its songs that are downloaded, in the album's order. */
data class DownloadedAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val coverArt: String,
    val year: Int,
    val songs: List<Song>,
    /** Downloaded whole (and so kept so as songs are added). */
    val whole: Boolean,
)

/** A playlist (or Liked songs) downloaded whole, with its songs that are downloaded, in its
 *  own order. */
data class DownloadedPlaylist(val list: DownloadedList, val songs: List<Song>)

/**
 * What is downloaded, laid out for browsing with no signal: by playlist, by album and as one
 * list of songs. Everything comes from the downloads' own record ([Catalogue]): names, order,
 * covers and lengths are kept with each download, and nothing is asked of the server or of
 * the kept pages. Used by the screens and the car alike.
 */
object DownloadedViews {
    /** Playlists and Liked songs downloaded whole, Liked songs first, then by name. */
    fun playlists(state: DownloadsState): List<DownloadedPlaylist> =
        state.collections.filter { it.isPlaylist }
            .sortedWith(compareBy({ it.key != "liked" }, { it.name.lowercase() }))
            .map { list -> DownloadedPlaylist(list, list.songs.mapNotNull { state.downloaded[it]?.song }) }

    /**
     * Every album with a song downloaded, by name: an album downloaded whole in its own order
     * (as it was kept), any other with what is downloaded of it in disc and track order.
     */
    fun albums(state: DownloadsState): List<DownloadedAlbum> {
        val lists = state.collections.filter { it.isAlbum }.associateBy { it.key.removePrefix("album:") }
        return state.downloaded.values.map { it.song }.filter { it.albumId.isNotEmpty() }.groupBy { it.albumId }.map { (id, songs) ->
            val list = lists[id]
            val ordered = list?.songs?.mapNotNull { s -> songs.firstOrNull { it.id == s } }?.let { known -> known + songs.filter { it !in known } }
                ?: songs.sortedWith(compareBy({ it.discNumber }, { it.track }, { it.title.lowercase() }))
            val first = ordered.first()
            DownloadedAlbum(
                id = id,
                name = list?.name?.ifEmpty { null } ?: first.album,
                artist = list?.by?.ifEmpty { null } ?: first.artistLabel,
                coverArt = list?.coverArt?.ifEmpty { null } ?: first.coverArt,
                year = list?.year?.takeIf { it > 0 } ?: first.year,
                songs = ordered,
                whole = list != null,
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun album(state: DownloadsState, id: String): DownloadedAlbum? = albums(state).firstOrNull { it.id == id }

    fun playlist(state: DownloadsState, key: String): DownloadedPlaylist? = playlists(state).firstOrNull { it.list.key == key }

    /**
     * The songs of a list the downloads name, as the screens and the car start them: all of
     * them ("downloads", newest first), an album ("downloads:album:<id>") or a list downloaded
     * whole ("downloads:playlist:<id>", "downloads:liked"). Null for any other name.
     */
    fun songsFor(state: DownloadsState, source: String): List<Song>? = when {
        source == SOURCE -> songs(state)
        source.startsWith("$SOURCE:album:") -> album(state, source.removePrefix("$SOURCE:album:"))?.songs
        source.startsWith("$SOURCE:") -> playlist(state, source.removePrefix("$SOURCE:"))?.songs
        else -> null
    }

    /** What a list started from the downloads is said to come from. */
    const val SOURCE = "downloads"

    /** Every downloaded song, the most lately downloaded first (as Windows' Downloads page). */
    fun songs(state: DownloadsState): List<Song> = state.downloaded.values.sortedByDescending { it.at }.map { it.song }

    /** What to keep of a list downloaded whole, from a kept page of it that has just changed:
     *  the list as it now is, and its songs. Null if the page isn't one of those. */
    fun listOf(key: String, page: Any?, was: DownloadedList): Pair<DownloadedList, List<Song>>? = when (page) {
        is Album -> was.copy(name = page.name, by = page.artistLabel, coverArt = page.coverArt, year = page.year) to page.songs
        is Playlist -> was.copy(name = page.name, by = page.owner, coverArt = page.coverArt) to page.songs
        is Found -> if (key == "liked") was to page.songs else null
        else -> null
    }

    /** A list downloaded whole, as a page shows it now. */
    fun ofAlbum(album: Album) = DownloadedList("album:${album.id}", album.name, album.artistLabel, album.coverArt, album.year, album.songs.map { it.id })

    fun ofPlaylist(playlist: Playlist) = DownloadedList("playlist:${playlist.id}", playlist.name, playlist.owner, playlist.coverArt, 0, playlist.songs.map { it.id })

    fun ofLiked(name: String, songs: List<Song>) = DownloadedList("liked", name, "", songs.firstOrNull { it.coverArt.isNotEmpty() }?.coverArt.orEmpty(), 0, songs.map { it.id })
}
