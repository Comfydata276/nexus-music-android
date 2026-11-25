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

    /** A phone whose files are 0.4.0's (it had keeping in step built and switched off, and
     *  kept the person's changes waiting), opened by this build for the account "a". */
    private fun from040(settings: (String) -> String = { it }): PretendPhone {
        val root = folder.newFolder()
        File(root, "settings.json").writeText(settings(fixture("settings-0.4.0.json").replace("ACCOUNT", "a").replace("PLAYLIST_B", "pl-b").replace("PLAYLIST_C", "pc-pin")))
        return PretendPhone(root, "a", scope).also { phones += it }
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

    @Test
    fun whatWasChosenOn040LosesToAnythingChosenSinceAndItsPinsAreAddedToThePCs() = runBlocking {
        // 0.4.0 shared nothing a person could see: a theme or a sort chosen there was never
        // a statement about the PCs, which hold the person's real choices.
        val text = SharedText.write(pc)
        val server = PretendServer().apply { put("r", text) }
        val phone = from040()
        // Until it has heard from the server, it shows what it showed.
        assertEquals(listOf("pl-b") to "Rose", phone.settings.value.let { it.pinnedPlaylists to it.theme })
        assertEquals(Belongs.MARK, phone.settings.value.stepMark)
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        val record = SharedText.read(server.comment("r")!!)!!
        // The playlist pinned on the phone is added to the PC's pins: a pin is something the
        // person did, and it replaces nothing. The unpin that was waiting took back a pin
        // of the phone's own, and does not take the PC's.
        assertEquals(listOf("pc-pin", "pl-b"), record.pins)
        // The PC's theme and its sort for the playlist stand, with the times they had.
        assertEquals("Ember" to pc.themeAt, record.theme to record.themeAt)
        assertEquals(pc.lists.getValue("playlist:pl-a"), record.lists.getValue("playlist:pl-a"))
        // The shuffle of a list the PC has said nothing about is offered, as chosen long ago.
        assertEquals(SharedPrefs(shuffle = true, at = LONG_AGO), record.lists.getValue("liked"))
        assertEquals(pc.added, record.added)
        // And the phone now shows what every device shows.
        val s = phone.settings.value
        assertEquals(listOf("pc-pin", "pl-b") to "Ember", s.pinnedPlaylists to s.theme)
        assertEquals(3 to true, s.lists.getValue("playlist:pl-a").let { it.sort to it.descending })
        assertTrue(s.lists.getValue("liked").shuffle)
        // Nothing is left waiting: a second exchange writes nothing.
        val wrote = server.writes.size
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(wrote, server.writes.size)
    }

    @Test
    fun whatWasChosenOn040IsWhatGoesIntoARecordNobodyHasWrittenYet() = runBlocking {
        // No PC has shared anything: then the phone's are the only choices there are.
        val server = PretendServer()
        val phone = from040()
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(
            Shared(
                layout = 1, pins = listOf("pl-b"), pinsAt = PRETEND_NOW, theme = "Rose", themeAt = LONG_AGO,
                lists = mapOf("liked" to SharedPrefs(shuffle = true, at = LONG_AGO), "playlist:pl-a" to SharedPrefs(sort = 4, descending = true, sortChosen = true, at = LONG_AGO)),
            ),
            SharedText.read(server.comment(server.made.single())!!),
        )
        assertEquals(listOf("pl-b") to "Rose", phone.settings.value.let { it.pinnedPlaylists to it.theme })
    }

    @Test
    fun aListThePCHasChosenAnythingForSinceIsThePCsWholeAndADefaultThemeChosenOn040IsOfferedToo() = runBlocking {
        // A list's choices have one time between them: the PC shuffled the playlist, with no
        // sort of its own, after 0.4.0's sort was chosen. The PC's entry is the newer, whole:
        // the phone's sort goes with it. And nobody has chosen a theme on a PC: Violet,
        // chosen on the phone on purpose (it is also the theme nobody chose), is offered.
        val theirs = Shared(layout = 1, lists = mapOf("playlist:pl-a" to SharedPrefs(shuffle = true, at = 1_790_000_200)))
        val server = PretendServer().apply { put("r", SharedText.write(theirs)) }
        val phone = from040 { it.replace("Rose", "Violet") }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        val record = SharedText.read(server.comment("r")!!)!!
        assertEquals(theirs.lists.getValue("playlist:pl-a"), record.lists.getValue("playlist:pl-a"))
        assertEquals("Violet" to LONG_AGO, record.theme to record.themeAt)
        assertEquals(listOf("pl-b"), record.pins)
        val shown = phone.settings.value.lists.getValue("playlist:pl-a")
        assertEquals(Triple(true, 0, false), Triple(shown.shuffle, shown.sort, shown.sortChosen))
        // A theme a PC then chooses is the newer.
        val chosen = record.copy(theme = "Ember", themeAt = 1_790_000_900)
        server.put("r", SharedText.write(chosen))
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals("Ember", phone.settings.value.theme)
        assertEquals(chosen, SharedText.read(server.comment("r")!!))
    }

    @Test
    fun whatWasPutAwayWithAnAccountBy040IsTakenOnTheSameWay() = runBlocking {
        // 0.4.0 put an account's choices away with it when another signed in (`in-step.json`,
        // as that build wrote it): brought out by this build, they are what its settings
        // would have been.
        // (The settings are another account's, "b", as 0.4.0 left them.)
        val root = folder.newFolder()
        File(root, "settings.json").writeText(fixture("settings-0.4.0.json").replace("ACCOUNT", "b").replace("PLAYLIST_B", "pl-b").replace("PLAYLIST_C", "pc-pin"))
        File(root, "accounts/a").mkdirs()
        File(root, "accounts/a/in-step.json").writeText(
            """{"pinnedPlaylists":["mine"],"lists":{"liked":{"shuffle":true,"sort":0,"descending":false,"sortChosen":false,"at":1795000400}},"theme":"Rose",
               "agreed":{"pins":[],"pinsAt":0,"theme":"","themeAt":0,"lists":{}},
               "waiting":[{"what":"pin","key":"mine","on":false,"sort":0,"descending":false},{"what":"shuffle","key":"liked","on":true,"sort":0,"descending":false},{"what":"theme","key":"Rose","on":false,"sort":0,"descending":false}],"mark":1}""",
        )
        val back = PretendPhone(root, "a", scope).also { phones += it }
        assertEquals(listOf("mine") to "Rose", back.settings.value.let { it.pinnedPlaylists to it.theme })
        val server = PretendServer().apply { put("r", SharedText.write(pc)) }
        assertEquals(InStep.Outcome.DONE, back.step(server).exchange())
        val record = SharedText.read(server.comment("r")!!)!!
        assertEquals(listOf("pc-pin", "mine"), record.pins)
        assertEquals("Ember" to pc.themeAt, record.theme to record.themeAt)
        assertEquals(SharedPrefs(shuffle = true, at = LONG_AGO), record.lists.getValue("liked"))
        assertEquals("Ember", back.settings.value.theme)
    }
}
