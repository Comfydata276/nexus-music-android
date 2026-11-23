package org.medianexus.music.playback

import java.text.Normalizer
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.Genre
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/**
 * A spoken request ("play Slow Rivers on Nexus Music"), as the Assistant hands it over: the
 * words, and what it made of them when it could tell (a "focus" naming the kind of thing, and
 * fields for an artist, album, song title, playlist or genre).
 */
data class VoiceRequest(
    val query: String,
    val focus: Focus = Focus.ANY,
    val artist: String = "",
    val album: String = "",
    val title: String = "",
    val playlist: String = "",
    val genre: String = "",
) {
    enum class Focus { ANY, ARTIST, ALBUM, SONG, PLAYLIST, GENRE }

    /** Nothing was named: "play music on Nexus Music". */
    val unnamed: Boolean get() = listOf(query, artist, album, title, playlist, genre).all { it.isBlank() }

    /** What to ask the server's search for. */
    fun searchWords(): String = when (focus) {
        Focus.ARTIST -> artist.ifBlank { query }
        Focus.ALBUM -> album.ifBlank { query }
        Focus.SONG -> title.ifBlank { query }
        Focus.PLAYLIST -> playlist.ifBlank { query }
        Focus.GENRE -> genre.ifBlank { query }
        Focus.ANY -> Voice.understood(query).words
    }

    companion object {
        /** From the focus the Assistant sends (a MIME type, "vnd.android.cursor.item/artist"). */
        fun focusOf(mime: String?): Focus = when (mime) {
            "vnd.android.cursor.item/artist" -> Focus.ARTIST
            "vnd.android.cursor.item/album" -> Focus.ALBUM
            "vnd.android.cursor.item/audio" -> Focus.SONG
            "vnd.android.cursor.item/playlist", "android.intent.extra.playlist" -> Focus.PLAYLIST
            "vnd.android.cursor.item/genre", "audio/*" -> Focus.GENRE
            else -> Focus.ANY
        }
    }
}

/** What a spoken request comes to. */
sealed interface VoiceChoice {
    data class OfArtist(val artist: Artist) : VoiceChoice
    data class OfAlbum(val album: Album) : VoiceChoice
    data class OfSong(val song: Song) : VoiceChoice
    data class OfPlaylist(val playlist: Playlist) : VoiceChoice
    data class OfGenre(val genre: String) : VoiceChoice
    data object Nothing : VoiceChoice
}

/**
 * Finding the best match for a spoken request among what search found and the playlists, in
 * the way search ranks: a name said exactly beats one it begins with, which beats one it is
 * part of. Plain Kotlin, so it can be tested on its own.
 */
object Voice {
    /** The words, and the kind of thing, a request with no focus seems to mean: "songs by
     *  X" is an artist, "the album X" an album, "X by Y" a song (or album) X by Y. */
    data class Understood(val words: String, val focus: VoiceRequest.Focus, val by: String = "")

    fun understood(query: String): Understood {
        var q = query.trim().replace(Regex("\\s+"), " ")
        q = q.replace(Regex("\\s+on nexus music$", RegexOption.IGNORE_CASE), "").replace(Regex("^play\\s+", RegexOption.IGNORE_CASE), "").trim()
        val lower = q.lowercase()
        fun after(prefix: String) = q.substring(prefix.length).trim()
        for (prefix in listOf("songs by ", "music by ", "anything by ", "something by ", "the artist ", "artist ")) {
            if (lower.startsWith(prefix)) return Understood(after(prefix), VoiceRequest.Focus.ARTIST)
        }
        for (prefix in listOf("the album ", "album ")) {
            if (lower.startsWith(prefix)) return Understood(after(prefix), VoiceRequest.Focus.ALBUM)
        }
        for (prefix in listOf("the playlist ", "playlist ", "my playlist ")) {
            if (lower.startsWith(prefix)) return Understood(after(prefix), VoiceRequest.Focus.PLAYLIST)
        }
        if (lower.startsWith("my ") && lower.endsWith(" playlist")) {
            return Understood(q.substring(3, q.length - " playlist".length).trim(), VoiceRequest.Focus.PLAYLIST)
        }
        for (prefix in listOf("the song ", "song ")) {
            if (lower.startsWith(prefix)) return Understood(after(prefix), VoiceRequest.Focus.SONG)
        }
        val by = lower.lastIndexOf(" by ")
        if (by > 0) return Understood(q.substring(0, by).trim(), VoiceRequest.Focus.ANY, q.substring(by + 4).trim())
        return Understood(q, VoiceRequest.Focus.ANY)
    }

    /** How well `name` matches what was said, from 0 (not at all) to 1 (exactly). */
    fun score(said: String, name: String): Double {
        val a = plain(said)
        val b = plain(name)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (b.startsWith("$a ") || b.startsWith(a)) return 0.8
        if (" $b ".contains(" $a ")) return 0.7
        if (b.contains(a)) return 0.6
        val saidWords = a.split(' ').toSet()
        val nameWords = b.split(' ').toSet()
        val shared = saidWords.count { it in nameWords }
        if (shared == 0) return 0.0
        return 0.5 * shared / maxOf(saidWords.size, nameWords.size)
    }

    /** Lower case, without accents, punctuation or a leading "the": how two names are
     *  compared, so "Zoe Alvdal" finds "Zoë Älvdal". */
    fun plain(text: String): String {
        val stripped = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val words = stripped.replace('&', ' ').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return words.removePrefix("the ").trim()
    }

    /** The least score that counts as a match at all. */
    private const val ENOUGH = 0.34

    /**
     * The best match for the request among `found` (what the server's search gave for
     * [VoiceRequest.searchWords]) and the account's playlists and genres.
     */
    fun choose(request: VoiceRequest, found: Found, playlists: List<Playlist>, genres: List<Genre>): VoiceChoice {
        val understood = if (request.focus == VoiceRequest.Focus.ANY) understood(request.query) else null
        val focus = understood?.focus ?: request.focus
        val words = understood?.words ?: request.searchWords()
        val by = understood?.by?.ifBlank { null } ?: request.artist.ifBlank { null }

        // Each candidate's score, and a little extra when the artist named is theirs.
        fun artistBonus(name: String) = if (by != null && score(by, name) >= 0.7) 0.25 else 0.0
        val candidates = mutableListOf<Pair<Double, VoiceChoice>>()
        fun consider(kind: VoiceRequest.Focus, rank: Double, choice: VoiceChoice) {
            if (focus != VoiceRequest.Focus.ANY && focus != kind) return
            if (rank >= ENOUGH) candidates += rank to choice
        }
        // A tie goes to the first kind considered: an artist, then an album, a playlist, a
        // song, a genre. ("Play Paper Lanterns" means the band more often than their album.)
        for (artist in found.artists) {
            consider(VoiceRequest.Focus.ARTIST, score(if (focus == VoiceRequest.Focus.ARTIST) request.artist.ifBlank { words } else words, artist.name), VoiceChoice.OfArtist(artist))
        }
        for (album in found.albums) {
            consider(VoiceRequest.Focus.ALBUM, score(words, album.name) + artistBonus(album.artistLabel), VoiceChoice.OfAlbum(album))
        }
        for (playlist in playlists) {
            consider(VoiceRequest.Focus.PLAYLIST, score(words, playlist.name), VoiceChoice.OfPlaylist(playlist))
        }
        for (song in found.songs) {
            val album = if (request.album.isNotBlank() && score(request.album, song.album) >= 0.7) 0.1 else 0.0
            consider(VoiceRequest.Focus.SONG, score(words, song.title) + artistBonus(song.artistLabel) + album, VoiceChoice.OfSong(song))
        }
        for (genre in genres) {
            consider(VoiceRequest.Focus.GENRE, score(words, genre.name), VoiceChoice.OfGenre(genre.name))
        }
        // maxBy keeps the first of equals, which is the order above.
        return candidates.maxByOrNull { it.first }?.second ?: VoiceChoice.Nothing
    }
}
