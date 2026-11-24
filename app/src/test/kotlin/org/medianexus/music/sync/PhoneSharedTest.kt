package org.medianexus.music.sync

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.Song
import org.medianexus.music.store.Added
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.PlayLog
import org.medianexus.music.store.RecentlyPlayed
import org.medianexus.music.store.Settings
import org.medianexus.music.store.SettingsStore

/**
 * What the phone has to say to the person's other devices, and what it takes in from them,
 * over its own stores: the rules about what it may say (docs/DESIGN.md, "In step with other
 * devices"), each with the case that breaks if the rule is taken out.
 */
class PhoneSharedTest {
    /** No test here may wait for ever: one that did would hold the machine's one turn at
     *  building, for everyone, until someone noticed. */
    @get:org.junit.Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val io = Dispatchers.Unconfined
    private val logs = ArrayList<PlayLog>()

    @After
    fun stop() {
        runBlocking { logs.forEach { it.close().join() } }
        scope.cancel()
    }

    private val settings by lazy { SettingsStore.open(File(folder.root, "settings.json"), scope, io) }

    private fun dataOf(account: String) = File(folder.root, "accounts/$account").apply { mkdirs() }

    /** An account's stores on the phone, and its side of an exchange. `read`: its history
     *  and log have been read from the phone. */
    private inner class Phone(val account: String, var read: Boolean = true, var playlists: Set<String>? = null) {
        val added = Added(File(dataOf(account), "playlist-added.json"), null, io)
        val history = RecentlyPlayed(File(dataOf(account), "history.json"), scope, io) { 1_795_000_000 }
        val log = PlayLog(File(dataOf(account), "plays.log"), io).also { logs += it }
        val asked = ArrayList<String>()
        val shared = PhoneShared(settings, account, added, history, log, read = { read }, playlists = { playlists }, songs = { ids -> asked += ids; ids.filter { it != "deleted" }.associateWith { Song(id = it, title = "Song $it") } })

        init {
            Belongs.takeOn(settings, account, ::dataOf)
            runBlocking {
                history.open()
                log.open()
            }
        }

        /** The account goes: its log lets its file go, for whoever opens it next. */
        fun close() = runBlocking { log.close().join() }

        fun record() = runBlocking { shared.record() }
        fun take(theirs: Shared) = runBlocking { shared.take(theirs) }
    }

    private val pc = Shared(
        layout = 1, pins = listOf("p1", "p2"), pinsAt = 1_700_000_000, theme = "Ember", themeAt = 1_700_000_100,
        lists = mapOf("playlist:p1" to SharedPrefs(shuffle = true, sort = 3u, descending = true, sortChosen = true, at = 1_700_000_200)),
        added = mapOf("p1" to mapOf("s1" to SharedNote(listOf(1_700_000_300), 1_700_000_300))),
        history = listOf(Play("s2", 1_700_000_500), Play("s1", 1_700_000_400)),
        plays = listOf(Play("s2", 1_700_000_500), Play("s1", 1_700_000_400)),
        playSources = listOf(PlayFrom("s1", 1_700_000_400, "playlist:p1")),
    )

    // ---- rule 1: no time on what the person did not change here ---------------------------------

    @Test
    fun aFreshPhoneHasNothingToSayAndAddsNothingToAPCsRecord() {
        val phone = Phone("a")
        val record = phone.record()!!
        // No pins, no time on them; the default theme with no time on it; nothing else.
        assertEquals(Shared(layout = 1, theme = "Violet"), record)
        // Put together with what a PC wrote, by the one function the app uses: nothing to write.
        val settled = KotlinSettler.settle(SharedText.write(pc), SharedText.write(record), 1_795_000_000) as Settled.Merged
        assertFalse(settled.write)
        assertEquals(pc, SharedText.read(settled.text))
    }

    @Test
    fun whatIsTakenInIsSharedBackWithTheTimeItCameWith() {
        val phone = Phone("a")
        phone.take(pc)
        // Everything the PC had is here now...
        val s = settings.value
        assertEquals(listOf("p1", "p2") to 1_700_000_000L, s.pinnedPlaylists to s.pinsAt)
        assertEquals("Ember" to 1_700_000_100L, s.theme to s.themeAt)
        assertEquals(ListPrefs(shuffle = true, sort = 3, descending = true, sortChosen = true, at = 1_700_000_200), s.lists["playlist:p1"])
        assertEquals(listOf("s2", "s1"), phone.history.state.value.map { it.song.id })
        assertEquals(listOf("s1" to 1_700_000_400L, "s2" to 1_700_000_500L), phone.log.shared())
        // ...and what the phone then has to say is exactly that, with the PC's own times: no
        // "now" on any of it, so nothing to write.
        assertEquals(pc, phone.record())
        val settled = KotlinSettler.settle(SharedText.write(pc), SharedText.write(phone.record()!!), 1_795_000_000) as Settled.Merged
        assertFalse(settled.write)
    }

    @Test
    fun theDefaultThemeIsNeverGivenATime() {
        assertEquals("Violet" to 0L, Sharing.theme(Settings()))
        // A theme with no time that isn't the default was chosen before times were kept:
        // passed on as "long ago", which loses to anything chosen since. Never now.
        assertEquals("Ocean" to LONG_AGO, Sharing.theme(Settings(theme = "Ocean")))
        assertEquals("Ocean" to 77L, Sharing.theme(Settings(theme = "Ocean", themeAt = 77)))
        // The look another account left the phone in is not this account's to tell of.
        assertEquals("" to 0L, Sharing.theme(Settings(theme = "Ocean", themeAt = 77, themeOwn = false)))
        // Nor a name Windows doesn't have.
        assertEquals("" to 0L, Sharing.theme(Settings(theme = "Sunset", themeAt = 77)))
        // Pins: none, with no time, is "never"; some, with no time, "long ago".
        assertEquals(0L, Sharing.pinsAt(Settings()))
        assertEquals(LONG_AGO, Sharing.pinsAt(Settings(pinnedPlaylists = listOf("p"))))
        assertEquals(9L, Sharing.pinsAt(Settings(pinnedPlaylists = emptyList(), pinsAt = 9)))
    }

    // ---- rule 2: never what it has not loaded -----------------------------------------------------

    @Test
    fun nothingIsSharedBeforeItHasBeenReadFromThePhoneForThisAccount() {
        // The history and the log not read yet.
        val phone = Phone("a", read = false)
        assertNull(phone.record())
        phone.read = true
        assertTrue(phone.record() != null)
        // The choices in the settings are another account's (this one not yet taken on).
        settings.update { it.copy(sharedFor = "someone-else", pinnedPlaylists = listOf("theirs"), pinsAt = 5) }
        assertNull(phone.record())
        // And nothing is taken into them either.
        phone.take(pc)
        assertEquals(listOf("theirs"), settings.value.pinnedPlaylists)
        assertTrue(phone.history.state.value.isEmpty())
    }

    @Test
    fun aListOfPlaylistsNotLoadedIsNotTakenToMeanThereAreNone() {
        val phone = Phone("a")
        phone.added.changed("p1", emptyList(), listOf("s1"), 100)
        phone.added.changed("gone", emptyList(), listOf("s2"), 100)
        // Not kept, or kept empty: there's no telling which playlists still exist.
        assertEquals(setOf("p1", "gone"), phone.record()!!.added.keys)
        phone.playlists = emptySet()
        assertEquals(setOf("p1", "gone"), phone.record()!!.added.keys)
        // Loaded: notes for playlists that no longer exist aren't passed on.
        phone.playlists = setOf("p1", "other")
        assertEquals(setOf("p1"), phone.record()!!.added.keys)
    }

    // ---- rule 3: only what Windows understands ------------------------------------------------------

    @Test
    fun aSortOrAListThePhoneAloneHasIsNotShared() {
        val lists = mapOf(
            "playlist:p1" to ListPrefs(shuffle = true, sort = 5, descending = true, sortChosen = true, at = 10),
            // An order Windows has no number for.
            "playlist:p2" to ListPrefs(sort = 6, at = 11),
            "liked" to ListPrefs(sort = -1, at = 12),
            // The phone's own lists.
            "downloads:album:al1" to ListPrefs(shuffle = true, at = 13),
            "downloads:liked" to ListPrefs(shuffle = true, at = 14),
            "radio" to ListPrefs(shuffle = true, at = 15),
            "mix:rule:3" to ListPrefs(shuffle = true, at = 16),
            // Windows' own keys.
            "songs" to ListPrefs(sort = 1, at = 17), "downloads" to ListPrefs(shuffle = true, at = 18), "album:al1" to ListPrefs(shuffle = true, at = 19),
            "artist:ar1" to ListPrefs(shuffle = true, at = 20), "genre:Rock" to ListPrefs(shuffle = true, at = 21), "mix:on-repeat" to ListPrefs(shuffle = true, at = 22),
            "playlist:" to ListPrefs(shuffle = true, at = 23),
        )
        val shared = Sharing.choices(Settings(lists = lists)).lists
        assertEquals(setOf("playlist:p1", "songs", "downloads", "album:al1", "artist:ar1", "genre:Rock", "mix:on-repeat"), shared.keys)
        assertEquals(SharedPrefs(shuffle = true, sort = 5u, descending = true, sortChosen = true, at = 10), shared["playlist:p1"])
        // The shared value is left as it was: merged with a PC's record, the PC's choice for
        // the list whose order the phone couldn't say stays the PC's.
        val server = Shared(layout = 1, lists = mapOf("playlist:p2" to SharedPrefs(sort = 2u, at = 5)))
        assertEquals(SharedPrefs(sort = 2u, at = 5), Merge.merge(Sharing.choices(Settings(lists = lists)), server, 0).lists["playlist:p2"])
    }

    @Test
    fun aThemeThePhoneDoesNotHaveIsLeftForTheDevicesThatDo() {
        val phone = Phone("a")
        settings.update { it.copy(theme = "Ocean", themeAt = 50) }
        phone.take(Shared(layout = 1, theme = "Sunset", themeAt = 99))
        assertEquals("Ocean" to 50L, settings.value.theme to settings.value.themeAt)
    }

    // ---- taking in ------------------------------------------------------------------------------

    @Test
    fun aChoiceMadeHereSinceIsNewerThanWhatComesBack() {
        val phone = Phone("a")
        phone.take(pc)
        // Unpinned on the phone, later than the PC's pins.
        settings.update { it.copy(pinnedPlaylists = listOf("p2"), pinsAt = 1_700_000_900) }
        phone.take(pc)
        assertEquals(listOf("p2"), settings.value.pinnedPlaylists)
        assertEquals(listOf("p2") to 1_700_000_900L, phone.record()!!.let { it.pins to it.pinsAt })
        // A PC's later change wins in its turn.
        phone.take(pc.copy(pins = emptyList(), pinsAt = 1_700_001_000))
        assertTrue(settings.value.pinnedPlaylists.isEmpty())
    }

    @Test
    fun aSongInAnotherDevicesHistoryThatCantBeFoundIsLeftOutHereAndStaysShared() {
        val phone = Phone("a")
        phone.take(pc.copy(history = listOf(Play("s2", 500), Play("deleted", 450), Play("s1", 400))))
        assertEquals(listOf("s2", "s1"), phone.history.state.value.map { it.song.id })
        assertEquals("Song s2", phone.history.state.value[0].song.title)
        // The phone shares what it has; merged with the record, the play it couldn't show is still there.
        val merged = Merge.mergeHistory(phone.record()!!.history, listOf(Play("s2", 500), Play("deleted", 450), Play("s1", 400)))
        assertEquals(listOf("s2", "deleted", "s1"), merged.map { it.song })
    }

    @Test
    fun playsHeardOfFromOtherDevicesAreCountedOnceEach() {
        val phone = Phone("a")
        phone.log.add("mine", 600, "playlist:p9")
        phone.take(pc)
        phone.take(pc)
        assertEquals(listOf("mine" to 600L, "s1" to 1_700_000_400L, "s2" to 1_700_000_500L), phone.log.shared())
        val record = phone.record()!!
        assertEquals(listOf(Play("s2", 1_700_000_500), Play("s1", 1_700_000_400), Play("mine", 600)), record.plays)
        assertEquals(setOf(PlayFrom("s1", 1_700_000_400, "playlist:p1"), PlayFrom("mine", 600, "playlist:p9")), record.playSources.toSet())
    }

    @Test
    fun onlyTheLatestFiveHundredPlaysAreShared() {
        val log = (0 until 620).map { "s${it % 7}" to 1_000L + it }
        val from = listOf(Triple("s0", 1_000L, "playlist:old"), Triple("s3", 1_619L, "playlist:new"))
        val (plays, sources) = Sharing.plays(log, from)
        assertEquals(PLAYS_SHARED, plays.size)
        assertEquals(Play("s3", 1_619), plays.first())
        assertEquals(1_120L, plays.last().at)
        // A source is passed on only with its play.
        assertEquals(listOf(PlayFrom("s3", 1_619, "playlist:new")), sources)
    }

    // ---- an account's choices are its own ---------------------------------------------------------

    @Test
    fun nothingChosenUnderOneAccountIsSharedIntoAnothersRecord() {
        val first = Phone("a")
        settings.update { it.copy(pinnedPlaylists = listOf("pa"), pinsAt = 100, theme = "Rose", themeAt = 110, themeOwn = true, lists = mapOf("liked" to ListPrefs(shuffle = true, at = 120)), syncPlaylist = "sync-a") }
        first.history.played(Song(id = "heard-by-a"))
        first.log.add("heard-by-a", 130)
        first.added.changed("pa", emptyList(), listOf("s"), 140)
        assertEquals(listOf("pa"), first.record()!!.pins)

        // Signed out, and in as someone else.
        val second = Phone("b")
        // The first account's objects, if any is still about, say nothing and take nothing in.
        assertNull(first.record())
        // The second account: no pins, no list choices, no notes, no plays; and the theme the
        // phone was left showing is not its to tell of. Exactly a fresh phone's record.
        assertEquals(Shared(layout = 1), second.record())
        assertEquals("", second.shared.playlist)
        // (The phone still looks as it did.)
        assertEquals("Rose", settings.value.theme)
        // A theme chosen now is this account's.
        settings.update { it.copy(theme = "Green", themeAt = 200, themeOwn = true) }
        assertEquals("Green" to 200L, second.record()!!.let { it.theme to it.themeAt })

        // Back as the first: its own choices are as they were, the theme it chose with them.
        first.close()
        val again = Phone("a")
        val s = settings.value
        assertEquals(listOf("pa") to 100L, s.pinnedPlaylists to s.pinsAt)
        assertEquals(Triple("Rose", 110L, true), Triple(s.theme, s.themeAt, s.themeOwn))
        assertEquals(mapOf("liked" to ListPrefs(shuffle = true, at = 120)), s.lists)
        assertEquals("sync-a", again.shared.playlist)
    }

    @Test
    fun choicesFromBeforeAccountsOwnedThemBelongToWhoeverIsSignedIn() {
        // Settings as an earlier version left them: no owner.
        settings.update { Settings(pinnedPlaylists = listOf("p"), pinsAt = 5, theme = "Ocean", themeAt = 6) }
        val phone = Phone("a")
        assertEquals("a", settings.value.sharedFor)
        assertEquals(listOf("p") to 5L, phone.record()!!.let { it.pins to it.pinsAt })
        assertEquals("Ocean" to 6L, phone.record()!!.let { it.theme to it.themeAt })
    }
}
