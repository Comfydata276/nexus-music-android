package org.medianexus.music.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The list of the person's other devices as the screens are handed it ([Linked]). */
class LinkedTest {
    private val shown = Json.parseToJsonElement(
        """{"entered":true,"note":"Paused Study PC.","steering":"Laptop","rows":[
            {"place":"AAAA","name":"Study PC","about":"Playing Harbour Lights","playing":true,"active":false,"ready":true,"update":""},
            {"place":"BBBB","name":"Laptop","about":"Paused on Nordlys","playing":false,"active":true,"ready":true,"update":""},
            {"place":"CCCC","name":"Old PC","about":"Playing Slow Rivers","playing":true,"active":false,"ready":false,"update":"Update Nexus Music on Old PC to control it from here"},
            {"place":"","name":"Tablet","about":"Not here just now","playing":false,"active":false,"ready":false,"update":""}]}""",
    ).jsonObject

    @Test
    fun theListIsReadAsTheLibrarySaysIt() {
        val linked = Linked()
        assertEquals(Linked.State(), linked.state.value)
        linked.shown(shown)
        val state = linked.state.value
        assertEquals(listOf(true, true, "Paused Study PC.", "Laptop", false), listOf(state.running, state.entered, state.note, state.steering, state.stopped))
        assertEquals(
            listOf(
                Linked.Device("AAAA", "Study PC", "Playing Harbour Lights", playing = true, steered = false, pickable = true, update = ""),
                Linked.Device("BBBB", "Laptop", "Paused on Nordlys", playing = false, steered = true, pickable = true, update = ""),
                // One that does not speak as the phone does: listed with what it is
                // playing and which to update, and not to be picked.
                Linked.Device("CCCC", "Old PC", "Playing Slow Rivers", playing = true, steered = false, pickable = false, update = "Update Nexus Music on Old PC to control it from here"),
                Linked.Device("", "Tablet", "Not here just now", playing = false, steered = false, pickable = false, update = ""),
            ),
            state.devices,
        )
    }

    @Test
    fun withNoLinkingEveryCallDoesNothingAndTheListIsEmpty() {
        val linked = Linked()
        linked.choose("AAAA"); linked.pause("AAAA"); linked.playHere(); linked.letGo(); linked.reset(); linked.lookedUp(3)
        assertNull(linked.lookingUp())
        val calls = ArrayList<String>()
        linked.doing = object : Linked.Doing {
            override fun choose(place: String) { calls += "choose $place" }
            override fun pause(place: String) { calls += "pause $place" }
            override fun playHere() { calls += "play here" }
            override fun letGo() { calls += "let go" }
            override fun reset() { calls += "reset" }
            override fun lookingUp(): Long? = 7L.also { calls += "looking up" }
            override fun lookedUp(ticket: Long) { calls += "looked up $ticket" }
        }
        linked.choose("AAAA"); linked.pause("BBBB"); linked.playHere(); linked.letGo(); linked.reset()
        assertEquals(7L, linked.lookingUp())
        linked.lookedUp(7)
        assertEquals(listOf("choose AAAA", "pause BBBB", "play here", "let go", "reset", "looking up", "looked up 7"), calls)
        // The linking ends: for a fault, the list is empty and says it has stopped.
        linked.shown(shown)
        linked.over(stopped = true)
        assertEquals(Linked.State(stopped = true), linked.state.value)
        linked.choose("AAAA")
        assertEquals(7, calls.size)
    }
}
