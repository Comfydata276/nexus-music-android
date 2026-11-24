package org.medianexus.music.playback

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.medianexus.music.net.Song

/**
 * Another player, shown and steered in this phone's player (M6): the host's, while the phone
 * is in a friend's jam, or one of the person's other devices, while it is being steered from
 * here. Windows does the same with its `remote` functions: while it is so, the player bar, Now
 * Playing and the queue sheet show the other player, and what is done in them is asked of it
 * (see [PlayerConnection]). The phone's own player is left paused where it was, and the
 * media session, the notification and the car go on showing that: nothing of the other
 * player is theirs (see docs/DESIGN.md, "What the car, the notification and the lock screen
 * show").
 */
class Steered {
    private val shownNow = MutableStateFlow<Shown?>(null)

    /** The other player as it is to be shown; null while the phone's player is its own. */
    val shown: StateFlow<Shown?> = shownNow.asStateFlow()

    /** What carries out what is done in the player while it shows another. Set with [show]. */
    @Volatile var hands: Hands? = null
        private set

    fun show(view: Shown, hands: Hands) {
        this.hands = hands
        shownNow.value = view
    }

    fun clear() {
        hands = null
        shownNow.value = null
    }

    companion object {
        /**
         * What a song of another player's that this phone's library doesn't have carries for
         * its cover: this, then where the relay serves it (empty for none). Nothing a music
         * server sends can begin so. Such a song is shown as the other side described it,
         * and can't be liked, downloaded or opened here (Windows' `jam_row`).
         */
        const val FOREIGN = "\u0001elsewhere:"

        fun foreign(song: Song): Boolean = song.coverArt.startsWith(FOREIGN)

        /**
         * A transport command from outside the app (a headset, the notification) while the
         * phone's player shows another: Play/Pause, Next and Previous go to that one, as
         * Windows' media keys do; the rest of the transport does nothing. True when the
         * command was taken here (and so is not to reach the phone's own player).
         */
        fun press(hands: Hands, command: Int): Boolean {
            when (command) {
                androidx.media3.common.Player.COMMAND_PLAY_PAUSE -> hands.toggle()
                androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT, androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hands.next()
                androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS, androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hands.previous()
                else -> if (command !in Access.TRANSPORT) return false
            }
            return true
        }

        /** The other player's position, moving while it plays. */
        fun ticking(shown: Shown, tickMillis: Long): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flow {
            while (true) {
                emit(shown.positionAt(android.os.SystemClock.elapsedRealtime()))
                if (!shown.playing) break
                kotlinx.coroutines.delay(tickMillis)
            }
        }

        /**
         * A PLAY a screen made (see `PlayerConnection.playArgs`), handed to the other player:
         * the rows as drawn, from the song tapped. The list is only looked at, not copied: the
         * Songs page's is every song in the library.
         */
        fun play(hands: Hands, args: android.os.Bundle) {
            val start = args.getInt("start")
            val whole = args.getBoolean("anyStart")
            val context = args.getString("context").orEmpty()
            val drawn = args.getLong("drawn", 0L).takeIf { it != 0L }?.let(Drawn::take)
            if (drawn == null) {
                val songs = AppCommands.songsOf(args)
                hands.play(songs, start.coerceIn(0, (songs.size - 1).coerceAtLeast(0)), context, whole)
                return
            }
            val shown = drawn.shown
            if (shown == null) {
                hands.play(drawn.songs, start, context, whole)
                return
            }
            val rows = object : AbstractList<Song>() {
                override val size: Int get() = shown.size
                override fun get(index: Int): Song = drawn.songs[shown[index]]
            }
            hands.play(rows, shown.indexOf(start).coerceAtLeast(0), context, whole)
        }
    }

    /** The other player at one moment. */
    @Immutable
    data class Shown(
        val song: Song?,
        val playing: Boolean,
        /** Milliseconds into the song when [at] (the phone's `elapsedRealtime`). */
        val positionMs: Long,
        val at: Long,
        val durationMs: Long,
        val queue: UpNext,
        val shuffle: Boolean,
        val repeat: Int,
        /** One of the person's own devices, whose volume this is (0 to 1); null in a
         *  friend's jam, where the volume is the phone's own. */
        val volume: Float?,
        /** Where the music is, in words: "Jo's jam", or the device's name. */
        val where: String,
    ) {
        /** Where the song has got to by `now`. */
        fun positionAt(now: Long): Long =
            if (!playing) positionMs else (positionMs + (now - at)).coerceIn(0, durationMs.coerceAtLeast(0))
    }

    /**
     * What the player does while it shows another. Entries of the queue are named by the
     * numbers in [Shown.queue]'s `uids`, as everywhere else in the app.
     */
    interface Hands {
        fun toggle()
        fun next()
        fun previous()
        fun seek(positionMs: Long)
        fun remove(uid: Long)
        fun move(uid: Long, after: Long?, queued: Boolean)
        fun clear(queued: Boolean)
        fun jump(uid: Long)
        fun shuffle()
        fun repeat()
        fun volume(to: Float)
        /** Songs queued from anywhere in the app: added there. */
        fun add(songs: List<Song>, playNext: Boolean)
        /**
         * A list played from anywhere in the app (its songs as drawn, from `start`; `whole`:
         * the list itself was played, no song chosen). Between the person's own devices it
         * plays there as it would here; in a friend's jam the song chosen, or the list, is
         * added. `songs` may be only the song chosen, when the list was too long to hand
         * over (the Songs page).
         */
        fun play(songs: List<Song>, start: Int, context: String, whole: Boolean)
    }
}
