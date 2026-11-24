package org.medianexus.music.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.library.arrangeable
import org.medianexus.music.library.idAt
import org.medianexus.music.library.pick
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

    fun songsOf(args: Bundle): List<Song> = args.getString("songs")?.let { runCatching { json.decodeFromString(songs, it) }.getOrNull() }.orEmpty()

    /** A list longer than this that is a page the phone keeps is started by naming the
     *  page, not by sending its songs. (Either way the PLAY says which song was tapped, and
     *  how the list was arranged on the screen.) */
    const val MOST_SENT = 200

    /** A PLAY's place in the order of choices, taken at the tap (see [Choices]). */
    const val PLACE = "place"

    /** The place a PLAY carries; for one that carries none (sent by an older build of the
     *  screens in this same process: none now), a new one. */
    fun placeOf(args: Bundle, take: () -> Long): Long = args.getLong(PLACE, 0L).takeIf { it > 0 } ?: take()

    /** Whether a PLAY names its list ("byPage") instead of carrying the songs. */
    fun byPage(args: Bundle): Boolean = args.getBoolean("byPage")

    /** Whether a PLAY's list plays shuffled: as it says, or as that list was last played. */
    fun shuffleOf(args: Bundle, shuffleFor: (source: String) -> Boolean): Boolean = when (args.getInt("shuffle", -1)) {
        0 -> false
        1 -> true
        else -> shuffleFor(args.getString("source").orEmpty())
    }

    /** What became of a PLAY. */
    sealed interface Started {
        class Ready(val queue: Queue) : Started

        /** Offline, and the song chosen, or nothing of the list, plays. */
        data object NothingOffline : Started

        data object Gone : Started

        data object Empty : Started
    }

    /** The number of the list a PLAY's screen left for the player (see [Drawn]); 0 if none. */
    fun drawnOf(args: Bundle): Long = args.getLong("drawn")

    /** An ENQUEUE's arguments. The songs are left for the player (see [Drawn]), not written
     *  out and read back on the main thread at both ends; only a few are written out as well,
     *  in case they aren't there to take. */
    fun enqueueArgs(list: List<Song>, playNext: Boolean): Bundle = Bundle().apply {
        putLong("drawn", Drawn.leave(DrawnList(list, null)))
        if (list.size <= MOST_SENT) putString("songs", songsArg(list))
        putBoolean("playNext", playNext)
    }

    /** The songs an ENQUEUE asks for. */
    fun enqueuedOf(args: Bundle): List<Song> = Drawn.take(drawnOf(args))?.songs ?: songsOf(args)

    /**
     * The queue a PLAY asks for, made ready to play. `drawn` is the list as the screen drew
     * it, taken from [Drawn]: what plays. Without it (the app's process started again since
     * the tap), `listed` is the list: the one the command carried, or, for a command that
     * names a page instead, that page's songs as the phone keeps them. Touches nothing of the
     * player (see [QueuePlayer.prepare]): for a long list, call it off the main thread.
     */
    fun queueFor(
        player: QueuePlayer,
        args: Bundle,
        listed: List<Song>,
        shuffle: Boolean,
        plays: ((Song) -> Boolean)? = null,
        dates: (source: String, songs: List<Song>) -> LongArray = { _, list -> LongArray(list.size) },
        drawn: DrawnList? = null,
    ): Started {
        val source = args.getString("source").orEmpty()
        val songs = drawn?.songs ?: listed
        // Sorted, or narrowed to what was typed in its find box: what was shown, in the order
        // shown (Windows' `visible_songs`): the rows as drawn, or, without them, worked out
        // here from the list as the phone keeps it, as the screen worked it out. Each song
        // still knows its place in the list itself.
        val view = viewOf(args)
        val startId = args.getString("startId")?.takeIf { !args.getBoolean("anyStart") }
        // Read without making a song of each where the list holds places (the Songs page's
        // 50,000 are places in the copy of the library): see PlacedSongs.
        val picked = if (drawn != null) {
            StartPlace.within({ songs.idAt(it) }, songs.size, drawn.shown, args.getInt("start"), startId)
        } else {
            val added = if (view?.sort == org.medianexus.music.library.ListView.ADDED) dates(source, songs) else null
            StartPlace.pick(songs.arrangeable(), { songs.idAt(it) }, view, args.getInt("start"), startId) { added?.get(it) ?: 0 }
        }
        val rows = when (picked) {
            is StartPlace.Picked.Rows -> picked
            StartPlace.Picked.Gone -> return Started.Gone
            StartPlace.Picked.Empty -> return Started.Empty
        }
        val shown = rows.shown
        val list = shown?.let { songs.pick(it) } ?: songs
        val places = shown?.toList() ?: songs.indices.toList()
        // Offline (`plays` given): only what can play, each still at its place in the list. A
        // song that was chosen and can't play starts nothing, not its neighbour; the play
        // button asks for the list, and starts at the first of it that plays.
        val cut = if (plays != null) playableFrom(list, rows.first, chosen = startId != null, plays) ?: return Started.NothingOffline else null
        return Started.Ready(
            player.prepare(
                cut?.songs ?: list,
                start = cut?.start ?: rows.first,
                context = args.getString("context").orEmpty(),
                source = source,
                // A list from a page is always the page's whole list, in its order: each song's
                // place in it is its place here (what marks the row playing).
                origins = if (source.isNotEmpty()) (cut?.origins?.map { places[it] } ?: places) else null,
                sourceList = List(songs.size) { songs.idAt(it) },
                shuffle = shuffle,
                anyStart = args.getBoolean("anyStart"),
                filter = view?.filter?.trim().orEmpty(),
            ),
        )
    }

    /** Says in a PLAY how the list was shown (sorted, narrowed) when the row was tapped:
     *  the arrangement on the screen, whatever the find box says by now. */
    fun putView(args: Bundle, view: org.medianexus.music.library.ListView?) {
        if (view == null || view.isDefault) return
        args.putBoolean("view", true)
        args.putInt("sort", view.sort)
        args.putBoolean("descending", view.descending)
        args.putString("filter", view.filter.take(org.medianexus.music.library.ListView.MOST_FILTER))
    }

    fun viewOf(args: Bundle): org.medianexus.music.library.ListView? =
        if (!args.getBoolean("view")) null
        else org.medianexus.music.library.ListView(args.getInt("sort").coerceIn(0, org.medianexus.music.library.ListView.SORTS - 1), args.getBoolean("descending"), args.getString("filter").orEmpty().take(org.medianexus.music.library.ListView.MOST_FILTER))

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
            PLAY.customAction -> player.play((queueFor(player, args, songsOf(args), shuffleOf(args, shuffleFor), plays, dates) as? Started.Ready)?.queue ?: return false)
            ENQUEUE.customAction -> player.enqueue(enqueuedOf(args), args.getBoolean("playNext"))
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
 * Where a list starts, in the arrangement a screen showed. Plain Kotlin, so it can be tested
 * on its own.
 */
object StartPlace {
    /** Where a PLAY's list starts, in the arrangement the screen showed (see [pick]). */
    sealed interface Picked {
        /** The rows to play (`shown`, places in the list; null: the list as it is) and the
         *  row to start at. */
        class Rows(val shown: IntArray?, val first: Int) : Picked

        /** The song that was tapped isn't in the arrangement any more: nothing plays. */
        data object Gone : Picked

        /** Nothing to play. */
        data object Empty : Picked
    }

    /**
     * The arrangement a screen showed, worked out again from the list as the phone has it
     * now (`songs`, `ids`), and the row of the song that was tapped (`startId`, which stood at
     * `start` in the list). `view` is how the screen arranged it: its find text and order at
     * the moment the arrangement was made, not whatever the find box says by the time of the
     * tap. With no song named (the play button), the arrangement from its top. Plain Kotlin.
     */
    fun pick(songs: org.medianexus.music.library.SongColumns, ids: (Int) -> String, view: org.medianexus.music.library.ListView?, start: Int, startId: String?, added: (Int) -> Long = { 0 }): Picked {
        val arranged = view?.takeIf { !it.isDefault }
        val shown = arranged?.let { org.medianexus.music.library.Arrange.arrange(songs, it, added = added) }
        return within(ids, songs.size, shown, start, startId)
    }

    /**
     * The rows a screen drew (`shown`, places in a list of `size` songs whose ids `ids` gives;
     * null: the whole list in its order) and the row of the song that was tapped (`startId`,
     * at place `start`; see [rowOf]). With no song named, the top row.
     */
    fun within(ids: (Int) -> String, size: Int, shown: IntArray?, start: Int, startId: String?): Picked {
        val rows = shown ?: IntArray(size) { it }
        if (rows.isEmpty()) return Picked.Empty
        val first = StartPlace.rowOf(ids, rows, start, startId) ?: return Picked.Gone
        return Picked.Rows(shown, first)
    }

    /**
     * The row of `rows` (places in a list whose ids `ids` gives) to start at. With a song
     * named (`tapped`, which stood at place `start` in the list when the screen drew it): the
     * row showing that place, if it still holds that song; else the first row that holds it,
     * wherever the list has moved it; null if the arrangement doesn't hold it at all. Never
     * a neighbour, nor whatever now sits at the old place: the person chose that song. With
     * none named, the first row. Null too if there are no rows.
     */
    fun rowOf(ids: (Int) -> String, rows: IntArray, start: Int, tapped: String?): Int? {
        if (rows.isEmpty()) return null
        if (tapped == null) return 0
        var first = -1
        for ((row, place) in rows.withIndex()) {
            if (ids(place) != tapped) continue
            if (place == start) return row
            if (first < 0) first = row
        }
        return first.takeIf { it >= 0 }
    }
}
