package org.medianexus.music.nexus

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.net.Song

internal enum class PlayBegan(val wire: String) {
    HERE("pressed_here"), NEXT("next_in_queue"), AUTOMATIC("added_by_itself"), CAR("car"),
}

internal enum class PlaySound(val wire: String) {
    STREAM("streamed"), KEPT("kept_copy"), DOWNLOAD("download"),
}

internal enum class StopReason(val wire: String) {
    FINISHED("finished"), NEXT("next"), PREVIOUS("previous"), CHOICE("another_choice"), STOPPED("stopped"), ERROR("error"),
}

@ConsistentCopyVisibility
internal data class PlayOrigin private constructor(val kind: String, val id: String? = null) {
    override fun toString() = "PlayOrigin($kind)"

    fun wire() = buildJsonObject {
        put("kind", kind)
        id?.let { if (kind == "search") put("words", it.displayPrefix()) else put("id", it) }
    }

    companion object {
        fun from(route: String, radioSeed: String? = null): PlayOrigin {
            val source = route.removePrefix("downloads:")
            val prefix = source.substringBefore(':')
            val name = source.substringAfter(':', "")
            return when {
                source == "radio" && !radioSeed.isNullOrEmpty() -> PlayOrigin("radio", radioSeed)
                source == "liked" -> PlayOrigin("liked_songs")
                name.isNotEmpty() && prefix in listOf("album", "playlist", "artist", "mix", "radio", "search") -> PlayOrigin(prefix, name)
                else -> PlayOrigin("queue")
            }
        }
    }
}

internal sealed interface Listening {
    data class Open(val version: String) : Listening
    data class Start(
        val songId: String,
        val title: String,
        val artist: String,
        val album: String,
        val origin: PlayOrigin,
        val began: PlayBegan,
        val sound: PlaySound,
        val position: Long,
    ) : Listening {
        constructor(song: Song, origin: PlayOrigin, began: PlayBegan, sound: PlaySound, position: Long) :
            this(song.id, song.title, song.artistLabel, song.album, origin, began, sound, position)

        override fun toString() = "Start"
    }
    data class Stop(val start: EventId, val position: Long, val duration: Long?, val heard: Long, val reason: StopReason, val fault: DiagnosticFault? = null) : Listening
    data class Pause(val start: EventId, val position: Long) : Listening
    data class Resume(val start: EventId, val position: Long) : Listening
    data class Seek(val start: EventId, val from: Long, val to: Long) : Listening
    data class Search(val words: String, val found: Int, val requests: Boolean) : Listening {
        override fun toString() = "Search"
    }
    data class Fault(val fault: DiagnosticFault) : Listening
    data class Position(val start: EventId, val position: Long) : Listening

    fun wire() = when (this) {
        is Open -> recordingEvent("open", buildJsonObject { put("version", version) })
        is Start -> recordingEvent("start", buildJsonObject {
            put("song_id", songId)
            put("title", title.displayPrefix())
            put("artist", artist.displayPrefix())
            put("album", album.displayPrefix())
            put("from", origin.wire())
            put("began", began.wire)
            put("sound", sound.wire)
            put("position_ms", ms(position))
        })
        is Stop -> recordingEvent("stop", buildJsonObject {
            put("start", start.wire())
            put("position_ms", ms(position))
            put("duration_ms", duration?.let { JsonPrimitive(ms(it)) } ?: JsonNull)
            put("heard_ms", ms(heard))
            put("why", buildJsonObject {
                put("kind", reason.wire)
                fault?.let { put("fault", it.eventWire()) }
            })
        })
        is Pause -> positionEvent("pause", start, position)
        is Resume -> positionEvent("resume", start, position)
        is Position -> positionEvent("position", start, position)
        is Seek -> recordingEvent("seek", buildJsonObject {
            put("start", start.wire())
            put("from_ms", ms(from))
            put("to_ms", ms(to))
        })
        is Search -> recordingEvent("search", buildJsonObject {
            put("words", words.displayPrefix())
            put("found", found.coerceAtLeast(0))
            put("requests", requests)
        })
        is Fault -> recordingEvent("fault", buildJsonObject { put("fault", fault.eventWire()) })
    }

    companion object {
        private fun ms(position: Long): Long = position.coerceIn(0L, 7 * 24 * 60 * 60 * 1000L)

        private fun positionEvent(name: String, start: EventId, position: Long) = recordingEvent(name, buildJsonObject {
            put("start", start.wire())
            put("position_ms", ms(position))
        })
    }
}

// The core keeps at most the first 200 Unicode scalars of each display string. Those
// cannot exceed 400 UTF-16 units. Bound the JNI envelope without changing that prefix.
private fun String.displayPrefix(): String = take(400)
