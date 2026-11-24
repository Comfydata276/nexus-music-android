package org.medianexus.music.playback

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Songs queued by hand reach the player as the screen had them, not written out and read
 *  back on the main thread (M3b fixes, E). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EnqueueArgsTest {
    @Test
    fun aLargeSelectionIsLeftForThePlayerNotWrittenOut() {
        val songs = List(5_000) { Song(id = "s$it", title = "Song $it") }
        val args = AppCommands.enqueueArgs(songs, playNext = false)
        assertNull(args.getString("songs"))
        assertSame(songs, AppCommands.enqueuedOf(args))
    }

    @Test
    fun aFewAreWrittenOutAsWellInCaseTheyAreNotThereToTake() {
        val songs = listOf(Song(id = "a", title = "A"))
        val args = AppCommands.enqueueArgs(songs, playNext = true)
        assertTrue(args.getBoolean("playNext"))
        // As if the list left had gone (taken already, or pushed out by others).
        assertEquals(songs, AppCommands.enqueuedOf(Bundle(args).apply { putLong("drawn", -1) }))
    }
}
