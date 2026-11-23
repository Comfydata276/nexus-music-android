package org.medianexus.music.playback

import org.medianexus.music.net.Song

/** A list cut down to the songs that can play offline: those songs, where each was in the
 *  whole list, and where to start. */
data class PlayableList(val songs: List<Song>, val origins: List<Int>, val start: Int)

/**
 * Offline, only what is downloaded or kept can play, so a list started then is only those
 * of its songs, as Windows starts one (`play_from` in src/playback.rs). It starts at the song
 * asked for if that one plays, else at the next one that does (else the last before it):
 * Windows starts such a list at its first playable song, which for a song chosen near the end
 * of a long album is a long way from what was chosen. Null if none of it plays.
 */
fun playableOnly(songs: List<Song>, start: Int, plays: (Song) -> Boolean): PlayableList? {
    val kept = songs.indices.filter { plays(songs[it]) }
    if (kept.isEmpty()) return null
    val at = kept.indexOfFirst { it >= start }.takeIf { it >= 0 } ?: (kept.size - 1)
    return PlayableList(kept.map { songs[it] }, kept, at)
}
