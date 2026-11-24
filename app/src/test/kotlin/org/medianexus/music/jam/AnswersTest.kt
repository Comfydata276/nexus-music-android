package org.medianexus.music.jam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

/**
 * What a session hosted on the phone answers (Windows' `jam_asked` and its helpers), and rule 5:
 * guests are strangers, and every request is bounded as Windows bounds it.
 */
class AnswersTest {
    private fun song(id: String, queued: Boolean = false) = Song(id = id, title = "T$id", artist = "A", album = "B", duration = 100, queued = queued)

    /** A player that does what it is told to a queue of its own. */
    private class Fake(var current: Song?, val ahead: MutableList<Song>, var queuedAhead: Int = 0) : HostPlayer {
        val done = ArrayList<String>()
        var on = true
        private fun uid(i: Int) = 1000L + i
        override fun now() = HostNow(current, ahead.toList(), ahead.indices.map(::uid), queuedAhead, ahead.size, on, 12.0, "List", false, 0, 0.5)
        override fun skip() { done += "skip" }
        override fun previous() { done += "previous" }
        override fun setPlaying(on: Boolean) { done += "playing $on"; this.on = on }
        override fun seek(seconds: Double) { done += "seek $seconds" }
        override fun jump(uid: Long) { done += "jump ${ahead[(uid - 1000).toInt()].id}" }
        override fun move(uid: Long, after: Long?, queued: Boolean) { done += "move ${ahead[(uid - 1000).toInt()].id} after ${after?.let { ahead[(it - 1000).toInt()].id } ?: "now"} $queued" }
        override fun clear(queued: Boolean) { done += "clear $queued" }
        override fun remove(uid: Long) { done += "remove ${ahead[(uid - 1000).toInt()].id}"; ahead.removeAt((uid - 1000).toInt()) }
        override fun shuffle(on: Boolean) { done += "shuffle $on" }
        override fun repeat(mode: Int) { done += "repeat $mode" }
        override fun volume(to: Double) { done += "volume $to" }
        override fun enqueue(songs: List<Song>, next: Boolean) {
            done += "enqueue ${songs.map { it.id }} $next"
            val at = if (next) 0 else queuedAhead
            ahead.addAll(at, songs.map { it.copy(queued = true) })
            queuedAhead += songs.size
        }
        override fun fromNowOn() = listOfNotNull(current) + ahead
    }

    private val library = object : HostLibrary {
        override fun song(id: String) = if (id.startsWith("s")) Song(id = id, title = "T$id") else null
        override fun search(query: String) = emptyList<Song>()
    }

    private val word = "owner-word"
    private val sam = JamGuest("g1", "Sam")
    private val kit = JamGuest("g2", "Kit")

    private fun ask(action: String, data: String, guest: JamGuest = sam) = Ask(1, guest, action, Json.parseToJsonElement(data))

    @Test
    fun aGuestsSongGoesIntoNextInQueueOnceAndIsTheirs() {
        val player = Fake(song("n"), mutableListOf(song("a"), song("b")))
        val answers = Answers(player, library, DefaultAnswers)
        val outcome = answers.answer(ask("add", """{"ids": ["s1", "s2"]}"""), word, guestsSteer = true, found = listOf(song("s1"), song("s2")))
        assertEquals(Answers.Outcome.Done(JsonObject(mapOf("added" to JsonPrimitive(2)))), outcome)
        assertEquals("enqueue [s1, s2] false", player.done.single())
        // Already waiting: not queued a second time.
        assertEquals(Answers.Outcome.No(DefaultAnswers.already), answers.answer(ask("add", """{"id": "s1"}"""), word, true, found = listOf(song("s1"))))
        // Who added them goes with the state, only for those still to come.
        val state = answers.state(true)
        assertEquals("Sam", (state["queue"] as kotlinx.serialization.json.JsonArray)[0].at("by").str())
        assertEquals("g1", (state["queue"] as kotlinx.serialization.json.JsonArray)[0].at("by_id").str())
    }

    @Test
    fun withoutSteeringAGuestMayTakeOutOnlyWhatTheyAdded() {
        val player = Fake(song("n"), mutableListOf(song("a")))
        val answers = Answers(player, library, DefaultAnswers)
        answers.answer(ask("add", """{"id": "s1"}"""), word, guestsSteer = false, found = listOf(song("s1")))
        // Kit can't take Sam's, nor anything else; Sam can take theirs.
        assertEquals(Answers.Outcome.No(DefaultAnswers.ownSongs), answers.answer(ask("remove", """{"at": 0, "id": "s1"}""", kit), word, false))
        assertEquals(Answers.Outcome.No(DefaultAnswers.ownSongs), answers.answer(ask("remove", """{"at": 1, "id": "a"}"""), word, false))
        assertEquals(Answers.Outcome.Done(), answers.answer(ask("remove", """{"at": 0, "id": "s1"}"""), word, false))
        assertEquals(Answers.Outcome.No(DefaultAnswers.hostChooses), answers.answer(ask("skip", "{}"), word, false))
    }

    @Test
    fun onlyThePersonsOwnDevicesGetTheRunOfThePhone() {
        val player = Fake(song("n"), mutableListOf(song("a")))
        val answers = Answers(player, library, DefaultAnswers)
        for (action in listOf("hello", "queue", "load", "volume")) {
            assertEquals(Answers.Outcome.No(DefaultAnswers.ownOnly), answers.answer(ask(action, """{"owner": "a guess"}"""), word, true))
        }
        assertEquals(Answers.Outcome.Done(), answers.answer(ask("volume", """{"to": 7, "owner": "owner-word"}"""), word, false))
        assertEquals("volume 1.0", player.done.last())
        // Own devices may steer whatever the setting.
        assertEquals(Answers.Outcome.Done(), answers.answer(ask("skip", """{"owner": "owner-word"}"""), word, false))
        assertTrue("g1" in answers.owners)
        // And nothing a guest names that isn't one of the actions is done.
        assertEquals(Answers.Outcome.No(DefaultAnswers.notForGuests), answers.answer(ask("format", "{}"), word, true))
        // No word at all on this phone (no saved password): nobody is its owner.
        assertEquals(Answers.Outcome.No(DefaultAnswers.ownOnly), answers.answer(ask("hello", """{"owner": null}"""), null, true))
    }

    @Test
    fun anEntryIsFoundByItsPlaceAndItsSongOrTheNearestCopy() {
        val player = Fake(song("n"), mutableListOf(song("a"), song("b"), song("a"), song("c")))
        val answers = Answers(player, library, DefaultAnswers)
        assertEquals(1, answers.entry(JsonPrimitive(1), JsonPrimitive("b")))
        // The queue moved on: the nearest copy of the song they saw.
        assertEquals(2, answers.entry(JsonPrimitive(3), JsonPrimitive("a")))
        assertEquals(null, answers.entry(JsonPrimitive(0), JsonPrimitive("gone")))
        answers.answer(ask("jump", """{"at": 9, "id": "c"}"""), word, true)
        assertEquals("jump c", player.done.last())
    }

    @Test
    fun aMoveIsMadeAsWindowsMakesIt() {
        val player = Fake(song("n"), mutableListOf(song("a"), song("b"), song("c")))
        val answers = Answers(player, library, DefaultAnswers)
        // From place 2 to place 0: straight after the song playing.
        answers.answer(ask("move", """{"from": 2, "to": 0, "id": "c", "queued": true}"""), word, true)
        assertEquals("move c after now true", player.done.last())
        // From place 0 to place 2 (in what's to come once it has left its own): after c.
        answers.answer(ask("move", """{"from": 0, "to": 2, "id": "a", "queued": false}"""), word, true)
        assertEquals("move a after c false", player.done.last())
        // A place past the end is the end.
        answers.answer(ask("move", """{"from": 0, "to": 99, "id": "a"}"""), word, true)
        assertEquals("move a after c false", player.done.last())
    }

    @Test
    fun whatIsAddedAtOnceAndHandedOverIsBounded() {
        val answers = Answers(Fake(song("n"), mutableListOf()), library, DefaultAnswers)
        val many = (0 until 500).joinToString(",") { "\"s$it\"" }
        assertEquals(MOST_ADDED, answers.wanted(Json.parseToJsonElement("""{"ids": [$many, 5, null, "../x"]}""")).size)
        assertEquals(listOf("s1"), answers.wanted(Json.parseToJsonElement("""{"ids": ["s1", "../../x", 3]}""")))
        val handed = (0 until 5000).joinToString(",") { """{"id": "s$it"}""" }
        val (songs, index) = answers.handed(Json.parseToJsonElement("""{"songs": [$handed], "index": 4500}"""))
        assertEquals(MOST_HANDED, songs.size)
        // A start past what is taken is no start, and the first song is (as Windows has it:
        // a sender hands a long list over from its start for that reason).
        assertEquals(0, index)
        // Songs this library doesn't have are left out, and the start moves with them.
        val (some, at) = answers.handed(Json.parseToJsonElement("""{"songs": [{"id": "x1"}, {"id": "s1"}, {"id": "x2"}, {"id": "s2"}], "index": 2}"""))
        assertEquals(listOf("s1", "s2"), some.map { it.id })
        assertEquals(1, at)
    }

    @Test
    fun theStateShowsAHundredSongsAndCountsTheRest() {
        val player = Fake(song("n"), MutableList(250) { song("q$it") })
        val state = Answers(player, library, DefaultAnswers).state(guestsSteer = false)
        assertEquals(SHOWN, (state["queue"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(150L, state["more"].u64())
        assertEquals(false, state["control"].bool())
        // Read back by a guest exactly as the Windows host's state is.
        val read = JamState.read(state)
        assertEquals("n", read.now?.id)
        assertEquals(SHOWN, read.queue.size)
    }

    @Test
    fun aHandOverIsWhatPlaysAndEverythingAfterIt() {
        val player = Fake(song("n"), mutableListOf(song("a", queued = true), song("b")))
        val handed: JsonElement = Answers(player, library, DefaultAnswers).handOver()
        assertEquals(listOf("n", "a", "b"), handed.at("songs").list().map { it.at("id").str() })
        assertEquals(true, handed.at("songs").list()[1].at("queued").bool())
        assertEquals(true, handed.at("playing").bool())
        assertEquals(12.0, handed.at("position").f64()!!, 0.0)
        assertFalse(handed.at("songs").list()[0].at("queued").bool() == true)
    }
}
