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

/**
 * A change the person made on this phone to something shared between devices, kept as the
 * change and not as a new value: `what` is "pin" or "unpin" (`key`: the playlist), "shuffle"
 * (`key`: the list, `on`), "sort" (`key`: the list, `sort`, `descending`) or "theme" (`key`:
 * its name). See `sync/Changes.kt`.
 */
@Serializable
data class Waiting(val what: String = "", val key: String = "", val on: Boolean = false, val sort: Int = 0, val descending: Boolean = false)

/** What this phone last agreed with the server, of the choices shared between devices: each
 *  with the time it has in the shared record. Never a time of the phone's own making, except
 *  as written to the server with the change that earned it. */
@Serializable
data class Agreed(
    val pins: List<String> = emptyList(),
    val pinsAt: Long = 0,
    /** Empty: none agreed. */
    val theme: String = "",
    val themeAt: Long = 0,
    val lists: Map<String, ListPrefs> = emptyMap(),
)

/** An equaliser curve saved under a name of the user's own. */
@Serializable
data class SavedCurve(val name: String = "", val gains: List<Float> = FLAT)

/**
 * The settings, as the Windows app keeps them (src/config.rs), less what only makes sense on
 * a computer: the window, the tray, the output device, exclusive mode, interface size and
 * folders of the user's choosing. Saved as JSON with the same field names, but the file is
 * the phone's own: it need not be one Windows can read, nor read one of Windows'.
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
    /** (No longer used: the playlist that holds what's shared between devices is looked for
     *  afresh at every exchange. Read past in a file that has it.) */
    val syncPlaylist: String = "",
    /** (No longer used, and read past: times an earlier build put on the pins and the theme
     *  as they were changed here. The times that count are in [agreed].) */
    val pinsAt: Long = 0,
    val themeAt: Long = 0,
    /**
     * What this phone last agreed with the server for the account in [sharedFor], of the
     * pins, the theme and each list's choices; and the person's changes to them made here
     * since, still waiting to be shared. The pins, lists and theme above are what the phone
     * shows: the agreed ones with the waiting changes on top (and, where nothing is agreed,
     * whatever the phone has of its own). Only [agreed] and [waiting] are ever shared, and a
     * waiting change gets a time only as it is written to the server (`sync/Changes.kt`).
     */
    val agreed: Agreed = Agreed(),
    val waiting: List<Waiting> = emptyList(),
    /**
     * Which rules the shared choices above were written under: `sync/Belongs.kt`'s
     * `MARK` once a build that keeps them as [agreed] and [waiting] has had them. 0 in a file
     * from any build before that, whose pins, list choices and theme carry times put on by
     * rules since thrown out: such a file's are demoted as it is first opened.
     */
    val stepMark: Int = 0,
    /** Which account the choices that are shared between devices belong to (its storage
     *  key): the pins and their time, each list's choices, the theme's time and the sync
     *  playlist's id. They are shared into that account's record and no other's: when
     *  another account is taken on they are put away with the one they belong to, and that
     *  account's own brought out (see `sync/Belongs.kt`). Empty until an account first is. */
    val sharedFor: String = "",

    // ---- The phone's own ----
    /** Android 17's local-network permission was refused and the system won't ask again:
     *  "Allow" opens the app's page in the phone's settings instead (see `LocalAccess`). */
    val localNetworkRefusedForGood: Boolean = false,
    /** Android 13 and later: whether the notifications permission has been asked for, at the
     *  first download (the progress notification needs it; the player's doesn't). Asked once:
     *  refused, downloads still run and show their progress in the app. */
    val downloadNotificationsAsked: Boolean = false,
) {
    /** The name of the preset matching the equaliser curve: one of the app's, one of the
     *  user's own, or neither. */
    fun eqPreset(): EqCurve {
        fun same(gains: List<Float>) = gains.size == eqGains.size && gains.zip(eqGains).all { (a, b) -> abs(a - b) < 0.05f }
        EQ_PRESETS.indexOfFirst { same(it) }.takeIf { it >= 0 }?.let { return EqCurve.BuiltIn(it) }
        eqSaved.firstOrNull { same(it.gains) }?.let { return EqCurve.Saved(it.name) }
        return EqCurve.Custom
    }

    /** The settings with what can't be used put right, as they're read: an equaliser curve
     *  has exactly six bands, so one of any other length is set flat, or dropped if saved. */
    internal fun repaired(): Settings {
        val gains = eqGains.takeIf { it.size == EQ_BANDS } ?: FLAT
        val saved = eqSaved.filter { it.gains.size == EQ_BANDS }
        return if (gains === eqGains && saved.size == eqSaved.size) this else copy(eqGains = gains, eqSaved = saved)
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

/** How many bands the equaliser has. */
const val EQ_BANDS = 6

private val FLAT = List(EQ_BANDS) { 0f }

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
