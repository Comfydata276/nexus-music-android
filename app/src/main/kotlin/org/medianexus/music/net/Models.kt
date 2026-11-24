package org.medianexus.music.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// What the server sends, as the Windows app reads it (src/subsonic.rs). Every field has a
// default, so a field the server leaves out is simply empty, and fields this app doesn't know
// are ignored (see `Subsonic.json`).
//
// Counts, durations and sizes are signed here (Int, Long) where Windows has them unsigned
// (u32, u64). Known and harmless: a negative number, which Windows would refuse, is read
// here as it is, and no real library comes near the top of either range.

@Serializable
data class ReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

@Serializable
data class Song(
    val id: String = "",
    val title: String = "",
    val album: String = "",
    val artist: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val displayArtist: String = "",
    /** Everyone credited on the song, when the server says (it may name only the first
     *  otherwise, in `artist`). */
    val artists: List<Credit> = emptyList(),
    val track: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val coverArt: String = "",
    val duration: Int = 0,
    val suffix: String = "",
    /** Size of the file on the server, in bytes. */
    val size: Long = 0,
    val bitRate: Int = 0,
    val samplingRate: Int = 0,
    val bitDepth: Int = 0,
    val genre: String = "",
    val starred: String? = null,
    /** When the song was added to the library. */
    val created: String = "",
    /** How many times it's been played, and when last. */
    val playCount: Int = 0,
    val played: String = "",
    /** The user's star rating, 1 to 5; 0 when unrated. */
    val userRating: Int = 0,
    val replayGain: ReplayGain? = null,
    /** Set (by this app, not the server) on entries that stand for an internet radio station. */
    val radioUrl: String = "",
    /** Set (by this app) on a queue entry that was put there by hand and hasn't played yet:
     *  it's in "Next in queue", ahead of the rest of whatever list is playing. Left out of
     *  what's written when false, as on Windows. */
    val queued: Boolean = false,
) {
    val artistLabel: String get() = displayArtist.ifEmpty { artist }
}

/** An artist named on a song. */
@Serializable
data class Credit(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class Album(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    val displayArtist: String = "",
    val coverArt: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int = 0,
    val genre: String = "",
    val starred: String? = null,
    /** When it was last played. */
    val played: String = "",
    val releaseTypes: List<String> = emptyList(),
    @SerialName("song") val songs: List<Song> = emptyList(),
) {
    val artistLabel: String get() = displayArtist.ifEmpty { artist }
}

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    val coverArt: String = "",
    val albumCount: Int = 0,
    val starred: String? = null,
    @SerialName("album") val albums: List<Album> = emptyList(),
)

@Serializable
data class ArtistInfo(
    val biography: String = "",
    /** MusicBrainz's name for the artist, where the server knows it. */
    val musicBrainzId: String = "",
    @SerialName("similarArtist") val similar: List<Artist> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val comment: String = "",
    val owner: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String = "",
    /** The server's word that this account may not change it (Navidrome sends it). */
    val readonly: Boolean = false,
    @SerialName("entry") val songs: List<Song> = emptyList(),
    /**
     * How many entries the server holds for it, as its list of playlists counts them: set by
     * the phone when it reads a playlist ([Subsonic.playlistCounted]); -1 if the list doesn't
     * have it, null where it wasn't asked. Navidrome keeps an entry whose song has gone from
     * the library, leaves it out of the playlist's own answer (whose `songCount` and `duration`
     * leave it out too), but counts it in the list, and its "remove at index" and "replace the
     * songs" count it or throw it away: docs/TESTING.md, "Songs missing from the library".
     */
    @SerialName("nexusHeld") val held: Int? = null,
) {
    /** The server holds entries it didn't list here (or can't say): its positions are not the
     *  rows shown, so nothing may be removed or moved by them. */
    val hidesSongs: Boolean get() = held != null && held != songs.size
}

@Serializable
data class ScanStatus(
    val scanning: Boolean = false,
    val count: Long = 0,
    val lastScan: String = "",
)

/** A play queue saved on the server. */
@Serializable
data class PlayQueue(
    @SerialName("entry") val songs: List<Song> = emptyList(),
    /** The id of the song that was playing. (Some servers send a number.) */
    @Serializable(with = TextOrNumber::class) val current: String = "",
    /** How far into that song, in milliseconds. */
    val position: Long = 0,
    /** When it was saved, and by which app. */
    val changed: String = "",
    val changedBy: String = "",
)

/** Reads text as it is and a number as its digits; anything else (true, null, a list) is
 *  empty. As `text_or_number` in src/subsonic.rs, where the text "null" is still text. A
 *  number is given as it was written: for whole numbers, as ids are, that is what Windows
 *  gives too. */
object TextOrNumber : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("TextOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = json.decodeJsonElement() as? JsonPrimitive ?: return ""
        return when {
            element is JsonNull -> ""
            element.isString -> element.content
            element.booleanOrNull != null -> ""
            else -> element.content
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class Genre(
    @SerialName("value") val name: String = "",
    val albumCount: Int = 0,
    val songCount: Int = 0,
)

@Serializable
data class Found(
    @SerialName("artist") val artists: List<Artist> = emptyList(),
    @SerialName("album") val albums: List<Album> = emptyList(),
    @SerialName("song") val songs: List<Song> = emptyList(),
    /** Set by the phone's own search, never read from the server: an artist matches the
     *  words at least as well as any song's title does (Windows' `artist_first`). Null when
     *  the server searched, which says nothing of the kind. */
    @kotlinx.serialization.Transient val artistFirst: Boolean? = null,
)

@Serializable
data class LyricLine(
    /** Milliseconds from the start of the track; absent for unsynced lyrics. */
    val start: Long? = null,
    val value: String = "",
)

@Serializable
data class Lyrics(
    val synced: Boolean = false,
    val line: List<LyricLine> = emptyList(),
)

data class ServerInfo(val version: String)

/** Which list `getAlbumList2` should return. */
@Serializable
sealed interface AlbumList {
    @Serializable data object Newest : AlbumList
    @Serializable data object Recent : AlbumList
    @Serializable data object Frequent : AlbumList
    @Serializable data object Random : AlbumList
    @Serializable data object Starred : AlbumList
    @Serializable data object AlphabeticalByName : AlbumList
    @Serializable data object AlphabeticalByArtist : AlbumList
    @Serializable data object ByYearDescending : AlbumList
    @Serializable data class ByGenre(val genre: String) : AlbumList
}

/** What a like is for: the parameter `star` and `unstar` take its id under. */
enum class StarTarget(val param: String) {
    SONG("id"),
    ALBUM("albumId"),
    ARTIST("artistId"),
}

/** Lyric lines with the file's own offset taken off their times, so everything after this
 *  can take the times as they are. */
internal fun shifted(lines: List<LyricLine>, offset: Long): List<LyricLine> =
    if (offset == 0L) lines
    else lines.map { line -> line.copy(start = line.start?.let { at -> (at - offset).coerceAtLeast(0) }) }
