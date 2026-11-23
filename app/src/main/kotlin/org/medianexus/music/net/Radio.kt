package org.medianexus.music.net

// Internet radio, as the Windows app reads it (src/subsonic.rs). The stream itself will be
// opened by the player's data source; these are the parts of reading one that don't depend
// on how it's opened.

/** The first stream address in an M3U or PLS playlist. (HLS playlists, which list short
 *  segments instead of one continuous stream, aren't supported.) */
fun streamInPlaylist(text: String): String? {
    if ("#EXT-X-TARGETDURATION" in text) return null
    return text.lineSequence()
        .map { it.trim() }
        .map { line ->
            // PLS: "File1=http://..."
            val equals = line.indexOf('=')
            if (equals >= 0 && line.substring(0, equals).lowercase().startsWith("file")) {
                line.substring(equals + 1).trim()
            } else {
                line
            }
        }
        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
}

/** Picks the song title out of an announcement like `StreamTitle='Artist - Song';StreamUrl='';`. */
fun streamTitle(note: String): String? {
    val start = note.indexOf("StreamTitle='")
    if (start < 0) return null
    val rest = note.substring(start + "StreamTitle='".length)
    val end = rest.indexOf("';")
    val title = if (end >= 0) rest.substring(0, end) else rest.trimEnd('\u0000', '\'', ';')
    return title.trim()
}
