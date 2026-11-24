package org.medianexus.music.playback

import org.medianexus.music.net.Song

/** A list cut down to the songs that can play offline: those songs, where each was in the
 *  whole list, and where to start. */
data class PlayableList(val songs: List<Song>, val origins: List<Int>, val start: Int)

/**
 * Offline, only what is downloaded or kept can play, so a list started then is only those
 * of its songs, as Windows starts one (`play_from` in src/playback.rs). It starts at the song
 * at `start` if that one plays, else at the next one that does (else the last before it):
 * for a list asked for as a list (its play button, Shuffle). A song that was chosen and can't
 * play is never swapped for this: the caller starts nothing. Null if none of it plays.
 */
fun playableOnly(songs: List<Song>, start: Int, plays: (Song) -> Boolean): PlayableList? {
    val kept = songs.indices.filter { plays(songs[it]) }
    if (kept.isEmpty()) return null
    val at = kept.indexOfFirst { it >= start }.takeIf { it >= 0 } ?: (kept.size - 1)
    return PlayableList(kept.map { songs[it] }, kept, at)
}

/**
 * The same, for a list started from a song that was chosen (`chosen`: a row tapped, on the
 * phone or in the car): if that song can't play, null, and nothing starts; whatever is
 * playing carries on. A song chosen is never swapped for another.
 */
fun playableFrom(songs: List<Song>, start: Int, chosen: Boolean, plays: (Song) -> Boolean): PlayableList? {
    if (chosen && songs.getOrNull(start)?.let(plays) != true) return null
    return playableOnly(songs, start, plays)
}
