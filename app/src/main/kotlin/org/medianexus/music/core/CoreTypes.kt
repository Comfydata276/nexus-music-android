package org.medianexus.music.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// What goes into the shared core and what comes out of it ([Core]), as Kotlin values. The
// meaning of each is the core's, and is written there (the Windows repository's
// `core/src/`); here is the shape, and what the phone does with it.

/** The shared library gave up on something: its own words for why. Either what it was
 *  handed isn't what the function takes, or it stopped part-way (a Rust panic, caught at
 *  the boundary). The app carries on; whatever asked has not been done. */
class CoreException(message: String) : RuntimeException(message)

/** A playlist as the server stated it: what an answer left out is null (`Stated`). */
@Serializable
data class Stated(val id: String, val name: String, val owner: String? = null, val comment: String? = null)

/** Which of the playlists the server listed holds the shared record (`Found`). */
sealed interface Found {
    /** This one. */
    data class Ours(val id: String) : Found

    /** There is none. */
    data object None : Found

    /** There is one of that name, and the server did not say whose it is. */
    data object NotStated : Found
}

/** A play: the song, and when (seconds since 1970). In the core's JSON, a pair. */
@Serializable(with = PlayAsPair::class)
data class Play(val song: String, val at: Long)

/** Where a play was made from: the route key of a playlist. In the core's JSON, a triple. */
@Serializable(with = PlayFromAsTriple::class)
data class PlayFrom(val song: String, val at: Long, val from: String)

internal object PlayAsPair : KSerializer<Play> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Play) = encoder.encodeSerializableValue(JsonArray.serializer(), JsonArray(listOf(JsonPrimitive(value.song), JsonPrimitive(value.at))))
    override fun deserialize(decoder: Decoder): Play {
        val parts = decoder.decodeSerializableValue(JsonArray.serializer())
        require(parts.size == 2 && parts[0].jsonPrimitive.isString) { "not a play: $parts" }
        return Play(parts[0].jsonPrimitive.content, parts[1].jsonPrimitive.long)
    }
}

internal object PlayFromAsTriple : KSerializer<PlayFrom> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor
    override fun serialize(encoder: Encoder, value: PlayFrom) =
        encoder.encodeSerializableValue(JsonArray.serializer(), JsonArray(listOf(JsonPrimitive(value.song), JsonPrimitive(value.at), JsonPrimitive(value.from))))
    override fun deserialize(decoder: Decoder): PlayFrom {
        val parts = decoder.decodeSerializableValue(JsonArray.serializer())
        require(parts.size == 3 && parts[0].jsonPrimitive.isString && parts[2].jsonPrimitive.isString) { "not a play and its source: $parts" }
        return PlayFrom(parts[0].jsonPrimitive.content, parts[1].jsonPrimitive.long, parts[2].jsonPrimitive.content)
    }
}

/** What's known about one song in one playlist (`Note`): one time per copy of the song,
 *  oldest first (0: before the app was keeping track; none once the song has been taken
 *  out), and when this note last changed (0: one the phone only noticed, never shared). */
@Serializable
data class Note(val times: List<Long> = emptyList(), val at: Long = 0)

/** Playlist id, then song id. */
typealias Notes = Map<String, Map<String, Note>>

/** One playlist's notes, and whether the phone has looked at that playlist before. */
@Serializable
data class OnePlaylist(val notes: Map<String, Note> = emptyMap(), val seen: Boolean = false)

/** A playlist's notes after one of the notes' operations, and what the operation said. */
@Serializable
data class OneTold(val notes: Map<String, Note> = emptyMap(), val seen: Boolean = false, val told: Boolean = false)

@Serializable
data class NotesTaken(val lists: Notes, val changed: Boolean)

/** What this phone holds, at the moment the server's answers are in hand (`Device`).
 *  `plays`: every play in the phone's log at least as new as its 500th newest; the core
 *  chooses the newest of them. `choices` is the core's own, kept unread ([Core.change]). */
class Device(
    val username: String,
    val account: String,
    val choices: JsonElement,
    val notes: Notes,
    val history: List<Play>,
    val plays: List<Play>,
    val playSources: List<PlayFrom>,
)

/** The server's answers, as they were received (`Answers`): the list of playlists, the one
 *  that holds the record if there is one, the server's clock by the last of them (null if
 *  it gave none), and the phone's clock as that arrived. */
class Answers(val listed: List<Stated>, val playlist: Stated?, val date: Long?, val received: Long)

/** Why: a name to tell kinds apart by, and the words to show. */
@Serializable
data class Said(val kind: String, val words: String)

/** The phone's clock beside the server's, by one answer. */
@Serializable
data class ClockRead(val measured: Boolean = false, val wrong: Boolean = false)

/** Everything to do after an exchange's reads, in order (`Plan`). `take` and `write` are
 *  the core's own, handed back unread ([Core.take], [Core.sending], [Core.written]). */
@Serializable
class Plan(
    /** Step 5: to be in the phone's log, on disk, before anything else is done. */
    val keepPlays: List<Play> = emptyList(),
    val keepSources: List<PlayFrom> = emptyList(),
    /** Step 6. */
    val take: JsonElement,
    val notes: Notes = emptyMap(),
    val history: List<Play> = emptyList(),
    /** Something the phone would have written and may not: said where the person sees it. */
    val held: Said? = null,
    /** Step 7, if there is something to write. */
    val write: JsonElement? = null,
    /** The playlist to write into; null to make one of the record's name first. */
    val writeInto: String? = null,
    val writeText: String = "",
    /** The comment as it was found, to keep before this version's first write. */
    val writeFound: String = "",
)

/** What an exchange is to do: stop (with why), or carry out the plan. */
@Serializable
class Settled(val clock: ClockRead = ClockRead(), val stop: Said? = null, val plan: Plan? = null)

@Serializable
class Sending(val choices: JsonElement, val fits: Boolean)

@Serializable
class Changed(val choices: JsonElement, val changed: Boolean)

/** Something the person changed on this phone, as the change itself (`Change`). */
@Serializable
sealed interface Change {
    /** Pin a playlist (at the end of the pins). */
    @Serializable @SerialName("pin") data class Pin(val id: String) : Change
    @Serializable @SerialName("unpin") data class Unpin(val id: String) : Change
    /** Whether the list `key` plays shuffled. */
    @Serializable @SerialName("shuffle") data class Shuffle(val key: String, val on: Boolean) : Change
    /** The order the list `key` is shown in, chosen by the person. */
    @Serializable @SerialName("sort") data class Sort(val key: String, val sort: Int, val descending: Boolean) : Change
    /** The list `key` is back to the usual choices (it has gone). */
    @Serializable @SerialName("usual") data class Usual(val key: String) : Change
    @Serializable @SerialName("theme") data class Theme(val name: String) : Change
}

/** One list's choices as the core has them. */
@Serializable
data class ListChoices(val shuffle: Boolean = false, val sort: Int = 0, val descending: Boolean = false, val sortChosen: Boolean = false, val at: Long = 0)

/** What a device shows of the shared choices: what was agreed, with the waiting changes on
 *  top (`Shown`). `pinsSaid`: the record has said something about the pins; until it has,
 *  the phone's own pins are the phone's alone. `theme`: empty when none is agreed or waiting. */
@Serializable
data class Shown(val pins: List<String> = emptyList(), val pinsSaid: Boolean = false, val theme: String = "", val lists: Map<String, ListChoices> = emptyMap())

/** What a build from before the shared core had agreed with the server. */
@Serializable
data class AgreedFormerly(val pins: List<String> = emptyList(), val pinsAt: Long = 0, val theme: String = "", val themeAt: Long = 0, val lists: Map<String, ListChoices> = emptyMap())

/** A change as such a build kept it. */
@Serializable
data class WaitingFormerly(val what: String = "", val key: String = "", val on: Boolean = false, val sort: Int = 0, val descending: Boolean = false)

@Serializable
data class Names(val playlist: String, val reserved: String, val historyLength: Int, val playsShared: Int)

/** A song of a queue, as far as the queue's rules look at one. */
@Serializable
data class QueueSong(val id: String, val duration: Int = 0, val radio: Boolean = false)

/** The server's saved queue as it answered, with the server's clock as that answer gave it
 *  (null if it gave none) and the phone's clock as it arrived. */
@Serializable
data class Theirs(
    val songs: List<QueueSong> = emptyList(),
    val current: String = "",
    /** Milliseconds into that song. */
    val position: Long = 0,
    val changed: String = "",
    val changedBy: String = "",
    val date: Long? = null,
    val received: Long = 0,
)

/** This phone's side of a comparison of queues (`Here`): `stamp` by the phone's own clock;
 *  `taken` set while the queue is one taken up from another device and untouched since;
 *  `ids` the songs of the queue as the phone would save them ([Core.queueWindow]), which is
 *  what tells another device's queue with the same song current from the same queue (left
 *  empty, only the current song is compared). */
@Serializable
data class Here(val device: String, val current: String?, val playing: Boolean, val stamp: Long, val taken: String?, val ids: List<String> = emptyList())

/** What of a queue is saved on the server (`Saved`); `position` in milliseconds. */
@Serializable
data class QueueSaved(val ids: List<String>, val current: String, val position: Long)

/** Where to pick a saved queue up, and what to keep with it (`Place`): `position` in
 *  seconds; `stamp` and `taken` are the queue's own from then on. */
@Serializable
data class Place(val cursor: Int, val position: Double, val stamp: Long, val taken: String)

enum class SaveVerdict { SAVE, NOT_NEWEST, NOT_NOW }

/** What a look at the server's saved queue leads to (`Looked`): `then` is "nothing",
 *  "take_up" (with `place`) or "save"; `offer` the song of that queue to offer as "Continue
 *  here", null to withdraw any offer. */
@Serializable
data class Looked(val offer: Int? = null, val then: String = "nothing", val place: Place? = null, val clock: ClockRead = ClockRead())

// ---- Requests: asking Lidarr for music that isn't in the library ----

/**
 * How something the library did over the network ended ([Core]'s `suspend` functions).
 * **Stopped is an outcome of its own**: it is never a failure with words to show and never
 * "nothing found". (A caller that is cancelled is not handed an outcome at all: it is
 * cancelled, as any coroutine is. This one is for a stop the caller didn't make itself.)
 */
sealed interface Outcome<out T> {
    data class Got<T>(val value: T) : Outcome<T>

    /** It failed, and `why` is the library's own sentence for it, as the Windows app shows. */
    data class Failed(val why: String) : Outcome<Nothing>

    data object Stopped : Outcome<Nothing>
}

/**
 * What a call to Lidarr is made with: where it is, its key, and the sign-in in front of it
 * if there is one (the number [Core.gateOpen] gave). Never printed: its key is a secret.
 *
 * `origin` is **the place the key was given for** ([Core.lidarrPlace]): a request with the
 * key in it is sent there and nowhere else, whatever is made of `address`. Whoever keeps a
 * key keeps this with it and hands over what was kept; left out, it is the place `address`
 * itself is.
 */
@Serializable
class LidarrAt(
    val address: String, val key: String, val session: Long? = null, val origin: String? = Core.lidarrPlace(address),
    /** Which connection this is, by the app's own count of them (it moves whenever the
     *  address, the key or the sign-in changes). Whatever is done with this takes what the
     *  connection has been offered **by this number**, on the Rust side, so that an
     *  operation's Lidarr and its offers are always one connection's. */
    val connection: Long = 0,
) {
    override fun toString(): String = "LidarrAt"
}

/** Where the sign-in service in front of Lidarr is, and the name it knows that sign-in by
 *  (the core's `gate::Found`). Neither is a secret. */
@Serializable
data class SignInDoor(val service: String, val client: String)

/** What a finished sign-in leaves behind (`gate::SignedIn`): the token to keep, and who
 *  signed in, for showing (may be empty). Never printed: the token is a secret. */
@Serializable
class SignedIn(val token: String, val name: String) {
    override fun toString(): String = "SignedIn"
}

/** An album that could be asked for, as Lidarr and MusicBrainz both describe one (`Offer`):
 *  `album` and `artistId` are their names for it and for its artist; `kind` is "EP" or
 *  "Single" for one of those and empty for an album. */
@Serializable
data class Offer(
    val album: String, val artistId: String, val title: String, val artist: String, val year: String = "", val cover: String = "",
    val requested: Boolean = false, val kind: String = "", val soundtrack: Boolean = false,
)

/** An album on Lidarr's list that hasn't fully arrived (`lidarr::Pending`): `id` is
 *  Lidarr's number for it, `progress` how much has been fetched in hundredths, null if it
 *  isn't being fetched yet. */
@Serializable
data class Pending(val id: Long, val title: String, val artist: String, val progress: Int? = null)

/** A song on an album, by name only (`musicbrainz::TrackName`). */
@Serializable
data class TrackName(val title: String, val seconds: Int, val disc: Int, val number: Int)

/** What asking Lidarr whether it is there came to ([Core.lidarrCheck]): its `version` if it
 *  answered as itself; otherwise `why`, the library's sentence, with the sign-in that
 *  answered in its place if that is why and one was found (`door`), or why what is in front
 *  of Lidarr couldn't be made out (`doorWhy`). */
@Serializable
data class Checked(val version: String? = null, val why: String = "", val door: SignInDoor? = null, val doorWhy: String? = null)
