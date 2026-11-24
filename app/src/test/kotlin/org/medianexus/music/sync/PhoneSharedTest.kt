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
        /** As an exchange does it: kept (the plays, on the disk), then taken in. */
        fun take(theirs: Shared) = runBlocking {
            shared.keep(theirs)
            shared.take(theirs)
        }
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
        // No pins, no theme, no list's choices, no time on anything: nothing at all.
        assertEquals(Shared(layout = 1), record)
        assertFalse(record.says)
        // Put together with what a PC wrote, by the one function the app uses: nothing to write.
        val settled = KotlinSettler.settle(SharedText.write(pc), SharedText.write(record), 1_795_000_000) as Settled.Merged
        assertFalse(settled.write)
        assertEquals(pc, SharedText.read(settled.text))
    }

    @Test
    fun whatIsTakenInIsSharedBackWithTheTimeItCameWith() {
        val phone = Phone("a")
        phone.take(pc)
        // Everything the PC had is here now, shown...
        val s = settings.value
        assertEquals(listOf("p1", "p2"), s.pinnedPlaylists)
        assertEquals("Ember", s.theme)
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
    fun whatThePhoneShowsIsNotWhatItOffers() {
        val phone = Phone("a")
        // Pins, a theme and a list's choices on the phone that were never agreed with the
        // server (they were there before it could keep in step, or the person has just made
        // them): shown, and not offered as the pins, the theme or the list's choices.
        settings.update { it.copy(pinnedPlaylists = listOf("mine"), theme = "Ocean", lists = mapOf("liked" to ListPrefs(shuffle = true, sort = 2, at = 50))) }
        settings.update { Changes.pin(it, "also-mine", on = true) }
        assertEquals(Shared(layout = 1), phone.record())
        // What was agreed is offered, with the times it has in the record and no others.
        phone.take(pc)
        assertEquals(pc.pins to pc.pinsAt, phone.record()!!.let { it.pins to it.pinsAt })
        assertEquals(pc.theme to pc.themeAt, phone.record()!!.let { it.theme to it.themeAt })
        // The phone goes on showing its own where the record says nothing (the list the PC
        // has no choices for), and the pin still waiting on top of the PC's.
        assertEquals(listOf("p1", "p2", "also-mine"), settings.value.pinnedPlaylists)
        assertEquals(ListPrefs(shuffle = true, sort = 2, at = 50), settings.value.lists["liked"])
        assertEquals("Ember", settings.value.theme)
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
        settings.update { it.copy(sharedFor = "someone-else", pinnedPlaylists = listOf("theirs"), agreed = org.medianexus.music.store.Agreed(pins = listOf("theirs"), pinsAt = 5)) }
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
        // Even held as agreed (taken in from a record that had them), only Windows' own go back.
        val held = Settings(agreed = org.medianexus.music.store.Agreed(lists = lists))
        val shared = Sharing.choices(held).lists
        assertEquals(setOf("playlist:p1", "songs", "downloads", "album:al1", "artist:ar1", "genre:Rock", "mix:on-repeat"), shared.keys)
        assertEquals(SharedPrefs(shuffle = true, sort = 5u, descending = true, sortChosen = true, at = 10), shared["playlist:p1"])
        // Chosen here: an order of the phone's own, or a choice for a list of the phone's
        // own, never waits to be shared, and is never made to the record.
        var s = Settings()
        s = Changes.sort(s, "playlist:p2", sort = 6, descending = false, now = 30)
        s = Changes.shuffle(s, "downloads:album:al1", on = true, now = 31)
        s = Changes.shuffle(s, "mix:rule:3", on = true, now = 32)
        assertTrue(s.waiting.isEmpty())
        assertEquals(6, s.lists.getValue("playlist:p2").sort)
        // The shared value is left as it was: the PC's choice for that list stays the PC's.
        val server = Shared(layout = 1, lists = mapOf("playlist:p2" to SharedPrefs(sort = 2u, at = 5)))
        val forced = listOf(org.medianexus.music.store.Waiting(Changes.SORT, "playlist:p2", sort = 6), org.medianexus.music.store.Waiting(Changes.SHUFFLE, "downloads:liked", on = true))
        assertEquals(server, Changes.onto(server, forced, 40))
    }

    @Test
    fun aSortArrivingFromAnotherDeviceIsTheOneAnOpenListReads() {
        val phone = Phone("a")
        // A list's page takes its order from the settings as they are (ListView.opening of
        // the list's own entry), each time it is drawn.
        fun order(key: String) = org.medianexus.music.library.ListView.opening(key, settings.value.lists[key])
        assertEquals(org.medianexus.music.library.ListView(org.medianexus.music.library.ListView.ADDED, true), order("playlist:p1"))
        // Chosen here: by title.
        settings.update { Changes.sort(it, "playlist:p1", org.medianexus.music.library.ListView.TITLE, false, now = 10) }
        assertEquals(org.medianexus.music.library.ListView(org.medianexus.music.library.ListView.TITLE, false), order("playlist:p1"))
        // The PC's arrives (album, Z to A), with the phone's own choice already written
        // and done with: the page reads that.
        settings.update { it.copy(waiting = emptyList()) }
        phone.take(pc)
        assertEquals(org.medianexus.music.library.ListView(org.medianexus.music.library.ListView.ALBUM, true), order("playlist:p1"))
        // One of the phone's still waiting to be shared stays on top of what arrives.
        settings.update { Changes.sort(it, "playlist:p1", org.medianexus.music.library.ListView.DURATION, false, now = 20) }
        phone.take(pc.copy(lists = mapOf("playlist:p1" to SharedPrefs(shuffle = true, sort = 2u, sortChosen = true, at = 1_700_000_300))))
        assertEquals(org.medianexus.music.library.ListView(org.medianexus.music.library.ListView.DURATION, false), order("playlist:p1"))
        assertTrue(settings.value.lists.getValue("playlist:p1").shuffle)
    }

    @Test
    fun anOrderOnlyThePhoneOffersStaysUnlessTheOneArrivingIsNewer() {
        val phone = Phone("a")
        settings.update { Changes.sort(it, "playlist:p2", sort = 6, descending = true, now = 100) }
        // A PC's choice for that list, made before the phone's: the phone keeps its own order.
        phone.take(Shared(layout = 1, lists = mapOf("playlist:p2" to SharedPrefs(shuffle = true, sort = 2u, at = 90))))
        assertEquals(ListPrefs(shuffle = true, sort = 6, descending = true, sortChosen = true, at = 100), settings.value.lists["playlist:p2"])
        // One made after it: that is the list's order now.
        phone.take(Shared(layout = 1, lists = mapOf("playlist:p2" to SharedPrefs(shuffle = true, sort = 4u, sortChosen = true, at = 110))))
        assertEquals(ListPrefs(shuffle = true, sort = 4, descending = false, sortChosen = true, at = 110), settings.value.lists["playlist:p2"])
    }

    @Test
    fun aThemeThePhoneDoesNotHaveIsLeftForTheDevicesThatDo() {
        val phone = Phone("a")
        phone.take(Shared(layout = 1, theme = "Ocean", themeAt = 50))
        phone.take(Shared(layout = 1, theme = "Sunset", themeAt = 99))
        assertEquals("Ocean" to 50L, settings.value.let { it.theme to it.agreed.themeAt })
    }

    // ---- taking in ------------------------------------------------------------------------------

    @Test
    fun aChoiceMadeHereSinceStaysWhenTheSameRecordComesBack() {
        val phone = Phone("a")
        phone.take(pc)
        // Unpinned on the phone.
        settings.update { Changes.pin(it, "p1", on = false) }
        phone.take(pc)
        assertEquals(listOf("p2"), settings.value.pinnedPlaylists)
        // A PC's later change is taken in, and the phone's own is still made on top of it.
        phone.take(pc.copy(pins = listOf("p1", "p2", "p3"), pinsAt = 1_700_001_000))
        assertEquals(listOf("p2", "p3"), settings.value.pinnedPlaylists)
        // Written with the record (the exchange hands the changes it applied back): done with.
        val changed = phone.shared.withChanges(pc.copy(pins = listOf("p1", "p2", "p3"), pinsAt = 1_700_001_000), 1_795_000_000)
        assertEquals(listOf("p2", "p3") to 1_795_000_000L, changed.record.pins to changed.record.pinsAt)
        runBlocking { phone.shared.take(changed.record, changed.applied) }
        assertTrue(settings.value.waiting.isEmpty())
        assertEquals(listOf("p2", "p3") to 1_795_000_000L, phone.record()!!.let { it.pins to it.pinsAt })
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
        // (The log's first line carries the latest time of all: a play taken in from another
        // device, say. What is shared is the last five hundred *lines*, as on Windows, and
        // only those are read: an exchange doesn't go through the whole log, however long.)
        val log = org.medianexus.music.store.Plays.of((0 until 620).map { Triple("s${it % 7}", if (it == 0) 5_000L else 1_000L + it, if (it == 0) "playlist:old" else if (it == 619) "playlist:new" else "") })
        val (plays, sources) = Sharing.plays(log)
        assertEquals(PLAYS_SHARED, plays.size)
        assertEquals("a line before the last five hundred was read", Play("s3", 1_619), plays.first())
        assertEquals(1_120L, plays.last().at)
        // A source is passed on only with its play.
        assertEquals(listOf(PlayFrom("s3", 1_619, "playlist:new")), sources)
    }

    // ---- an account's choices are its own ---------------------------------------------------------

    @Test
    fun nothingChosenUnderOneAccountIsSharedIntoAnothersRecord() {
        val first = Phone("a")
        first.take(Shared(layout = 1, pins = listOf("pa"), pinsAt = 100, theme = "Rose", themeAt = 110, lists = mapOf("liked" to SharedPrefs(shuffle = true, at = 120))))
        settings.update { Changes.pin(it, "pa2", on = true) }
        first.history.played(Song(id = "heard-by-a"))
        first.log.add("heard-by-a", 130)
        first.added.changed("pa", emptyList(), listOf("s"), 140)
        assertEquals(listOf("pa"), first.record()!!.pins)

        // Signed out, and in as someone else.
        val second = Phone("b")
        // The first account's objects, if any is still about, say nothing and take nothing in.
        assertNull(first.record())
        assertTrue(first.shared.withChanges(Shared(layout = 1), 5).applied.isEmpty())
        // The second account: no pins, no list choices, no notes, no plays, nothing waiting;
        // and the theme the phone was left showing is not its to tell of. A fresh phone's record.
        assertEquals(Shared(layout = 1), second.record())
        assertTrue(settings.value.waiting.isEmpty() && settings.value.pinnedPlaylists.isEmpty() && settings.value.lists.isEmpty())
        assertTrue(second.shared.withChanges(Shared(layout = 1), 5).applied.isEmpty())
        // (The phone still looks as it did.)
        assertEquals("Rose", settings.value.theme)
        // A theme chosen now is this account's, and waits to be shared into its record.
        settings.update { Changes.theme(it, "Green") }
        assertEquals("Green", second.shared.withChanges(Shared(layout = 1), 200).record.theme)

        // Back as the first: what it showed, what it had agreed and what was waiting are as
        // they were, the theme it had with them.
        first.close()
        Phone("a")
        val s = settings.value
        assertEquals(listOf("pa", "pa2"), s.pinnedPlaylists)
        assertEquals(org.medianexus.music.store.Agreed(listOf("pa"), 100, "Rose", 110, mapOf("liked" to ListPrefs(shuffle = true, at = 120))), s.agreed)
        assertEquals(listOf(org.medianexus.music.store.Waiting(Changes.PIN, "pa2")), s.waiting)
        assertEquals("Rose", s.theme)
    }

    @Test
    fun whatThePhoneHeldBeforeItCouldKeepInStepIsItsOwnAndNobodysRecord() {
        // Settings as a build without syncing left them: no owner, and times put on by rules
        // since thrown out (under whichever account was signed in then).
        settings.update { Settings(pinnedPlaylists = listOf("p"), pinsAt = 5, theme = "Ocean", themeAt = 6, lists = mapOf("liked" to ListPrefs(shuffle = true, sort = 2, sortChosen = true, at = 7))) }
        val phone = Phone("a")
        assertEquals("a", settings.value.sharedFor)
        // Still what the phone shows...
        assertEquals(Triple(listOf("p"), "Ocean", true), settings.value.let { Triple(it.pinnedPlaylists, it.theme, it.lists.getValue("liked").shuffle) })
        // ...and none of it is in what the phone has to say, with any time.
        assertEquals(Shared(layout = 1), phone.record())
        assertTrue(phone.shared.withChanges(Shared(layout = 1), 99).let { it.applied.isEmpty() && it.record == Shared(layout = 1) })
    }
}
