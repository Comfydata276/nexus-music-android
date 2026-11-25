package org.medianexus.music.sync

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song
import org.medianexus.music.store.ListPrefs

/**
 * What the phone held before it could keep in step carries no weight. A phone upgraded from
 * the build without syncing has, in its files, times put on by rules since thrown out: pins,
 * list choices and a theme stamped under whichever account was signed in, and "dates added"
 * that are only the moment that build looked at a playlist. The files here are that build's
 * (`instep-upgrade/`: see its ABOUT.txt).
 */
class UpgradeTest {
    /** No test here may wait for ever (see InStepContractTest). */
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val phones = ArrayList<PretendPhone>()

    /** The shared core's "long ago": the time of a choice that had none, so that it is
     *  offered and loses to anything chosen since. */
    private val LONG_AGO = 1L

    @After
    fun stop() {
        phones.forEach { runCatching { it.stop() } }
        scope.cancel()
    }

    private fun fixture(name: String) = javaClass.getResource("/instep-upgrade/$name")!!.readText()
        .replace("PLAYLIST_A", "pl-a").replace("SONG_1", "s1").replace("SONG_2", "s2")

    /** A phone whose files are the earlier build's, opened by this one for `account`. */
    private fun upgraded(account: String = "a", settings: (String) -> String = { it }): PretendPhone {
        val root = folder.newFolder()
        File(root, "settings.json").writeText(settings(fixture("settings.json")))
        File(root, "accounts/$account").mkdirs()
        File(root, "accounts/$account/playlist-added.json").writeText(fixture("playlist-added.json"))
        return PretendPhone(root, account, scope).also { phones += it }
    }

    /** What the person's PC has shared: its own pins, theme and sort for the playlist, and
     *  the day the second song really was added, long before the phone looked. */
    private val pc = Shared(
        layout = 1, pins = listOf("pc-pin"), pinsAt = 1_790_000_000, theme = "Ember", themeAt = 1_790_000_100,
        lists = mapOf("playlist:pl-a" to SharedPrefs(shuffle = false, sort = 3, descending = true, sortChosen = true, at = 1_790_000_200)),
        added = mapOf("pl-a" to mapOf("s2" to SharedNote(listOf(1_780_000_000), 1_780_000_000))),
    )

    @Test
    fun anUpgradedPhoneLeavesThePCsDatesAndChoicesAsTheyWere() = runBlocking {
        val text = SharedText.write(pc)
        val server = PretendServer().apply { put("r", text) }
        val phone = upgraded()
        // What it holds, it still shows; none of it is anything it has to say.
        assertEquals(listOf("pl-a") to "Rose", phone.settings.value.let { it.pinnedPlaylists to it.theme })
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // Nothing written: not its pin over the PC's, not its theme, not its shuffle over the
        // PC's sort, and not "the second song was added when I looked" over the day it was.
        assertTrue(server.writes.isEmpty())
        assertEquals(text, server.comment("r"))
        // The phone has the PC's now, where the PC said anything...
        val s = phone.settings.value
        assertEquals(listOf("pc-pin") to "Ember", s.pinnedPlaylists to s.theme)
        assertEquals(ListPrefs(shuffle = false, sort = 3, descending = true, sortChosen = true, at = 1_790_000_200), s.lists["playlist:pl-a"])
        assertEquals(listOf(0L, 1_780_000_000L), phone.added.dates("pl-a", listOf(Song(id = "s1"), Song(id = "s2"))).toList())
        // ...and its own where the PC said nothing (how Liked songs is sorted on this phone).
        assertEquals(2, s.lists.getValue("liked").sort)
        // The files are marked: opened again, nothing more is done to them.
        phone.stop()
        val again = PretendPhone(phone.root, "a", scope).also { phones += it }
        assertEquals(s, again.settings.value)
        assertEquals(InStep.Outcome.DONE, again.step(server).exchange())
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun anUpgradedPhoneMakesNoRecordOutOfWhatItHeld() = runBlocking {
        // No PC has shared anything yet. Its old pins, theme, list choices and "dates" are
        // not the first thing to go into the person's record.
        val server = PretendServer()
        val phone = upgraded()
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue(server.made.isEmpty() && server.writes.isEmpty())
        // The dates it noticed are still its own "Date added", and none has a time to share.
        assertEquals(listOf(0L, 1_795_000_300L), phone.added.dates("pl-a", listOf(Song(id = "s1"), Song(id = "s2"))).toList())
        assertTrue(phone.added.shared().isEmpty())
        // What the person does from here on is theirs: pinned now, it goes into the record,
        // added to nothing, with the time of the exchange.
        phone.pin("new")
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        val record = SharedText.read(server.comment(server.made.single())!!)!!
        assertEquals(Shared(layout = 1, pins = listOf("new"), pinsAt = PRETEND_NOW), record)
    }

    @Test
    fun choicesTheFirstBuildToKeepInStepPutAnAccountsNameOnAreOfferedAsLongAgo() = runBlocking {
        // That build (never on a real phone) gave the settings' choices to the account that
        // opened them, with the times it had put on whole values. Under this one they are
        // offered, and lose to anything chosen since on any device.
        val owned: (String) -> String = { it.replace("\"sync\": true,", "\"sync\": true,\n    \"shared_for\": \"a\",") }
        val server = PretendServer().apply { put("r", SharedText.write(pc)) }
        val phone = upgraded(settings = owned)
        assertEquals(listOf("pl-a") to "Rose", phone.settings.value.let { it.pinnedPlaylists to it.theme })
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // The PC's pins, theme and sort stand; only the list the PC had nothing for is added.
        val record = SharedText.read(server.comment("r")!!)!!
        assertEquals(pc.copy(lists = pc.lists + ("liked" to SharedPrefs(sort = 2, descending = true, sortChosen = true, at = LONG_AGO))), record)
        // The same put away with an account by that build, and brought out by this one.
        val other = upgraded(account = "b")
        File(other.root, "accounts/a").mkdirs()
        File(other.root, "accounts/a/in-step.json").writeText("""{"pinnedPlaylists":["old"],"pinsAt":1795000100,"lists":{"songs":{"sort":1,"at":1795000000}},"theme":"Ocean","themeAt":1795000200}""")
        other.stop()
        val back = PretendPhone(other.root, "a", scope).also { phones += it }
        // (What it has to say is what it writes where nothing has been said yet.)
        val empty = PretendServer()
        assertEquals(InStep.Outcome.DONE, back.step(empty).exchange())
        assertEquals(
            Shared(layout = 1, pins = listOf("old"), pinsAt = LONG_AGO, theme = "Ocean", themeAt = LONG_AGO, lists = mapOf("songs" to SharedPrefs(sort = 1, at = LONG_AGO))),
            SharedText.read(empty.comment(empty.made.single())!!),
        )
    }
}
