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
 * row of the phone's copy of the library, which is a day old, say). Of two things the server
 * said, the one read later is shown: a page as it comes from the server (an album, a
 * playlist, Liked songs) replaces what was seen before it, and a newer copy of the library
 * replaces every sighting read before it (the row drawn from the copy is then the newest).
 * What the app itself has just reported comes on top: a play is counted until the server is
 * seen to have it (a sighting whose last play is as late as it); a rating made here is shown
 * until the account's sender says it settled (the server took it, or refused it), and is then
 * forgotten, so that what the server says shows again: taken, that is the rating made.
 *
 * Kept in memory for the account's time; safe from any thread.
 */
class Freshest(private val clock: () -> Long = System::currentTimeMillis) {
    /** What the server said of a song, asked at `at` (milliseconds); null: not said. */
    private class Seen(val count: Int?, val played: Long?, val rating: Int?, val at: Long)

    private val seen = HashMap<String, Seen>()
    /** Plays this app reported, by song: when each counted (milliseconds). */
    private val plays = HashMap<String, MutableList<Long>>()
    private val ratings = HashMap<String, Int>()
    private val changed = MutableStateFlow(0L)

    /** Moves on whenever anything here changes: what shows a song's details watches it. */
    val version: StateFlow<Long> = changed.asStateFlow()

    /** A page as the server sent it, of whatever kind, asked for at `at`: its songs are noted. */
    fun sawPage(page: Any?, at: Long = clock()) {
        val songs = when (page) {
            is Album -> page.songs
            is Playlist -> page.songs
            is Found -> page.songs
            is List<*> -> page.filterIsInstance<Song>()
            else -> return
        }
        saw(songs, at)
    }

    /** Songs as the server had them when asked at `at`. */
    fun saw(songs: List<Song>, at: Long = clock()) {
        if (songs.isEmpty()) return
        synchronized(this) {
            for (song in songs) {
                if (song.id.isEmpty()) continue
                // The later reading is what the server says, whatever was seen before it.
                if ((seen[song.id]?.at ?: Long.MIN_VALUE) > at) continue
                seen[song.id] = Seen(song.playCount, millis(song.played), song.userRating, at)
            }
        }
        changed.value++
    }

    /** A copy of the library fetched at `fetched` (milliseconds) is in use: whatever was seen
     *  before that is older than the copy's rows. */
    fun copyArrived(fetched: Long) {
        val dropped = synchronized(this) { seen.values.removeAll { it.at < fetched } }
        if (dropped) changed.value++
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

    /** The account's sender has settled a change: the server took it (`taken`), or refused
     *  it. A rating is forgotten; taken, it is what the server says now. */
    fun settled(change: Pending, taken: Boolean) {
        if (change !is Pending.Rating) return
        synchronized(this) {
            if (ratings[change.id] == change.rating) ratings.remove(change.id)
            if (taken) {
                val before = seen[change.id]
                seen[change.id] = Seen(before?.count, before?.played, change.rating, clock())
            }
        }
        changed.value++
    }

    /** `song` with the newest play count, last play and rating the app has seen of it. */
    fun of(song: Song): Song = synchronized(this) {
        val sighting = seen[song.id]
        val count = sighting?.count ?: song.playCount
        val played = sighting?.played ?: millis(song.played)
        // Plays reported here that the server, as last seen, hasn't counted yet.
        val mine = plays[song.id].orEmpty().filter { it > played }
        val last = maxOf(played, mine.maxOrNull() ?: 0)
        val rating = ratings[song.id] ?: sighting?.rating ?: song.userRating
        if (count + mine.size == song.playCount && last == millis(song.played) && rating == song.userRating) return song
        song.copy(
            playCount = count + mine.size,
            played = if (last > 0) java.time.Instant.ofEpochMilli(last).toString() else "",
            userRating = rating,
        )
    }

    private fun millis(text: String): Long = if (text.isEmpty()) 0 else org.medianexus.music.store.SongTable.millisOf(text) ?: 0
}
