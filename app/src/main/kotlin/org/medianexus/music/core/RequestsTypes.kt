package org.medianexus.music.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.jsonArray

// What the pages and actions of requests hand back: **the shared library's own answers**
// (`requests`, `asked`, `discover` in the Windows repository's `core/`), field for field.
// Every line of text in them (a caption, a heading, what to say when there is nothing) is
// the library's, ready to show; nothing here is to be reworded or worked out again.

/** Two things the library hands over as a pair. */
internal class PairAsArray<A, B>(private val first: KSerializer<A>, private val second: KSerializer<B>) : KSerializer<Pair<A, B>> {
    override val descriptor: SerialDescriptor = ListSerializer(JsonElement.serializer()).descriptor

    override fun deserialize(decoder: Decoder): Pair<A, B> {
        val json = decoder as JsonDecoder
        val both = json.decodeJsonElement().jsonArray
        return json.json.decodeFromJsonElement(first, both[0]) to json.json.decodeFromJsonElement(second, both[1])
    }

    override fun serialize(encoder: Encoder, value: Pair<A, B>) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(JsonArray(listOf(json.json.encodeToJsonElement(first, value.first), json.json.encodeToJsonElement(second, value.second))))
    }
}

internal object SongAndAsked : KSerializer<Pair<SongOffer, Boolean>> by PairAsArray(SongOffer.serializer(), Boolean.serializer())
internal object AskedAndLine : KSerializer<Pair<Asked, String>> by PairAsArray(Asked.serializer(), String.serializer())
internal object PendingAndLine : KSerializer<Pair<Pending, String>> by PairAsArray(Pending.serializer(), String.serializer())
internal object NameAndNames : KSerializer<Pair<String, List<String>>> by PairAsArray(String.serializer(), ListSerializer(String.serializer()))
internal object TwoNames : KSerializer<Pair<String, String>> by PairAsArray(String.serializer(), String.serializer())

/** A song that isn't in the library, and the album it could be had on (`musicbrainz::SongOffer`).
 *  `album` is Lidarr's name for that album: what [org.medianexus.music.core.Core.asking]
 *  and a request take. */
@Serializable
data class SongOffer(
    val title: String = "", val artist: String = "", val artistId: String = "", val artistName: String = "",
    val album: String = "", val albumTitle: String = "", val year: String = "", val seconds: Int = 0,
    /** "EP" or "Single" when that is what it is on; empty for an album. */
    val kind: String = "",
    val recordings: List<String> = emptyList(), val close: Int = 0, val listens: Long = 0,
)

/** An artist Lidarr knows of (`lidarr::ArtistOffer`): `id` opens their page. */
@Serializable
data class ArtistOffer(val id: String = "", val name: String = "", val about: String = "", val cover: String = "")

/** What a search has found beyond the library so far (`requests::Outside`). */
@Serializable
data class Outside(
    /** What to say above it. */
    val note: String = "",
    /** Songs that could be asked for, each with whether its album has been asked for. */
    val songs: List<@Serializable(with = SongAndAsked::class) Pair<SongOffer, Boolean>> = emptyList(),
    val artists: List<ArtistOffer> = emptyList(),
    val albums: List<Offer> = emptyList(),
)

/** One of the library's own albums (`requests::LibraryAlbum`): the music server's id for it. */
@Serializable
data class LibraryAlbum(val id: String = "", val title: String = "", val artist: String = "", val cover: String = "")

/** One of an artist's releases, as their page shows it (`requests::Release`). */
@Serializable
data class Release(
    val offer: Offer = Offer("", "", "", ""),
    /** The line under it ("2019 • EP • Requested", "2016 • In your library"). */
    val caption: String = "",
    /** The library's own copy, if it has it: then it is shown, and opened, as that. */
    val library: LibraryAlbum? = null,
    /** Lidarr has it. */
    val arrived: Boolean = false,
)

/** An artist as the wider world knows them (`requests::OutsideArtist`). */
@Serializable
data class OutsideArtist(
    val name: String = "", val cover: String = "",
    /** What is said of them under the name ("3 albums • 1 in your library"). */
    val about: String = "",
    val albums: List<Release> = emptyList(),
    /** Singles and EPs, and soundtracks other than albums. */
    val smaller: List<Release> = emptyList(),
    /** How many albums could be asked for: none means there is nothing for "Add artist" to do. */
    val open: Int = 0,
    /** What to say instead, when there is nothing to show, or why it couldn't be had. */
    val empty: String = "",
    val failed: Boolean = false,
    /** In server mode only rows with the server's removal bit can be acted on. */
    val removable: Set<Long>? = null,
) {
    fun canRemove(row: Pending): Boolean = removable?.contains(row.id) ?: true
}

/** The songs an artist is most played for, for the top of their page (`requests::Popular`).
 *  With no `songs` there is nothing to show. */
@Serializable
data class Popular(
    /** "Popular", or "Popular, and not in your library". */
    val heading: String = "",
    val songs: List<@Serializable(with = SongAndAsked::class) Pair<SongOffer, Boolean>> = emptyList(),
)

/** An album that isn't in the library, as its page shows it (`requests::OutsideAlbum`). */
@Serializable
data class OutsideAlbum(
    val offer: Offer = Offer("", "", "", ""), val cover: String = "", val tracks: List<TrackName> = emptyList(),
    /** Why the songs couldn't be listed, if they couldn't. */
    val tracksFailed: String = "",
    /** What is said of it under its name ("2019 • 12 songs • Not in your library"). */
    val about: String = "",
    /** "Request album" (or EP, single), or "Requested". */
    val action: String = "",
)

/** What an album's page comes to (`requests::AlbumPage`). */
sealed interface AlbumPage {
    data class Found(val album: OutsideAlbum) : AlbumPage

    /** It can't be shown, and this says why. */
    data class Unavailable(val why: String) : AlbumPage
}

/** Whether to ask, and how the question is put (`requests::Asking`). */
sealed interface Asking {
    /** It has been asked for already: this says so. */
    data class Already(val said: String) : Asking

    data class Ask(val heading: String, val text: String) : Asking

    /** It can't be asked for from where it was pressed, and this is why. `stale`: because
     *  the page it was on was shown under a connection to Lidarr that has since changed,
     *  and is to be asked for again. (The phone's own: the library is not asked.) */
    data class Refused(val said: String, val stale: Boolean) : Asking
}

/**
 * One album that was asked for, as it is kept to be followed up (`asked::Asked`). `fills`
 * is the Windows app's (songs an imported playlist was missing): the phone makes none and
 * carries whatever is there untouched.
 */
@Serializable
data class Asked(
    val album: String = "", val title: String = "", val artist: String = "",
    /** When it was asked for (seconds since 1970). */
    val at: Long = 0,
    /** The song that was wanted from it, if it was asked for by a song. */
    val song: String = "",
    val fills: List<JsonElement> = emptyList(),
    /** When it turned up in the library; 0 while it is still awaited. */
    val arrived: Long = 0,
    /** The library's own album it turned out to be: the music server's id, and its cover. */
    val libraryAlbum: String = "", val cover: String = "",
    val unfilled: Boolean = false,
)

/** What has been asked for (`requests::Waiting`): what has arrived lately, then what Lidarr
 *  has still to get. */
@Serializable
data class Waiting(
    /** What this app asked for that has since turned up, newest first, each with the line
     *  under it ("Coldplay • for “Yellow”"). */
    val arrived: List<@Serializable(with = AskedAndLine::class) Pair<Asked, String>> = emptyList(),
    /** "On their way (3)"; empty when nothing is. */
    val heading: String = "",
    /** Each album Lidarr has still to get, with the line under it. `Pending.id` is what
     *  taking it off the list needs. */
    val pending: List<@Serializable(with = PendingAndLine::class) Pair<Pending, String>> = emptyList(),
    /** What to say when nothing is on its way, or why Lidarr couldn't say. */
    val empty: String = "",
    val failed: Boolean = false,
)

/** An artist worth a look (`discover::Suggestion`): `like` is the favourite they are like. */
@Serializable
data class Suggestion(val id: String = "", val name: String = "", val cover: String = "", val like: String = "")

/** The suggestions as they are kept between runs (`discover::Kept`). */
@Serializable
data class KeptSuggestions(val at: Long = 0, val artists: List<Suggestion> = emptyList())

/** The phone's copy of the library, as the library's own rules ask it (`requests::Snapshot`). */
@Serializable
data class LibraryLists(
    val albums: List<LibraryAlbum> = emptyList(),
    /** Each song's title and who it is credited to. */
    val songs: List<List<String>> = emptyList(),
    val artists: List<String> = emptyList(),
)

/** What has been asked for after a change, and whether it changed. */
@Serializable
data class AskedChange(val changed: Boolean = false, val asked: List<Asked> = emptyList())

/** What looking through the library for what was asked for came to. */
@Serializable
data class Arrivals(
    val asked: List<Asked> = emptyList(), val changed: Boolean = false,
    /** The arrivals that are news. */
    val told: List<Asked> = emptyList(),
    /** What to say of them; empty when none is news. */
    val news: String = "",
)

/** The suggestions that are kept, read as the library reads them. */
@Serializable
data class Suggested(val fresh: Boolean = false, val shown: List<Suggestion> = emptyList(), val kept: KeptSuggestions = KeptSuggestions(), val changed: Boolean = false)
