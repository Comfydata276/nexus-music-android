package org.medianexus.music.jam

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Something done to playback while this phone's player shows another's (Windows' `Remote`):
 * it isn't done here, it is asked of the jam's host, or of the person's other device.
 * Entries of the queue are named by their place among the songs after the one playing, as
 * the phone was shown them, and by their song: the host finds the entry by both.
 */
sealed interface Remote {
    data object Toggle : Remote
    data class Play(val on: Boolean) : Remote
    data object Next : Remote
    data object Previous : Remote
    /** Seconds. */
    data class Seek(val to: Double) : Remote
    data class Remove(val at: Int) : Remote
    /** Into "Next in queue" (`queued`) or out of it. */
    data class Move(val from: Int, val to: Int, val queued: Boolean) : Remote
    /** Empty "Next in queue", or else the rest of what's to come. */
    data class Clear(val queued: Boolean) : Remote
    data class Jump(val at: Int) : Remote
    data object Shuffle : Remote
    data object Repeat : Remote
    /** Only between a person's own devices: in a friend's jam the volume is the phone's own. */
    data class Volume(val to: Double) : Remote
}

/** What becomes of a [Remote] (Windows' `remote`, as a plain function). */
sealed interface Steer {
    /** Not the other side's to do: the phone does it itself (the volume, in a friend's jam). */
    data object Here : Steer
    /** The host doesn't let guests steer: say "<host> is choosing what plays…". */
    data object NotAllowed : Steer
    /** It is already so: nothing to ask. */
    data object Already : Steer
    /** Ask for `action` with `data`; the player shows `shown` at once, and the other side's
     *  word on it follows a moment later. */
    data class Ask(val action: String, val data: JsonObject, val shown: JamState) : Steer
}

object Steering {
    /**
     * What `what` asks of the other side, given the jam as last heard (`state`, its song at
     * `position` seconds by now), this guest's id `me`, and whether the other side is one of
     * the person's own devices (`own`), which may always be steered. A guest may always take
     * out what they queued themselves.
     */
    fun steer(state: JamState, position: Double, what: Remote, me: String, own: Boolean): Steer {
        if (what is Remote.Volume && !own) return Steer.Here
        val mine = what is Remote.Remove && state.queue.getOrNull(what.at)?.let { it.byId.isNotEmpty() && it.byId == me } == true
        if (!state.control && !mine && !own) return Steer.NotAllowed
        fun entry(at: Int) = state.queue.getOrNull(at)?.id.orEmpty()
        return when (what) {
            Remote.Toggle, is Remote.Play -> {
                val wanted = if (what is Remote.Play) what.on else !state.playing
                if (wanted == state.playing) return Steer.Already
                Steer.Ask(if (wanted) "play" else "pause", JsonObject(emptyMap()), state.copy(playing = wanted, position = position))
            }
            Remote.Next -> Steer.Ask("skip", JsonObject(emptyMap()), state)
            Remote.Previous -> Steer.Ask("previous", JsonObject(emptyMap()), state)
            is Remote.Seek -> Steer.Ask("seek", buildJsonObject { put("to", what.to) }, state.copy(position = what.to))
            is Remote.Remove -> {
                val id = entry(what.at)
                val queue = if (what.at in state.queue.indices) state.queue.toMutableList().apply { removeAt(what.at) } else state.queue
                Steer.Ask("remove", buildJsonObject { put("at", what.at); put("id", id) }, state.copy(queue = queue))
            }
            is Remote.Move -> {
                val id = entry(what.from)
                val queue = if (what.from in state.queue.indices && what.to in state.queue.indices) {
                    state.queue.toMutableList().apply { add(what.to, removeAt(what.from).copy(queued = what.queued)) }
                } else {
                    state.queue
                }
                Steer.Ask("move", buildJsonObject { put("from", what.from); put("to", what.to); put("queued", what.queued); put("id", id) }, state.copy(queue = queue))
            }
            is Remote.Clear -> Steer.Ask("clear", buildJsonObject { put("queued", what.queued) }, state)
            is Remote.Jump -> Steer.Ask("jump", buildJsonObject { put("at", what.at); put("id", entry(what.at)) }, state)
            Remote.Shuffle -> Steer.Ask("shuffle", buildJsonObject { put("on", !state.shuffle) }, state)
            Remote.Repeat -> Steer.Ask("repeat", buildJsonObject { put("mode", (state.repeat + 1) % 3) }, state)
            is Remote.Volume -> Steer.Ask("volume", buildJsonObject { put("to", what.to) }, state.copy(volume = what.to.coerceIn(0.0, 1.0)))
        }
    }

    /**
     * Where the host's song has got to (Windows' `jam_position`): where it was when last
     * heard, plus the time since if it is playing and the host's app is there.
     */
    fun position(heard: Heard, secondsSinceHeard: Double): Double {
        val state = heard.state
        val duration = (state.now?.duration ?: 0).toDouble()
        val moving = state.playing && !heard.away
        val since = if (moving) heard.age + secondsSinceHeard else 0.0
        return (state.position + since).coerceIn(0.0, duration.coerceAtLeast(0.0))
    }
}
