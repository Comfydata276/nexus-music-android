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
 * session as everything else (see [PlayerConnection]), and accepted only from this app.
 *
 * The app and the service share one process, so a long list of songs, sent as JSON text,
 * is handed over directly rather than copied between processes (where Android limits the
 * size of what can be sent).
 */
@OptIn(UnstableApi::class)
object AppCommands {
    val PLAY = SessionCommand("org.medianexus.music.app.PLAY", Bundle.EMPTY)
    val ENQUEUE = SessionCommand("org.medianexus.music.app.ENQUEUE", Bundle.EMPTY)
    val MOVE = SessionCommand("org.medianexus.music.app.MOVE", Bundle.EMPTY)
    val REMOVE = SessionCommand("org.medianexus.music.app.REMOVE", Bundle.EMPTY)
    val CLEAR = SessionCommand("org.medianexus.music.app.CLEAR", Bundle.EMPTY)
    val JUMP = SessionCommand("org.medianexus.music.app.JUMP", Bundle.EMPTY)

    val all = listOf(PLAY, ENQUEUE, MOVE, REMOVE, CLEAR, JUMP)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val songs = ListSerializer(Song.serializer())

    fun songsArg(list: List<Song>): String = json.encodeToString(songs, list)

    private fun songsOf(args: Bundle): List<Song> = args.getString("songs")?.let { runCatching { json.decodeFromString(songs, it) }.getOrNull() }.orEmpty()

    /** Carries out one, on the main thread. Returns false if it isn't one of these. */
    fun handle(player: QueuePlayer, command: SessionCommand, args: Bundle, shuffleFor: (source: String) -> Boolean): Boolean {
        when (command.customAction) {
            PLAY.customAction -> {
                val list = songsOf(args)
                val shuffle = when (args.getInt("shuffle", -1)) {
                    0 -> false
                    1 -> true
                    else -> shuffleFor(args.getString("source").orEmpty())
                }
                val source = args.getString("source").orEmpty()
                player.play(
                    list,
                    start = args.getInt("start"),
                    context = args.getString("context").orEmpty(),
                    source = source,
                    // A list from a page is always the page's whole list, in its order: each
                    // song's place in it is its place here (what marks the row playing).
                    origins = if (source.isNotEmpty()) list.indices.toList() else null,
                    sourceList = args.getStringArrayList("sourceList") ?: list.map { it.id },
                    shuffle = shuffle,
                    anyStart = args.getBoolean("anyStart"),
                )
            }
            ENQUEUE.customAction -> player.enqueue(songsOf(args), args.getBoolean("playNext"))
            MOVE.customAction -> player.moveAhead(args.getInt("from"), args.getInt("to"), args.getBoolean("queued"))
            REMOVE.customAction -> player.removeAhead(args.getInt("offset"))
            CLEAR.customAction -> player.clearAhead(args.getBoolean("queued"))
            JUMP.customAction -> player.jumpAhead(args.getInt("offset"))
            else -> return false
        }
        return true
    }
}
