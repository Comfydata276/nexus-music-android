package org.medianexus.music.jam

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.net.Song

// What is passed about through the relay, as the Windows app writes and reads it (its
// `song_json`, the host's state in `jam_sync`, the guest's reading in `show_joined`,
// `show_remote` and `jam_row`). Read the way serde's `Value` is read there: a field that
// is missing or of another kind is its empty value, never an error.
//
// A jam joined from here is someone else's app talking to us, through someone else's relay:
// nothing in it is trusted. Every text is cut to a length a screen can hold and stripped of
// what can't be seen, every number is held to what it can mean, an id that isn't one a music
// server would write is dropped (it is sent back in requests and used in a cover's address),
// and a queue is no longer than a host shows (`SHOWN`).

/** How much of the queue guests are shown (Windows' `SHOWN`). */
const val SHOWN = 100
/** How many songs a guest's search brings back (`FOUND`). */
const val FOUND = 40
/** The most songs one request may add to a jam's queue (`MOST_ADDED`). */
const val MOST_ADDED = 50
/** The most songs handed from one device to another with a queue (`MOST_HANDED`). */
const val MOST_HANDED = 3000

internal fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal fun JsonElement?.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull }?.content?.let { if (it == "true") true else if (it == "false") false else null }
/** A whole number that isn't negative, as serde's `as_u64`. */
internal fun JsonElement?.u64(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull }?.content?.toLongOrNull()?.takeIf { it >= 0 }
internal fun JsonElement?.f64(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
internal fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
internal val JsonElement?.isObject: Boolean get() = this is JsonObject

/** Ids as music servers write them: letters, digits and a little punctuation (the relay's
 *  `plain_id`). */
fun plainId(text: String): Boolean =
    text.isNotEmpty() && text.length <= 128 && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == '~' }

/** Text from the other side, fit to show: one line, nothing invisible, not too long. */
fun shownText(text: String?, most: Int = 200): String {
    if (text.isNullOrEmpty()) return ""
    val out = StringBuilder(minOf(text.length, most))
    var count = 0
    for (c in text) {
        if (count >= most) break
        if (c.isISOControl() || c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2060'..'\u2069' || c == '\uFEFF') {
            if (c == '\n' || c == '\t') { out.append(' '); count++ }
            continue
        }
        out.append(c)
        count++
    }
    // A cut that left half a surrogate pair is cut before it.
    if (out.isNotEmpty() && out.last().isHighSurrogate()) out.setLength(out.length - 1)
    return out.toString().trim()
}

/** A song of a jam, as the host described it. */
data class JamSong(
    /** Empty when what was sent isn't an id at all. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** The cover's id on the host's server; empty for none (or one that isn't an id). */
    val cover: String,
    /** Seconds. */
    val duration: Int,
    /** Part of "Next in queue". */
    val queued: Boolean = false,
    /** Which guest queued it, by name and by the jam's id for them. */
    val by: String = "",
    val byId: String = "",
) {
    companion object {
        fun read(value: JsonElement?): JamSong = JamSong(
            id = value.at("id").str()?.takeIf(::plainId).orEmpty(),
            title = shownText(value.at("title").str()),
            artist = shownText(value.at("artist").str()),
            album = shownText(value.at("album").str()),
            cover = value.at("cover").str()?.takeIf(::plainId).orEmpty(),
            duration = (value.at("duration").u64() ?: 0L).coerceAtMost(MOST_SECONDS).toInt(),
            queued = value.at("queued").bool() == true,
            by = shownText(value.at("by").str(), 24),
            byId = value.at("by_id").str()?.takeIf { it.length <= 64 && plainId(it) }.orEmpty(),
        )

        /** A week: anything longer is not a song's length. */
        private const val MOST_SECONDS = 7L * 24 * 3600
    }
}

/** What's playing and what's queued at a jam, as the host last described it. */
data class JamState(
    val playing: Boolean = false,
    /** Seconds into the song, when described. */
    val position: Double = 0.0,
    /** Guests may steer. */
    val control: Boolean = false,
    val now: JamSong? = null,
    val queue: List<JamSong> = emptyList(),
    /** How many more are queued than are shown. */
    val more: Int = 0,
    val context: String = "",
    val shuffle: Boolean = false,
    /** 0 off, 1 all, 2 one. */
    val repeat: Int = 0,
    /** Between a person's own devices: the other device's volume, 0 to 1. */
    val volume: Double? = null,
) {
    /** How many of the queue shown are "Next in queue": the run of them at its top. */
    val queuedAhead: Int get() = queue.takeWhile { it.queued }.size

    companion object {
        fun read(value: JsonElement?): JamState = JamState(
            playing = value.at("playing").bool() == true,
            position = (value.at("position").f64() ?: 0.0).coerceIn(0.0, 7.0 * 24 * 3600),
            control = value.at("control").bool() == true,
            now = value.at("now").takeIf { it.isObject }?.let(JamSong::read),
            queue = value.at("queue").list().asSequence().take(SHOWN).map(JamSong::read).toList(),
            more = (value.at("more").u64() ?: 0L).coerceAtMost(10_000_000L).toInt(),
            context = shownText(value.at("context").str()),
            shuffle = value.at("shuffle").bool() == true,
            repeat = (value.at("repeat").u64() ?: 0L).coerceAtMost(2L).toInt(),
            volume = value.at("volume").f64()?.coerceIn(0.0, 1.0),
        )
    }
}

/** Someone in a jam: the jam's id for them, and their name. */
data class JamGuest(val id: String, val name: String) {
    companion object {
        fun list(value: JsonElement?): List<JamGuest> = value.list().asSequence().take(MOST_GUESTS).map { guest ->
            JamGuest(guest.at("id").str()?.takeIf { it.length <= 64 && plainId(it) }.orEmpty(), shownText(guest.at("name").str(), 24))
        }.toList()

        private const val MOST_GUESTS = 200
    }
}

/** A jam as the relay last told this guest of it (its `snapshot`). */
data class Heard(
    /** The jam's version: asked after, so the relay answers when it has a newer one. */
    val version: Long = 0,
    val host: String = "",
    /** The host's app hasn't been heard from for a while. */
    val away: Boolean = false,
    /** How old the host's description was when this was sent, in seconds. */
    val age: Double = 0.0,
    val state: JamState = JamState(),
    val guests: List<JamGuest> = emptyList(),
) {
    companion object {
        fun read(value: JsonElement?): Heard = Heard(
            version = value.at("v").u64() ?: 0L,
            host = shownText(value.at("host").str(), 24),
            away = value.at("away").bool() == true,
            age = (value.at("age").f64() ?: 0.0).coerceIn(0.0, 24.0 * 3600),
            state = JamState.read(value.at("state")),
            guests = JamGuest.list(value.at("guests")),
        )
    }
}

/** One of the person's other devices, as it says of itself in the room. */
data class Device(
    val id: String,
    val name: String,
    /** The session it can be steered through; null while it has none. */
    val session: String?,
    val title: String,
    val playing: Boolean,
    /** What it is steering itself, if anything: another device's name, or "X's jam". */
    val steering: String?,
) {
    companion object {
        fun list(value: JsonElement?): List<Device> = value.at("devices").list().asSequence().take(MOST_DEVICES).mapNotNull { device ->
            val id = device.at("id").str()?.takeIf { it.length <= 64 && plainId(it) } ?: return@mapNotNull null
            val status = device.at("status")
            Device(
                id = id,
                name = shownText(device.at("name").str(), 24),
                session = status.at("session").str()?.takeIf { it.length <= 64 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' } },
                title = shownText(status.at("title").str()),
                playing = status.at("playing").bool() == true,
                steering = status.at("steering").str()?.let { shownText(it, 60) },
            )
        }.toList()

        private const val MOST_DEVICES = 12
    }
}

/** A song for guests, as Windows' `song_json` writes it. */
fun songJson(song: Song): JsonObject = buildJsonObject {
    put("id", song.id)
    put("title", song.title)
    put("artist", song.artistLabel)
    put("album", song.album)
    put("cover", song.coverArt)
    put("duration", song.duration)
}

/** A list of ids as a JSON array. */
internal fun idsJson(ids: List<String>): JsonArray = buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }
