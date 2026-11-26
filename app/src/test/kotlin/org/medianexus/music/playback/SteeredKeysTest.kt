package org.medianexus.music.playback

import android.view.KeyEvent
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.net.Song
import org.robolectric.RobolectricTestRunner

/**
 * **While the phone steers one of the person's own devices, a control does what it says.**
 * Play is Play and Pause is Pause, to that device, never a toggle; only a key that is itself
 * play/pause (a headset's one button) toggles. The notification's own Play/Pause button,
 * which Media3 sends as that same key, is what it showed: the phone's own player, parked.
 * The system's controls, which Media3 hands over as one command for both, do nothing. A friend's jam,
 * which has no outright Play, is toggled as it always was.
 */
@RunWith(RobolectricTestRunner::class)
class SteeredKeysTest {
    private val did = ArrayList<String>()

    private val hands = object : Steered.Hands {
        override fun toggle() { did += "toggle" }
        override fun next() { did += "next" }
        override fun previous() { did += "previous" }
        override fun seek(positionMs: Long) { did += "seek" }
        override fun remove(uid: Long) {}
        override fun move(uid: Long, after: Long?, queued: Boolean) {}
        override fun clear(queued: Boolean) {}
        override fun jump(uid: Long) {}
        override fun shuffle() {}
        override fun repeat() {}
        override fun volume(to: Float) {}
        override fun add(songs: List<Song>, playNext: Boolean) {}
        override fun play(songs: List<Song>, start: Int, context: String, whole: Boolean) {}
    }

    private val outright = Steered.Outright { on -> did += if (on) "play" else "pause" }

    private fun down(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)

    @Test
    fun aKeyThatSaysPlayOrPauseIsThatToTheDeviceSteered() {
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_MEDIA_PLAY), outright))
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_MEDIA_PAUSE), outright))
        // The headset's one button toggles, whichever key it sends.
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_HEADSETHOOK), outright))
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), outright))
        // The notification's own button, as that same key: what it showed.
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), outright, notificationShowsPlay = true))
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), outright, notificationShowsPlay = false))
        // (And Next and Previous as themselves; a key going up is taken and does nothing.)
        assertTrue(Steered.key(hands, down(KeyEvent.KEYCODE_MEDIA_NEXT), outright))
        assertTrue(Steered.key(hands, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY), outright))
        assertEquals(listOf("play", "pause", "toggle", "toggle", "play", "pause", "next"), did)
    }

    /** The system's Play and Pause arrive as one command that says neither: taken, and
     *  nothing is asked. */
    @Test
    fun theSystemsPlayOrPauseIsNothing() {
        assertTrue(Steered.press(hands, Player.COMMAND_PLAY_PAUSE, outright))
        assertTrue(Steered.press(hands, Player.COMMAND_SEEK_TO_NEXT, outright))
        assertEquals(listOf("next"), did)
    }

    @Test
    fun aFriendsJamIsToggledAsBefore() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
            assertTrue(Steered.key(hands, down(code)))
        }
        assertTrue(Steered.press(hands, Player.COMMAND_PLAY_PAUSE))
        assertEquals(List(5) { "toggle" }, did)
    }
}
