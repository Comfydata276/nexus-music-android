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

/**
 * The page's grouping, worked out again **only when what is downloaded changes**: a state
 * that differs from the last only in how far a song has got (twice a second while one
 * arrives) is given the grouping already made. Told apart by whether the downloaded songs
 * and the lists are the very same objects as last time, which they are from one tick to the
 * next (the engine hands on the catalogue's own), so asking costs nothing however many
 * thousand songs there are.
 */
class DownloadsViews {
    private var from: Triple<Map<String, org.medianexus.music.downloads.Downloaded>, List<org.medianexus.music.downloads.DownloadedList>, String>? = null
    private var made: DownloadsView? = null

    fun of(state: DownloadsState, likedName: String): DownloadsView {
        val was = from
        made?.let { if (was != null && was.first === state.downloaded && was.second === state.collections && was.third == likedName) return it.takeIf { v -> v.bytes == state.bytes } ?: it.copy(bytes = state.bytes).also { v -> made = v } }
        return DownloadsView.of(state, likedName).also {
            from = Triple(state.downloaded, state.collections, likedName)
            made = it
        }
    }
}

/**
 * A section of the Downloads page that can run to thousands of rows (everything waiting,
 * everything that failed), **folded**: its first few, and how many more there are, until it
 * is opened. What is downloaded is below these sections; a queue of five thousand waiting
 * songs put all five thousand ahead of the music.
 */
data class Folded<T>(val shown: List<T>, val more: Int) {
    companion object {
        /** Rows shown of a folded section. */
        const val MOST = 5

        /** (One over is shown whole: "and 1 more" takes the room the row would.) */
        fun <T> of(all: List<T>, open: Boolean, most: Int = MOST): Folded<T> =
            if (open || all.size <= most + 1) Folded(all, 0) else Folded(all.subList(0, most), all.size - most)
    }
}
