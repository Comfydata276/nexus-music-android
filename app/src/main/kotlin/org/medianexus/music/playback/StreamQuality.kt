package org.medianexus.music.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.medianexus.music.net.Song
import org.medianexus.music.store.Settings

/**
 * What a song is streamed as: **the one place the streaming limit is read.** Four things
 * ask it, and must never disagree: where a song's bytes come from (`PlaybackService.resolve`:
 * the form the server is asked for, and the key it is kept under), whether a kept copy plays
 * with no server (`library.Availability`), which songs fetching ahead passes over (the same),
 * and the format line before the player has opened the song (`PlayerConnection.format`).
 * Anything else that needs the limit (Diagnostics, the switch that opens the song playing
 * again when it changes) asks here too.
 */
class StreamQuality(private val settings: StateFlow<Settings>) {
    /** The limit for a stream opened now, in kbps; 0 for the files as they are. */
    fun limit(): Int = limitOf(settings.value)

    /** The limit as it changes. */
    val limits: Flow<Int> = settings.map(::limitOf).distinctUntilChanged()

    /** The form a song is streamed in now. `native`: the phone plays its file as it is (by
     *  its kind, and not known to fail on this phone). */
    fun formOf(native: Boolean, suffix: String, fileKbps: Int): StreamForm = streamForm(native, limit(), suffix, fileKbps)

    /** The form a song would be streamed in now, by its kind alone: for asking whether it
     *  is kept, with no player. */
    fun formOf(song: Song): StreamForm = streamFormOf(song, limit())

    /** The key the song is kept under in the form it would be streamed in now. */
    fun keyOf(song: Song): String = cacheKey(song.id, formOf(song))

    companion object {
        /** The limit the settings give. */
        fun limitOf(settings: Settings): Int = settings.bitrate
    }
}
