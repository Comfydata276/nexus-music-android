package org.medianexus.music.ui.downloads

import org.medianexus.music.downloads.DownloadedViews
import org.medianexus.music.downloads.DownloadsState
import org.medianexus.music.net.Song

/** An album or a list downloaded whole, as the Downloads page lists it: what it is, and how
 *  much of it is on the phone. `list` is what opens it (see `Route.Downloaded`). */
data class DownloadedGroup(
    val list: String,
    val title: String,
    val by: String,
    val cover: String,
    val songs: Int,
    val bytes: Long,
    val liked: Boolean = false,
)

/**
 * What the Downloads page shows of what is downloaded, grouped as the car's Downloads tab
 * groups it: the playlists (and Liked songs) downloaded whole, the albums anything is
 * downloaded of, and every song, newest first, each with the room it takes. All of it from
 * the downloads' own record, so it is there with no server and from a cold start. Plain
 * Kotlin, so it can be tested on its own.
 */
data class DownloadsView(
    val playlists: List<DownloadedGroup>,
    val albums: List<DownloadedGroup>,
    val songs: List<Song>,
    /** Room the downloads take on the phone, in bytes. */
    val bytes: Long,
) {
    companion object {
        fun of(state: DownloadsState, likedName: String): DownloadsView {
            fun bytesOf(songs: List<Song>) = songs.sumOf { state.downloaded[it.id]?.bytes ?: 0L }
            val playlists = DownloadedViews.playlists(state).filter { it.songs.isNotEmpty() }.map { list ->
                val liked = list.list.key == "liked"
                DownloadedGroup(
                    list = list.list.key,
                    title = if (liked) likedName else list.list.name,
                    by = list.list.by,
                    cover = list.list.coverArt.ifEmpty { list.songs.firstOrNull { it.coverArt.isNotEmpty() }?.coverArt.orEmpty() },
                    songs = list.songs.size,
                    bytes = bytesOf(list.songs),
                    liked = liked,
                )
            }
            val albums = DownloadedViews.albums(state).map { album ->
                DownloadedGroup("album:${album.id}", album.name, album.artist, album.coverArt, album.songs.size, bytesOf(album.songs))
            }
            return DownloadsView(playlists, albums, DownloadedViews.songs(state), state.bytes)
        }
    }
}
