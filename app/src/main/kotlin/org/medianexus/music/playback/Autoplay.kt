package org.medianexus.music.playback

import kotlinx.coroutines.launch
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.Song

/** Where autoplay finds its songs (the Subsonic calls behind it). */
interface AutoplaySource {
    suspend fun similarSongs(id: String, count: Int): List<Song>
    suspend fun randomSongs(count: Int, genre: String?): List<Song>
}

/**
 * When the queue ends, music like its last song carries on (Windows' `load_autoplay`, with its
 * order of looking): songs the server considers similar first, which needs Last.fm or the like
 * set up in Navidrome; then, with fewer than five, songs of the same genre; then, with still
 * fewer, anything at all. Never a song already in the queue, never one twice, thirty at most.
 * "Start radio" is the same thing begun at once from one song.
 */
object Autoplay {
    /** How many songs one lookup adds. */
    const val MOST = 30

    /** The songs to carry on with after `seed`; `known` are the ids already in the queue.
     *  Throws only if the server can't be asked at all for the last resort. */
    suspend fun lookup(source: AutoplaySource, seed: Song, known: Set<String>): List<Song> {
        fun fresh(songs: List<Song>) = songs.filter { it.id.isNotEmpty() && it.radioUrl.isEmpty() && it.id !in known }
        val songs = ArrayList(fresh(try { source.similarSongs(seed.id, 40) } catch (_: ApiError) { emptyList() }))
        if (songs.size < 5 && seed.genre.isNotEmpty()) songs += fresh(try { source.randomSongs(30, seed.genre) } catch (_: ApiError) { emptyList() })
        if (songs.size < 5) songs += fresh(source.randomSongs(30, null))
        val seen = HashSet<String>()
        return songs.filter { seen.add(it.id) }.take(MOST)
    }

    /** Whether a queue's name says it is a song's radio (its context, as Windows names it). */
    fun isRadio(source: String): Boolean = source == RADIO_SOURCE

    /** The source a song's radio is started under: autoplay carries it on whatever the
     *  setting says, as a radio is what was asked for. One name for every radio, so the
     *  lists' remembered choices don't gain one per song. */
    const val RADIO_SOURCE = "radio"
}

/** What the player tells of the queue's end (see [QueuePlayer.end]). */
interface QueueEnd {
    /** The last song of the queue has become the one playing (or the queue has changed with
     *  it so): worth finding what follows now, so the music runs on without a pause. */
    fun lastSong(queue: Queue)

    /** The player has played to the end of the queue: true if something will carry on (the
     *  player then waits, not parked on the first song). */
    fun ended(queue: Queue): Boolean
}

/**
 * Autoplay for one account's turn at the player (Windows' `load_autoplay` and
 * `autoplay_loaded`). When the last song of the queue starts, songs like it are looked for;
 * they join the end of the queue if it is still the queue they were found for, so the player
 * plays on into them. If the queue has already ended, the player waits for them and carries on
 * when they land. It follows the autoplay setting, except for a song's radio, which is what was
 * asked for; it asks nothing offline; and it never starts music by itself after a deliberate
 * stop or pause, nor once something else has been chosen.
 */
class AutoplayRunner(
    private val enabled: () -> Boolean,
    private val source: AutoplaySource,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val offline: () -> Boolean,
    /** Hands the songs found to the player: for the queue whose last entry was `seed`. */
    private val found: (seed: Long, songs: List<Song>, resume: Boolean) -> Unit,
    /** The player's [QueuePlayer.intent]: what the person last did. */
    private val intent: () -> Long,
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : QueueEnd {
    /** The queue entry the songs on their way were found for. */
    private var seed: Long? = null
    private var job: kotlinx.coroutines.Job? = null
    /** The player has ended and waits for them: what the person had last done then. */
    private var waitingSince: Long? = null

    private fun wanted(queue: Queue): Boolean {
        if (queue.isEmpty || queue.repeat != Repeat.OFF || offline() || scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == false) return false
        if (!enabled() && !Autoplay.isRadio(queue.source)) return false
        val last = queue.songs.getOrNull(queue.order.last()) ?: return false
        return last.id.isNotEmpty() && last.radioUrl.isEmpty()
    }

    override fun lastSong(queue: Queue) {
        if (queue.cursor != queue.order.lastIndex || !wanted(queue)) return
        look(queue)
    }

    override fun ended(queue: Queue): Boolean {
        if (!wanted(queue)) return false
        waitingSince = intent()
        look(queue)
        return true
    }

    /** Looks for what follows the queue's last song, unless that is already on its way. */
    private fun look(queue: Queue) {
        val uid = queue.lastUid() ?: return
        if (uid == seed && job?.isActive == true) return
        job?.cancel()
        seed = uid
        val last = queue.songs[queue.order.last()]
        // (Every song in the queue once, read here on the player's thread.)
        val known = HashSet<String>(queue.songs.size * 2)
        for (i in 0 until queue.songs.size) known += queue.songs.id(i)
        job = scope.launch {
            val songs = try {
                kotlinx.coroutines.withContext(io) { Autoplay.lookup(source, last, known) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: ApiError) {
                emptyList()
            }
            // As it lands, on the player's thread: still wanted (the setting, offline, the
            // queue's end not changed by then), and carried on into only if the person has done
            // nothing since the queue ended (no pause or stop, the sleep timer, the sound taken
            // away, another choice).
            val since = waitingSince
            waitingSince = null
            if (seed != uid || !wanted(queue)) return@launch
            found(uid, songs, since != null && since == intent())
        }
    }
}

/**
 * How soon a dropped station is joined again: 1, 2, 4 and 8 seconds after each drop of the same
 * entry within three minutes, and then not (null). A different entry, or three minutes without
 * a drop, starts again from one second. (Windows' `reconnect` waits, counted the way the
 * reader counts a song's stalls.)
 */
class Rejoins {
    private var entry = -1L
    private val drops = ArrayList<Long>()

    fun next(uid: Long, now: Long): Long? {
        if (uid != entry) {
            entry = uid
            drops.clear()
        }
        drops.removeAll { now - it > WINDOW_MILLIS }
        drops += now
        return WAITS.getOrNull(drops.size - 1)
    }

    private companion object {
        val WAITS = longArrayOf(1_000, 2_000, 4_000, 8_000).toList()
        const val WINDOW_MILLIS = 3 * 60_000L
    }
}

/** The player of the playback service, for what the app's screens in the same process ask of
 *  the whole queue (they are shown only the part the player holds). On the main thread. */
object QueueNow {
    @Volatile internal var player: java.lang.ref.WeakReference<QueuePlayer>? = null

    /** What is playing and everything after it, in the order it will play, as it is now; the
     *  songs are made when the function given back is called, which can be off this thread. */
    fun fromNowOn(): () -> List<org.medianexus.music.net.Song> = player?.get()?.fromNowOn() ?: { emptyList() }
}
