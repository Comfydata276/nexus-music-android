package org.medianexus.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.net.Song
import org.medianexus.music.ui.player.SeekDrag

/** A seek drag on a device being steered is for the song it began on (review X3). */
class SteeredEntryTest {
    @get:Rule val limit: Timeout = Timeout.seconds(10)

    private fun on(id: String) = Steered.Shown(
        song = Song(id = id, title = id, duration = 300), playing = true, positionMs = 1_000, at = 0, durationMs = 300_000,
        queue = UpNext(), shuffle = false, repeat = 0, volume = 1f, where = "Study PC",
    )

    @Test
    fun aDragBegunOnAIsDroppedWhenTheDeviceMovesToB() {
        val drag = SeekDrag(PlayerConnection.steeredEntry(on("a")))
        drag.move(0.6f)
        // The device has moved on to B before the finger is lifted: nothing is sought.
        assertNull(drag.end(0.6f, PlayerConnection.steeredEntry(on("b"))))
    }

    @Test
    fun aDragOnTheSongStillPlayingIsSought() {
        val drag = SeekDrag(PlayerConnection.steeredEntry(on("a")))
        drag.move(0.6f)
        // A later view of the same song (its place moved on) is the same entry.
        assertEquals(0.6f, drag.end(0.6f, PlayerConnection.steeredEntry(on("a").copy(positionMs = 9_000, at = 8_000))))
    }

    @Test
    fun aSteeredSongIsNeverTheNoEntryOfAnEmptyPlayer() {
        assertNotEquals(AppCommands.NONE, PlayerConnection.steeredEntry(on("a")))
        assertEquals(AppCommands.NONE, PlayerConnection.steeredEntry(on("a").copy(song = null)))
    }
}
