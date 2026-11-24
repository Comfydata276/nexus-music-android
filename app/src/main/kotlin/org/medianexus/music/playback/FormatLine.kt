package org.medianexus.music.playback

import org.medianexus.music.net.Song

/** The short line under a song in Now Playing that says what is being played: "FLAC ·
 *  48 kHz · 24-bit", "MP3 · 320 kbps". `lossless` draws it in the accent, as on Windows. */
data class FormatLine(val text: String, val lossless: Boolean)

/** File types that carry every bit of the original, as Windows counts them. */
private val LOSSLESS_SUFFIXES = setOf("flac", "wav", "aiff", "aif")

/**
 * The format line for a song, as the Windows app words it (`show_format` in
 * src/playback.rs): the file as stored when that's what arrives, or the MP3 the server makes
 * of it. `cap` is the streaming limit in kbps (0 for none); `converted` says the phone turned
 * out not to play the file, so the server converts it.
 */
fun formatLine(song: Song, cap: Int, converted: Boolean, liveStream: String): FormatLine {
    if (song.radioUrl.isNotEmpty()) return FormatLine(liveStream, false)
    val suffix = song.suffix.lowercase()
    // As the player decides it (see `PlaybackService.resolve`): a file of no known type is
    // tried as it is.
    val native = (suffix.isEmpty() || suffix in NATIVE_SUFFIXES) && !converted
    // With a limit, an MP3 already within it is sent untouched.
    val asStored = native && (cap == 0 || (suffix == "mp3" && song.bitRate in 1..cap))
    if (!asStored) return FormatLine("MP3 · ${if (cap == 0) 320 else cap} kbps", false)
    if (suffix.isEmpty()) return FormatLine("", false)
    val lossless = suffix in LOSSLESS_SUFFIXES || song.bitDepth > 0
    val parts = mutableListOf(suffix.uppercase())
    if (lossless) {
        if (song.samplingRate > 0) {
            val khz = song.samplingRate / 1000.0
            parts += if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else "%.1f kHz".format(java.util.Locale.ROOT, khz)
        }
        if (song.bitDepth > 0) parts += "${song.bitDepth}-bit"
    } else if (song.bitRate > 0) {
        parts += "${song.bitRate} kbps"
    }
    return FormatLine(parts.joinToString(" · "), lossless)
}

/**
 * The format line for a song from the copy of it that is actually being played (the player
 * says which: see `SongCopy`): the MP3 it was converted to, at `kbps`, or the file as it is
 * (0). Not worked out from what the settings and the downloads say now, which can change
 * while the song plays on from the copy it started on.
 */
fun formatLineOf(song: Song, kbps: Int, liveStream: String): FormatLine = when {
    song.radioUrl.isNotEmpty() -> FormatLine(liveStream, false)
    kbps > 0 -> FormatLine("MP3 · $kbps kbps", false)
    else -> formatLine(song, 0, false, liveStream)
}
