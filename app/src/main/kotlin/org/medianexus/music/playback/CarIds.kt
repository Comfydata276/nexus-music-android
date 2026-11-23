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
}

/** The list a song is played as part of. */
sealed interface ListRef {
    data class Album(val id: String) : ListRef
    data class Playlist(val id: String) : ListRef
    data object Liked : ListRef
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
    }

    private fun list(list: ListRef): String = when (list) {
        is ListRef.Album -> "album/${escape(list.id)}"
        is ListRef.Playlist -> "playlist/${escape(list.id)}"
        ListRef.Liked -> "liked"
    }

    private fun listOf(parts: List<String>): ListRef? = when {
        parts.size == 1 && parts[0] == "liked" -> ListRef.Liked
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
                else -> null
            }
            parts[0] == "track" && parts.size >= 4 -> {
                val index = parts[1].toIntOrNull() ?: return null
                CarNode.Track(parts[2], listOf(parts.drop(3)) ?: return null, index)
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
