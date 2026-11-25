package org.medianexus.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private val untrustedApp = Access.caller(trusted = false, knownHost = false, sameUid = false, ownPackage = false, legacy = false, notification = false)
    private val untrustedBrowser = Access.caller(trusted = false, knownHost = false, sameUid = false, ownPackage = false, legacy = true, notification = false)
    private val car = Access.caller(trusted = true, knownHost = false, sameUid = false, ownPackage = false, legacy = true, notification = false)
    private val notification = Access.caller(trusted = true, knownHost = false, sameUid = true, ownPackage = true, legacy = false, notification = true)
    private val screens = Access.caller(trusted = true, knownHost = false, sameUid = true, ownPackage = true, legacy = false, notification = false)

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
    fun anAppThePersonAllowedGetsWhatAHeadsetGetsAndWhatIsPlaying() {
        // Allowed in Settings, by who signed it; nothing else vouches for it.
        val watch = Access.caller(trusted = false, knownHost = false, sameUid = false, ownPackage = false, legacy = false, notification = false, allowed = true)
        assertEquals(Caller.ALLOWED, watch)
        // Whatever else is true of a caller counts first: the person's list never makes a
        // car or the app's own screens into less than they are.
        assertEquals(Caller.TRUSTED, Access.caller(trusted = true, knownHost = false, sameUid = false, ownPackage = false, legacy = true, notification = false, allowed = true))
        // Transport, and nothing that chooses what plays or how.
        for (command in Access.TRANSPORT) assertTrue("command $command", Access.mayPlayer(watch, command))
        for (command in listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)) {
            assertFalse("command $command", Access.mayPlayer(watch, command))
        }
        // None of the buttons, and none of the app's own commands.
        for (action in queueEdits + Access.BUTTONS) assertFalse(action, Access.mayCustom(watch, action))
        // What it may use of the player at all: transport, the song playing and its details.
        assertEquals(Access.TRANSPORT + setOf(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA), Access.ALLOWED_PLAYER)
        assertFalse(Player.COMMAND_GET_TIMELINE in Access.ALLOWED_PLAYER)
    }

    @Test
    fun whatArrivesAsTheNotificationCannotTouchTheQueue() {
        // A start intent sent to the exported service by any app is carried out under this
        // name: it was given every one of the app's own commands.
        assertEquals(Caller.NOTIFICATION, notification)
        for (action in queueEdits) assertFalse(action, Access.mayCustom(notification, action))
        // Like, shuffle and repeat under this name are the system's own media controls
        // (Media3 reports System UI as the notification controller). A start intent shaped
        // like one of those buttons no longer becomes a command at all: `ForgedStartTest`.
        for (action in Access.BUTTONS) assertTrue(action, Access.mayCustom(notification, action))
        // As player commands, which a media-button intent from any app is carried out as,
        // shuffle and repeat are not transport.
        for (command in listOf(Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)) assertFalse("command $command", Access.mayPlayer(notification, command))
        assertTrue(Access.mayPlayer(car, Player.COMMAND_SET_SHUFFLE_MODE))
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
        val passedOn = Access.caller(trusted = true, knownHost = false, sameUid = true, ownPackage = true, legacy = true, notification = false)
        assertEquals(Caller.TRUSTED, passedOn)
        // Another app claiming to be the notification controller isn't one.
        assertEquals(Caller.TRUSTED, Access.caller(trusted = true, knownHost = false, sameUid = false, ownPackage = false, legacy = false, notification = true))
        assertEquals(Caller.REFUSED, Access.caller(trusted = false, knownHost = false, sameUid = false, ownPackage = false, legacy = false, notification = true))
        // Nothing unknown is let through as a custom command.
        assertFalse(Access.mayCustom(screens, "org.medianexus.music.app.SOMETHING_ELSE"))
    }

    // ---- Carrying on ---------------------------------------------------------------------------

    @Test
    fun swipingTheAppAwayOnlyStopsMusicThatWasPaused() {
        fun carriesOn(playWhenReady: Boolean, state: Int, waiting: Boolean = false) = Recovery.carriesOnWhenSwipedAway(playWhenReady, state, waiting)
        assertTrue(carriesOn(true, Player.STATE_READY))
        // Buffering, and silent for a navigation prompt or a call (still READY, still meaning
        // to play): Media3 asks "is it sounding right now?" and would stop both for good.
        assertTrue(carriesOn(true, Player.STATE_BUFFERING))
        // Waiting for the server to come back.
        assertTrue(carriesOn(true, Player.STATE_IDLE, waiting = true))
        // Paused by the person, at whatever stage.
        assertFalse(carriesOn(false, Player.STATE_READY))
        assertFalse(carriesOn(false, Player.STATE_BUFFERING))
        assertFalse(carriesOn(false, Player.STATE_IDLE, waiting = true))
        // Nothing going on at all.
        assertFalse(carriesOn(true, Player.STATE_IDLE))
        assertFalse(carriesOn(true, Player.STATE_ENDED))
    }

    @Test
    fun aRetryPutOffIsOnlyForTheAccountAndSongItWasPutOffFor() {
        val retry = Recovery.Retry(generation = 3, uid = 42)
        assertTrue(retry.stillStands(3, 42, failed = true))
        // The person skipped to another song meanwhile.
        assertFalse(retry.stillStands(3, 43, failed = true))
        // They signed out, or someone else signed in.
        assertFalse(retry.stillStands(null, 42, failed = true))
        assertFalse(retry.stillStands(4, 42, failed = true))
        // It came right by itself.
        assertFalse(retry.stillStands(3, 42, failed = false))
        assertFalse(retry.stillStands(3, null, failed = true))
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

    @Test
    fun waitingForTheServerEndsBeforeTheForegroundDoes() {
        val every = 15_000L
        // Trying every fifteen seconds, for as long as the service keeps its standing with
        // nothing playing (ten minutes): it used to try for ever, long after Android had
        // stopped treating it as a player.
        assertFalse(Recovery.givesUp(waitedMs = 0, nextWaitMs = every))
        assertFalse(Recovery.givesUp(waitedMs = 9 * 60_000L, nextWaitMs = every))
        assertFalse(Recovery.givesUp(waitedMs = 10 * 60_000L - every, nextWaitMs = every))
        // The try after that would fall past the ten minutes.
        assertTrue(Recovery.givesUp(waitedMs = 10 * 60_000L - every + 1, nextWaitMs = every))
        assertTrue(Recovery.givesUp(waitedMs = 3 * 60 * 60_000L, nextWaitMs = every))
        assertEquals(androidx.media3.session.MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS, Recovery.GIVE_UP_MILLIS)
    }
}
