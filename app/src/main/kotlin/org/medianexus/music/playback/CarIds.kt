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
}

/** The list a song is played as part of. */
sealed interface ListRef {
    data class Album(val id: String) : ListRef
    data class Playlist(val id: String) : ListRef
    data object Liked : ListRef
}

object CarIds {
    const val ROOT = "root"

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
        is CarNode.Track -> "track/${node.index}/${escape(node.songId)}/" + when (val list = node.list) {
            is ListRef.Album -> "album/${escape(list.id)}"
            is ListRef.Playlist -> "playlist/${escape(list.id)}"
            ListRef.Liked -> "liked"
        }
    }

    /**
     * The same place under another name, for showing one thing twice in a list (an album in
     * both "Recently played" and "Recently added"), which the car needs to have different ids.
     */
    fun inGroup(group: String, node: CarNode): String = "${escape(group)}~${format(node)}"

    fun parse(id: String): CarNode? {
        val path = id.substringAfter('~')
        val parts = path.split('/').map(::unescape)
        return when {
            path == ROOT -> CarNode.Root
            parts.size == 1 -> when (parts[0]) {
                "home" -> CarNode.Home
                "playlists" -> CarNode.Playlists
                "library" -> CarNode.Library
                "albums" -> CarNode.Albums
                "artists" -> CarNode.Artists
                "genres" -> CarNode.Genres
                "liked" -> CarNode.Liked
                else -> null
            }
            parts.size == 2 -> when (parts[0]) {
                "albums" -> CarNode.AlbumLetter(parts[1])
                "artists" -> CarNode.ArtistLetter(parts[1])
                "album" -> CarNode.Album(parts[1])
                "artist" -> CarNode.Artist(parts[1])
                "playlist" -> CarNode.Playlist(parts[1])
                "genre" -> CarNode.Genre(parts[1])
                else -> null
            }
            parts[0] == "track" && parts.size >= 4 -> {
                val index = parts[1].toIntOrNull() ?: return null
                val list = when {
                    parts.size == 4 && parts[3] == "liked" -> ListRef.Liked
                    parts.size == 5 && parts[3] == "album" -> ListRef.Album(parts[4])
                    parts.size == 5 && parts[3] == "playlist" -> ListRef.Playlist(parts[4])
                    else -> return null
                }
                CarNode.Track(parts[2], list, index)
            }
            else -> null
        }?.takeIf { node -> node !is CarNode.Track || node.songId.isNotEmpty() }
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
