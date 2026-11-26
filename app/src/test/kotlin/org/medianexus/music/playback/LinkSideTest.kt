package org.medianexus.music.playback

import android.app.Notification
import android.os.Looper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.App
import org.medianexus.music.core.CoreHttp
import org.medianexus.music.graph
import org.medianexus.music.net.Song
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's player as linking asks it ([LinkSide]), **against the real player**: the
 * playback service whole, its [QueuePlayer] and ExoPlayer. What the Windows repository's
 * `docs/linking-design.md`, 8.1, says each answer owes.
 *
 * Linking is switched off in these, as it is in every build: the service makes none of it,
 * and the first test is that. The side under test is made here, beside the service's player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class LinkSideTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: PlaybackService
    private lateinit var inside: PlaybackService.Inside
    private val server = StandInServer()
    private val android = Android()
    private val owner = Owner()
    private lateinit var side: LinkSide

    /** Android's foreground, which lets the service in, or does not. */
    private class Android : PlaybackService.Foreground {
        var allows = true
        var inIt = false
        override fun isIn() = inIt
        override fun enter(notification: Notification) {
            if (!allows) throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
            inIt = true
        }

        override fun leave() {
            inIt = false
        }
    }

    private class Owner : LinkSide.Owner {
        override var taken: String? = null
        var claims = 0
        var written = 0
        var writes = true
        override fun claim() {
            claims++
            taken = null
        }

        override fun written(): Boolean {
            written++
            return writes
        }
    }

    /** Every song the phone can make, by id; and one it cannot be asked about at all. */
    private val library = (mine() + theirs()).associateBy { it.id }
    private var songForFails: String? = null

    @Before
    fun start() {
        kotlinx.coroutines.runBlocking { app.graph.session.restored() }
        service = Robolectric.buildService(PlaybackService::class.java).create().get()
        service.foreground = android
        inside = service.inside()
        server.start()
        server.signIn(app)
        settle()
        side = LinkSide(inside.player, inside.exo, owner, songFor = { id ->
            check(id != songForFails) { "the phone's copy of the library could not be read" }
            library[id]
        }, mayPlay = { android.allows })
    }

    @After
    fun stop() {
        side.detach()
        service.onDestroy()
        settle()
        server.stop()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun mine() = List(6) { Song(id = "s$it", title = "Song $it", artist = "Somebody", duration = 200) }
    private fun theirs() = List(3) { Song(id = "pc$it", title = "Theirs $it", duration = 200) }

    private val nothing: JsonElement = JsonPrimitive("nothing")
    private fun entry(place: Int): JsonElement = buildJsonObject { put("entry", place) }
    private fun command(name: String, with: JsonObject? = null): JsonObject = buildJsonObject {
        put("do", name)
        with?.let { put("with", it) }
    }

    private fun carry(name: String, with: JsonObject? = null, ready: JsonElement = nothing): Int = side.carryOut(command(name, with), ready)

    private fun ids(): List<String> = inside.player.queue.playingOrder().map { it.id }
    private fun title(): String = side.brief().getValue("title").jsonPrimitive.content
    private fun queueOf(songs: List<Song>, begin: Int = 0, position: Double = 0.0): JsonObject = buildJsonObject {
        put("songs", buildJsonArray { songs.forEach { add(buildJsonArray { add(JsonPrimitive(it.id)); add(JsonPrimitive(false)) }) } })
        put("begin", begin)
        put("position", position)
        put("context", "From the PC")
    }

    @Test
    fun withLinkingSwitchedOffNothingOfItExists() {
        assertFalse(org.medianexus.music.link.LinkBuild.on)
        // The service made no linking, pointed nothing of the library's requests at the
        // music server or a relay, and its player answers as it always did.
        assertNull(Linking.current.get())
        assertNull(CoreHttp.linkMusic())
        assertNull(CoreHttp.linkRelay())
        assertNull(CoreHttp.linkClient())
    }

    @Test
    fun aPlayerWithNothingInItSaysSoInEveryAnswer() {
        assertFalse(side.hasSong())
        assertEquals("""{"playing":false,"title":"","artist":""}""", side.brief().toString())
        val full = side.full()
        assertEquals("null", full.getValue("now").toString())
        assertEquals(0, full.getValue("coming").jsonArray.size)
        assertEquals(0L, full.getValue("in_all").jsonPrimitive.long)
        assertEquals(0, side.handed().getValue("songs").jsonArray.size)
        assertEquals(0, side.ahead().size)
        assertEquals(0, side.toCome())
        assertNull(side.sounding())
        // And every command that needs a song is refused, and changes nothing.
        for (name in listOf("play", "next", "previous")) assertEquals(name, LinkSide.REFUSED, carry(name))
        assertEquals(LinkSide.REFUSED, carry("seek", buildJsonObject { put("to", 10.0) }))
    }

    @Test
    fun whatItHoldsIsSaidAsTheNextCommandWouldFindIt() {
        inside.player.play(mine(), 1, "A list", playNow = false)
        settle()
        assertEquals("""{"playing":false,"title":"Song 1","artist":"Somebody"}""", side.brief().toString())
        val full = side.full()
        assertEquals("s1", full.getValue("now").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(listOf("s2", "s3", "s4", "s5"), full.getValue("coming").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(4L, full.getValue("in_all").jsonPrimitive.long)
        assertEquals("A list", full.getValue("context").jsonPrimitive.content)
        // What is to come, in the order it would play: the first is what Next goes to.
        assertEquals(listOf("s2", "s3", "s4", "s5"), side.ahead().map { it.jsonPrimitive.content })
        assertEquals(4, side.toCome())
        // What another device could carry on with: from the song playing on.
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), side.handed().getValue("songs").jsonArray.map { it.jsonPrimitive.content })
        // A radio station cannot be handed on, and is left out where it is further down.
        inside.player.play(listOf(Song(id = "r1", title = "A station", radioUrl = "http://radio.example/stream")) + mine(), 0, "", playNow = false)
        settle()
        assertEquals(0, side.handed().getValue("songs").jsonArray.size)
        assertEquals(LinkSide.REFUSED, carry("seek", buildJsonObject { put("to", 10.0) }))
    }

    /**
     * The phone may truthfully refuse to start playing (Android will not let the service into
     * the foreground): that is a refusal, and is not said as "it was so already".
     */
    @Test
    fun aPlayThePhoneRefusesIsARefusalAndNotSoAlready() {
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        android.allows = false
        val asItWas = inside.exo.playbackState
        assertEquals(LinkSide.REFUSED, carry("play"))
        assertFalse(inside.exo.playWhenReady)
        assertFalse(side.playing())
        // (Asked before anything is made ready: it is exactly as it was.)
        assertEquals(asItWas, inside.exo.playbackState)
        // Allowed, it changes the player; asked again, it is so already.
        android.allows = true
        assertEquals(LinkSide.CHANGED, carry("play"))
        assertTrue(side.playing())
        // (And made ready, as the play button makes it: a queue that was only put in the
        // player, and never played in this run, would otherwise sit set to play and silent.)
        assertNotEquals(androidx.media3.common.Player.STATE_IDLE, inside.exo.playbackState)
        assertEquals(LinkSide.SO_ALREADY, carry("play"))
        // Set to play is not playing: a player that has stopped by itself (an error, the
        // end of the queue) is started by Play, which is a change and not "so already".
        inside.exo.stop()
        // (The player over ExoPlayer hears of it as it hears of anything: a moment later.)
        settle()
        assertTrue(inside.exo.playWhenReady)
        assertFalse(side.playing())
        assertEquals(LinkSide.CHANGED, carry("play"))
        assertTrue(side.playing())
        // The stop that ends a move is judged by whether the player then says it is playing.
        assertEquals(LinkSide.CHANGED, carry("moved"))
        assertFalse(side.playing())
        assertEquals(LinkSide.SO_ALREADY, carry("pause"))
        // A phone's volume is the phone's own buttons': refused, and said as 1.
        assertEquals(LinkSide.REFUSED, carry("volume", buildJsonObject { put("to", 0.3) }))
        assertEquals("1.0", side.full().getValue("volume").toString())
    }

    /**
     * A command from another device is carried out, and then the player's own listeners catch
     * up with it, a moment later, as they do. **Nothing those echoes do looks like a change**:
     * what the library is told of the player as the command returns is what it still holds
     * when everything posted has run. (So one remote Next is one choice, not two.)
     */
    @Test
    fun aCommandsLaterEchoesAreNotAChange() {
        inside.player.play(mine(), 0, "A list", playNow = true)
        settle()
        for ((name, with) in listOf("next" to null, "seek" to buildJsonObject { put("to", 12.5) }, "pause" to null, "next" to null, "play" to null, "previous" to null)) {
            val before = side.known()
            val title = title()
            assertEquals(name, LinkSide.CHANGED, carry(name, with))
            // As the call returns: what is current is said at once, not when the cursor follows.
            val after = side.known()
            val said = title()
            if (name == "next") {
                assertNotEquals("$name changed nothing that is compared", before, after)
                assertNotEquals(title, said)
            }
            // Everything the player posted for it runs.
            settle()
            // (Whether it sounds is left out: with no audio to play here, the player gives
            // up in its own time, which is a change of its own and no echo.)
            assertEquals("the echoes of $name changed what is compared", after.copy(sounds = false), side.known().copy(sounds = false))
            assertEquals(said, title())
        }
        // Something the app does by itself is a change: the queue running on is found.
        val before = side.known()
        inside.player.passTo(inside.player.queue.cursor + 1)
        settle()
        assertNotEquals(before, side.known())
    }

    /**
     * The queue's number moves when a whole queue is in the player in place of what was
     * there, and at no other time; and if a queue could not be put in, it has not moved.
     */
    @Test
    fun theQueuesNumberMovesForAWholeQueueAndNotForAChangeInPlace() {
        val empty = side.queueNumber()
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        val first = side.queueNumber()
        assertNotEquals(empty, first)
        // Changes in place: each is carried out, and the number stays.
        assertEquals(LinkSide.CHANGED, carry("add", buildJsonObject { put("ids", buildJsonArray { add(JsonPrimitive("pc0")) }); put("next", true) }, buildJsonObject { put("songs", buildJsonArray { add(JsonPrimitive("pc0")) }) }))
        assertEquals(listOf("s0", "pc0", "s1"), ids().take(3))
        assertEquals(LinkSide.CHANGED, carry("remove", buildJsonObject { put("at", 0); put("id", "pc0") }, entry(0)))
        assertEquals(LinkSide.CHANGED, carry("move", buildJsonObject { put("from", 2); put("to", 0); put("queued", true); put("id", "s3") }, entry(2)))
        assertEquals(listOf("s0", "s3", "s1", "s2"), ids().take(4))
        assertEquals(LinkSide.SO_ALREADY, carry("move", buildJsonObject { put("from", 0); put("to", 0); put("queued", true); put("id", "s3") }, entry(0)))
        assertEquals(LinkSide.CHANGED, carry("shuffle", buildJsonObject { put("on", true) }))
        assertEquals(LinkSide.SO_ALREADY, carry("shuffle", buildJsonObject { put("on", true) }))
        assertEquals(LinkSide.CHANGED, carry("shuffle", buildJsonObject { put("on", false) }))
        assertEquals(LinkSide.CHANGED, carry("repeat", buildJsonObject { put("mode", 1) }))
        assertEquals(LinkSide.CHANGED, carry("jump", buildJsonObject { put("at", 1); put("id", "x") }, entry(1)))
        assertEquals(LinkSide.CHANGED, carry("clear", buildJsonObject { put("queued", false) }))
        assertEquals(LinkSide.SO_ALREADY, carry("clear", buildJsonObject { put("queued", false) }))
        assertEquals(LinkSide.REFUSED, carry("remove", buildJsonObject { put("at", 9); put("id", "gone") }, entry(9)))
        settle()
        assertEquals("a change to the queue in place moved its number", first, side.queueNumber())

        // A queue to take, none of whose songs the phone can make: nothing is put in.
        side.take(queueOf(listOf(Song(id = "nobody-has-this"))))
        assertEquals(first, side.queueNumber())
        // One that fails part-way: the failure goes out (it is a fault), and the number has
        // not moved, with the player's queue as it was.
        val held = ids()
        songForFails = "pc1"
        assertThrows(IllegalStateException::class.java) { side.take(queueOf(theirs())) }
        assertEquals(first, side.queueNumber())
        assertEquals(held, ids())
        songForFails = null
        // And one that goes in: a new number, playing from where it was asked to.
        side.take(queueOf(theirs(), begin = 1, position = 30.0))
        assertNotEquals(first, side.queueNumber())
        assertEquals(listOf("pc0", "pc1", "pc2"), ids())
        assertEquals("Theirs 1", title())
        assertTrue(side.playing())
        // A queue sent as a command says the same by the same number.
        val second = side.queueNumber()
        val adopt = buildJsonObject { put("songs", JsonArray(emptyList())); put("index", 0); put("queued", 0); put("position", 0.0); put("context", "") }
        val ready = buildJsonObject { put("queue", buildJsonArray { add(buildJsonArray { mine().take(2).forEach { add(buildJsonArray { add(JsonPrimitive(it.id)); add(JsonPrimitive(false)) }) } }); add(JsonPrimitive(0)) }) }
        assertEquals(LinkSide.CHANGED, carry("adopt", adopt, ready))
        assertNotEquals(second, side.queueNumber())
        // Emptying the player is another number too.
        val fourth = side.queueNumber()
        inside.player.clear()
        assertNotEquals(fourth, side.queueNumber())
    }

    /** What the player says of its audio, as it says it: of the entry it belongs to. */
    private fun audioOf(timeline: androidx.media3.common.Timeline, window: Int, period: Long? = 1) =
        androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime(
            0, timeline, window, period?.let { androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any(), it) }, 0,
            inside.exo.currentTimeline, inside.exo.currentMediaItemIndex, null, 0, 0,
        )

    /** `period`: which loading of the entry the audio is of; null for an event that has
     *  none of its own, which the player fills in from whatever is current. */
    private fun audioNow(period: Long? = 1) = audioOf(inside.exo.currentTimeline, inside.exo.currentMediaItemIndex, period)

    /**
     * Sound is the sound of the entry a queue went in with, for a move of music: what the
     * player says, late, of the queue that was there before does not stand for it, and
     * nor does the sound of whatever plays after that entry.
     */
    @Test
    fun soundIsTheSoundOfTheEntryAQueueWentInWithAndOfNoOther() {
        var playing = true
        side = LinkSide(inside.player, inside.exo, owner, songFor = { library[it] }, isPlaying = { playing })
        side.attach()
        // A queue the person started is waited on by no move: it is never said to sound.
        inside.player.play(mine(), 0, "A list", playNow = true)
        side.hears.onAudioPositionAdvancing(audioNow(), 0)
        assertNull(side.sounding())
        // What the player has to say of that queue's audio, kept for a moment.
        val late = audioNow()
        side.take(queueOf(theirs()))
        assertNull("a queue that was only put in was said to be sounding", side.sounding())
        // It arrives now, with the new queue in the player: it is the old entry's.
        side.hears.onAudioPositionAdvancing(late, 0)
        assertNull("the sound of the queue before was taken for this queue's", side.sounding())
        // An event with no period of its own is filled in from whatever is current, which
        // is this queue's entry whatever the audio was of: it proves nothing.
        side.hears.onAudioPositionAdvancing(audioNow(period = null), 0)
        assertNull("an event the player filled in from what is current was taken for this queue's sound", side.sounding())
        // The audio of the entry this queue went in with.
        side.hears.onAudioPositionAdvancing(audioNow(), 0)
        assertEquals(side.queueNumber(), side.sounding()?.getValue("queue")?.jsonPrimitive?.long)
        // Given a new place to play from, what was heard was before it; and a word of
        // that same loading of the entry, arriving late, does not bring it back.
        inside.player.seekTo(40_000)
        assertNull(side.sounding())
        side.hears.onAudioPositionAdvancing(audioNow(), 0)
        assertNull("sound from before a seek was believed after it", side.sounding())
        // The audio of a loading that began after does.
        side.hears.onAudioPositionAdvancing(audioNow(period = 2), 0)
        assertNotNull(side.sounding())
        // A player that is not playing is not sounding, whatever was heard.
        playing = false
        assertNull(side.sounding())
        playing = true
        assertNotNull(side.sounding())
        // The entry begins again by itself (a song on repeat), or the next one begins:
        // the entry the move waited on is over, and what sounds now is not its proof.
        val nowhere = androidx.media3.common.Player.PositionInfo(null, 0, null, null, 0, 0L, 0L, androidx.media3.common.C.INDEX_UNSET, androidx.media3.common.C.INDEX_UNSET)
        side.watches.onPositionDiscontinuity(nowhere, nowhere, androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        side.hears.onAudioPositionAdvancing(audioNow(period = 3), 0)
        assertNull("the sound after the entry had ended was taken for its proof", side.sounding())
        // And another entry being the current one ends it by itself.
        side.take(queueOf(theirs()))
        side.hears.onAudioPositionAdvancing(audioNow(), 0)
        assertNotNull(side.sounding())
        inside.exo.seekToNextMediaItem()
        side.hears.onAudioPositionAdvancing(audioNow(), 0)
        assertNull(side.sounding())
    }

    /** The entries queued by hand go in as queued by hand, and are said so again; what
     *  cannot be made is left out, and a queue that cannot begin where it was asked to
     *  begins with the next entry it has, from its top. */
    @Test
    fun aQueueTakenKeepsWhatWasQueuedByHandAndBeginsWhereItCan() {
        fun queue(vararg entries: Pair<String, Boolean>, begin: Int = 0, position: Double = 0.0) = buildJsonObject {
            put("songs", buildJsonArray { entries.forEach { (id, queued) -> add(buildJsonArray { add(JsonPrimitive(id)); add(JsonPrimitive(queued)) }) } })
            put("begin", begin)
            put("position", position)
            put("context", "From the PC")
        }
        side.take(queue("s0" to false, "pc0" to false, "pc1" to true, "pc2" to true, "s1" to false, "s2" to true, begin = 1, position = 30.0))
        assertEquals(listOf("s0", "pc0", "pc1", "pc2", "s1", "s2"), ids())
        assertEquals("Theirs 0", title())
        assertEquals(30_000L, inside.exo.currentPosition)
        // Two are next in the queue; the one marked further on is not (it is not in the run).
        assertEquals(2, inside.player.queue.queuedAhead())
        val handed = side.handed()
        assertEquals(listOf("pc0", "pc1", "pc2", "s1", "s2"), handed.getValue("songs").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2L, handed.getValue("queued").jsonPrimitive.long)
        // One of the queued cannot be made: it is left out, and the other is still queued.
        side.take(queue("pc0" to false, "nobody-has-this" to true, "pc2" to true, "s1" to false))
        assertEquals(listOf("pc0", "pc2", "s1"), ids())
        assertEquals(1, inside.player.queue.queuedAhead())
        assertEquals(1L, side.handed().getValue("queued").jsonPrimitive.long)
        // The one to begin with cannot be made: the next that can, from its top, and what
        // was queued after it still is.
        side.take(queue("s0" to false, "nobody-has-this" to false, "pc1" to true, "pc2" to true, "s1" to false, begin = 1, position = 30.0))
        assertEquals(listOf("s0", "pc1", "pc2", "s1"), ids())
        assertEquals("Theirs 1", title())
        assertEquals(0L, inside.exo.currentPosition)
        assertEquals(1, inside.player.queue.queuedAhead())
    }

    /** A queue the player already holds, handed to the session to be carried on with and
     *  come back, is not another whole queue. */
    @Test
    fun theSameQueueHandedToTheSessionAndBackIsNotAnotherQueue() {
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        val number = side.queueNumber()
        val (items, index) = inside.player.stageCurrent()!!
        inside.player.setMediaItems(items, index, 0L)
        settle()
        assertEquals("the same queue, loaded again, was given another number", number, side.queueNumber())
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "s5"), ids())
    }

    /**
     * What a command is answered with is the player after it against the player before
     * it: one that moved nothing is a refusal, and one that starts playing asks whether
     * the phone may, before it puts anything in or moves to anything.
     */
    @Test
    fun aCommandThatMovedNothingIsARefusalAndOneThatMayNotStartChangesNothing() {
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        // A seek to where it is moves nothing; one elsewhere does, once.
        assertEquals(LinkSide.REFUSED, carry("seek", buildJsonObject { put("to", 0.0) }))
        assertEquals(LinkSide.CHANGED, carry("seek", buildJsonObject { put("to", 20.0) }))
        assertEquals(LinkSide.REFUSED, carry("seek", buildJsonObject { put("to", 20.0) }))
        // Whatever Next and Previous do at the ends of a queue, the answer is whether
        // anything a device looking at this one would see is different.
        fun seen() = listOf(inside.player.queue.current()?.id, (inside.exo.currentMediaItem?.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid, inside.exo.currentPosition, inside.exo.playWhenReady, side.playing())
        for (name in listOf("previous", "previous", "previous", "next", "next", "next", "next", "next", "next", "next", "previous")) {
            val was = seen()
            val said = carry(name)
            assertEquals("$name answered $said with ${seen()} after $was", seen() != was, said == LinkSide.CHANGED)
            assertTrue(said == LinkSide.CHANGED || said == LinkSide.REFUSED)
        }
        // The phone may not start playing: an entry chosen at another device is not
        // moved to, and a queue from one is not put in.
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        android.allows = false
        val was = seen()
        val number = side.queueNumber()
        assertEquals(LinkSide.REFUSED, carry("jump", buildJsonObject { put("at", 1); put("id", "x") }, entry(1)))
        assertEquals(was, seen())
        side.take(queueOf(theirs()))
        val adopt = buildJsonObject { put("songs", JsonArray(emptyList())); put("index", 0); put("queued", 0); put("position", 0.0); put("context", "") }
        val ready = buildJsonObject { put("queue", buildJsonArray { add(buildJsonArray { theirs().forEach { add(buildJsonArray { add(JsonPrimitive(it.id)); add(JsonPrimitive(false)) }) } }); add(JsonPrimitive(0)) }) }
        assertEquals(LinkSide.REFUSED, carry("adopt", adopt, ready))
        assertEquals(number, side.queueNumber())
        assertEquals(was, seen())
        android.allows = true
        assertEquals(LinkSide.CHANGED, carry("jump", buildJsonObject { put("at", 1); put("id", "x") }, entry(1)))
        assertEquals("s2", inside.player.queue.current()?.id)
        // **A phone that is already playing is asked a question that touches nothing**,
        // and never the service's own, whose "no" to a start stops the player: a command
        // from another device must not stop music the person is listening to. Refused,
        // the phone plays on exactly as it was, with its queue; allowed, it goes ahead.
        inside.exo.prepare()
        inside.exo.play()
        assertTrue(side.playing())
        var asked = 0
        var allowed = false
        side = LinkSide(inside.player, inside.exo, owner, songFor = { library[it] }, mayPlay = {
            asked++
            false
        }, mayPlayOn = { allowed })
        val playing = seen()
        val held = side.queueNumber()
        assertEquals(LinkSide.REFUSED, carry("jump", buildJsonObject { put("at", 0); put("id", "x") }, entry(0)))
        side.take(queueOf(theirs()))
        assertEquals(playing.take(2), seen().take(2))
        assertEquals(held, side.queueNumber())
        assertTrue(inside.exo.playWhenReady && side.playing())
        allowed = true
        assertEquals(LinkSide.CHANGED, carry("jump", buildJsonObject { put("at", 0); put("id", "x") }, entry(0)))
        assertEquals("a phone that was playing was asked the question that can stop it", 0, asked)
        assertTrue(inside.exo.playWhenReady)
        // A stop is the intent to play being cleared, and is answered by that.
        assertTrue(side.pause())
        assertFalse(inside.exo.playWhenReady)
        assertEquals(LinkSide.SO_ALREADY, carry("moved"))
    }

    /** Two things nothing else says: playback that has stopped by itself (an error, the
     *  end), and the entry that is current beginning again. Both are found by comparing. */
    @Test
    fun playbackThatStopsAndAnEntryBeginningAgainAreFoundByComparing() {
        side.attach()
        inside.player.play(mine(), 0, "A list", playNow = true)
        // (Made ready here and now, whatever the player has done by itself meanwhile.)
        inside.exo.stop()
        inside.exo.prepare()
        val sounding = side.known()
        assertTrue(sounding.sounds && side.playing())
        // It stops, still set to play: that is not playing, and it is a change.
        inside.exo.stop()
        assertTrue(inside.exo.playWhenReady)
        assertFalse(side.playing())
        assertNotEquals(sounding, side.known())
        assertEquals("whether it sounds", LinkSide.differing(sounding, side.known()))
        assertEquals("""{"playing":false,"title":"Song 0","artist":"Somebody"}""", side.brief().toString())
        // The same entry begins again by itself: nothing else about the player moved.
        val before = side.known()
        side.watches.onMediaItemTransition(inside.exo.currentMediaItem, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(before, side.known())
        side.watches.onMediaItemTransition(inside.exo.currentMediaItem, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertEquals("the same entry again", LinkSide.differing(before, side.known()))
    }

    /** The mark of a queue handed on belongs to that queue; whether it is written is what
     *  the writer says; and what the queue was before is kept for the call that marked
     *  it, and no longer. */
    @Test
    fun aQueueHandedOnIsMarkedWrittenAndTakenBackFromThatQueueOnly() {
        inside.player.play(mine(), 0, "A list", playNow = false)
        settle()
        owner.taken = "2026-01-01T00:00:00Z"
        assertTrue(side.handedOn())
        assertEquals(LinkSide.HANDED, owner.taken)
        assertEquals(1, owner.written)
        assertTrue(side.handedStill())
        // The phone did not stop: it is whose it was, written again.
        side.handedBack()
        assertEquals("2026-01-01T00:00:00Z", owner.taken)
        assertEquals(2, owner.written)
        // **A mark that is not written is no mark**: the answer is no, the queue is whose
        // it was, and nothing says it is handed on.
        owner.writes = false
        assertFalse(side.handedOn())
        assertEquals("2026-01-01T00:00:00Z", owner.taken)
        assertFalse(side.handedStill())
        // And a hand-over taken back that cannot be written is a failure, which is a fault.
        owner.writes = true
        assertTrue(side.handedOn())
        owner.writes = false
        assertThrows(IllegalStateException::class.java) { side.handedBack() }
        owner.writes = true
        assertEquals("2026-01-01T00:00:00Z", owner.taken)
        // The call that marked it is over: the mark stands, and nothing takes it back.
        side.handedOn()
        side.callOver()
        side.handedBack()
        assertEquals(LinkSide.HANDED, owner.taken)
        // Used here (the phone's own again), and handed on once more: it does not go back
        // to what it was before the first time.
        owner.taken = null
        side.handedOn()
        side.handedBack()
        assertNull(owner.taken)
        // (Nor if all of that were inside one call, with the record still there.)
        owner.taken = "theirs"
        side.handedOn()
        owner.taken = null
        side.handedOn()
        side.handedBack()
        assertNull(owner.taken)
        // Marked, and then another whole queue is in the player: nothing is given back.
        side.handedOn()
        inside.player.play(theirs(), 0, "Another", playNow = false)
        settle()
        assertFalse(side.handedStill())
        owner.taken = null
        side.handedBack()
        assertNull(owner.taken)
        // The app starts with a queue that was marked: it is that queue which is.
        side.callOver()
        owner.taken = LinkSide.HANDED
        assertFalse(side.handedStill())
        side.restored()
        assertTrue(side.handedStill())
        // Linking stopping for a fault: a queue left marked while the phone is the one
        // playing is the phone's again (what it was, if the fault is in the call that
        // marked it; its own otherwise).
        owner.taken = "theirs"
        side.handedOn()
        inside.player.play()
        side.stopped()
        assertEquals("theirs", owner.taken)
        assertFalse(side.handedStill())
        side.handedOn()
        side.callOver()
        side.stopped()
        assertNull(owner.taken)
        // (And one that is not playing stays marked: its music did move.)
        side.handedOn()
        inside.player.pause()
        side.stopped()
        assertEquals(LinkSide.HANDED, owner.taken)
    }
}
