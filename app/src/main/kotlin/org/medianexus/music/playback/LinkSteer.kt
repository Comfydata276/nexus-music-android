package org.medianexus.music.playback

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.medianexus.music.net.Song

/**
 * **A device being steered by the new linking, in the phone's player.** The library says
 * which device (`steering`) and what it was last heard to be doing (`view`); here that is
 * put in [Steered], which the player bar, Now Playing and the queue sheet already draw
 * from, and what is pressed in them is told to the library as a press ([hands]), which
 * asks it of the other device.
 *
 * - **Only one thing is in the phone's player at a time.** If it is showing something
 *   else when a view arrives (a friend's jam), that stays, and the library is told to let
 *   go of the device: the phone does not steer one thing while it shows another.
 * - **It takes down only what it put up**: a jam's view is never cleared from here.
 * - **"Play here" clears the view as the queue is taken, not after.** The library has let
 *   go of the device by the time it asks the player to take its queue, and says so in
 *   what follows the call; the service will not start the phone's own music while its
 *   player shows another's, so the view is gone before the player is asked ([taking]).
 *
 * Main thread only.
 */
internal class LinkSteer(
    private val steered: Steered,
    /** A song by its id, as the phone's copy of the library has it; null for one it lacks. */
    private val songFor: (String) -> Song?,
    /** Tells the library of something pressed while the player shows the device. Whether
     *  it was asked of the device; false, and the library was not steering one. */
    private val press: (JsonObject) -> Boolean,
    /** Tells the library to stop steering, once what it is doing now is done. */
    private val letGo: () -> Unit,
    private val note: (String) -> Unit,
) {
    /** The device the library says the player is steering. */
    private var name: String? = null

    /** `{"steering": name}` or `{"steering": null}`, as the library says it. */
    fun steering(name: String?) {
        this.name = name
        if (name == null) clear()
    }

    /** `{"act":"view","view":…}`: the device as it is to be shown, or nothing. */
    fun view(view: JsonElement) {
        val of = view as? JsonObject
        val name = name
        if (of == null || name == null) return clear()
        val theirs = steered.hands
        if (theirs != null && theirs !== hands) {
            note("linking: the phone's player is showing something else, so the device is let go of")
            return letGo()
        }
        steered.show(shown(of, name, songFor), hands)
    }

    /** The library is about to put the device's queue in the phone's player. */
    fun taking() = clear()

    /** Takes the device out of the phone's player, if it is what is there. */
    fun clear() {
        if (steered.hands === hands) steered.clear()
    }

    private fun pressed(press: JsonElement) = tell(buildJsonObject {
        put("event", "press")
        put("press", press)
    })

    private fun tell(event: JsonObject) {
        if (!press(event)) note("linking: something pressed for the device being steered found none being steered")
    }

    private fun one(name: String, value: JsonElement) = JsonObject(mapOf(name to value))

    /** The player's buttons while it shows the device. */
    val hands = object : Steered.Hands {
        override fun toggle() = pressed(JsonPrimitive("toggle"))
        override fun next() = pressed(JsonPrimitive("next"))
        override fun previous() = pressed(JsonPrimitive("previous"))
        override fun seek(positionMs: Long) = pressed(one("seek", JsonPrimitive(positionMs.coerceAtLeast(0) / 1000.0)))
        override fun remove(uid: Long) {
            place(uid)?.let { pressed(one("remove", JsonPrimitive(it))) }
        }

        override fun move(uid: Long, after: Long?, queued: Boolean) {
            val from = place(uid) ?: return
            // "Straight after `after`" as a place in the queue once the row has left its own.
            val to = if (after == null || after == AppCommands.NONE) 0 else (place(after) ?: return).let { if (it < from) it + 1 else it }
            pressed(one("move", JsonArray(listOf(JsonPrimitive(from), JsonPrimitive(to), JsonPrimitive(queued)))))
        }

        override fun clear(queued: Boolean) = pressed(one("clear", JsonPrimitive(queued)))
        override fun jump(uid: Long) {
            place(uid)?.let { pressed(one("jump", JsonPrimitive(it))) }
        }

        override fun shuffle() = pressed(JsonPrimitive("shuffle"))
        override fun repeat() = pressed(JsonPrimitive("repeat"))
        override fun volume(to: Float) = pressed(one("volume", JsonPrimitive(to.toDouble().coerceIn(0.0, 1.0))))

        override fun add(songs: List<Song>, playNext: Boolean) = tell(buildJsonObject {
            put("event", "add")
            // (No more than one command carries; a song of another player's is not one
            // the music server knows by that id, and is left out.)
            put("ids", JsonArray(songs.asSequence().filterNot(Steered::foreign).take(MOST_ADDED).map { JsonPrimitive(it.id) }.toList()))
            put("next", playNext)
        })

        override fun play(songs: List<Song>, start: Int, context: String, whole: Boolean) {
            if (songs.isEmpty()) return
            val at = start.coerceIn(0, songs.size - 1)
            // A list longer than one message carries is handed over from the song chosen:
            // what the library would cut it to, cut here before it is written out.
            val (from, begin) = if (songs.size > MOST_PLAYED) at to 0 else 0 to at
            val ids = (from until minOf(songs.size, from + MOST_PLAYED)).map { JsonPrimitive(if (Steered.foreign(songs[it])) "" else songs[it].id) }
            tell(buildJsonObject {
                put("event", "play")
                put("ids", JsonArray(ids))
                put("start", begin)
                put("context", context)
            })
        }

        private fun place(uid: Long): Int? = (uid - ROW).takeIf { it in 0 until MOST_SHOWN }?.toInt()
    }

    companion object {
        /** Where the numbers of the device's rows begin: far from any entry of the phone's
         *  own, and from a jam's. A row is named by its place among those shown; the
         *  numbers are only places, made anew with every view. */
        const val ROW = Long.MIN_VALUE / 4

        /** The most rows a view has (the library's `MOST_SHOWN`). */
        const val MOST_SHOWN = 200

        /** The most songs one Add carries (the library's `MOST_ADDED`). */
        const val MOST_ADDED = 50

        /** The most songs of a list played on the device that are written out for the
         *  library to fit to one message: more than a message carries of the shortest
         *  ids a music server gives. */
        const val MOST_PLAYED = 10_000

        /**
         * The library's view of the device (`{"full":{…},"playing","heard_ms"}`) as the
         * player shows it. `heard_ms` is on the clock the library is told the time by,
         * which is the one [Steered.Shown.at] is on.
         */
        fun shown(view: JsonObject, name: String, songFor: (String) -> Song?): Steered.Shown {
            val full = view.getValue("full").jsonObject
            fun song(entry: JsonObject): Song {
                val id = entry.getValue("id").jsonPrimitive.content
                // As the phone's own copy of the library has it (its cover, its album's
                // id); or, for a song this phone has not got, as the device described it.
                return songFor(id) ?: Song(
                    id = id,
                    title = entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artist = entry["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = entry["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    duration = entry["duration"]?.jsonPrimitive?.int ?: 0,
                )
            }
            val now = (full["now"] as? JsonObject)
            val entries = full.getValue("queue").jsonArray.map { it.jsonObject }
            val playing = view.getValue("playing").jsonPrimitive.boolean
            return Steered.Shown(
                song = now?.let(::song),
                playing = playing,
                positionMs = (full.getValue("position").jsonPrimitive.double * 1000).toLong(),
                at = view.getValue("heard_ms").jsonPrimitive.long,
                durationMs = (now?.get("duration")?.jsonPrimitive?.int ?: 0) * 1000L,
                queue = UpNext(
                    songs = entries.map(::song),
                    queuedAhead = entries.takeWhile { it["queued"]?.jsonPrimitive?.booleanOrNull == true }.size,
                    context = full["context"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    total = entries.size + (full["more"]?.jsonPrimitive?.long ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt(),
                    uids = List(entries.size) { ROW + it },
                ),
                shuffle = full.getValue("shuffle").jsonPrimitive.boolean,
                repeat = full.getValue("repeat").jsonPrimitive.int,
                volume = full.getValue("volume").jsonPrimitive.double.toFloat(),
                where = name,
            )
        }
    }
}
