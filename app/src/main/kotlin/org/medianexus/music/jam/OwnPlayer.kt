package org.medianexus.music.jam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.withTimeoutOrNull
import org.medianexus.music.Graph
import org.medianexus.music.library.SearchLimits
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Choices
import org.medianexus.music.playback.PlayerConnection
import org.medianexus.music.playback.QueueNow
import org.medianexus.music.playback.Repeat

/**
 * The phone's own player as a session hosted here sees it: a connection of its own to the
 * playback service ([PlayerConnection], with no other player to show), held only while the
 * session is open, and the service's way to take a queue handed over. The screens' connection
 * can't be used: it shows the other player while this phone steers one.
 */
class OwnPlayer(private val app: Context, private val graph: Graph) : HostPlayer, HostLibrary {
    val connection = PlayerConnection(app, graph.accounts, graph.settings, graph.scope, quality = graph.quality)
    private val audio = app.getSystemService(AudioManager::class.java)

    override fun now(): HostNow {
        val queue = connection.queue.value
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return HostNow(
            song = connection.song.value,
            ahead = queue.songs,
            uids = queue.uids,
            queuedAhead = queue.queuedAhead,
            totalAhead = queue.total,
            playing = connection.playing.value,
            position = connection.positionNowMs / 1000.0,
            context = queue.context,
            shuffle = connection.shuffle.value,
            repeat = connection.repeat.value.coerceIn(Repeat.OFF, Repeat.ONE),
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max,
        )
    }

    override fun skip() = connection.next()
    override fun previous() = connection.previous()
    override fun setPlaying(on: Boolean) {
        // As a tap on Play or Pause here: a choice, with its place in the order of choices.
        if (on != connection.playing.value) connection.playPause()
    }
    override fun seek(seconds: Double) = connection.seek((seconds * 1000).toLong())
    override fun jump(uid: Long) = connection.jump(uid)
    override fun move(uid: Long, after: Long?, queued: Boolean) = connection.move(uid, after, queued)
    override fun clear(queued: Boolean) = connection.clear(queued)
    override fun remove(uid: Long) = connection.remove(uid)
    override fun shuffle(on: Boolean) = connection.setShuffle(on)
    override fun repeat(mode: Int) = connection.setRepeat(mode)

    /** The phone's media volume: what a person turning a phone up or down means. */
    override fun volume(to: Double) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (to * max).toInt().coerceIn(0, max), 0)
    }

    override fun enqueue(songs: List<Song>, next: Boolean) {
        if (connection.song.value == null) {
            // Nothing was queued at all: the first song anyone adds starts the music.
            connection.play(songs, 0, "")
            return
        }
        if (next) connection.playNext(songs) else connection.addToQueue(songs)
    }

    override fun fromNowOn(): List<Song> = QueueNow.fromNowOn()()

    override fun song(id: String): Song? {
        val copy = graph.accounts.state.value?.copy?.state?.value ?: return null
        return SongIds.of(copy)[id]?.let(copy.songs::song)
    }

    override fun search(query: String): List<Song> {
        val copy = graph.accounts.state.value?.copy?.state?.value ?: return emptyList()
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        return copy.find(q, SearchLimits(0, 0, FOUND), { false })?.songs.orEmpty().filter { it.radioUrl.isEmpty() }.take(FOUND)
    }

    /** Why a queue handed over can't be played here, or null if it can (rule 3). */
    enum class Refusal { IN_CALL, SOUND_TAKEN, OFFLINE, NOT_READY }

    /**
     * Plays a queue another of the person's devices handed this phone (rule 3). It takes its
     * place in the order of choices as it arrives (`place`), and gives way to anything chosen
     * here afterwards; offline, only the songs that play offline are kept, as for any list;
     * and it is refused, with the other device told why, while the phone is in a call or
     * another app holds the sound and won't give it up.
     */
    suspend fun load(songs: List<Song>, index: Int, positionMs: Long, play: Boolean, context: String, place: Long): Refusal? {
        if (play) {
            val mode = audio.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING) return Refusal.IN_CALL
            // Asked for the sound as the player would be: refused only while something holds
            // it that won't give it up (a call, by Android's rule). Given, it is handed straight
            // back: the player asks for it in its turn a moment later.
            val ask = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .build()
            val given = audio.requestAudioFocus(ask)
            audio.abandonAudioFocusRequest(ask)
            if (given != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return Refusal.SOUND_TAKEN
        }
        var list = songs
        var at = index
        if (graph.watch.offline) {
            val availability = graph.accounts.state.value?.availability ?: return Refusal.NOT_READY
            val playable = org.medianexus.music.playback.playableOnly(songs, index, availability::plays) ?: return Refusal.OFFLINE
            list = playable.songs
            at = playable.start
        }
        // The service may not be running: the connection starts it, and it says when it can
        // take a queue.
        connection.connect()
        val handOver = withTimeoutOrNull(5_000) {
            while (graph.steered.handOver == null) kotlinx.coroutines.delay(100)
            graph.steered.handOver
        } ?: return Refusal.NOT_READY
        val done = handOver.take(list, at, if (at == index) positionMs else 0L, play, context, place)
        return if (done) null else Refusal.NOT_READY
    }

    /** A place in the order of choices, taken now. */
    fun take(): Long = Choices.take()
}

/** Each song's place in a copy of the library, by id: made once for each copy. */
object SongIds {
    private val made = java.util.WeakHashMap<org.medianexus.music.library.LibrarySnapshot, Map<String, Int>>()

    @Synchronized
    fun of(copy: org.medianexus.music.library.LibrarySnapshot): Map<String, Int> = made.getOrPut(copy) {
        HashMap<String, Int>(copy.songs.size * 2).apply { for (i in 0 until copy.songs.size) put(copy.songs.id(i), i) }
    }
}
