package org.medianexus.music.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.medianexus.music.net.Album
import org.medianexus.music.net.Found
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song
import org.medianexus.music.store.Pending

/**
 * The newest the app has seen of how often each song has been played, when last, and how it
 * is rated: for a song's details, which would otherwise show what the song was drawn with (a
 * row of the phone's copy of the library, which is a day old, say). Two sources: a page as it
 * comes from the server (an album, a playlist, Liked songs, a search), and what the app itself
 * has just reported (a play that counted, a rating). A play reported is counted until the
 * server is seen to have it: a sighting whose last play is as late as it.
 *
 * Kept in memory for the account's time; safe from any thread.
 */
class Freshest {
    private class Seen(val count: Int, val played: Long, val rating: Int)

    private val seen = HashMap<String, Seen>()
    /** Plays this app reported, by song: when each counted (milliseconds). */
    private val plays = HashMap<String, MutableList<Long>>()
    private val ratings = HashMap<String, Int>()
    private val changed = MutableStateFlow(0L)

    /** Moves on whenever anything here changes: what shows a song's details watches it. */
    val version: StateFlow<Long> = changed.asStateFlow()

    /** A page as the server sent it, of whatever kind: its songs are noted. */
    fun sawPage(page: Any?) {
        val songs = when (page) {
            is Album -> page.songs
            is Playlist -> page.songs
            is Found -> page.songs
            is List<*> -> page.filterIsInstance<Song>()
            else -> return
        }
        saw(songs)
    }

    /** Songs as the server has them now. */
    fun saw(songs: List<Song>) {
        if (songs.isEmpty()) return
        synchronized(this) {
            for (song in songs) {
                if (song.id.isEmpty()) continue
                val before = seen[song.id]
                val played = millis(song.played)
                // The later sighting: what the server says now (its count never goes down).
                seen[song.id] = Seen(maxOf(song.playCount, before?.count ?: 0), maxOf(played, before?.played ?: 0), song.userRating)
            }
        }
        changed.value++
    }

    /** Something this app has just told the server (or holds to tell it). */
    fun reported(change: Pending) {
        synchronized(this) {
            when (change) {
                is Pending.Played -> plays.getOrPut(change.id) { ArrayList() } += change.at
                is Pending.Rating -> ratings[change.id] = change.rating
                else -> return
            }
        }
        changed.value++
    }

    /** `song` with the newest play count, last play and rating the app has seen of it. */
    fun of(song: Song): Song = synchronized(this) {
        val sighting = seen[song.id]
        val count = maxOf(song.playCount, sighting?.count ?: 0)
        val played = maxOf(millis(song.played), sighting?.played ?: 0)
        // Plays reported here that the server, as last seen, hasn't counted yet.
        val mine = plays[song.id].orEmpty().filter { it > played }
        val last = maxOf(played, mine.maxOrNull() ?: 0)
        val rating = ratings[song.id] ?: sighting?.rating ?: song.userRating
        if (count + mine.size == song.playCount && last == millis(song.played) && rating == song.userRating) return song
        song.copy(
            playCount = count + mine.size,
            played = if (last > 0) java.time.Instant.ofEpochMilli(last).toString() else song.played,
            userRating = rating,
        )
    }

    private fun millis(text: String): Long = if (text.isEmpty()) 0 else org.medianexus.music.store.SongTable.millisOf(text) ?: 0
}
