package org.medianexus.music.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.Song

/**
 * What the app's own screens ask of the player beyond what any controller can: playing a
 * list with where it came from, and the "Next in queue" edits. Sent through the same
 * session as everything else (see [PlayerConnection]), and accepted only from this app's
 * own screens: checked when they connect and again as each is carried out (see [Access]).
 *
 * The app and the service share one process, so a long list of songs, sent as JSON text,
 * is handed over directly rather than copied between processes (where Android limits the
 * size of what can be sent).
 */
@OptIn(UnstableApi::class)
object AppCommands {
    val PLAY = SessionCommand(AppActions.PLAY, Bundle.EMPTY)
    val ENQUEUE = SessionCommand(AppActions.ENQUEUE, Bundle.EMPTY)
    val MOVE = SessionCommand(AppActions.MOVE, Bundle.EMPTY)
    val REMOVE = SessionCommand(AppActions.REMOVE, Bundle.EMPTY)
    val CLEAR = SessionCommand(AppActions.CLEAR, Bundle.EMPTY)
    val JUMP = SessionCommand(AppActions.JUMP, Bundle.EMPTY)

    val all = listOf(PLAY, ENQUEUE, MOVE, REMOVE, CLEAR, JUMP)

    /** No entry: entry numbers start at 1. */
    const val NONE = -1L

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val songs = ListSerializer(Song.serializer())

    fun songsArg(list: List<Song>): String = json.encodeToString(songs, list)

    private fun songsOf(args: Bundle): List<Song> = args.getString("songs")?.let { runCatching { json.decodeFromString(songs, it) }.getOrNull() }.orEmpty()

    /** A list longer than this that is a page the phone keeps is started by naming the
     *  page, not by sending its songs. */
    const val MOST_SENT = 200

    /** Whether a PLAY names its list ("byPage") instead of carrying the songs. */
    fun byPage(args: Bundle): Boolean = args.getBoolean("byPage")

    /** Whether a PLAY's list plays shuffled: as it says, or as that list was last played. */
    fun shuffleOf(args: Bundle, shuffleFor: (source: String) -> Boolean): Boolean = when (args.getInt("shuffle", -1)) {
        0 -> false
        1 -> true
        else -> shuffleFor(args.getString("source").orEmpty())
    }

    /**
     * The queue a PLAY asks for, made ready to play. `songs` is the list: the one the
     * command carried, or, for a command that names a page instead, that page's songs as the
     * phone keeps them. Touches nothing of the player (see [QueuePlayer.prepare]): for a
     * long list, call it off the main thread.
     */
    fun queueFor(player: QueuePlayer, args: Bundle, songs: List<Song>, shuffle: Boolean): Queue {
        val source = args.getString("source").orEmpty()
        // Where to start: the place the screen named, unless the kept list has moved on
        // since the screen drew it, in which case the song that was tapped is found again.
        var start = args.getInt("start")
        val tapped = args.getString("startId")
        if (tapped != null && songs.getOrNull(start)?.id != tapped) songs.indexOfFirst { it.id == tapped }.takeIf { it >= 0 }?.let { start = it }
        return player.prepare(
            songs,
            start = start,
            context = args.getString("context").orEmpty(),
            source = source,
            // A list from a page is always the page's whole list, in its order: each song's
            // place in it is its place here (what marks the row playing).
            origins = if (source.isNotEmpty()) songs.indices.toList() else null,
            sourceList = songs.map { it.id },
            shuffle = shuffle,
            anyStart = args.getBoolean("anyStart"),
        )
    }

    /** Carries out one, on the main thread. Returns false if it isn't one of these. (A PLAY
     *  that names a page is the service's to carry out: it has to read the page.) */
    fun handle(player: QueuePlayer, command: SessionCommand, args: Bundle, shuffleFor: (source: String) -> Boolean): Boolean {
        when (command.customAction) {
            PLAY.customAction -> player.play(queueFor(player, args, songsOf(args), shuffleOf(args, shuffleFor)))
            ENQUEUE.customAction -> player.enqueue(songsOf(args), args.getBoolean("playNext"))
            // Each of these names the entry it means by the entry's own number (see
            // Queue.dropUid), not by where it was on the screen when it was tapped.
            MOVE.customAction -> player.moveEntry(args.getLong("uid", NONE), args.getLong("after", NONE).takeIf { it != NONE }, args.getBoolean("queued"))
            REMOVE.customAction -> player.removeEntry(args.getLong("uid", NONE))
            CLEAR.customAction -> player.clearAhead(args.getBoolean("queued"))
            JUMP.customAction -> player.jumpTo(args.getLong("uid", NONE))
            else -> return false
        }
        return true
    }
}
