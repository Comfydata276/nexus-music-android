package org.medianexus.music.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.net.Song

/**
 * A device steered by the new linking, in the phone's player ([LinkSteer]): the library's
 * view as the screens are handed it, each button as the library is told of it, and whose
 * the player is when something else is in it.
 */
class LinkSteerTest {
    private val steered = Steered()
    private val told = ArrayList<String>()
    private val notes = ArrayList<String>()
    private var letGo = 0
    private var steeringOne = true
    private val mine = mapOf("s1" to Song(id = "s1", title = "Song 1 as the phone has it", coverArt = "cover-1", duration = 200))
    private val steer = LinkSteer(steered, mine::get, press = { told += it.toString(); steeringOne }, letGo = { letGo++ }, note = { notes += it })

    private fun view(playing: Boolean = true): JsonObject = Json.parseToJsonElement(
        """{"full":{"position":41.5,"now":{"id":"s0","title":"Now","artist":"Them","album":"Theirs","duration":300},
           "queue":[{"id":"s1","title":"One","artist":"Them","album":"Theirs","duration":200,"queued":true},
                    {"id":"s2","title":"Two","artist":"Them","album":"Theirs","duration":180,"queued":true},
                    {"id":"s3","title":"Three","artist":"Them","album":"Theirs","duration":170}],
           "more":40,"context":"Morning mix","shuffle":true,"repeat":2,"volume":0.5},"playing":$playing,"heard_ms":9000}""",
    ).jsonObject

    @Test
    fun theLibrarysViewIsWhatThePlayerShows() {
        steer.steering("Study PC")
        assertNull("nothing is shown before the device has been heard from", steered.shown.value)
        steer.view(view())
        val shown = steered.shown.value ?: error("the device is not in the player")
        assertSame(steer.hands, steered.hands)
        assertEquals("Study PC", shown.where)
        // A song the phone has not got is shown as the device described it.
        assertEquals(Song(id = "s0", title = "Now", artist = "Them", album = "Theirs", duration = 300), shown.song)
        assertEquals(listOf(true, 41_500L, 9_000L, 300_000L), listOf(shown.playing, shown.positionMs, shown.at, shown.durationMs))
        // Its place moves on from when it was heard, on the clock the library is told the time by.
        assertEquals(43_500L, shown.positionAt(11_000))
        assertEquals(listOf(true, 2, 0.5f), listOf(shown.shuffle, shown.repeat, shown.volume))
        // One the phone has is the phone's own copy of it: its cover, and what else it knows.
        assertEquals(listOf("Song 1 as the phone has it", "Two", "Three"), shown.queue.songs.map { it.title })
        assertEquals("cover-1", shown.queue.songs[0].coverArt)
        assertEquals(listOf(2, "Morning mix", 43), listOf(shown.queue.queuedAhead, shown.queue.context, shown.queue.total))
        assertEquals(listOf(LinkSteer.ROW, LinkSteer.ROW + 1, LinkSteer.ROW + 2), shown.queue.uids)
        // Paused: it stays where it was heard.
        steer.view(view(playing = false))
        assertEquals(41_500L, steered.shown.value?.positionAt(60_000))
        // The library says no device is steered: the player is the phone's own again.
        steer.steering(null)
        assertNull(steered.shown.value)
        assertNull(steered.hands)
    }

    @Test
    fun eachButtonIsToldAsTheLibraryReadsIt() {
        steer.steering("Study PC")
        steer.view(view())
        val hands = steered.hands ?: error("no hands")
        val row = { at: Int -> LinkSteer.ROW + at }
        hands.toggle(); hands.next(); hands.previous(); hands.shuffle(); hands.repeat()
        hands.seek(61_500)
        hands.volume(0.25f)
        hands.remove(row(1))
        hands.jump(row(2))
        hands.clear(true)
        // Row 2 put straight after row 0, into "Next in queue"; and row 0 put first.
        hands.move(row(2), row(0), true)
        hands.move(row(1), null, false)
        // Row 0 put after row 2: its place once it has left its own.
        hands.move(row(0), row(2), false)
        val pressed = { press: String -> """{"event":"press","press":$press}""" }
        assertEquals(
            listOf(
                pressed("\"toggle\""), pressed("\"next\""), pressed("\"previous\""), pressed("\"shuffle\""), pressed("\"repeat\""),
                pressed("""{"seek":61.5}"""), pressed("""{"volume":0.25}"""), pressed("""{"remove":1}"""), pressed("""{"jump":2}"""), pressed("""{"clear":true}"""),
                pressed("""{"move":[2,1,true]}"""), pressed("""{"move":[1,0,false]}"""), pressed("""{"move":[0,2,false]}"""),
            ),
            told,
        )
        // A row that is not one of the device's (an entry of the phone's own) names nothing.
        told.clear()
        hands.remove(7)
        hands.jump(AppCommands.NONE)
        hands.move(7, row(0), false)
        assertEquals(emptyList<String>(), told)
        // Songs queued and a list played from anywhere in the app go to the device.
        hands.add(listOf(Song(id = "a"), Song(id = "b")), playNext = true)
        hands.play(listOf(Song(id = "a"), Song(id = "b"), Song(id = "c")), 1, "An album", whole = false)
        assertEquals(listOf("""{"event":"add","ids":["a","b"],"next":true}""", """{"event":"play","ids":["a","b","c"],"start":1,"context":"An album"}"""), told)
        // No more than one Add carries is written out for it.
        told.clear()
        hands.add(List(120) { Song(id = "s$it") }, playNext = false)
        assertEquals(LinkSteer.MOST_ADDED, Json.parseToJsonElement(told.single()).jsonObject.getValue("ids").let { it as kotlinx.serialization.json.JsonArray }.size)
    }

    /** The Songs page plays "every song in the library, from this one": what one message
     *  cannot carry is not written out to be cut. */
    @Test
    fun aListLongerThanAMessageCarriesIsHandedOverFromTheSongChosen() {
        steer.steering("Study PC")
        steer.view(view())
        val songs = List(LinkSteer.MOST_PLAYED + 500) { Song(id = "s$it") }
        steered.hands!!.play(songs, 700, "Songs", whole = false)
        val event = Json.parseToJsonElement(told.single()).jsonObject
        val ids = event.getValue("ids") as kotlinx.serialization.json.JsonArray
        assertEquals(LinkSteer.MOST_PLAYED - 200, ids.size)
        assertEquals("\"s700\"", ids.first().toString())
        assertEquals("0", event.getValue("start").toString())
        // One that fits is handed over whole, from where it was started.
        told.clear()
        steered.hands!!.play(songs.take(900), 700, "Songs", whole = false)
        val whole = Json.parseToJsonElement(told.single()).jsonObject
        assertEquals(listOf(900, "700"), listOf((whole.getValue("ids") as kotlinx.serialization.json.JsonArray).size, whole.getValue("start").toString()))
    }

    private object Jam : Steered.Hands {
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun remove(uid: Long) = Unit
        override fun move(uid: Long, after: Long?, queued: Boolean) = Unit
        override fun clear(queued: Boolean) = Unit
        override fun jump(uid: Long) = Unit
        override fun shuffle() = Unit
        override fun repeat() = Unit
        override fun volume(to: Float) = Unit
        override fun add(songs: List<Song>, playNext: Boolean) = Unit
        override fun play(songs: List<Song>, start: Int, context: String, whole: Boolean) = Unit
    }

    @Test
    fun whatElseIsInThePlayerStaysAndTheDeviceIsLetGoOf() {
        val jam = LinkSteer.shown(view(), "Jo's jam") { null }
        steered.show(jam, Jam)
        steer.steering("Study PC")
        steer.view(view())
        assertEquals("Jo's jam", steered.shown.value?.where)
        assertSame(Jam, steered.hands)
        assertEquals(1, letGo)
        // And nothing of the linking's takes a jam out of the player: not the library
        // saying it steers nothing, not a queue being taken, not the linking ending.
        steer.steering(null)
        steer.taking()
        steer.clear()
        assertNotNull(steered.shown.value)
        // A jam that takes the player's place while a device is steered: at the next
        // word of the device, the device is let go of.
        steered.clear()
        steer.steering("Study PC")
        steer.view(view())
        assertEquals("Study PC", steered.shown.value?.where)
        steered.show(jam, Jam)
        steer.view(view())
        assertEquals(listOf("Jo's jam", 2), listOf(steered.shown.value?.where, letGo))
    }

    /** "Play here": the device is out of the phone's player before the player is asked to
     *  take its queue, which the service refuses while its player shows another's. */
    @Test
    fun theDeviceIsOutOfThePlayerAsItsQueueIsTaken() {
        steer.steering("Study PC")
        steer.view(view())
        assertNotNull(steered.shown.value)
        steer.taking()
        assertNull(steered.shown.value)
        // A view with nothing in it, or one that comes with no device steered, shows nothing.
        steer.view(view())
        assertNotNull(steered.shown.value)
        steer.view(JsonNull)
        assertNull(steered.shown.value)
        steer.steering(null)
        steer.view(view())
        assertNull(steered.shown.value)
        // A press the library says found nothing steered is noted, and nothing more.
        steer.steering("Study PC")
        steer.view(view())
        steeringOne = false
        steered.hands!!.next()
        assertTrue(notes.single().contains("found none being steered"))
    }

    // ---- a row is the entry it was drawn from (review X2) ------------------------------------

    /** A view with `now` playing and `queue` to come, each song by its id. */
    private fun viewOf(now: String, vararg queue: String, context: String = ""): JsonObject = Json.parseToJsonElement(
        """{"full":{"position":1.0,"now":{"id":"$now","title":"$now","artist":"Them","album":"Theirs","duration":200},
           "queue":[${queue.joinToString(",") { """{"id":"$it","title":"$it","artist":"Them","album":"Theirs","duration":200}""" }}],
           "more":0,"context":"$context","shuffle":false,"repeat":0,"volume":1.0},"playing":true,"heard_ms":9000}""",
    ).jsonObject

    @Test
    fun aMenuOpenedOnBNeverRemovesTheSongThatTookBsRow() {
        steer.steering("Study PC")
        steer.view(viewOf("a", "b", "c"))
        // The queue sheet's menu is opened on B: it keeps B's row.
        val b = steered.shown.value!!.queue.uids[0]
        // The device moves on to B: C is now where B was.
        steer.view(viewOf("b", "c"))
        told.clear()
        val hands = steered.hands!!
        hands.remove(b)
        hands.jump(b)
        hands.move(b, null, false)
        assertEquals("B is playing, not to come: nothing is asked, and never of C", emptyList<String>(), told)
    }

    @Test
    fun aRowKeepsItsEntryWhenOthersComeBeforeIt() {
        steer.steering("Study PC")
        steer.view(viewOf("a", "b", "c"))
        val (b, c) = steered.shown.value!!.queue.uids
        // Something is put before them on the device.
        steer.view(viewOf("a", "x", "b", "c"))
        assertEquals("their rows are the same entries", listOf(b, c), steered.shown.value!!.queue.uids.drop(1))
        told.clear()
        steered.hands!!.remove(b)
        steered.hands!!.jump(c)
        assertEquals(listOf("""{"event":"press","press":{"remove":1}}""", """{"event":"press","press":{"jump":2}}"""), told)
    }

    @Test
    fun anotherDeviceSteeredHasRowsOfItsOwn() {
        steer.steering("Study PC")
        steer.view(viewOf("a", "b"))
        val b = steered.shown.value!!.queue.uids[0]
        steer.steering("Laptop")
        steer.view(viewOf("a", "b"))
        told.clear()
        steered.hands!!.remove(b)
        // (The same song at the same place on another device is not the row the menu was opened on.)
        assertEquals(emptyList<String>(), told)
    }

    @Test
    fun aRowNamesNothingInAQueueChosenAnewThere() {
        // The reviewer's sequence: A playing, C then B to come, B's menu open.
        steer.steering("Study PC")
        steer.view(viewOf("a", "c", "b", context = "Morning mix"))
        val b = steered.shown.value!!.queue.uids[1]
        // On the device, a different list: D playing, a single B to come.
        steer.view(viewOf("d", "b", context = "Evening"))
        told.clear()
        steered.hands!!.remove(b)
        steered.hands!!.jump(b)
        assertEquals("the new list's B is not the row the menu was opened on", emptyList<String>(), told)
    }
}
