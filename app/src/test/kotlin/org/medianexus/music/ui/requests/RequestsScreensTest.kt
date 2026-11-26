package org.medianexus.music.ui.requests

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.medianexus.music.core.ArtistOffer
import org.medianexus.music.core.Asking
import org.medianexus.music.core.Outside
import org.medianexus.music.core.OutsideArtist
import org.medianexus.music.core.Popular
import org.medianexus.music.core.SongOffer
import org.medianexus.music.requests.Found
import org.medianexus.music.requests.Said

/**
 * The screens of requests as they hold what they show (`Models.kt`) and as they ask
 * (`Asker`): what each asks and when; that **leaving a screen cancels what it looked for**;
 * and that **asking is not a look**: what the person confirmed outlives the page that asked,
 * is done once, and is said wherever the person is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestsScreensTest {
    @get:Rule val limit: Timeout = Timeout.seconds(60)

    private fun outside(note: String, vararg artists: String) = Outside(note = note, artists = artists.map { ArtistOffer(id = it, name = it) })

    // ---- "Not in your library" ---------------------------------------------------------------------

    @Test
    fun aLookWaitsForTheTypingToPauseThenFillsInAsMoreIsKnown() = runTest {
        val asked = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val model = OutsideSearch({ query, soFar ->
            asked += query
            soFar(Found.Got(outside("Looking for songs beyond your library…", "Quillon Vane")))
            gate.await()
            Found.Got(outside("Ask Lidarr for any of these.", "Quillon Vane", "Marrow Lake"))
        })
        backgroundScope.launch { model.run() }
        for (word in listOf("q", "qu", "qui")) {
            model.type(word)
            advanceTimeBy(100)
        }
        assertTrue("nothing asked while the typing goes on", asked.isEmpty())
        assertEquals("qui", model.state.value.query)
        assertTrue(model.state.value.looking)
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("qui"), asked)
        // What is known so far is shown, and the look is still going.
        assertEquals("Looking for songs beyond your library…", model.state.value.found?.note)
        assertTrue(model.state.value.looking)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Quillon Vane", "Marrow Lake"), model.state.value.found?.artists?.map { it.name })
        assertTrue(model.state.value.done)
    }

    @Test
    fun typingAgainStopsTheLookUnderWayAndWhatItSaysAfterwardsIsDropped() = runTest {
        var stopped = false
        var late: ((Outside) -> Unit)? = null
        val model = OutsideSearch({ query, soFar ->
            if (query == "old") {
                late = { found -> soFar(Found.Got(found)) }
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            Found.Got(outside("found for $query"))
        })
        backgroundScope.launch { model.run() }
        model.type("old")
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        model.type("new")
        runCurrent()
        assertTrue("typing again stops the look for the old words", stopped)
        // The library can call `soFar` once more just after a stop, on one of its threads.
        late?.invoke(outside("found for old"))
        assertEquals("new", model.state.value.query)
        assertNull(model.state.value.found)
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        assertEquals("found for new", model.state.value.found?.note)
        late?.invoke(outside("found for old"))
        assertEquals("found for new", model.state.value.found?.note)
    }

    @Test
    fun leavingTheSearchStopsTheLookAndNothingArrivesAfterwards() = runTest {
        var stopped = false
        var late: ((Outside) -> Unit)? = null
        val model = OutsideSearch({ _, soFar ->
            late = { found -> soFar(Found.Got(found)) }
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        })
        val shown = launch { model.run() }
        model.type("quillon vane")
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        shown.cancel()
        runCurrent()
        assertTrue("leaving the search stops what it was looking for", stopped)
        late?.invoke(outside("too late"))
        assertNull(model.state.value.found)
    }

    @Test
    fun aLookThatFinishedIsShownAgainNotAskedAgainAndClearingAsksNothing() = runTest {
        val asked = mutableListOf<String>()
        val first = OutsideSearch({ query, _ -> asked += query; Found.Got(outside("done")) })
        val running = launch { first.run() }
        first.type("lantern")
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        running.cancel()
        val kept = first.state.value
        assertTrue(kept.done)
        // Back on the search, with the same words: what was found, at once, nobody asked.
        val again = OutsideSearch({ query, _ -> asked += query; Found.Got(outside("asked again")) }, kept = kept)
        backgroundScope.launch { again.run() }
        again.type("lantern")
        advanceUntilIdle()
        assertEquals(listOf("lantern"), asked)
        assertEquals("done", again.state.value.found?.note)
        again.type("")
        advanceUntilIdle()
        assertEquals(OutsideSearch.State(), again.state.value)
        assertEquals(listOf("lantern"), asked)
    }

    @Test
    fun lookingAgainKeepsWhatIsShownUntilMoreIsKnown() = runTest {
        var round = 0
        val gate = CompletableDeferred<Unit>()
        val model = OutsideSearch({ _, _ ->
            round++
            if (round == 2) gate.await()
            Found.Got(outside("round $round"))
        })
        backgroundScope.launch { model.run() }
        model.type("paper tides")
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        assertEquals("round 1", model.state.value.found?.note)
        model.again()
        runCurrent()
        // Asked again (something was asked for, and it is to say "Requested"): no jump.
        assertEquals("round 1", model.state.value.found?.note)
        assertTrue(model.state.value.looking)
        gate.complete(Unit)
        runCurrent()
        assertEquals("round 2", model.state.value.found?.note)
    }

    @Test
    fun aLookThatCouldNotBeHadSaysWhyInTheLibrarysWords() = runTest {
        val model = OutsideSearch({ _, _ -> Found.Not("Couldn't reach Lidarr. Offline mode is on.") })
        backgroundScope.launch { model.run() }
        model.type("anything")
        advanceTimeBy(OutsideSearch.WAIT_MILLIS + 1)
        runCurrent()
        assertEquals("Couldn't reach Lidarr. Offline mode is on.", model.state.value.why)
        assertTrue(model.state.value.done)
    }

    // ---- a page of one call -------------------------------------------------------------------------

    @Test
    fun aPageIsAskedWhenShownKeepsWhatItShowsWhenAskingAgainFailsAndLeavingStopsIt() = runTest {
        var answer: Found<String> = Found.Got("On their way (2)")
        var asks = 0
        var stopped = false
        val gate = CompletableDeferred<Unit>()
        val model = PageLoad {
            asks++
            if (asks == 3) {
                try {
                    gate.await()
                } finally {
                    stopped = true
                }
            }
            answer
        }
        val shown = launch { model.run() }
        runCurrent()
        assertEquals("On their way (2)", model.state.value.value)
        answer = Found.Not("Couldn't reach Lidarr. The connection was refused.")
        model.again()
        runCurrent()
        // What was shown stays; why the new ask failed is said beside it.
        assertEquals("On their way (2)", model.state.value.value)
        assertEquals("Couldn't reach Lidarr. The connection was refused.", model.state.value.why)
        model.again()
        runCurrent()
        assertTrue(model.state.value.asking)
        shown.cancel()
        runCurrent()
        assertTrue("leaving the page stops what it asked for", stopped)
        assertEquals(3, asks)
    }

    // ---- an artist beyond the library ---------------------------------------------------------------

    @Test
    fun theirMostPlayedAreAskedOnlyOnceThePageIsShownAndLeavingStopsBoth() = runTest {
        val seen = mutableListOf<String>()
        var popularStopped = false
        lateinit var model: OutsideArtistModel
        model = OutsideArtistModel(
            page = { seen += "page"; Found.Got(OutsideArtist(name = "Quillon Vane", about = "2 albums", open = 1)) },
            popular = { name ->
                // The page is on show before this is asked.
                seen += "popular for $name, page shown: ${model.state.value.artist != null}"
                try {
                    awaitCancellation()
                } finally {
                    popularStopped = true
                }
            },
        )
        val shown = launch { model.run() }
        runCurrent()
        assertEquals(listOf("page", "popular for Quillon Vane, page shown: true"), seen)
        assertEquals("2 albums", model.state.value.artist?.about)
        // Room is kept for what is most played while it is asked for.
        assertNull(model.state.value.popular)
        shown.cancel()
        runCurrent()
        assertTrue("leaving the page stops what it asked for", popularStopped)
    }

    @Test
    fun anArtistPageThatFailedAsksNothingMoreAndLeavesNoRoom() = runTest {
        var popularAsked = false
        val model = OutsideArtistModel(
            page = { Found.Got(OutsideArtist(name = "Marrow Lake", empty = "MusicBrainz answered with an error (404).", failed = true)) },
            popular = { popularAsked = true; Found.Got(Popular()) },
        )
        backgroundScope.launch { model.run() }
        runCurrent()
        assertFalse(popularAsked)
        assertEquals(Popular(), model.state.value.popular)
        assertEquals("MusicBrainz answered with an error (404).", model.state.value.artist?.empty)
    }

    @Test
    fun theirMostPlayedComeAfterAndNoneMeansNothingShown() = runTest {
        val song = SongOffer(title = "Low Water", album = "rg-1", albumTitle = "Tidewater")
        val model = OutsideArtistModel(
            page = { Found.Got(OutsideArtist(name = "Quillon Vane")) },
            popular = { Found.Got(Popular("Popular, and not in your library", listOf(song to false))) },
        )
        backgroundScope.launch { model.run() }
        runCurrent()
        assertEquals("Popular, and not in your library", model.state.value.popular?.heading)
        val none = OutsideArtistModel(page = { Found.Got(OutsideArtist(name = "Nobody")) }, popular = { Found.Not("Couldn't reach ListenBrainz.") })
        backgroundScope.launch { none.run() }
        runCurrent()
        assertEquals(Popular(), none.state.value.popular)
    }

    // ---- asking ----------------------------------------------------------------------------------------

    private class Seen : AskShow {
        val said = mutableListOf<Pair<String, Boolean>>()
        var question: Triple<String, String, AskShow.Button>? = null
        var yes: (() -> Unit)? = null
        var changes = 0

        override fun say(words: String, wayIn: Boolean) {
            said += words to wayIn
        }

        override fun confirm(heading: String, text: String, button: AskShow.Button, onYes: () -> Unit) {
            question = Triple(heading, text, button)
            yes = onYes
        }

        override fun changed() {
            changes++
        }
    }

    private open class Ops(val asked: Asking = Asking.Ask("Ask for this album?", "“Paper Tides” by Quillon Vane will go on Lidarr's list.")) : AskOps {
        val calls = mutableListOf<String>()
        /** Held until opened, while set. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun asking(album: String, title: String, by: String, song: String, kind: String, connection: Long): Asking {
            calls += "asking $album $title $by $song $kind"
            return asked
        }

        override fun askingFor(title: String) = "Asking for “$title”…"

        override suspend fun request(album: String, title: String, song: String, connection: Long): Said {
            calls += "request $album $title $song"
            gate?.await()
            return Said(true, "Asked for “$title”. It'll be in your library once it arrives.")
        }

        override suspend fun adding(name: String, open: Int) = "Add $name?" to "Lidarr will look for the $open album by $name that you don't have."

        override fun addingNow(name: String) = "Adding $name…"

        override suspend fun addArtist(id: String, name: String, connection: Long): Said {
            calls += "add $id $name"
            gate?.await()
            return Said(true, "Added $name. Their albums are on Lidarr's list")
        }

        override suspend fun cancel(entry: org.medianexus.music.core.Pending, connection: Long): Said {
            calls += "cancel ${entry.id} ${entry.title}"
            gate?.await()
            return Said(true, "“${entry.title}” is off the list")
        }
    }

    @Test
    fun askingPutsTheQuestionFirstThenAsksAndSaysHowItWent() = runTest {
        val ops = Ops()
        val show = Seen()
        val asker = Asker(backgroundScope, backgroundScope, ops, show, UnderWay())
        asker.album("rg-pt", "Paper Tides", "Quillon Vane", song = "Low Water", kind = "EP", under = 1)
        runCurrent()
        assertEquals(listOf("asking rg-pt Paper Tides Quillon Vane Low Water EP"), ops.calls)
        assertEquals(AskShow.Button.REQUEST, show.question?.third)
        assertTrue("nothing asked of Lidarr before the question is answered", show.said.isEmpty())
        show.yes!!()
        runCurrent()
        assertEquals("request rg-pt Paper Tides Low Water", ops.calls.last())
        // (As on Windows, "Asked for …" doesn't point to Requests; "already" and "Added" do.)
        assertEquals(listOf("Asking for “Paper Tides”…" to false, "Asked for “Paper Tides”. It'll be in your library once it arrives." to false), show.said)
        assertEquals(1, show.changes)
    }

    @Test
    fun somethingAskedForAlreadyIsSaidNotAskedAgain() = runTest {
        val ops = Ops(Asking.Already("“Paper Tides” has been asked for already."))
        val show = Seen()
        Asker(backgroundScope, backgroundScope, ops, show, UnderWay()).album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        runCurrent()
        assertNull(show.question)
        assertEquals(listOf("“Paper Tides” has been asked for already." to true), show.said)
        assertTrue(ops.calls.none { it.startsWith("request") })
    }

    @Test
    fun leavingThePageMidAskTheAskCompletesOnceAndItsSentenceIsSaid() = runTest {
        val ops = Ops().apply { gate = CompletableDeferred() }
        val show = Seen()
        val page = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val asker = Asker(page, backgroundScope, ops, show, UnderWay())
        asker.album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        runCurrent()
        show.yes!!()
        runCurrent()
        assertEquals(listOf("Asking for “Paper Tides”…" to false), show.said)
        // The page is left while Lidarr is being asked.
        page.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runCurrent()
        ops.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, ops.calls.count { it.startsWith("request") })
        assertEquals("Asked for “Paper Tides”. It'll be in your library once it arrives." to false, show.said.last())
        assertEquals(1, show.changes)
    }

    @Test
    fun aSecondPressWhileTheFirstIsUnderWayDoesNothing() = runTest {
        val ops = Ops().apply { gate = CompletableDeferred() }
        val show = Seen()
        val underWay = UnderWay()
        val asker = Asker(backgroundScope, backgroundScope, ops, show, underWay)
        asker.album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        runCurrent()
        val yes = show.yes!!
        yes()
        runCurrent()
        // Pressed again, from this page or another (the same account's), while it goes on.
        show.question = null
        asker.album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        Asker(backgroundScope, backgroundScope, ops, show, underWay).album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        yes()
        runCurrent()
        assertNull("not asked again", show.question)
        assertEquals(1, ops.calls.count { it.startsWith("asking") })
        assertEquals(1, ops.calls.count { it.startsWith("request") })
        ops.gate!!.complete(Unit)
        runCurrent()
        assertTrue(underWay.going.value.isEmpty())
        // Once it has finished, it can be pressed again (and the library says "already").
        asker.album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        runCurrent()
        assertEquals(2, ops.calls.count { it.startsWith("asking") })
    }

    @Test
    fun theSameThingUnderAnotherConnectionIsAnotherThing() = runTest {
        val ops = Ops().apply { gate = CompletableDeferred() }
        val show = Seen()
        val underWay = UnderWay()
        val asker = Asker(backgroundScope, backgroundScope, ops, show, underWay)
        // Taking number 7 off the first Lidarr's list is under way...
        asker.takeOff(org.medianexus.music.core.Pending(7, "Night Ferry", "Them"), under = 1)
        runCurrent()
        // ...when the app is pointed at another Lidarr, which has a 7 of its own. Pressing
        // that one is not "the same thing again", and is not swallowed.
        asker.takeOff(org.medianexus.music.core.Pending(7, "Somebody Else's", "Them"), under = 2)
        runCurrent()
        assertEquals(listOf("cancel 7 Night Ferry", "cancel 7 Somebody Else's"), ops.calls)
        ops.gate!!.complete(Unit)
        runCurrent()
        assertTrue(underWay.going.value.isEmpty())
    }

    @Test
    fun whatNeverStartedIsNotLeftUnderWay() = runTest {
        val ops = Ops()
        val show = Seen()
        val underWay = UnderWay()
        // (The account went, signed out of, in the moment between the press and its start.)
        val gone = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job().apply { cancel() })
        Asker(backgroundScope, gone, ops, show, underWay).takeOff(org.medianexus.music.core.Pending(7, "Night Ferry", "Them"), under = 1)
        runCurrent()
        assertTrue(ops.calls.isEmpty())
        assertTrue("left under way for ever: ${underWay.going.value}", underWay.going.value.isEmpty())
    }

    @Test
    fun takingOneOffTheListOutlivesThePageAndIsDoneOnce() = runTest {
        val ops = Ops().apply { gate = CompletableDeferred() }
        val show = Seen()
        val underWay = UnderWay()
        val page = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val asker = Asker(page, backgroundScope, ops, show, underWay)
        var heard: Boolean? = null
        asker.takeOff(org.medianexus.music.core.Pending(7, "Night Ferry", "Them"), under = 1) { heard = it }
        asker.takeOff(org.medianexus.music.core.Pending(7, "Night Ferry", "Them"), under = 1)
        runCurrent()
        assertEquals(setOf(UnderWay.offList(7, 1)), underWay.going.value)
        page.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        ops.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("cancel 7 Night Ferry"), ops.calls)
        assertEquals(listOf("“Night Ferry” is off the list" to false), show.said)
        assertEquals(true, heard)
        assertTrue(underWay.going.value.isEmpty())
    }

    @Test
    fun addingAnArtistEndsTheLibrarysSentenceAndOnlyWhenThereIsSomethingToAdd() = runTest {
        val ops = Ops()
        val show = Seen()
        val asker = Asker(backgroundScope, backgroundScope, ops, show, UnderWay())
        asker.artist("qv", "Quillon Vane", open = 0, under = 1)
        runCurrent()
        assertNull("nothing to ask for: no question", show.question)
        asker.artist("qv", "Quillon Vane", open = 1, under = 1)
        runCurrent()
        assertEquals(AskShow.Button.ADD_ARTIST, show.question?.third)
        show.yes!!()
        runCurrent()
        assertEquals(listOf("Adding Quillon Vane…" to false, "Added Quillon Vane. Their albums are on Lidarr's list." to true), show.said)
        assertEquals(1, show.changes)
    }

    @Test
    fun aRefusalIsSaidAsTheLibrarySaysIt() = runTest {
        val ops = object : Ops() {
            override suspend fun request(album: String, title: String, song: String, connection: Long) = Said(false, "Couldn't reach Lidarr. The connection was refused.")
        }
        val show = Seen()
        Asker(backgroundScope, backgroundScope, ops, show, UnderWay()).album("rg-pt", "Paper Tides", "Quillon Vane", under = 1)
        runCurrent()
        show.yes!!()
        runCurrent()
        assertEquals("Couldn't reach Lidarr. The connection was refused." to false, show.said.last())
        assertEquals(0, show.changes)
        assertEquals("Ends a sentence once", "Done.", Asker.ended("Done."))
    }

    @Test
    fun whatWasAskedReachesWhicheverScreenIsAliveForThatAccountOnly() {
        val account = Any()
        val other = Any()
        val before = AskedChanges.of(account).asked.value
        AskedChanges.of(account).outsideKept = "connection" to OutsideSearch.State("lantern", done = true)
        // Said by an ask that outlived its page (and its activity): what a new screen reads.
        AskedChanges.of(account).changed()
        assertEquals(before + 1, AskedChanges.of(account).asked.value)
        assertNull(AskedChanges.of(account).outsideKept)
        assertEquals(0, AskedChanges.of(other).asked.value)
    }
}
