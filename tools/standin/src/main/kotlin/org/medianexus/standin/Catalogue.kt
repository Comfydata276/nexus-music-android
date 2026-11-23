package org.medianexus.standin

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * FIXTURE. The stand-in's made-up library: six albums of five tones each, two playlists,
 * and a browse tree shaped like the one docs/DESIGN.md gives the real app (four tabs;
 * albums and playlists as grids, songs as lists; choosing a song plays its whole album or
 * playlist from that song).
 */
class Catalogue(private val context: Context) {
    class Song(val key: String, val title: String, val artist: String, val album: Int, val number: Int)

    val albums = listOf("Alpha Sessions", "Bravo Nights", "Charlie Road", "Delta Blue", "Echo Park", "Foxtrot Live")
    private val albumArtists = listOf("The Stand-ins", "The Stand-ins", "Fixture Four", "Fixture Four", "The Stand-ins", "Placeholder Trio")
    val artists = albumArtists.distinct()
    private val ordinals = listOf("First", "Second", "Third", "Fourth", "Fifth")
    val songs = albums.flatMapIndexed { a, album ->
        ordinals.mapIndexed { n, word -> Song("a${a}s$n", "${album.substringBefore(' ')} $word", albumArtists[a], a, n) }
    }
    val playlists = listOf("Fixture favourites" to listOf(0, 6, 12, 18, 24, 29), "Long playlist" to songs.indices.toList())
    private val liked = listOf(1, 7, 13, 21)
    private val downloaded = listOf(0, 3)

    private fun faults() = Faults.active(context)

    private fun art(key: String): Uri =
        if ("art_http" in faults()) Uri.parse("https://example.invalid/art/$key.png")
        else Uri.parse("content://org.medianexus.standin.art/$key")

    private fun icon(name: String): Uri = Uri.parse("android.resource://${context.packageName}/drawable/$name")

    private fun folder(id: String, title: String, artwork: Uri?, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                       subtitle: String? = null, extras: Bundle? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setArtworkUri(artwork)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).setExtras(extras).build()
        ).build()

    private fun style(browsable: Int? = null, playable: Int? = null, group: String? = null) = Bundle().apply {
        browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
        group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
    }

    private val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val list = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    val root: MediaItem = folder("root", "Stand-in", null)

    private fun albumFolder(a: Int, group: String? = null) =
        folder("album/$a", albums[a], art("album$a"), MediaMetadata.MEDIA_TYPE_ALBUM, albumArtists[a], style(playable = list, group = group))

    /** The songs behind a list id (an album, a playlist, the liked songs), in order. */
    private fun songsOf(listId: String): List<Song>? = when {
        listId == "liked" -> liked.map { songs[it] }
        listId.startsWith("album/") -> listId.substringAfter('/').toIntOrNull()?.let { a -> songs.filter { it.album == a } }?.takeIf { it.isNotEmpty() }
        listId.startsWith("playlist/") -> listId.substringAfter('/').toIntOrNull()?.let { playlists.getOrNull(it) }?.second?.map { songs[it] }
        listId.startsWith("artist-songs/") -> listId.substringAfter('/').toIntOrNull()?.let { artists.getOrNull(it) }?.let { name -> songs.filter { it.artist == name } }
        else -> null
    }

    /** A song as shown in a list. Its id says which list it was shown in and where. */
    private fun shown(song: Song, listId: String, index: Int): MediaItem =
        MediaItem.Builder().setMediaId("song|$listId|$index").setMediaMetadata(metadata(song)).build()

    private fun metadata(song: Song) = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
        .setAlbumTitle(albums[song.album]).setAlbumArtist(albumArtists[song.album]).setArtworkUri(art("album${song.album}"))
        .setTrackNumber(song.number + 1).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()

    fun children(parent: String): List<MediaItem>? = when (parent) {
        "root" -> listOf(
            folder("tab/home", "Home", icon("ic_home")),
            folder("tab/playlists", "Playlists", icon("ic_playlists"), extras = style(browsable = grid)),
            folder("tab/library", "Library", icon("ic_library"), extras = style(browsable = list)),
            folder("tab/downloads", "Downloads", icon("ic_downloads"), extras = style(browsable = grid)),
        ) + if ("five_tabs" in faults()) listOf(shown(songs[0], "album/0", 0)) else emptyList()
        "tab/home" -> listOf(folder("liked", "Liked songs", art("liked"), MediaMetadata.MEDIA_TYPE_PLAYLIST, extras = style(playable = list))) +
            listOf(2, 4, 1).map { albumFolder(it, "Recently played") } + listOf(5, 3).map { albumFolder(it, "Recently added") }
        "tab/playlists" -> playlists.mapIndexed { i, p ->
            folder("playlist/$i", p.first, art("playlist$i"), MediaMetadata.MEDIA_TYPE_PLAYLIST, "${p.second.size} songs", style(playable = list))
        }
        "tab/library" -> listOf(
            folder("albums", "Albums", icon("ic_library"), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, extras = style(browsable = grid)),
            folder("artists", "Artists", icon("ic_library"), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, extras = style(browsable = list)),
            folder("genres", "Genres", icon("ic_library"), MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
        )
        "tab/downloads" -> downloaded.map { albumFolder(it) }
        "albums" -> albums.indices.map { albumFolder(it) }
        "artists" -> artists.mapIndexed { i, name -> folder("artist/$i", name, art("artist$i"), MediaMetadata.MEDIA_TYPE_ARTIST, extras = style(browsable = grid)) }
        "genres" -> emptyList() // on purpose: one empty folder, to see how it is reported and drawn
        else -> when {
            parent.startsWith("artist/") -> parent.substringAfter('/').toIntOrNull()?.let { artists.getOrNull(it) }
                ?.let { name -> albums.indices.filter { albumArtists[it] == name }.map { albumFolder(it) } }
            else -> songsOf(parent)?.mapIndexed { i, song -> shown(song, parent, i) }
        }
    }

    fun item(id: String): MediaItem? = when {
        id == "root" -> root
        id.startsWith("song|") -> queueFor(id)?.let { it.first[it.second] }
        else -> (listOf("root", "tab/home", "tab/playlists", "tab/library", "albums", "artists")).firstNotNullOfOrNull { p -> children(p)?.firstOrNull { it.mediaId == id } }
    }

    /** What the player should be given for a list: every song, with the file to play. */
    private fun playable(listId: String): List<MediaItem>? = songsOf(listId)?.mapIndexed { i, song ->
        MediaItem.Builder().setMediaId("song|$listId|$i").setUri(Uri.fromFile(tone(song))).setMediaMetadata(metadata(song)).build()
    }

    /** Choosing one song queues the whole list it was shown in, starting at that song. */
    fun queueFor(songId: String): Pair<List<MediaItem>, Int>? {
        val parts = songId.split("|")
        if (parts.size != 3 || parts[0] != "song") return null
        val items = playable(parts[1]) ?: return null
        val index = parts[2].toIntOrNull()?.takeIf { it in items.indices } ?: return null
        return items to index
    }

    fun queueOfList(listId: String, start: Int = 0): Pair<List<MediaItem>, Int>? = playable(listId)?.let { it to start.coerceIn(0, it.size - 1) }

    fun search(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = mutableListOf<MediaItem>()
        albums.indices.filter { albums[it].contains(q, true) }.forEach { found += albumFolder(it, "Albums") }
        artists.indices.filter { artists[it].contains(q, true) }.forEach { found += children("artists")!![it] }
        playlists.indices.filter { playlists[it].first.contains(q, true) }.forEach { found += children("tab/playlists")!![it] }
        songs.filter { it.title.contains(q, true) }.forEach { found += shown(it, "album/${it.album}", it.number) }
        return found
    }

    /**
     * A spoken request. [extras] are what the Assistant adds when it knows the kind of
     * thing asked for; without them the words are tried as a playlist, an album, an
     * artist, then a song.
     */
    fun forVoice(query: String, extras: Bundle?): Pair<List<MediaItem>, Int>? {
        fun album(name: String?) = albums.indexOfFirst { name != null && it.contains(name, true) }.takeIf { it >= 0 }?.let { queueOfList("album/$it") }
        fun artist(name: String?) = artists.indexOfFirst { name != null && it.contains(name, true) }.takeIf { it >= 0 }?.let { queueOfList("artist-songs/$it") }
        fun playlist(name: String?) = playlists.indexOfFirst { name != null && it.first.contains(name, true) }.takeIf { it >= 0 }?.let { queueOfList("playlist/$it") }
        fun song(name: String?) = songs.firstOrNull { name != null && it.title.contains(name, true) }?.let { queueOfList("album/${it.album}", it.number) }
        return when (extras?.getString("android.intent.extra.focus")) {
            "vnd.android.cursor.item/artist" -> artist(extras.getString("android.intent.extra.artist"))
            "vnd.android.cursor.item/album" -> album(extras.getString("android.intent.extra.album"))
            "vnd.android.cursor.item/playlist" -> playlist(extras.getString("android.intent.extra.playlist"))
            "vnd.android.cursor.item/audio" -> song(extras.getString("android.intent.extra.title"))
            else -> null
        } ?: playlist(query) ?: album(query) ?: artist(query) ?: song(query)
    }

    /** Sixteen seconds of one note, written the first time the song is needed. */
    private fun tone(song: Song): File {
        val file = File(context.cacheDir, "tones/${song.key}.wav")
        if (file.length() > 44) return file
        file.parentFile?.mkdirs()
        val rate = 11025
        val count = rate * 16
        val pitch = 220.0 * Math.pow(2.0, (song.album * 5 + song.number * 2) / 12.0)
        val data = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count * 2)
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            val swell = 0.6 + 0.4 * sin(2 * PI * t / 2.0)
            val edge = minOf(1.0, t / 0.02, (16.0 - t) / 0.02)
            data.putShort((sin(2 * PI * pitch * t) * 0.3 * swell * edge * Short.MAX_VALUE).toInt().toShort())
        }
        val part = File(file.path + ".part")
        part.writeBytes(data.array())
        part.renameTo(file)
        return file
    }
}
