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

    // ---- this phone, at the top -----------------------------------------------------------

    @Test
    fun thePhoneIsNamedAsItsOtherDevicesListItWithJamsTheirNameAsBefore() {
        assertEquals("without jams: the name linking gives it", "Pixel of Sam", ConnectView.ownName(jamName = null, me = "Pixel of Sam"))
        assertEquals("with jams: the jam's name for it, as M6 has it", "Kitchen phone", ConnectView.ownName(jamName = "Kitchen phone", me = "Pixel of Sam"))
        assertEquals("with jams and no name there: linking's", "Pixel of Sam", ConnectView.ownName(jamName = "", me = "Pixel of Sam"))
        assertEquals("no linking, no jams: none (the sheet says \"This phone\")", "", ConnectView.ownName(jamName = null, me = ""))
    }

    // ---- "Playing there" says what is so (review X4) --------------------------------------

    private fun there(where: String, playing: Boolean) = org.medianexus.music.playback.Steered.Shown(
        song = null, playing = playing, positionMs = 0, at = 0, durationMs = 0,
        queue = org.medianexus.music.playback.UpNext(), shuffle = false, repeat = 0, volume = 1f, where = where,
    )

    @Test
    fun aPausedDeviceSteeredIsNotSaidToBePlaying() {
        val state = Linked.State(running = true, entered = true, steering = "Study PC")
        assertEquals(ConnectView.There.PAUSED, ConnectView.there(state, there("Study PC", playing = false)))
        assertEquals(ConnectView.There.PLAYING, ConnectView.there(state, there("Study PC", playing = true)))
        // The player showing something else, and the list saying nothing of it: only the name.
        assertEquals(ConnectView.There.NAME_ONLY, ConnectView.there(state, there("Jo's jam", playing = true)))
    }

    @Test
    fun beforeTheFirstViewTheTopSaysWhatTheListLastSaidOrOnlyTheName() {
        // Picked, and its first full view held: the player shows nothing of it yet.
        val pausedRow = Linked.State(running = true, entered = true, steering = "Study PC", devices = listOf(device("Study PC", about = "Paused on Nordlys", steered = true)))
        assertEquals(ConnectView.There.PAUSED, ConnectView.there(pausedRow, null))
        val playingRow = pausedRow.copy(devices = listOf(device("Study PC", about = "Playing Nordlys", playing = true, steered = true)))
        assertEquals(ConnectView.There.PLAYING, ConnectView.there(playingRow, null))
        // Not in the list: only its name.
        assertEquals(ConnectView.There.NAME_ONLY, ConnectView.there(pausedRow.copy(devices = emptyList()), null))
        // Its view in: that says.
        assertEquals(ConnectView.There.PLAYING, ConnectView.there(pausedRow, there("Study PC", playing = true)))
        assertEquals(ConnectView.There.PAUSED, ConnectView.there(playingRow, there("Study PC", playing = false)))
    }
}
