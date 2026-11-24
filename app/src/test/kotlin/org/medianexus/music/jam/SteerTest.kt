package org.medianexus.music.jam

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a press in the player asks of the jam (Windows' `remote`). */
class SteerTest {
    private fun song(id: String, by: String = "") = JamSong(id, id, "", "", "", 100, queued = by.isNotEmpty(), by = if (by.isEmpty()) "" else "N", byId = by)
    private val state = JamState(playing = true, position = 10.0, control = false, now = song("n"), queue = listOf(song("a", by = "me"), song("b", by = "other"), song("c")))

    @Test
    fun aGuestMayAlwaysTakeOutWhatTheyQueued() {
        val mine = Steering.steer(state, 11.0, Remote.Remove(0), me = "me", own = false)
        assertTrue(mine is Steer.Ask)
        mine as Steer.Ask
        assertEquals("remove", mine.action)
        assertEquals(JsonPrimitive("a"), mine.data["id"])
        assertEquals(listOf("b", "c"), mine.shown.queue.map { it.id })
        // Someone else's, with steering off: not this guest's to do.
        assertEquals(Steer.NotAllowed, Steering.steer(state, 11.0, Remote.Remove(1), me = "me", own = false))
        assertEquals(Steer.NotAllowed, Steering.steer(state, 11.0, Remote.Next, me = "me", own = false))
    }

    @Test
    fun ownDevicesMayAlwaysBeSteered() {
        assertTrue(Steering.steer(state, 11.0, Remote.Next, me = "me", own = true) is Steer.Ask)
        // The volume: the other device's between one's own; the phone's own in a friend's jam.
        assertTrue(Steering.steer(state, 11.0, Remote.Volume(0.5), me = "me", own = true) is Steer.Ask)
        assertEquals(Steer.Here, Steering.steer(state.copy(control = true), 11.0, Remote.Volume(0.5), me = "me", own = false))
    }

    @Test
    fun playingIsShownAtOnceWhereItHadGot() {
        val pause = Steering.steer(state.copy(control = true), 42.0, Remote.Toggle, me = "me", own = false) as Steer.Ask
        assertEquals("pause", pause.action)
        assertEquals(false, pause.shown.playing)
        assertEquals(42.0, pause.shown.position, 0.0)
        assertEquals(Steer.Already, Steering.steer(state.copy(control = true), 42.0, Remote.Play(true), me = "me", own = false))
    }

    @Test
    fun aMoveIsShownAtOnce() {
        val move = Steering.steer(state.copy(control = true), 0.0, Remote.Move(2, 0, queued = true), me = "me", own = false) as Steer.Ask
        assertEquals(listOf("c", "a", "b"), move.shown.queue.map { it.id })
        assertTrue(move.shown.queue[0].queued)
        assertEquals(JsonPrimitive("c"), move.data["id"])
    }

    @Test
    fun theHostsSongMovesOnFromWhereItWasHeard() {
        val heard = Heard(age = 1.0, state = JamState(playing = true, position = 10.0, now = song("n")))
        assertEquals(13.0, Steering.position(heard, 2.0), 1e-9)
        // Not past the song's end, and not at all while the host's app is away or paused.
        assertEquals(100.0, Steering.position(heard, 500.0), 1e-9)
        assertEquals(10.0, Steering.position(heard.copy(away = true), 2.0), 1e-9)
        assertEquals(10.0, Steering.position(heard.copy(state = heard.state.copy(playing = false)), 2.0), 1e-9)
    }
}
