package org.medianexus.music.playback

/**
 * The places in the car's browse tree, and how each is named in a media id. Ids are paths
 * ("album/<id>", "track/3/<song>/playlist/<id>") with each part escaped, so a genre called
 * "Drum/Bass" or a server's odd id can't be read as something else.
 */
sealed interface CarNode {
    data object Root : CarNode
    data object Home : CarNode
    data object Playlists : CarNode
    data object Library : CarNode
    data object Albums : CarNode
    data object Artists : CarNode
    data object Genres : CarNode
    data object Liked : CarNode
    /** Albums, or artists, whose names start with one letter ("#" for the rest), when there
     *  are too many to list in one go. */
    data class AlbumLetter(val letter: String) : CarNode
    data class ArtistLetter(val letter: String) : CarNode
    data class Album(val id: String) : CarNode
    data class Artist(val id: String) : CarNode
    data class Playlist(val id: String) : CarNode
    data class Genre(val name: String) : CarNode
    /** A song to play, as part of a list: `index` is its place in that list (a playlist can
     *  hold one song twice), or -1 to find it by id. */
    data class Track(val songId: String, val list: ListRef, val index: Int) : CarNode
    /** "Shuffle", at the top of a list of songs: plays the whole list shuffled. */
    data class Shuffle(val list: ListRef) : CarNode
    /** Albums in one of the orders the server gives in a single answer: "newest" (recently
     *  added), "recent" (recently played) or "frequent" (most played). */
    data class AlbumsBy(val kind: String) : CarNode
    /** One stretch of a folder too long to show whole: its entries from `from` up to `to`
     *  (see [CarFolders]). */
    data class Part(val parent: CarNode, val from: Int, val to: Int) : CarNode

    /** The Downloads tab, and what is in it: every downloaded song, an album as the
     *  downloads have it, a playlist (or Liked songs) downloaded whole. All from the
     *  downloads' own record: none of it asks the server or the kept pages. */
    data object Downloads : CarNode
    data object DownloadedSongs : CarNode
    data class DownloadedAlbum(val id: String) : CarNode
    data class DownloadedList(val key: String) : CarNode
}

/** What a "Tap to try again" entry loads again: a folder, by its id, or a search. */
sealed interface CarRetry {
    data class Folder(val parentId: String) : CarRetry
    data class Search(val query: String) : CarRetry
}

/** The list a song is played as part of. */
sealed interface ListRef {
    data class Album(val id: String) : ListRef
    data class Playlist(val id: String) : ListRef
    data object Liked : ListRef
    /** The Downloads tab's lists (see [CarNode.Downloads]). */
    data object DownloadedSongs : ListRef
    data class DownloadedAlbum(val id: String) : ListRef
    data class DownloadedList(val key: String) : ListRef
}

object CarIds {
    const val ROOT = "root"

    /** The orders [CarNode.AlbumsBy] can name. */
    val ALBUM_ORDERS = setOf("newest", "recent", "frequent")

    fun format(node: CarNode): String = when (node) {
        CarNode.Root -> ROOT
        CarNode.Home -> "home"
        CarNode.Playlists -> "playlists"
        CarNode.Library -> "library"
        CarNode.Albums -> "albums"
        CarNode.Artists -> "artists"
        CarNode.Genres -> "genres"
        CarNode.Liked -> "liked"
        is CarNode.AlbumLetter -> "albums/${escape(node.letter)}"
        is CarNode.ArtistLetter -> "artists/${escape(node.letter)}"
        is CarNode.Album -> "album/${escape(node.id)}"
        is CarNode.Artist -> "artist/${escape(node.id)}"
        is CarNode.Playlist -> "playlist/${escape(node.id)}"
        is CarNode.Genre -> "genre/${escape(node.name)}"
        is CarNode.Track -> "track/${node.index}/${escape(node.songId)}/" + list(node.list)
        is CarNode.Shuffle -> "shuffle/" + list(node.list)
        is CarNode.AlbumsBy -> "albums-by/${escape(node.kind)}"
        // The folder it is part of comes last, as it is written on its own.
        is CarNode.Part -> "part/${node.from}/${node.to}/${format(node.parent)}"
        CarNode.Downloads -> "downloads"
        CarNode.DownloadedSongs -> "downloads-songs"
        is CarNode.DownloadedAlbum -> "downloads-album/${escape(node.id)}"
        is CarNode.DownloadedList -> "downloads-list/${escape(node.key)}"
    }

    private fun list(list: ListRef): String = when (list) {
        is ListRef.Album -> "album/${escape(list.id)}"
        is ListRef.Playlist -> "playlist/${escape(list.id)}"
        ListRef.Liked -> "liked"
        ListRef.DownloadedSongs -> "downloads-songs"
        is ListRef.DownloadedAlbum -> "downloads-album/${escape(list.id)}"
        is ListRef.DownloadedList -> "downloads-list/${escape(list.key)}"
    }

    private fun listOf(parts: List<String>): ListRef? = when {
        parts.size == 1 && parts[0] == "liked" -> ListRef.Liked
        parts.size == 1 && parts[0] == "downloads-songs" -> ListRef.DownloadedSongs
        parts.size == 2 && parts[0] == "downloads-album" -> ListRef.DownloadedAlbum(parts[1])
        parts.size == 2 && parts[0] == "downloads-list" -> ListRef.DownloadedList(parts[1])
        parts.size == 2 && parts[0] == "album" -> ListRef.Album(parts[1])
        parts.size == 2 && parts[0] == "playlist" -> ListRef.Playlist(parts[1])
        else -> null
    }

    /**
     * The same place under another name, for showing one thing twice in a list (an album in
     * both "Recently played" and "Recently added"), which the car needs to have different ids.
     */
    fun inGroup(group: String, node: CarNode): String = "${escape(group)}~${format(node)}"

    fun parse(id: String): CarNode? {
        val path = id.substringAfter('~')
        val raw = path.split('/')
        val parts = raw.map(::unescape)
        return when {
            path == ROOT -> CarNode.Root
            parts[0] == "part" && parts.size >= 4 -> {
                val from = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val to = parts[2].toIntOrNull()?.takeIf { it > from } ?: return null
                // Only a folder has parts, and a part is not itself split by name.
                val parent = parse(raw.drop(3).joinToString("/")) ?: return null
                if (parent is CarNode.Part || parent is CarNode.Track || parent is CarNode.Shuffle || parent == CarNode.Root) return null
                CarNode.Part(parent, from, to)
            }
            parts[0] == "shuffle" && parts.size >= 2 -> CarNode.Shuffle(listOf(parts.drop(1)) ?: return null)
            parts.size == 1 -> when (parts[0]) {
                "home" -> CarNode.Home
                "playlists" -> CarNode.Playlists
                "library" -> CarNode.Library
                "albums" -> CarNode.Albums
                "artists" -> CarNode.Artists
                "genres" -> CarNode.Genres
                "liked" -> CarNode.Liked
                "downloads" -> CarNode.Downloads
                "downloads-songs" -> CarNode.DownloadedSongs
                else -> null
            }
            parts.size == 2 -> when (parts[0]) {
                "albums" -> CarNode.AlbumLetter(parts[1])
                "artists" -> CarNode.ArtistLetter(parts[1])
                "album" -> CarNode.Album(parts[1])
                "artist" -> CarNode.Artist(parts[1])
                "playlist" -> CarNode.Playlist(parts[1])
                "genre" -> CarNode.Genre(parts[1])
                "albums-by" -> CarNode.AlbumsBy(parts[1]).takeIf { parts[1] in ALBUM_ORDERS }
                "downloads-album" -> CarNode.DownloadedAlbum(parts[1])
                "downloads-list" -> CarNode.DownloadedList(parts[1])
                else -> null
            }
            parts[0] == "track" && parts.size >= 4 -> {
                val index = parts[1].toIntOrNull() ?: return null
                CarNode.Track(parts[2], listOf(parts.drop(3)) ?: return null, index)
            }
            else -> null
        }?.takeIf { node -> node !is CarNode.Track || node.songId.isNotEmpty() }
    }

    /** The id of the "Tap to try again" entry shown in a folder that couldn't be fetched. It
     *  names nothing to play: opening it loads the folder again. `attempt` makes each one's id
     *  its own: a car keeps what it was given for an id it has opened before, and would show
     *  the same failure again without asking. */
    fun retryFolder(parentId: String, attempt: Long): String = "retry/folder/$attempt/${escape(parentId)}"

    /** The same, in the results of a search. */
    fun retrySearch(query: String, attempt: Long): String = "retry/search/$attempt/${escape(query)}"

    /** What a "Tap to try again" entry's id asks to load again; null for any other id. */
    fun retryOf(id: String): CarRetry? {
        val parts = id.split('/')
        if (parts.size != 4 || parts[0] != "retry" || parts[2].toLongOrNull() == null) return null
        return when (parts[1]) {
            "folder" -> CarRetry.Folder(unescape(parts[3]))
            "search" -> CarRetry.Search(unescape(parts[3]))
            else -> null
        }
    }

    /** '%', '/' and '~' as %25, %2F and %7E; everything else as it is. */
    internal fun escape(part: String): String = buildString {
        for (c in part) when (c) {
            '%' -> append("%25")
            '/' -> append("%2F")
            '~' -> append("%7E")
            else -> append(c)
        }
    }

    internal fun unescape(part: String): String = buildString {
        var i = 0
        while (i < part.length) {
            val code = if (part[i] == '%' && i + 3 <= part.length) part.substring(i, i + 3) else ""
            when (code) {
                "%25" -> append('%')
                "%2F" -> append('/')
                "%7E" -> append('~')
                else -> {
                    append(part[i])
                    i++
                    continue
                }
            }
            i += 3
        }
    }
}
