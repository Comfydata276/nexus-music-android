package org.medianexus.music.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.library.columns
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
    fun queueFor(
        player: QueuePlayer,
        args: Bundle,
        songs: List<Song>,
        shuffle: Boolean,
        plays: ((Song) -> Boolean)? = null,
        dates: (source: String, songs: List<Song>) -> LongArray = { _, list -> LongArray(list.size) },
    ): Queue? {
        val source = args.getString("source").orEmpty()
        // Where to start: the place the screen named, unless the kept list has moved on
        // since the screen drew it (see StartPlace.startIn). A place in the list's own order.
        val start = StartPlace.startIn(songs, args.getInt("start"), args.getString("startId"), args.getStringArrayList("startNear").orEmpty()) ?: 0
        // Sorted, or narrowed to what was typed in its find box: what is shown, in the order
        // shown (Windows' `visible_songs`), worked out here from the list as the phone keeps it,
        // as the screen worked it out. Each song still knows its place in the list itself.
        val view = viewOf(args)?.takeIf { !it.isDefault }
        val shown: IntArray? = view?.let { v ->
            val added = if (v.sort == org.medianexus.music.library.ListView.ADDED) dates(source, songs) else null
            org.medianexus.music.library.Arrange.arrange(songs.columns(), v) { added?.get(it) ?: 0 }
        }
        if (shown != null && shown.isEmpty()) return null
        val list = shown?.map { songs[it] } ?: songs
        val places = shown?.toList() ?: songs.indices.toList()
        val first = if (shown == null) start else shown.indexOf(start).takeIf { it >= 0 } ?: 0
        // Offline (`plays` given): only what can play, each still at its place in the list.
        val cut = if (plays != null) playableOnly(list, first, plays) ?: return null else null
        return player.prepare(
            cut?.songs ?: list,
            start = cut?.start ?: first,
            context = args.getString("context").orEmpty(),
            source = source,
            // A list from a page is always the page's whole list, in its order: each song's
            // place in it is its place here (what marks the row playing).
            origins = if (source.isNotEmpty()) (cut?.origins?.map { places[it] } ?: places) else null,
            sourceList = songs.map { it.id },
            shuffle = shuffle,
            anyStart = args.getBoolean("anyStart"),
        )
    }

    /** Says in a PLAY how the list is shown (sorted, narrowed), when it is anything but as
     *  it comes. */
    fun putView(args: Bundle, view: org.medianexus.music.library.ListView?) {
        if (view == null || view.isDefault) return
        args.putBoolean("view", true)
        args.putInt("sort", view.sort)
        args.putBoolean("descending", view.descending)
        args.putString("filter", view.filter.take(MOST_FILTER))
    }

    fun viewOf(args: Bundle): org.medianexus.music.library.ListView? =
        if (!args.getBoolean("view")) null
        else org.medianexus.music.library.ListView(args.getInt("sort").coerceIn(0, org.medianexus.music.library.ListView.SORTS - 1), args.getBoolean("descending"), args.getString("filter").orEmpty().take(MOST_FILTER))

    /** What a find box sends, at most. */
    private const val MOST_FILTER = 200

    /** Carries out one, on the main thread. Returns false if it isn't one of these. (A PLAY
     *  that names a page is the service's to carry out: it has to read the page.) */
    fun handle(
        player: QueuePlayer,
        command: SessionCommand,
        args: Bundle,
        shuffleFor: (source: String) -> Boolean,
        plays: ((Song) -> Boolean)? = null,
        dates: (source: String, songs: List<Song>) -> LongArray = { _, list -> LongArray(list.size) },
    ): Boolean {
        when (command.customAction) {
            PLAY.customAction -> player.play(queueFor(player, args, songsOf(args), shuffleOf(args, shuffleFor), plays, dates) ?: return false)
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

/**
 * Where a list that a screen named, rather than sent, is started (see [AppCommands.queueFor]).
 * Plain Kotlin, apart from the commands, so it can be tested on its own.
 */
object StartPlace {
    /** How many songs either side of the one tapped a PLAY that names its page also names
     *  (see [StartPlace.startIn]). */
    const val NEIGHBOURS = 8

    /** The songs around the one tapped, as the screen showed them, nearest first: the one
     *  after it, the one before it, the second after, the second before, and so on. */
    fun neighbours(songs: List<Song>, start: Int): ArrayList<String> {
        val near = ArrayList<String>()
        for (step in 1..NEIGHBOURS) {
            songs.getOrNull(start + step)?.let { near += it.id }
            songs.getOrNull(start - step)?.let { near += it.id }
        }
        return near
    }

    /**
     * Where to start a list that was named rather than sent. `start` and `tapped` are the
     * place and the song the screen named; `songs` is the list as the phone keeps it now,
     * which may have moved on since the screen drew it. The song that was tapped, wherever
     * it is now. If it has gone from the list: the nearest song to it that is still there
     * (`near`, from [neighbours]: nearest first, whichever side of the tap it was on), which
     * is the nearest thing to what was meant. With none of those left either there is no
     * telling what was meant: the list from its first song. Never whatever happens to sit at
     * the old place. Null if the list is empty: nothing to start.
     */
    fun startIn(songs: List<Song>, start: Int, tapped: String?, near: List<String>): Int? {
        if (songs.isEmpty()) return null
        if (tapped == null || songs.getOrNull(start)?.id == tapped) return start.coerceIn(0, songs.size - 1)
        songs.indexOfFirst { it.id == tapped }.takeIf { it >= 0 }?.let { return it }
        // Nearest to where it was, among the copies of that neighbour (a list can hold a song twice).
        for (id in near) {
            val places = songs.indices.filter { songs[it].id == id }
            places.minByOrNull { kotlin.math.abs(it - start) }?.let { return it }
        }
        return 0
    }
}
