package org.medianexus.music.ui.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.playback.Linked
import org.medianexus.music.ui.link.ConnectView.Kind

/** What the Connect sheet shows of the new linking, and whether it can be reached at all. */
class ConnectViewTest {
    @get:Rule val limit: Timeout = Timeout.seconds(10)

    private fun device(
        name: String,
        place: String = "p-$name",
        about: String = "Nothing playing",
        playing: Boolean = false,
        steered: Boolean = false,
        pickable: Boolean = true,
        update: String = "",
    ) = Linked.Device(place, name, about, playing, steered, pickable, update)

    // ---- where it is reached ------------------------------------------------------------

    @Test
    fun theSheetIsReachedInABuildWithLinkingWhetherOrNotJamsExist() {
        assertTrue(ConnectView.reachable(jams = false, link = true))
        assertTrue(ConnectView.reachable(jams = true, link = true))
        assertTrue("jams alone, as today", ConnectView.reachable(jams = true, link = false))
        assertFalse("neither: nothing is reachable, as today", ConnectView.reachable(jams = false, link = false))
    }

    // ---- the three kinds of row ---------------------------------------------------------

    @Test
    fun aRowHereIsToPickOneNotHereIsDrawnBackOneOfAnotherGenerationIsNeither() {
        assertEquals(Kind.PICK, ConnectView.kind(device("Study PC")))
        assertEquals(Kind.AWAY, ConnectView.kind(device("Laptop", place = "", about = "Not here just now", pickable = false)))
        assertEquals(
            Kind.UPDATE,
            ConnectView.kind(device("Copy B", about = "Paused on Quiet Morning", pickable = false, update = "Update Nexus Music on Copy B to control it from here")),
        )
    }

    @Test
    fun aPauseOnlyBesideOneThatIsPlayingAndCanBePicked() {
        assertTrue(ConnectView.pause(device("Study PC", about = "Playing Harbour Lights", playing = true)))
        assertFalse("not playing", ConnectView.pause(device("Study PC")))
        assertFalse("not here", ConnectView.pause(device("Laptop", playing = true, pickable = false)))
        assertFalse(
            "another generation: no Pause",
            ConnectView.pause(device("Copy B", playing = true, pickable = false, update = "Update Nexus Music on this phone to control it from here")),
        )
    }

    // ---- the device being steered -------------------------------------------------------

    @Test
    fun theDeviceBeingSteeredIsNamedAtTheTopAndLeftOutOfTheList() {
        val state = Linked.State(
            running = true,
            entered = true,
            devices = listOf(device("Study PC", about = "Steering Laptop", playing = true, steered = true), device("Laptop")),
            steering = "Study PC",
        )
        assertEquals(listOf("Laptop"), ConnectView.rows(state).map { it.name })
        assertEquals("Study PC", ConnectView.steering(state))
    }

    @Test
    fun withLinkingStoppedNothingIsOffered() {
        val state = Linked.State(stopped = true, steering = "Study PC", devices = listOf(device("Laptop")))
        assertEquals(emptyList<Linked.Device>(), ConnectView.rows(state))
        assertEquals("no Play here, no Stop steering", "", ConnectView.steering(state))
    }
}
