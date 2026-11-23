package org.medianexus.music.store

import kotlin.math.abs
import kotlinx.serialization.Serializable

/** What's remembered about one list of songs (a playlist, an album, Liked songs). */
@Serializable
data class ListPrefs(
    /** Play it shuffled. */
    val shuffle: Boolean = false,
    /** The order it's shown in (an index into the track sorts), and whether that's reversed. */
    val sort: Int = 0,
    val descending: Boolean = false,
    /** The order was chosen by the user. Until it is, the list opens in its usual order: for
     *  a playlist, the most recently added songs first. */
    val sortChosen: Boolean = false,
    /** When these choices last changed, in seconds since 1970: between devices, the newer
     *  set wins. */
    val at: Long = 0,
)

/** An equaliser curve saved under a name of the user's own. */
@Serializable
data class SavedCurve(val name: String = "", val gains: List<Float> = FLAT)

/**
 * The settings, as the Windows app keeps them (src/config.rs), less what only makes sense on
 * a computer: the window, the tray, the output device, exclusive mode, interface size and
 * folders of the user's choosing. Saved as JSON with the same field names.
 *
 * Every field has a default, so a file written by an older version reads as it was, with
 * anything new at its default.
 */
@Serializable
data class Settings(
    // ---- The account (the password is kept apart: see `PasswordVault`) ----
    val server: String = "",
    val username: String = "",

    // ---- Playback ----
    val shuffle: Boolean = false,
    /** 0 off, 1 the whole list, 2 one song. */
    val repeat: Int = 0,
    /** Bitrate cap in kbps sent to the server; 0 streams the original files. */
    val bitrate: Int = 0,
    val scrobble: Boolean = true,
    /** Seconds of overlap between tracks; 0 for none. */
    val crossfade: Float = 0f,
    val gapless: Boolean = true,
    /** Keep playing similar music when the queue runs out. */
    val autoplay: Boolean = true,

    // ---- Sound ----
    /** Even out volume from ReplayGain tags. */
    val normalize: Boolean = false,
    /** Loudness target when evening out volume: 0 quiet, 1 normal, 2 loud. */
    val volumeLevel: Int = 1,
    /** Which ReplayGain value evens out volume: 0 decides by what's playing, 1 each song's
     *  own, 2 its album's. */
    val levelMode: Int = 0,
    /** How much songs with no levelling tags are turned down while levelling is on, in dB
     *  (0 or negative). Tagged songs are usually turned down by about this much. */
    val untaggedDb: Float = -6f,
    val mono: Boolean = false,
    val equalizer: Boolean = false,
    /** Gain in dB for each of the six equaliser bands. */
    val eqGains: List<Float> = FLAT,
    /** Turns everything down before the equaliser, in dB (0 or negative), to leave room for
     *  bands that are turned up. Anything above 0 stands for Auto (see `eqPreampNow`). */
    val eqPreamp: Float = 0f,
    val eqSaved: List<SavedCurve> = emptyList(),

    // ---- Storage ----
    /** Space allowed for songs kept as they're played, in megabytes; 0 keeps none. */
    val cacheLimitMb: Int = 2048,
    /** How many songs past the one playing are fetched ahead of time. */
    val cacheAhead: Int = 3,
    /** Bitrate cap in kbps for new downloads; 0 saves the original files. */
    val downloadQuality: Int = 0,
    /** Downloads wait for Wi-Fi. (The phone's own: a computer has no mobile data.) */
    val downloadWifiOnly: Boolean = true,
    /** Offline mode: don't contact the server; play downloads only. */
    val offline: Boolean = false,

    // ---- Lists ----
    /** Playlists kept at the top of Library, in the order they were pinned. */
    val pinnedPlaylists: List<String> = emptyList(),
    /** Choices remembered for individual playlists, albums and so on, by route key. */
    val lists: Map<String, ListPrefs> = emptyMap(),

    // ---- Appearance ----
    /** Name of the colour theme (see `THEMES`). */
    val theme: String = DEFAULT_THEME,

    // ---- In step with other devices ----
    /** Share the queue, pins and so on with the app on other devices. */
    val sync: Boolean = true,
    /** A made-up name for this device, so it can tell its own saved queue from others'. */
    val deviceId: String = "",
    /** The id of the playlist that holds what's shared between devices, once known. */
    val syncPlaylist: String = "",
    /** When the pins and the theme were last changed, to settle which device's are newer. */
    val pinsAt: Long = 0,
    val themeAt: Long = 0,
) {
    /** The name of the preset matching the equaliser curve: one of the app's, one of the
     *  user's own, or neither. */
    fun eqPreset(): EqCurve {
        fun same(gains: List<Float>) = gains.size == eqGains.size && gains.zip(eqGains).all { (a, b) -> abs(a - b) < 0.05f }
        EQ_PRESETS.indexOfFirst { same(it) }.takeIf { it >= 0 }?.let { return EqCurve.BuiltIn(it) }
        eqSaved.firstOrNull { same(it.gains) }?.let { return EqCurve.Saved(it.name) }
        return EqCurve.Custom
    }

    /** The preamp in use, in dB: the amount set, or for Auto exactly enough room for the band
     *  turned up the most. */
    fun eqPreampNow(): Float = if (eqPreamp > 0f) -eqGains.fold(0f) { most, gain -> maxOf(most, gain) } else eqPreamp
}

sealed interface EqCurve {
    /** An index into `EQ_PRESETS`; its name is in the string resources. */
    data class BuiltIn(val index: Int) : EqCurve
    data class Saved(val name: String) : EqCurve
    data object Custom : EqCurve
}

const val DEFAULT_THEME = "Violet"

private val FLAT = List(6) { 0f }

// The choices Settings offers, as on Windows. Their names are words a person reads, so they
// live in the string resources, in the same order.

/** Streaming and download quality: the bitrate cap sent to the server (0 = the original). */
val QUALITIES = listOf(96, 160, 320, 0)

/** How much space kept songs may take, in megabytes. The first keeps none. */
val CACHE_LIMITS = listOf(0, 1024, 2048, 5120, 10240, 20480)

/** How many songs to fetch ahead of the one playing. */
val CACHE_AHEAD = listOf(0, 1, 3, 5, 10)

/** Equaliser preamp choices, in dB (1 for Auto). */
val PREAMPS = listOf(1f, 0f, -3f, -6f, -9f, -12f)

/** How much to turn down songs that have no levelling tags, in dB. */
val UNTAGGED_LEVELS = listOf(0f, -3f, -6f, -9f)

/** Loudness targets: the offset in dB from the ReplayGain reference level. */
val VOLUME_LEVELS = listOf(-1f, 4f, 7f)

/** Equaliser presets: the gain in dB for each of the six bands. Flat, Bass boost, Bass
 *  reduce, Treble boost, Vocal, Rock, Pop, Jazz, Classical, Electronic. */
val EQ_PRESETS: List<List<Float>> = listOf(
    listOf(0f, 0f, 0f, 0f, 0f, 0f),
    listOf(6f, 4f, 0f, 0f, 0f, 0f),
    listOf(-6f, -4f, 0f, 0f, 0f, 0f),
    listOf(0f, 0f, 0f, 1f, 4f, 6f),
    listOf(-2f, -1f, 1f, 4f, 3f, 0f),
    listOf(4f, 2f, -2f, -1f, 2f, 4f),
    listOf(-1f, 1f, 3f, 3f, 1f, -1f),
    listOf(3f, 1f, -1f, 1f, 2f, 3f),
    listOf(3f, 1f, 0f, 0f, 1f, 3f),
    listOf(5f, 3f, 0f, -1f, 2f, 4f),
)
