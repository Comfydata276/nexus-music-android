package org.medianexus.music.jam

import androidx.media3.common.Player
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song
import org.medianexus.music.playback.Steered
import org.medianexus.music.store.SecretBox

class RulesTest {
    /** A box that seals by turning the bytes round: enough to see what goes in the file. */
    private object Turned : SecretBox {
        override fun seal(plain: ByteArray) = plain.reversedArray()
        override fun open(sealed: ByteArray) = sealed.reversedArray()
    }

    /** Rule 4: the relay's key belongs to the account it was kept for, and goes at sign-out. */
    @Test
    fun theKeyBelongsToTheAccountAndGoes() {
        val dir = Files.createTempDirectory("key").toFile()
        val vault = KeyVault(File(dir, "relay-key.sealed"), Turned)
        assertTrue(vault.save("https://music.example", "jo", "a-made-up-host-key-1234"))
        assertEquals("a-made-up-host-key-1234", vault.load("https://music.example", "jo"))
        // Another account on this phone can't open it.
        assertNull(vault.load("https://music.example", "sam"))
        assertNull(vault.load("https://other.example", "jo"))
        // Not in the settings file: in a file of its own, and sealed (here, merely turned).
        assertFalse(String(File(dir, "relay-key.sealed").readBytes()).contains("a-made-up-host-key-1234"))
        assertTrue(vault.delete())
        assertFalse(vault.kept())
        assertNull(vault.load("https://music.example", "jo"))
    }

    /**
     * Rule 2: while the phone's player shows another, a headset's (and the notification's)
     * transport steers that one, as Windows' media keys do, and none of it reaches the
     * phone's own player.
     */
    @Test
    fun aHeadsetSteersWhatThePlayerShows() {
        val pressed = ArrayList<String>()
        val hands = object : Steered.Hands {
            override fun toggle() { pressed += "toggle" }
            override fun next() { pressed += "next" }
            override fun previous() { pressed += "previous" }
            override fun seek(positionMs: Long) { pressed += "seek" }
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
        assertTrue(Steered.press(hands, Player.COMMAND_PLAY_PAUSE))
        assertTrue(Steered.press(hands, Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(Steered.press(hands, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        // The rest of a headset's transport is taken too, and does nothing.
        assertTrue(Steered.press(hands, Player.COMMAND_STOP))
        assertTrue(Steered.press(hands, Player.COMMAND_PREPARE))
        assertEquals(listOf("toggle", "next", "previous"), pressed)
        // Anything else is not the headset's: left to the session's own rules.
        assertFalse(Steered.press(hands, Player.COMMAND_SET_MEDIA_ITEM))
    }

    @Test
    fun aJamsLinkBecomesACode() {
        val code = Qr.encode("https://jam.example.org/j/abcdefghijkmnpqrstuvwx")!!
        // 46 bytes: more than version 3 holds at level M (42), so version 4, 33 modules.
        assertEquals(33, code.size)
        // The three finders.
        for ((r, c) in listOf(0 to 0, 0 to 26, 26 to 0)) {
            assertTrue(code[r][c] && code[r + 6][c + 6] && code[r + 3][c + 3] && !code[r + 1][c + 1])
        }
        // Too long for the versions drawn: none, rather than a wrong one.
        assertNull(Qr.encode("x".repeat(300)))
    }
}
