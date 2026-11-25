package org.medianexus.music.sync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.medianexus.music.core.Play
import org.medianexus.music.core.PlayFrom
import org.medianexus.music.store.SettingsStore

/**
 * The phone's own side of an exchange, held to the shared core's contract for a caller (the
 * numbered steps at the top of the Windows repository's `core/src/step.rs`), step by step:
 * what is on the disk before which call, what is checked before sending, what is applied
 * after. The phone here is its real stores and the real library; the server is a pretend
 * one that counts every question and every write, and can be made to wait.
 *
 * What the core itself decides (how two records combine, what a change does to the pins) is
 * tested where it is written. These are the things only the phone can get wrong.
 */
class InStepContractTest {
    /** No test here may wait for ever: one that did would hold the machine's one turn at
     *  building, for everyone, until someone noticed. */
    @get:Rule val limit: Timeout = Timeout.seconds(60)

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun phone(name: String = "phone", account: String = "acct") = PretendPhone(File(folder.root, name), account, scope)

    /** A PC's record, as the Windows app itself writes it (its own order of fields). */
    private val windows = """{"nexus_music_sync":1,"pins":["p1","p2"],"pins_at":1700000000,"theme":"Ember","theme_at":1700000100,"lists":{"playlist:p1":{"shuffle":true,"sort":3,"descending":true,"sort_chosen":true,"at":1700000200}},"added":{"p1":{"s1":{"times":[1700000300],"at":1700000300}}},"history":[["s2",1700000500],["s1",1700000400]],"plays":[["s2",1700000500],["s1",1700000400]],"play_sources":[["s1",1700000400,"playlist:p1"]]}"""

    private fun record(server: PretendServer, id: String = "a") = SharedText.read(server.comment(id)!!)!!

    // ---- the case that matters most ----------------------------------------------------------

    @Test
    fun aFreshPhoneLeavesThePCsRecordExactlyAsItIs() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue("nothing was written", server.writes.isEmpty() && server.made.isEmpty())
        assertEquals(windows, server.comment("a"))
        // The phone has taken in what the PC had: the pins, the theme, the list's choices,
        // the date, Recently played and the plays.
        assertEquals(listOf("p1", "p2"), phone.pins)
        assertEquals("Ember", phone.settings.value.theme)
        assertEquals(3, phone.settings.value.lists.getValue("playlist:p1").sort)
        assertEquals(1_700_000_300L, phone.added.dates("p1", listOf(org.medianexus.music.net.Song(id = "s1")))[0])
        assertEquals(listOf("s2", "s1"), phone.history.state.value.map { it.song.id })
        assertEquals(2, phone.log.state.value.size)
        // Nothing was written, so no copy of the record was kept either.
        assertFalse(phone.keptFirst.exists())
        // Again, now that it holds it: still nothing to write.
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(windows, server.comment("a"))
        assertTrue(server.writes.isEmpty())
    }

    // ---- steps 1 to 3: nothing taken from the phone before the answers are in hand ------------

    @Test
    fun aChangeMadeWhileTheServerIsBeingAskedIsInWhatIsDecided() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        // The person pins a playlist while the record is on its way from the server.
        server.gate = { what -> if (what == "read") phone.pin("mine") }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // It went out with this very exchange, on top of the PC's pins.
        assertEquals(listOf("p1", "p2", "mine"), record(server).pins)
        assertEquals(1, server.writes.size)
        assertEquals(listOf("p1", "p2", "mine"), phone.pins)
    }

    @Test
    fun aChangeTakenBackWhileTheServerIsBeingAskedIsNeverSent() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        // Unpinned again while the record is on its way: a plan made from the phone as it
        // was when the exchange began would send the pin, with nothing left to undo it.
        server.gate = { what -> if (what == "read") phone.unpin("mine") }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        assertEquals(listOf("p1", "p2"), phone.pins)
    }

    @Test
    fun theAccountOrTheSettingGoingWhileTheServerIsBeingAskedDropsWhatComesBack() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        var may = true
        server.gate = { what -> if (what == "read") may = false }
        assertEquals(InStep.Outcome.NOT_NOW, phone.step(server, may = { may }).exchange())
        // Nothing of it was applied, and nothing sent.
        assertEquals(listOf("mine"), phone.pins)
        assertTrue(server.writes.isEmpty())
        assertEquals(0, phone.log.state.value.size)
    }

    @Test
    fun anExchangeBeforeThePhoneHasReadItsOwnSendsNothingAndTakesNothing() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = PretendPhone(File(folder.root, "unread"), "acct", scope, read = false)
        assertEquals(InStep.Outcome.NOT_READ_YET, phone.step(server).exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        assertTrue(phone.pins.isEmpty())
    }

    // ---- step 5: what other devices played is kept before anything else ------------------------

    @Test
    fun nothingIsTakenInOrWrittenUntilThePlaysThatCameAreOnTheDisk() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        // The phone's log can't be written (the disk is full).
        val real = phone.log.appendBytes
        phone.log.appendBytes = { _, _ -> throw IOException("No space left on device") }
        assertEquals(InStep.Outcome.FAILED, phone.step(server).exchange())
        assertTrue("nothing was written on the strength of plays that weren't kept", server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        // And nothing was taken in: the PC's pins are not shown as if the exchange had been made.
        assertEquals(listOf("mine"), phone.pins)
        assertTrue(phone.notes.any { "couldn't be kept" in it })
        // The disk has room again: the same plays are offered again, kept, and then the rest follows.
        phone.log.appendBytes = real
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(2, phone.log.state.value.size)
        assertEquals(listOf("p1", "p2", "mine"), record(server).pins)
    }

    // ---- step 6: what is taken in is on the disk before the exchange is done -------------------

    @Test
    fun whatIsTakenInIsOnTheDiskWhenTheExchangeIsDone() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // Read back from the files themselves, as a phone started again would read them:
        // nothing here was flushed or waited for after the exchange said it was done. (The
        // first phone only lets its log's file go.)
        phone.log.close().join()
        val again = PretendPhone(File(folder.root, "phone"), "acct", scope)
        assertEquals(listOf("p1", "p2"), again.pins)
        assertEquals("Ember", again.settings.value.theme)
        assertEquals(1_700_000_300L, again.added.dates("p1", listOf(org.medianexus.music.net.Song(id = "s1")))[0])
        assertEquals(listOf("s2", "s1"), again.history.state.value.map { it.song.id })
        assertEquals(2, again.log.state.value.size)
    }

    @Test
    fun ifWhatCameCannotBeKeptTheExchangeHasFailedAndNothingIsWritten() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        // The settings can't be written from here on (a folder stands where the file is).
        phone.settingsFile.delete()
        File(phone.settingsFile, "in the way").mkdirs()
        assertEquals(InStep.Outcome.FAILED, phone.step(server).exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        // What couldn't be kept isn't shown as taken in.
        assertEquals(listOf("mine"), phone.pins)
        assertTrue(phone.notes.any { "couldn't be kept" in it })
    }

    // ---- step 7.1: the record as first found is kept before anything is sent -------------------

    @Test
    fun theRecordAsFoundIsKeptBeforeTheFirstWriteAndNeverWrittenAgain() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        var keptWhenSent: String? = null
        server.gate = { what -> if (what == "write") keptWhenSent = phone.keptFirst.takeIf { it.isFile }?.readText() }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // It was there, exactly as found, before the write was made.
        assertEquals(windows, keptWhenSent)
        // A later write (the record is by now this phone's own text) leaves the copy as it is.
        server.gate = {}
        phone.pin("another")
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(2, server.writes.size)
        assertEquals(windows, phone.keptFirst.readText())
    }

    @Test
    fun anEmptyRecordIsKeptAsAnEmptyFile() = runBlocking {
        // The playlist is there and empty (Navidrome leaves an empty comment out of its answers).
        val server = PretendServer().apply { put("a", null) }
        val phone = phone()
        phone.pin("mine")
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(listOf("mine"), record(server).pins)
        assertTrue(phone.keptFirst.isFile)
        assertEquals("", phone.keptFirst.readText())
        // And the next write does not keep this phone's own text as "the original".
        phone.pin("another")
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals("", phone.keptFirst.readText())
    }

    @Test
    fun ifTheCopyCannotBeKeptNothingIsSent() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        // A folder stands where the copy would go.
        File(phone.keptFirst, "in the way").mkdirs()
        assertEquals(InStep.Outcome.FAILED, phone.step(server).exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        assertTrue(phone.notes.any { "copy of the shared record couldn't be kept" in it })
        // The change still waits, and still shows.
        assertEquals(listOf("p1", "p2", "mine"), phone.pins)
        // The folder gone, the copy is kept and the change sent.
        phone.keptFirst.deleteRecursively()
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(windows, phone.keptFirst.readText())
        assertEquals(listOf("p1", "p2", "mine"), record(server).pins)
    }

    // ---- steps 7.2 and 7.4: tried before it is sent, agreed only once the server answers -------

    @Test
    fun aWriteIsNotedAsTriedOnTheDiskBeforeItIsSentAndAsAgreedOnlyOnceTheServerAnswers() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        assertFalse(phone.onDisk().contains("\"tried\": true"))
        var triedWhenSent = false
        server.gate = { what -> if (what == "write") triedWhenSent = phone.onDisk().contains("\"tried\": true") }
        // The write reaches the server, and its answer never arrives.
        server.loseAnswers = 1
        assertEquals(InStep.Outcome.FAILED, phone.step(server).exchange())
        assertTrue("on the disk, as tried, before the request was made", triedWhenSent)
        assertEquals(listOf("p1", "p2", "mine"), record(server).pins)
        // Not agreed, and not forgotten: it still waits as tried, and still shows; after a
        // restart too.
        assertTrue(phone.onDisk().contains("\"tried\": true"))
        phone.log.close().join()
        val again = PretendPhone(File(folder.root, "phone"), "acct", scope)
        assertEquals(listOf("p1", "p2", "mine"), again.pins)
        // At the next exchange it is made again over its own result, which alters nothing:
        // no second write, and it is done with.
        server.gate = {}
        assertEquals(InStep.Outcome.DONE, again.step(server).exchange())
        assertEquals(1, server.writes.size)
        assertFalse(again.onDisk().contains("\"tried\": true"))
        assertEquals(listOf("p1", "p2", "mine"), again.pins)
    }

    // ---- step 7.3: each request that changes the server is asked about first --------------------

    @Test
    fun eachRequestThatChangesTheServerIsAskedAboutFirst() = runBlocking {
        // No playlist yet: the phone has something to say, so one is made and then filled.
        val server = PretendServer()
        val phone = phone()
        phone.pin("mine")
        var may = true
        // Sharing is switched off (or the account goes) while the playlist is being made.
        server.gate = { what -> if (what == "make") may = false }
        assertEquals(InStep.Outcome.NOT_NOW, phone.step(server, may = { may }).exchange())
        assertEquals(1, server.made.size)
        assertTrue("the record is not then written into it", server.writes.isEmpty())
        // Switched on again: the playlist is found, empty, and filled. None is made beside it.
        may = true
        server.gate = {}
        assertEquals(InStep.Outcome.DONE, phone.step(server, may = { may }).exchange())
        assertEquals(1, server.made.size)
        assertEquals(1, server.writes.size)
        assertEquals(listOf("mine"), record(server, server.made[0]).pins)
    }

    // ---- one at a time, and still on its way until it has answered ------------------------------

    @Test
    fun anExchangeLetGoIsStillOnItsWayUntilItsWriteHasAnswered() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.gate = { what ->
            if (what == "write") {
                reached.complete(Unit)
                release.await()
            }
        }
        // The exchange runs for an account that is then signed out: whatever asked for it
        // is cancelled while its write is on its way.
        val leaving = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = InStep(server, phone.shared, username = "tester", account = "one-account", may = { true }, scope = leaving, clock = { PRETEND_NOW }, pause = {})
        val running = leaving.async { first.exchange() }
        reached.await()
        leaving.cancel()
        // Signed straight back in: a second exchange for the same account does not run
        // across the first's write. (It would read the record from before that write.)
        val second = InStep(server, phone.shared, username = "tester", account = "one-account", may = { true }, scope = scope, clock = { PRETEND_NOW }, pause = {})
        server.gate = { what -> if (what == "write") release.await() }
        assertEquals(InStep.Outcome.BUSY, second.exchange())
        assertEquals(1, server.listed)
        // The first write is answered: it was not abandoned, and what it carried is agreed.
        release.complete(Unit)
        running.join()
        assertEquals(listOf("p1", "p2", "mine"), record(server).pins)
        assertFalse(phone.onDisk().contains("\"tried\": true"))
        // Now the next may run, and finds nothing to do.
        assertEquals(InStep.Outcome.DONE, second.exchange())
        assertEquals(1, server.writes.size)
    }

    @Test
    fun aSecondExchangeWaitsForNothingAndAnotherFollowsTheFirst() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        val step = phone.step(server)
        var second: InStep.Outcome? = null
        var asked = false
        server.gate = { what ->
            if (what == "read" && !asked) {
                asked = true
                second = step.exchange()
            }
        }
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(InStep.Outcome.BUSY, second)
        // The one that was asked for meanwhile has followed (its wait is nothing here).
        assertEquals(2, server.listed)
    }

    // ---- the clock -------------------------------------------------------------------------------

    @Test
    fun aPhoneWhoseClockDisagreesWithTheServersTakesInAndSharesNothing() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        phone.pin("mine")
        val fast = phone.step(server, clock = { PRETEND_NOW + 3_600 })
        assertEquals(InStep.Outcome.CLOCK_WRONG, fast.exchange())
        assertTrue(fast.clockWrong.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        // What the PC has was taken in; what was changed here still waits, and shows.
        assertEquals(listOf("p1", "p2", "mine"), phone.pins)
        assertTrue(phone.notes.any { "clock" in it })
        // The clocks agree again: it goes.
        val right = phone.step(server)
        assertEquals(InStep.Outcome.DONE, right.exchange())
        assertFalse(right.clockWrong.value)
        assertEquals(listOf("p1", "p2", "mine"), record(server).pins)
    }

    // ---- what the server said, and did not say ---------------------------------------------------

    @Test
    fun whatTheCoreStopsAtIsLeftExactlyAsItIsAndSaidInDiagnostics() = runBlocking {
        val phone = phone()
        phone.pin("mine")
        // Somebody's note in the comment: not a record.
        val server = PretendServer().apply { put("a", "do not delete: my notes") }
        assertEquals(InStep.Outcome.UNREADABLE, phone.step(server).exchange())
        assertEquals("do not delete: my notes", server.comment("a"))
        // The server left out whose the playlist is: it may be anybody's.
        val quiet = PretendServer().apply { put("a", null, by = null) }
        assertEquals(InStep.Outcome.NOT_STATED, phone.step(quiet).exchange())
        assertTrue(quiet.writes.isEmpty() && quiet.made.isEmpty())
        // A record from a later layout: read, and not written.
        val later = PretendServer().apply { put("a", """{"nexus_music_sync":2,"pins":["p1"],"pins_at":1700000000}""") }
        assertEquals(InStep.Outcome.NEWER_LAYOUT, phone.step(later).exchange())
        assertTrue(later.writes.isEmpty())
        assertEquals(listOf("p1", "mine"), phone.pins)
        assertTrue(phone.notes.any { "isn't the shared record" in it } && phone.notes.any { "didn't say whose" in it } && phone.notes.any { "newer version" in it })
        // Nothing of any of them names an account, an address or what is in the record.
        assertTrue(phone.notes.none { "tester" in it || "p1" in it })
    }

    @Test
    fun aPhoneWithNothingToSayMakesNoRecordAndFillsNoEmptyOne() = runBlocking {
        val none = PretendServer()
        val phone = phone()
        assertEquals(InStep.Outcome.DONE, phone.step(none).exchange())
        assertTrue(none.made.isEmpty() && none.writes.isEmpty())
        val empty = PretendServer().apply { put("a", null) }
        assertEquals(InStep.Outcome.DONE, phone.step(empty).exchange())
        assertTrue(empty.writes.isEmpty())
    }

    // ---- what the phone may say, and what it shows -------------------------------------------------

    @Test
    fun whatOnlyThePhoneHasIsNotShared() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        // A list the Windows app doesn't have, an order it has no number for.
        phone.does(Chosen.Did.Shuffle("download:album:x", true))
        phone.does(Chosen.Did.Sort("playlist:p9", 7, false))
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(windows, server.comment("a"))
        // They are still the phone's own.
        assertTrue(phone.settings.value.lists.getValue("download:album:x").shuffle)
        assertEquals(7, phone.settings.value.lists.getValue("playlist:p9").sort)
    }

    @Test
    fun anOrderOnlyThePhoneOffersIsNotDisplacedByAShuffleAloneButIsByALaterSort() = runBlocking {
        val server = PretendServer().apply { put("a", windows) }
        val phone = phone()
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // The list has the PC's choices (sort 3, shuffled). The person picks an order only
        // the phone offers for it.
        phone.does(Chosen.Did.Sort("playlist:p1", 7, false))
        assertEquals(7, phone.settings.value.lists.getValue("playlist:p1").sort)
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue("an order the Windows app has no number for is never written", server.writes.isEmpty())
        // On a PC, later, the list's shuffle is switched off: its choices change, its order
        // does not.
        val pc = record(server)
        server.put("a", SharedText.write(pc.copy(lists = mapOf("playlist:p1" to pc.lists.getValue("playlist:p1").copy(shuffle = false, at = 1_794_000_000)))))
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        val shown = phone.settings.value.lists.getValue("playlist:p1")
        assertFalse(shown.shuffle)
        assertEquals("the phone's own order stays", 7, shown.sort)
        // Then a sort is chosen on the PC: that one takes its place.
        server.put("a", SharedText.write(pc.copy(lists = mapOf("playlist:p1" to SharedPrefs(shuffle = false, sort = 2, descending = false, sortChosen = true, at = 1_794_000_100)))))
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(2, phone.settings.value.lists.getValue("playlist:p1").sort)
        // After a restart too.
        phone.log.close().join()
        assertEquals(2, PretendPhone(File(folder.root, "phone"), "acct", scope).settings.value.lists.getValue("playlist:p1").sort)
    }

    @Test
    fun nothingChosenUnderOneAccountIsSharedIntoAnothersRecord() = runBlocking {
        val root = File(folder.root, "two")
        val one = PretendPhone(root, "one", scope)
        one.pin("ones-own")
        one.stop()
        // Another account is signed in on the same phone.
        val other = PretendPhone(root, "other", scope)
        assertTrue(other.pins.isEmpty())
        val theirs = PretendServer().apply { put("b", null) }
        assertEquals(InStep.Outcome.DONE, other.step(theirs).exchange())
        assertTrue(theirs.writes.isEmpty())
        other.pin("the-others")
        assertEquals(InStep.Outcome.DONE, other.step(theirs).exchange())
        assertEquals(listOf("the-others"), record(theirs, "b").pins)
        other.stop()
        // Back as the first: its pin is as it was, still waiting, and goes into its own record.
        val back = PretendPhone(root, "one", scope)
        assertEquals(listOf("ones-own"), back.pins)
        val mine = PretendServer().apply { put("a", windows) }
        assertEquals(InStep.Outcome.DONE, back.step(mine).exchange())
        assertEquals(listOf("p1", "p2", "ones-own"), record(mine).pins)
    }

    // ---- the upgrade from the build before the shared core ----------------------------------------

    @Test
    fun aPhoneFromTheBuildBeforeTheSharedCoreKeepsWhatItHadAgreedAndWhatWasWaiting() = runBlocking {
        // The settings as 0.4.0 wrote them: what it showed, what it had agreed with the
        // server, and the changes still waiting (0.4.0 kept them whether or not sharing ran).
        val root = File(folder.root, "upgraded").apply { mkdirs() }
        File(root, "settings.json").writeText(
            """{"server":"https://music.example.org","username":"tester","pinned_playlists":["p9","mine"],"theme":"Ocean",
               "lists":{"liked":{"shuffle":true,"sort":0,"descending":false,"sort_chosen":false,"at":1790000000}},
               "agreed":{"pins":["p9"],"pins_at":1690000000,"theme":"","theme_at":0,"lists":{}},
               "waiting":[{"what":"pin","key":"mine"},{"what":"shuffle","key":"liked","on":true},{"what":"theme","key":"Ocean"}],
               "step_mark":1,"shared_for":"acct"}""",
        )
        val phone = PretendPhone(root, "acct", scope)
        // Shown as it was, and now kept as the core keeps it.
        assertEquals(listOf("p9", "mine"), phone.pins)
        assertEquals("Ocean", phone.settings.value.theme)
        assertNotNull(phone.settings.value.choices)
        assertEquals(Belongs.MARK, phone.settings.value.stepMark)
        assertTrue(phone.settings.value.waiting.isEmpty())
        // Its first exchange, with a PC's record: the PC's pins are the newer and stand; the
        // pin, the shuffle and the theme that were waiting are made on top of them.
        val server = PretendServer().apply { put("a", windows) }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        val now = record(server)
        assertEquals(listOf("p1", "p2", "mine"), now.pins)
        assertEquals("Ocean", now.theme)
        assertTrue(now.lists.getValue("liked").shuffle)
        // Nothing else of the PC's was touched.
        assertEquals(SharedText.read(windows)!!.added, now.added)
        assertEquals(SharedText.read(windows)!!.lists.getValue("playlist:p1"), now.lists.getValue("playlist:p1"))
        assertEquals(windows, phone.keptFirst.readText())
    }

    // ---- what goes to the core of the phone's log ----------------------------------------------------

    @Test
    fun everyPlayThatCouldBeAmongTheNewestIsOfferedHoweverTheLogIsOrdered() = runBlocking {
        val phone = phone()
        // 600 plays a second apart, the newer half of them first in the log (plays taken in
        // from other devices go on the end, whenever they were made), and two more at the
        // very second of the 500th newest.
        fun play(n: Int) = Play("s$n", 1_000_000L + n)
        val tied = listOf(Play("t1", play(100).at), Play("t2", play(100).at))
        phone.log.absorb((300 until 600).map { play(it).song to play(it).at }, listOf(Triple("s303", play(303).at, "playlist:p")))
        phone.log.absorb(((0 until 300).map(::play) + tied).map { it.song to it.at }, listOf(Triple("s3", play(3).at, "playlist:q")))
        assertEquals(602, phone.log.state.value.size)
        val (offered, sources) = PhoneShared.newest(phone.log.state.value)
        // Every play at least as new as the 500th newest: the 499 after it, and all three
        // of that second (which 500 of them are shared is the core's to say).
        assertEquals(((100 until 600).map(::play) + tied).toSet(), offered.toSet())
        assertEquals("each once", 502, offered.size)
        assertEquals("only the sources of plays that are offered", listOf(PlayFrom("s303", play(303).at, "playlist:p")), sources)
        // A short log is offered whole.
        val short = phone("short")
        short.log.absorb(listOf("a" to 5L, "b" to 3L), emptyList())
        assertEquals(setOf(Play("a", 5), Play("b", 3)), PhoneShared.newest(short.log.state.value).first.toSet())
    }

    @Test
    fun aCheckedWriteOfTheSettingsIsOnTheDiskBeforeItShowsOrIsNotMade() = runBlocking {
        val file = File(folder.root, "checked/settings.json").apply { parentFile.mkdirs() }
        val store = SettingsStore.open(file, scope, Dispatchers.Unconfined)
        assertTrue(store.commit { it.copy(deviceId = "feedf00d") })
        assertTrue(file.readText().contains("feedf00d"))
        assertEquals("feedf00d", store.value.deviceId)
        // The file can't be written: nothing changes, here or on the disk, and it says so.
        file.delete()
        file.mkdirs()
        assertFalse(store.commit { it.copy(deviceId = "0badc0de") })
        assertEquals("feedf00d", store.value.deviceId)
        assertTrue(store.saveFailed.value)
    }
}
