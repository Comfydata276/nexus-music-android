package org.medianexus.music.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.medianexus.music.core.Note
import org.medianexus.music.core.Play
import org.medianexus.music.core.PlayFrom

// For the tests only: the shared record as the tests write one down and read one back (the
// text in the comment of the playlist "Nexus Music sync"). The app itself has no such type:
// reading, combining and writing the record is the shared core's, and nothing in the app
// looks inside one. A test has to put a PC's record on its pretend server and see what the
// phone left there.

@Serializable
internal data class Shared(
    @SerialName("nexus_music_sync") val layout: Long = 0,
    val pins: List<String> = emptyList(),
    @SerialName("pins_at") val pinsAt: Long = 0,
    val theme: String = "",
    @SerialName("theme_at") val themeAt: Long = 0,
    val lists: Map<String, SharedPrefs> = emptyMap(),
    val added: Map<String, Map<String, Note>> = emptyMap(),
    val history: List<Play> = emptyList(),
    val plays: List<Play> = emptyList(),
    @SerialName("play_sources") val playSources: List<PlayFrom> = emptyList(),
)

@Serializable
internal data class SharedPrefs(
    val shuffle: Boolean = false,
    val sort: Long = 0,
    val descending: Boolean = false,
    @SerialName("sort_chosen") val sortChosen: Boolean = false,
    val at: Long = 0,
)

internal typealias SharedNote = Note

internal object SharedText {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The record in a text; null for a text that isn't one. */
    fun read(text: String): Shared? = runCatching { json.decodeFromString(Shared.serializer(), text) }.getOrNull()

    /** A record as text (any device's reader takes it; it is not held to any device's own
     *  way of writing one). */
    fun write(shared: Shared): String = json.encodeToString(Shared.serializer(), shared)
}
