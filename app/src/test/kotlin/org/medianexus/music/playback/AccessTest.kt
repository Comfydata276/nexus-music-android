package org.medianexus.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private val untrustedApp = Access.caller(trusted = false, sameUid = false, ownPackage = false, legacy = false, notification = false)
    private val untrustedBrowser = Access.caller(trusted = false, sameUid = false, ownPackage = false, legacy = true, notification = false)
    private val car = Access.caller(trusted = true, sameUid = false, ownPackage = false, legacy = true, notification = false)
    private val notification = Access.caller(trusted = true, sameUid = true, ownPackage = true, legacy = false, notification = true)
    private val screens = Access.caller(trusted = true, sameUid = true, ownPackage = true, legacy = false, notification = false)

    private val queueEdits = AppActions.all

    @Test
    fun anAppWithNoStandingIsRefusedOutright() {
        assertEquals(Caller.REFUSED, untrustedApp)
        assertEquals(Caller.REFUSED, untrustedBrowser)
        for (action in queueEdits + Access.BUTTONS) assertFalse(action, Access.mayCustom(Caller.REFUSED, action))
        assertFalse(Access.mayPlayer(Caller.REFUSED, Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun aCarBrowsesAndPlaysButCannotUseTheAppsOwnCommands() {
        assertEquals(Caller.TRUSTED, car)
        for (action in Access.BUTTONS) assertTrue(action, Access.mayCustom(car, action))
        for (action in queueEdits) assertFalse(action, Access.mayCustom(car, action))
        // It chooses what plays, and picks from the queue.
        assertTrue(Access.mayPlayer(car, Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(Access.mayPlayer(car, Player.COMMAND_SEEK_TO_MEDIA_ITEM))
    }

    @Test
    fun whatArrivesAsTheNotificationCannotTouchTheQueue() {
        // A start intent sent to the exported service by any app is carried out under this
        // name: it was given every one of the app's own commands.
        assertEquals(Caller.NOTIFICATION, notification)
        for (action in queueEdits) assertFalse(action, Access.mayCustom(notification, action))
        for (action in Access.BUTTONS) assertTrue(action, Access.mayCustom(notification, action))
        // Media3 also turns "custom actions" of its own into player commands, a media item to
        // play among them. Transport only.
        for (command in listOf(
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_PLAYLIST_METADATA,
        )) assertFalse("command $command", Access.mayPlayer(notification, command))
        for (command in listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_STOP,
        )) assertTrue("command $command", Access.mayPlayer(notification, command))
    }

    @Test
    fun onlyTheAppsOwnScreensEditTheQueue() {
        assertEquals(Caller.OWN_INTERFACE, screens)
        for (action in queueEdits + Access.BUTTONS) assertTrue(action, Access.mayCustom(screens, action))
        // The same uid speaking the platform's older protocol (a media button passed on
        // inside the app) is trusted, but is not the app's interface.
        val passedOn = Access.caller(trusted = true, sameUid = true, ownPackage = true, legacy = true, notification = false)
        assertEquals(Caller.TRUSTED, passedOn)
        // Another app claiming to be the notification controller isn't one.
        assertEquals(Caller.TRUSTED, Access.caller(trusted = true, sameUid = false, ownPackage = false, legacy = false, notification = true))
        assertEquals(Caller.REFUSED, Access.caller(trusted = false, sameUid = false, ownPackage = false, legacy = false, notification = true))
        // Nothing unknown is let through as a custom command.
        assertFalse(Access.mayCustom(screens, "org.medianexus.music.app.SOMETHING_ELSE"))
    }

    // ---- The phone's Assistant ----------------------------------------------------------------

    private fun ask(action: String?, vararg extras: Pair<String, String>) = VoiceAsk.read(action) { key -> extras.toMap()[key] }

    @Test
    fun onlyPlayFromSearchIsAccepted() {
        assertNull(ask("android.intent.action.VIEW", "query" to "anything"))
        assertNull(ask(null, "query" to "anything"))
        assertNull(ask("android.media.action.MEDIA_PLAY_FROM_SEARCH ", "query" to "anything"))
        val asked = ask(VoiceAsk.ACTION, "query" to "Slow Rivers", "android.intent.extra.focus" to "vnd.android.cursor.item/album",
            "android.intent.extra.album" to "Slow Rivers")!!
        assertEquals("Slow Rivers", asked.query)
        assertEquals("Slow Rivers", asked.fields["android.intent.extra.album"])
        // "Play music": nothing named.
        assertEquals(VoiceAsk("", emptyMap()), ask(VoiceAsk.ACTION))
    }

    @Test
    fun onlyTheAssistantsOwnFieldsAreReadAndEachIsBounded() {
        val asked = ask(
            VoiceAsk.ACTION,
            "query" to "x".repeat(5_000),
            "android.intent.extra.artist" to "  Paper\u0000 Lanterns\n ",
            "android.intent.extra.title" to "y".repeat(5_000),
            "org.medianexus.music.app.PLAY" to "not a field",
            "songs" to "[]",
        )!!
        assertEquals(VoiceAsk.MOST, asked.query.length)
        assertEquals("Paper Lanterns", asked.fields["android.intent.extra.artist"])
        assertEquals(VoiceAsk.MOST, asked.fields["android.intent.extra.title"]!!.length)
        assertEquals(setOf("android.intent.extra.artist", "android.intent.extra.title"), asked.fields.keys)
    }
}
