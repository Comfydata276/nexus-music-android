package org.medianexus.music.sync

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.medianexus.music.net.ApiError
import org.medianexus.music.store.ListPrefs
import org.medianexus.music.store.Waiting

/**
 * A change made on the phone is kept as the change, and becomes a value with a time on it
 * only at an exchange, on top of what the server has then (sync/Changes.kt). These are the
 * cases where the phone used to put the time on a whole value it had made from a stale
 * base, and every PC then took the phone's for the newer.
 */
class StaleBaseTest {
    /** No test here may wait for ever (see InStepTest). */
    @get:Rule val limit: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    @get:Rule val folder = TemporaryFolder()
    private val scopes = ArrayList<CoroutineScope>()
    private val phones = ArrayList<PretendPhone>()

    @After
    fun stop() {
        phones.forEach { runCatching { it.stop() } }
        scopes.forEach { it.cancel() }
    }

    private fun phone(root: java.io.File = folder.newFolder(), account: String = "a"): PretendPhone {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return PretendPhone(root, account, scope).also { phones += it }
    }

    private fun text(shared: Shared) = SharedText.write(shared)
    private fun PretendServer.record(id: String = "r") = SharedText.read(comment(id)!!)!!

    /** What a PC holding `pc` holds after its next exchange with the server: Windows' own
     *  rule, each item the newer of the two. */
    private fun pcAfter(pc: Shared, server: PretendServer) = Merge.merge(pc, server.record(), NOW)

    private val sorted = SharedPrefs(shuffle = false, sort = 3u, descending = true, sortChosen = true, at = 1_700_000_200)
    private val pc = Shared(
        layout = 1, pins = listOf("A", "B"), pinsAt = 1_700_000_000, theme = "Ember", themeAt = 1_700_000_100,
        lists = mapOf("playlist:x" to sorted),
        history = listOf(Play("s1", 1_700_000_400)), plays = listOf(Play("s1", 1_700_000_400)),
    )

    @Test
    fun aPinMadeBeforeThePCsPinsWereReadIsAddedToThemNotPutInTheirPlace() = runBlocking {
        // The reviewer's case, exactly. The PC's pins are [A, B], on the server. The phone
        // has never read them: its first read is held at a gate.
        val server = PretendServer().apply { put("r", text(pc)) }
        val root = folder.newFolder()
        val first = phone(root)
        val held = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        server.gate = { if (it == "read") { reached.complete(Unit); held.await() } }
        val exchange = async(Dispatchers.Default) { runCatching { first.step(server).exchange() } }
        reached.await()
        // The person pins C on the phone. It shows at once...
        first.does { Changes.pin(it, "C", on = true) }
        assertEquals(listOf("C"), first.settings.value.pinnedPlaylists)
        // ...and the phone's process is killed before the exchange gets its answer.
        first.stop()
        scopes.forEach { it.cancel() }
        exchange.cancel()
        server.gate = {}
        assertTrue(server.writes.isEmpty())

        // Started again: the pin is still shown, still waiting, and has no time on it.
        val again = phone(root)
        assertEquals(listOf("C"), again.settings.value.pinnedPlaylists)
        assertEquals(listOf(Waiting(Changes.PIN, "C")), again.settings.value.waiting)
        assertEquals(Shared(layout = 1), again.shared.record())
        // The exchange, made again: C is added to the pins the server has.
        assertEquals(InStep.Outcome.DONE, again.step(server).exchange())
        assertEquals(listOf("A", "B", "C"), server.record().pins)
        assertEquals(NOW, server.record().pinsAt)
        // The PC ends with [A, B, C], and everything else it had as it had it.
        val after = pcAfter(pc, server)
        assertEquals(listOf("A", "B", "C"), after.pins)
        assertEquals(pc.copy(pins = after.pins, pinsAt = after.pinsAt), after)
        // The phone shows them all, has nothing left waiting, and has nothing more to write.
        assertEquals(listOf("A", "B", "C"), again.settings.value.pinnedPlaylists)
        assertTrue(again.settings.value.waiting.isEmpty())
        assertEquals(InStep.Outcome.DONE, again.step(server).exchange())
        assertEquals(1, server.writes.size)
    }

    @Test
    fun aShuffleTappedBeforeTheListsChoicesArrivedLeavesItsSortAlone() = runBlocking {
        val server = PretendServer().apply { put("r", text(pc)) }
        val phone = phone()
        // Shuffle on, for a list the PC has sorted, on a phone that hasn't heard so.
        phone.does { Changes.shuffle(it, "playlist:x", on = true, now = NOW) }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        // The shuffle is set on the list's choices as the server had them: its sort stands.
        assertEquals(sorted.copy(shuffle = true, at = NOW), server.record().lists["playlist:x"])
        assertEquals(ListPrefs(shuffle = true, sort = 3, descending = true, sortChosen = true, at = NOW), phone.settings.value.lists["playlist:x"])
        // And nothing else of the PC's was touched, or given a new time.
        assertEquals(pc.copy(lists = server.record().lists), server.record())
        // A sort chosen for a list whose shuffle a PC set: the same the other way round.
        val shuffled = PretendServer().apply { put("r", text(Shared(layout = 1, lists = mapOf("liked" to SharedPrefs(shuffle = true, at = 500))))) }
        val other = phone()
        other.does { Changes.sort(it, "liked", sort = 1, descending = false, now = NOW) }
        assertEquals(InStep.Outcome.DONE, other.step(shuffled).exchange())
        assertEquals(SharedPrefs(shuffle = true, sort = 1u, descending = false, sortChosen = true, at = NOW), shuffled.record().lists["liked"])
    }

    @Test
    fun anUnpinAndAThemeAreMadeToWhatTheServerHas() = runBlocking {
        val server = PretendServer().apply { put("r", text(pc)) }
        val phone = phone()
        // Offline, say: the phone shows nothing pinned, the person "unpins" nothing they can
        // see; later, having read the PC's, they unpin A and choose a theme.
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue(server.writes.isEmpty())
        phone.does { Changes.pin(it, "A", on = false) }
        phone.does { Changes.theme(it, "Ocean") }
        assertEquals(listOf("B") to "Ocean", phone.settings.value.let { it.pinnedPlaylists to it.theme })
        // What is offered is still what was agreed, with the PC's own times.
        assertEquals(listOf("A", "B") to 1_700_000_000L, phone.shared.record()!!.let { it.pins to it.pinsAt })
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(Triple(listOf("B"), "Ocean", NOW), server.record().let { Triple(it.pins, it.theme, it.themeAt) })
        // The agreed is now that, and it is offered with the times it was written with.
        assertEquals(listOf("B") to NOW, phone.shared.record()!!.let { it.pins to it.pinsAt })
    }

    @Test
    fun aChangeThatMakesNoDifferenceToWhatTheServerHasPutsNoTimeOnAnything() = runBlocking {
        val server = PretendServer().apply { put("r", text(pc)) }
        val phone = phone()
        // Pinned what the PC had pinned; chose the theme the PC had chosen; a sort it had.
        phone.does { Changes.pin(it, "B", on = true) }
        phone.does { Changes.theme(it, "Ember") }
        phone.does { Changes.sort(it, "playlist:x", sort = 3, descending = true, now = NOW) }
        phone.does { Changes.pin(it, "never-pinned", on = false) }
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertTrue("nothing was written", server.writes.isEmpty())
        assertEquals(text(pc), server.comment("r"))
        // The changes are done with all the same, and the phone shows the PC's.
        assertTrue(phone.settings.value.waiting.isEmpty())
        assertEquals(listOf("A", "B"), phone.settings.value.pinnedPlaylists)
    }

    @Test
    fun changesMadeWithSharingOffAreMadeToTheServersWhenItIsSwitchedOn() = runBlocking {
        val server = PretendServer().apply { put("r", text(pc)) }
        val phone = phone()
        var on = false
        val step = phone.step(server, may = { on })
        phone.does { Changes.pin(it, "C", on = true) }
        phone.does { Changes.shuffle(it, "playlist:x", on = true, now = NOW) }
        assertEquals(InStep.Outcome.NOT_NOW, step.exchange())
        assertEquals(0, server.asked())
        on = true
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(listOf("A", "B", "C"), server.record().pins)
        assertEquals(sorted.copy(shuffle = true, at = NOW), server.record().lists["playlist:x"])
    }

    @Test
    fun aChangeIsForgottenOnlyOnceItHasBeenWritten() = runBlocking {
        val server = PretendServer().apply { put("r", text(pc)) }
        val phone = phone()
        phone.does { Changes.pin(it, "C", on = true) }
        // The write never reaches the server.
        server.gate = { if (it == "write") throw ApiError.Network("The connection dropped.") }
        assertEquals(InStep.Outcome.FAILED, phone.step(server).exchange())
        assertEquals(listOf(Waiting(Changes.PIN, "C")), phone.settings.value.waiting)
        assertEquals(listOf("C"), phone.settings.value.pinnedPlaylists)
        // Meanwhile the PC unpins A. The change is made again, to what the server has now.
        server.put("r", text(pc.copy(pins = listOf("B"), pinsAt = 1_700_000_900)))
        server.gate = {}
        assertEquals(InStep.Outcome.DONE, phone.step(server).exchange())
        assertEquals(listOf("B", "C"), server.record().pins)
        assertTrue(phone.settings.value.waiting.isEmpty())
    }

    @Test
    fun aChangeMadeWhileAnExchangeIsOutStaysOnScreenAndGoesWithTheNext() = runBlocking {
        val server = PretendServer().apply { put("r", text(pc)) }
        val phone = phone()
        phone.does { Changes.pin(it, "C", on = true) }
        // As the write goes out, the person pins D as well.
        server.gate = { if (it == "write") phone.does { s -> Changes.pin(s, "D", on = true) } }
        val step = phone.step(server)
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(listOf("A", "B", "C"), server.record().pins)
        // D was not in what was written, and is not lost: shown, and still waiting.
        assertEquals(listOf("A", "B", "C", "D"), phone.settings.value.pinnedPlaylists)
        assertEquals(listOf(Waiting(Changes.PIN, "D")), phone.settings.value.waiting)
        server.gate = {}
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(listOf("A", "B", "C", "D"), server.record().pins)
    }

    @Test
    fun playsThePhoneCouldNotWriteDownAreNotCountedAsTakenIn() = runBlocking {
        val record = pc.copy(plays = listOf(Play("s2", 1_700_000_500), Play("s1", 1_700_000_400)), playSources = listOf(PlayFrom("s1", 1_700_000_400, "playlist:x")))
        val server = PretendServer().apply { put("r", text(record)) }
        val root = folder.newFolder()
        val phone = phone(root)
        phone.does { Changes.pin(it, "C", on = true) }
        // The phone's storage won't take the log's lines.
        phone.log.appendBytes = { _, _ -> throw java.io.IOException("No space left on device") }
        val step = phone.step(server)
        assertEquals(InStep.Outcome.FAILED, step.exchange())
        assertTrue(server.writes.isEmpty())
        // Again. The plays are in the log's memory by now, so nothing about this exchange is
        // "new": it used to be taken for done, the record written, and the plays left
        // unwritten until some later play happened to be noted (or lost, if none was).
        assertEquals(InStep.Outcome.FAILED, step.exchange())
        assertTrue(server.writes.isEmpty())
        assertEquals(listOf(Waiting(Changes.PIN, "C")), phone.settings.value.waiting)
        // The storage takes it: done, and the plays are on the disk.
        phone.log.appendBytes = { raf, bytes -> raf.write(bytes) }
        assertEquals(InStep.Outcome.DONE, step.exchange())
        assertEquals(1, server.writes.size)
        phone.stop()
        val again = phone(root)
        assertEquals(listOf("s1" to 1_700_000_400L, "s2" to 1_700_000_500L), again.log.shared())
        assertEquals(listOf(Triple("s1", 1_700_000_400L, "playlist:x")), again.log.sources())
    }

    @Test
    fun aPlayAlreadyHereIsGivenTheSourceThatArrivesLater() = runBlocking {
        val root = folder.newFolder()
        val phone = phone(root)
        // Heard of from a device (or a version) that didn't say where it was played from.
        assertEquals(2, phone.log.absorb(listOf("s1" to 400L, "s2" to 500L), emptyList()))
        phone.log.add("mine", 600, "playlist:own")
        val before = phone.log.state.value
        // Now the record says which playlist: the play is given it, and is not a new play.
        assertEquals(0, phone.log.absorb(listOf("s1" to 400L, "s2" to 500L), listOf(Triple("s1", 400L, "playlist:x"), Triple("mine", 600L, "playlist:other"), Triple("gone", 1L, "playlist:y"))))
        assertEquals(listOf("s1" to 400L, "s2" to 500L, "mine" to 600L), phone.log.shared())
        // (A play that has its source keeps it; a source for a play that isn't here is nothing.)
        assertEquals(listOf(Triple("s1", 400L, "playlist:x"), Triple("mine", 600L, "playlist:own")), phone.log.sources())
        // What had been handed out before is as it was.
        assertEquals("", before.from(0))
        // On the disk too, after a restart.
        phone.log.flushed()
        assertEquals("[\"s1\",400,\"playlist:x\"]\n[\"s2\",500]\n[\"mine\",600,\"playlist:own\"]\n", java.io.File(root, "accounts/a/plays.log").readText())
        phone.stop()
        assertEquals(listOf(Triple("s1", 400L, "playlist:x"), Triple("mine", 600L, "playlist:own")), phone(root).log.sources())
    }

    @Test
    fun theLastWordOnOneThingIsTheOneThatWaits() {
        var s = org.medianexus.music.store.Settings()
        s = Changes.pin(s, "A", on = true)
        s = Changes.pin(s, "B", on = true)
        s = Changes.pin(s, "A", on = false)
        s = Changes.shuffle(s, "liked", on = true, now = 5)
        s = Changes.shuffle(s, "liked", on = false, now = 6)
        s = Changes.sort(s, "liked", 2, descending = true, now = 7)
        s = Changes.theme(s, "Rose")
        s = Changes.theme(s, "Green")
        assertEquals(
            listOf(Waiting(Changes.PIN, "B"), Waiting(Changes.UNPIN, "A"), Waiting(Changes.SHUFFLE, "liked", on = false), Waiting(Changes.SORT, "liked", sort = 2, descending = true), Waiting(Changes.THEME, "Green")),
            s.waiting,
        )
        assertEquals(listOf("B"), s.pinnedPlaylists)
        // The phone's own lists and orders are kept and never wait to be shared.
        s = Changes.shuffle(s, "downloads:album:al1", on = true, now = 8)
        s = Changes.sort(s, "playlist:p", sort = 7, descending = false, now = 9)
        assertEquals(5, s.waiting.size)
        assertEquals(7, s.lists.getValue("playlist:p").sort)
        // A time later than the one an item had, whatever clock put that on.
        val ahead = Shared(layout = 1, pins = listOf("A"), pinsAt = NOW + 120)
        assertEquals(NOW + 121, Changes.onto(ahead, listOf(Waiting(Changes.PIN, "Z")), NOW).pinsAt)
        assertFalse(Changes.onto(ahead, listOf(Waiting(Changes.PIN, "A")), NOW) != ahead)
    }

    private companion object {
        const val NOW = PRETEND_NOW
    }
}
