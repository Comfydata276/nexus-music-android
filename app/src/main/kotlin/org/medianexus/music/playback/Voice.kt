package org.medianexus.music.playback

import java.text.Normalizer
import org.medianexus.music.library.SearchRank
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
 * part of. What search found is a match on search's own terms (every word said is in it, a
 * slip or two forgiven, as Windows' search judges it): the scoring here chooses between the
 * kinds of thing found (an artist, an album, a song, a playlist), and never turns a match
 * down because the words weren't said exactly ("Lantern Sesaon" is "Lantern Season"). Plain
 * Kotlin, so it can be tested on its own.
 */
object Voice {
    /** The words, and the kind of thing, a request with no focus seems to mean: "songs by
     *  X" is an artist, "the album X" an album, "X by Y" a song (or album) X by Y. */
    data class Understood(val words: String, val focus: VoiceRequest.Focus, val by: String = "")

    fun understood(query: String): Understood {
        val q = spoken(query)
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

    /** The words of a request, without "play" before them or "on Nexus Music" after. */
    fun spoken(query: String): String =
        query.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+on nexus music$", RegexOption.IGNORE_CASE), "").replace(Regex("^play\\s+", RegexOption.IGNORE_CASE), "").trim()

    /** The artist a request names besides what it asks for ("Duke by Paper Lanterns"; the
     *  Assistant's artist field), if any. */
    fun artistNamed(request: VoiceRequest): String? =
        (if (request.focus == VoiceRequest.Focus.ANY) understood(request.query).by.ifBlank { null } else null) ?: request.artist.ifBlank { null }

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
     * How a name matches what was said on search's terms ([SearchRank.rate]: every word said
     * in the name, or in the other names it answers to, slips forgiven), as a score of this
     * scorer's: from [ENOUGH] for a match at all to a little under a name merely containing
     * the words; 0 for no match. A name said exactly scores higher by [score] anyway.
     */
    private fun matched(said: List<String>, name: String, others: List<Pair<String, Float>> = emptyList()): Double {
        val rated = SearchRank.rate(said, SearchRank.words(name), others.map { (text, weight) -> SearchRank.words(text) to weight }) ?: return 0.0
        return ENOUGH + 0.25 * (rated.score / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * The best match for the request among `found` (what the server's search gave for
     * [VoiceRequest.searchWords]) and the account's playlists and genres. A request that
     * names an artist besides a song or album ("Duke by Paper Lanterns") is never given a
     * song or album by another. `exactOnly`: only a name said exactly (what the phone may
     * play at once before it is known whether the server is there). `plain`: the words as
     * said, any kind, nothing read into them (no "by").
     */
    fun choose(request: VoiceRequest, found: Found, playlists: List<Playlist>, genres: List<Genre>, exactOnly: Boolean = false, plain: Boolean = false): VoiceChoice {
        val understood = if (plain) Understood(spoken(request.query), VoiceRequest.Focus.ANY) else if (request.focus == VoiceRequest.Focus.ANY) understood(request.query) else null
        val focus = understood?.focus ?: request.focus
        val words = understood?.words ?: request.searchWords()
        val by = if (plain) null else artistNamed(request)

        // Each candidate's score, and a little extra when the artist named is theirs.
        fun theirs(name: String) = by != null && score(by, name) >= 0.7
        fun artistBonus(name: String) = if (theirs(name)) 0.25 else 0.0
        // The words said, as search folds them: what search's own terms are judged on.
        val said = SearchRank.words(words)
        val candidates = mutableListOf<Pair<Double, VoiceChoice>>()
        // Said exactly: the name is the words, as understood or as said whole ("Song 4" is a
        // title said exactly, though it is also understood as "the song 4").
        val whole = spoken(request.query)
        fun exactly(asked: String, name: String) = score(asked, name) >= 1.0 || score(whole, name) >= 1.0
        fun consider(kind: VoiceRequest.Focus, rank: Double, choice: VoiceChoice, exact: Boolean) {
            if (focus != VoiceRequest.Focus.ANY && focus != kind) return
            if (exactOnly && !exact) return
            if (rank >= ENOUGH) candidates += rank to choice
        }
        // A tie goes to the first kind considered: an artist, then an album, a playlist, a
        // song, a genre. ("Play Paper Lanterns" means the band more often than their album.)
        // Each is as good as its name said exactly, or as its match on search's terms, the
        // better of the two: search's match is never turned down here.
        for (artist in found.artists) {
            val asked = if (focus == VoiceRequest.Focus.ARTIST) request.artist.ifBlank { words } else words
            consider(VoiceRequest.Focus.ARTIST, maxOf(score(asked, artist.name), matched(SearchRank.words(asked), artist.name)), VoiceChoice.OfArtist(artist), exactly(asked, artist.name))
        }
        for (album in found.albums) {
            // By another artist than the one named: not what was asked for.
            if (by != null && !theirs(album.artistLabel)) continue
            // (Search's terms for an album: its name, or its artist's at 0.85.)
            consider(VoiceRequest.Focus.ALBUM, maxOf(score(words, album.name), matched(said, album.name, listOf(album.artistLabel to 0.85f))) + artistBonus(album.artistLabel), VoiceChoice.OfAlbum(album), exactly(words, album.name))
        }
        for (playlist in playlists) {
            // Not found by search, but judged as search judges a playlist's name.
            consider(VoiceRequest.Focus.PLAYLIST, maxOf(score(words, playlist.name), matched(said, playlist.name)), VoiceChoice.OfPlaylist(playlist), exactly(words, playlist.name))
        }
        for (song in found.songs) {
            if (by != null && !theirs(song.artistLabel) && song.artists.none { theirs(it.name) }) continue
            val album = if (request.album.isNotBlank() && score(request.album, song.album) >= 0.7) 0.1 else 0.0
            // (A song's: its title, its artist's name at 0.9, its album's at 0.8.)
            val rank = maxOf(score(words, song.title), matched(said, song.title, listOf(song.artistLabel to 0.9f, song.album to 0.8f)))
            consider(VoiceRequest.Focus.SONG, rank + artistBonus(song.artistLabel) + album, VoiceChoice.OfSong(song), exactly(words, song.title))
        }
        for (genre in genres) {
            consider(VoiceRequest.Focus.GENRE, score(words, genre.name), VoiceChoice.OfGenre(genre.name), exactly(words, genre.name))
        }
        // maxBy keeps the first of equals, which is the order above.
        return candidates.maxByOrNull { it.first }?.second ?: VoiceChoice.Nothing
    }
}
