package org.medianexus.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.medianexus.music.net.Song

/**
 * **The phone's player, as linking the person's devices asks it.** The contract is the
 * Windows repository's `docs/linking-design.md`, section 8.1 ("What the phone's adapter
 * owes"); this is the half of it that knows [QueuePlayer] and ExoPlayer, and nothing here
 * knows the shared library exists ([Linking] is the half that calls it).
 *
 * **Every answer is a total function of the player's state at that moment, and none can
 * fail.** Nothing here catches an exception to answer with something else: one that is
 * thrown goes out, and is a fault (linking stops on the phone until the app next starts).
 *
 * **It goes by what the player holds, not by what it is told.** ExoPlayer tells its
 * listeners of some things later than the call that caused them; so nothing here says
 * "something changed" from a listener. What is current is read from ExoPlayer itself
 * (`exo.currentMediaItem`, which a seek or a Next sets at once), not from the queue's
 * cursor, which follows.
 *
 * Main thread only, as the player is.
 */
@OptIn(UnstableApi::class)
internal class LinkSide(
    private val player: QueuePlayer,
    private val exo: ExoPlayer,
    private val owner: Owner,
    /** A song by its id, as the phone can play it: from its copy of the library, or one a
     *  look-up on the music server brought for a command. Null for one it cannot make. */
    private val songFor: (String) -> Song?,
    /** **Whether the phone may start playing now**, as the service answers its own player
     *  just before any start (in the foreground first; not while the phone's player shows
     *  another device's music; and so on). Asked here **before anything is made ready,
     *  put in or sought for a command that starts playing**, so that one that may not
     *  start changes nothing. */
    private val mayPlay: () -> Boolean = { true },
    /** **For a phone that is already sounding or about to: whether a start would be
     *  allowed now, asked without anything being done.** The service's own question
     *  ([mayPlay]) is for a start, and its "no" stops the player; this one touches
     *  nothing, so a command that is refused leaves the music playing. */
    private val mayPlayOn: () -> Boolean = { true },
    /** Whether the player is playing at this moment, as ExoPlayer says it. (Given, so that
     *  the tests of [sounding] need no sound.) */
    private val isPlaying: () -> Boolean = { exo.isPlaying },
) {
    /** Whose the queue is: the playback service's own word on it, and its own way of
     *  writing it down. */
    interface Owner {
        /** Set while the queue is one taken up from another device and not touched, or one
         *  handed to another device ([HANDED]). */
        var taken: String?

        /** The queue is the phone's own from now, by the person's choice: what a press on
         *  the phone does (the service's `claimQueue`). */
        fun claim()

        /** [taken] has been changed here: it is another queue to a save being prepared,
         *  and the queue's place is written, where the next start will find it. **Not back
         *  until the file holds it**; false if it does not by then (nothing is saved for
         *  this sign-in yet, or the writer did not get to it within a moment: it is
         *  written with the next save). */
        fun written(): Boolean
    }

    private val queue: Queue get() = player.queue

    // ---- What is current -------------------------------------------------------------------

    /** The entry that is current, as ExoPlayer has it (set at once by a seek or a Next);
     *  the queue's own, where the player holds none of ours. */
    private fun currentUid(): Long? = (exo.currentMediaItem?.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid ?: queue.currentUid()

    private fun currentSong(): Song? = (exo.currentMediaItem?.localConfiguration?.tag as? QueuePlayer.SongTag)?.song ?: queue.current()

    /** Its place in the playing order. (The cursor, unless the cursor has yet to follow.) */
    private fun place(): Int {
        val uid = currentUid() ?: return queue.cursor
        if (queue.currentUid() == uid) return queue.cursor
        return queue.placeOf(uid).takeIf { it >= 0 } ?: queue.cursor
    }

    private fun songAt(place: Int): Song? = queue.order.getOrNull(place)?.let { queue.songs.getOrNull(it) }

    private fun uidAt(place: Int): Long? = queue.order.getOrNull(place)?.let { queue.uids.getOrNull(it) }

    /** Seconds into the song. */
    private fun position(): Double = if (queue.isEmpty) 0.0 else (exo.currentPosition.coerceAtLeast(0) / 1000.0)

    // ---- Questions ---------------------------------------------------------------------------

    fun hasSong(): Boolean = !queue.isEmpty

    /**
     * **Sounding, or about to**: set to play, with something to play that the player has
     * made ready or is making ready, and nothing holding the sound back (a call, say).
     * Not the intent alone: a player set to play that has reached the end, has stopped for
     * an error, or was never made ready in this run is not playing, and Play is what
     * starts it.
     */
    fun playing(): Boolean = !queue.isEmpty && exo.playWhenReady && exo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING)

    fun brief(): JsonObject = buildJsonObject {
        val song = currentSong().takeIf { hasSong() }
        put("playing", playing())
        put("title", song?.title.orEmpty())
        put("artist", song?.artistLabel.orEmpty())
    }

    private fun entry(song: Song, queued: Boolean = false): JsonObject = buildJsonObject {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artistLabel)
        put("album", song.album)
        put("duration", song.duration.coerceAtLeast(0))
        put("queued", queued)
    }

    /** Whether the entry at this place was queued by hand ("Next in queue"). */
    private fun queuedAt(place: Int): Boolean = queue.order.getOrNull(place)?.let { queue.songs.isQueued(it) } == true

    fun toCome(): Int = if (queue.isEmpty) 0 else (queue.order.size - place() - 1).coerceAtLeast(0)

    fun full(): JsonObject = buildJsonObject {
        val place = place()
        val now = currentSong().takeIf { hasSong() }
        put("position", position())
        put("now", now?.let { entry(it) } ?: kotlinx.serialization.json.JsonNull)
        put("coming", buildJsonArray {
            if (hasSong()) for (at in place + 1 until minOf(queue.order.size, place + 1 + MOST_SHOWN)) songAt(at)?.let { add(entry(it, queuedAt(at))) }
        })
        put("in_all", toCome())
        put("context", queue.context)
        put("shuffle", queue.shuffle)
        put("repeat", queue.repeat)
        // (A phone's volume is the phone's own buttons': it has none of its own to say.)
        put("volume", 1.0)
    }

    fun handed(): JsonObject = buildJsonObject {
        val now = currentSong().takeIf { hasSong() }
        var queued = 0
        put("songs", buildJsonArray {
            // (Nothing, where what is playing is a radio station: another device cannot be
            // handed one. Stations further on are left out.)
            if (now != null && now.radioUrl.isEmpty()) {
                val place = place()
                var given = 0
                var run = true
                for (at in place until queue.order.size) {
                    val song = songAt(at) ?: continue
                    // The entries queued by hand are the run of them straight after the
                    // one playing, **as this list has them**: counted from the entry that
                    // is current here, and only those that are given.
                    val inRun = at > place && run && queuedAt(at)
                    if (at > place && !inRun) run = false
                    if (song.radioUrl.isNotEmpty() || song.id.isEmpty()) continue
                    add(JsonPrimitive(song.id))
                    if (inRun) queued++
                    if (++given >= MOST_HANDED) break
                }
            }
        })
        put("queued", queued)
        put("position", position())
        put("context", queue.context)
    }

    fun ahead(): JsonArray = buildJsonArray {
        if (hasSong()) for (at in place() + 1 until queue.order.size) add(JsonPrimitive(songAt(at)?.id.orEmpty()))
    }

    /** The number of the queue in the player ([QueuePlayer.whole]). */
    fun queueNumber(): Long = player.whole

    // ---- Sound ---------------------------------------------------------------------------------

    /**
     * **A move of music that waits on this phone's sound**: the queue that was put in for
     * it, by number, and the entry that was current as it went in, by the entry's own
     * number (never used twice while the app runs, so it is that queue's and no other's).
     * `heard` while the player has said audio is advancing **for that entry** and nothing
     * has interrupted it since.
     */
    private class Proof(val whole: Long, val uid: Long) {
        var heard = false

        /** The period (one loading of the entry) whose sound was last heard. */
        var heardIn: Long? = null

        /** The period whose sound was heard and then interrupted: nothing more that is
         *  said of that period arms this again. */
        var spent: Long? = null

        /** What was heard was before a new place to play from, or a stop. */
        fun interrupted() {
            if (heard) spent = heardIn
            heard = false
        }
    }

    private var proof: Proof? = null

    /** The entry an event of the player's belongs to, as the player says: not what is
     *  current when the event arrives. **Only an event with a period of its own says**:
     *  one without is filled in by Media3 from whatever is current as it is made, which
     *  is the very thing it must not be judged by. */
    private fun entryOf(eventTime: AnalyticsListener.EventTime): Long? {
        if (eventTime.mediaPeriodId == null) return null
        val timeline = eventTime.timeline
        if (timeline.isEmpty || eventTime.windowIndex !in 0 until timeline.windowCount) return null
        return (timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.localConfiguration?.tag as? QueuePlayer.SongTag)?.uid
    }

    /** (For the tests too, which hand it an event as the player would have made it.) */
    internal val hears = object : AnalyticsListener {
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            // The player posts this: by the time it arrives another queue may be in the
            // player. It counts for the entry it says it is of, and for no other.
            val waiting = proof ?: return
            if (entryOf(eventTime) != waiting.uid) return
            // (Said late, of a period whose sound has been interrupted since: not again.)
            val period = eventTime.mediaPeriodId?.windowSequenceNumber ?: return
            if (period == waiting.spent) return
            waiting.heard = true
            waiting.heardIn = period
        }
    }

    /** How many times the entry that is current has begun again by itself (a song on
     *  repeat): nothing else about the player says so. */
    private var again = 0L

    /** (For the tests too.) */
    internal val watches = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) proof?.interrupted()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // One song ending and the next beginning, or the same one beginning again:
            // the entry the move waited on is over, and what sounds now is not its proof.
            // Anything else is a new place to play from: what was heard was before it.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) proof = null else proof?.interrupted()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) again++
        }
    }

    /** Begins listening for sound. Nothing is listened for until linking exists. */
    fun attach() {
        exo.addAnalyticsListener(hears)
        exo.addListener(watches)
    }

    fun detach() {
        exo.removeAnalyticsListener(hears)
        exo.removeListener(watches)
        proof = null
    }

    /**
     * Seconds into the entry, **only while audio is advancing for the entry that was
     * current when a queue was put in for a move of music, in that queue**; null
     * otherwise, and from then on once that entry is no longer the one playing.
     */
    fun sounding(): JsonObject? {
        val waiting = proof ?: return null
        if (waiting.whole != player.whole || currentUid() != waiting.uid) {
            proof = null
            return null
        }
        if (!waiting.heard || !isPlaying() || queue.isEmpty) return null
        return buildJsonObject {
            put("queue", waiting.whole)
            put("at", position())
        }
    }

    // ---- What the library last knew of the player ------------------------------------------

    /**
     * Everything that says what plays, or whether: each set before the call that changes
     * it returns, and none changed later by that call's echoes. `sounds` is whether
     * playing is under way ([underWay]: so playback that ends, by reaching the end or by
     * an error, is a change; the sound being held back for a call is not); `again` counts
     * the times the current entry has begun again by itself.
     */
    data class Known(
        val entry: Long?, val playing: Boolean, val sounds: Boolean, val revision: Long, val shuffle: Boolean, val repeat: Int, val whole: Long, val choice: Long,
        val again: Long,
    )

    fun known(): Known = Known(currentUid().takeIf { hasSong() }, exo.playWhenReady, underWay(), queue.revision, queue.shuffle, queue.repeat, player.whole, Choices.latest, again)

    /**
     * Set to play with a song made ready or being made ready: [playing], **but for the
     * sound being held back** (a call). What is compared goes by this and not by
     * [playing]: a call arriving is not something that was chosen, and counted as a
     * change it refused the stop that ends a move of music as "made too early", so that
     * when the call ended both devices played (found on the walk). Held back, the phone
     * says it is not playing to whoever looks ([brief]); its order of choices does not
     * move for it.
     */
    private fun underWay(): Boolean = !queue.isEmpty && exo.playWhenReady && (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING)

    /**
     * Carries out something that moves what plays (Next, Previous, a seek, a jump):
     * whether it did is the player after it against the player before it. **Moved** is
     * another entry being current, or the place in the entry being somewhere the song
     * playing on would not have taken it in the time the call took: before where it was,
     * or further on than that time and a little.
     */
    private inline fun moved(act: () -> Unit): Int {
        val entry = currentUid().takeIf { hasSong() }
        val at = exo.currentPosition
        val began = android.os.SystemClock.elapsedRealtime()
        act()
        val took = android.os.SystemClock.elapsedRealtime() - began
        val now = exo.currentPosition
        val jumped = now < at - PLAYED_ON_SLACK_MILLIS || now > at + took + PLAYED_ON_SLACK_MILLIS
        return if (currentUid().takeIf { hasSong() } != entry || jumped) CHANGED else REFUSED
    }

    // ---- Acts -----------------------------------------------------------------------------------

    /** For the debug walk's measuring only: the queue in the player made this many entries
     *  long, of the songs it already has, paused. How long it then is. */
    fun fill(entries: Int): Int {
        val songs = queue.playingOrder()
        if (songs.isNotEmpty() && entries > 0) player.play(List(entries) { songs[it % songs.size] }, 0, "For measuring", playNow = false)
        return queue.order.size
    }

    /** Stops the phone's own sound, through the player's own pause. Whether the phone is
     *  then not set to play. */
    fun pause(): Boolean {
        player.pause()
        return !exo.playWhenReady
    }

    /**
     * Whether a command that starts playing may go ahead: asked before anything is put in
     * or moved to. **A phone that is already sounding, or about to, is asked the question
     * that touches nothing** ([mayPlayOn]): the service's own is for a start, and its
     * "no" stops the player, which is right for a start that was refused and would stop
     * music the person is listening to. Refused, the phone plays on exactly as it was.
     */
    private fun mayStart(): Boolean = if (playing()) mayPlayOn() else mayPlay()

    fun claim() = owner.claim()

    /** The number of the queue that is marked as handed on, while one is. */
    private var marked: Long? = null

    /**
     * What a queue was before it was marked: **kept from the mark to the end of the call
     * into the library that made it** ([callOver]), which is as long as the library can
     * take the mark back, and no longer. So it is never out of date, and there is nothing
     * of it to keep through a restart.
     */
    private class Before(val whole: Long, val taken: String?)

    private var before: Before? = null

    /** Marks the queue in the player as handed to another device, and has it written.
     *  Whether the queue's file holds the mark as this returns ([Owner.written]). */
    fun handedOn(): Boolean {
        // (Marked twice with nothing between, which is to say while it is still marked:
        // what it was before the first still stands. Nothing goes back to "handed on".)
        val still = owner.taken == HANDED
        before = before?.takeIf { it.whole == player.whole && still } ?: Before(player.whole, owner.taken.takeIf { !still })
        marked = player.whole
        owner.taken = HANDED
        if (owner.written()) return true
        // Not in the queue's file (the write failed, or did not get there in time):
        // **nothing is marked**. The queue is whose it was, the library refuses the
        // hand-over before the phone is stopped, and whose the queue is is written again
        // in its own time (the write that was asked for may yet land; this one follows it).
        val was = before
        before = null
        marked = null
        owner.taken = was?.taken
        owner.written()
        return false
    }

    /** The phone did not stop: the queue is whose it was. **If that cannot be written it
     *  throws**, which is a fault: linking stops, and its clean-up has the queue the
     *  phone's own in memory, and a run without linking reads the mark as that. */
    fun handedBack() {
        val was = before ?: return
        before = null
        // (Another whole queue is in the player: whose that is was settled when it went in.)
        if (was.whole != player.whole) return
        marked = null
        owner.taken = was.taken
        check(owner.written()) { "a hand-over that was taken back could not be written down" }
    }

    /** The call into the library that may have marked a queue is over: the mark stands. */
    fun callOver() {
        before = null
    }

    /** The last run's queue has been put back in the player: if it was marked as handed
     *  on, it is this queue that is. */
    fun restored() {
        marked = player.whole.takeIf { owner.taken == HANDED }
    }

    /** Whether the queue in the player is the one that is marked as handed on: until the
     *  person uses it here, nothing else makes it the phone's own. */
    fun handedStill(): Boolean = owner.taken == HANDED && marked == player.whole

    /**
     * Linking has stopped (a fault), and can ask nothing more: **a queue left marked as
     * handed on while the phone is the one playing is the phone's again** (what it was
     * before, if the fault came in the very call that marked it). The one thing only this
     * side can put right. Whatever happens, nothing is left that says a queue is marked.
     */
    fun stopped() {
        val was = before
        before = null
        val mine = marked == player.whole
        marked = null
        proof = null
        if (owner.taken == HANDED && mine && exo.playWhenReady && !queue.isEmpty) {
            owner.taken = was?.takeIf { it.whole == player.whole }?.taken
            owner.written()
        }
    }

    /**
     * Carries one command out, as the same press on the phone would be.
     * [CHANGED]: it changed the player; [SO_ALREADY]: it was so already; [REFUSED]: it
     * refused, or could not. `ready` is what the library found for it against the player
     * as it stands, and is what is gone by.
     */
    fun carryOut(command: JsonObject, ready: JsonElement): Int {
        val with = command["with"] as? JsonObject ?: JsonObject(emptyMap())
        val entry = (ready as? JsonObject)?.get("entry")?.jsonPrimitive?.int
        return when (command.getValue("do").jsonPrimitive.content) {
            // **What the answer is, is the player after the call against the player before
            // it** ([moved]): "changed" only if something a device looking at this one
            // would see is different; "so already" only if it was; otherwise it refused.
            "play" -> when {
                queue.isEmpty -> REFUSED
                // Set to play is not playing: one that has ended, stopped for an error
                // or was never made ready in this run is started by Play.
                playing() -> SO_ALREADY
                // Asked before anything is made ready: one that may not start is left
                // exactly as it was.
                !mayStart() -> REFUSED
                // **What the play button does**, not only "play": a player that has not
                // been made ready in this run (the last run's queue put back, a queue
                // taken up from another device) is made ready first. Asked only to play,
                // it sat set to play and silent until the service gave up on it (found on
                // the walk). **Done is the player being about to sound**, and nothing
                // less: not that something about it moved.
                else -> {
                    androidx.media3.common.util.Util.handlePlayButtonAction(player)
                    if (playing()) CHANGED else REFUSED
                }
            }
            // **Stopped is the intent to play being cleared**, not nothing sounding: a
            // phone whose sound a call holds back, still set to play, has not stopped.
            "pause", "moved" -> if (!exo.playWhenReady) SO_ALREADY else {
                player.pause()
                if (!exo.playWhenReady) CHANGED else REFUSED
            }
            "next" -> if (queue.isEmpty) REFUSED else moved { player.seekToNext() }
            "previous" -> if (queue.isEmpty) REFUSED else moved { player.seekToPrevious() }
            "seek" -> if (queue.isEmpty || currentSong()?.radioUrl?.isNotEmpty() == true) REFUSED else {
                moved { player.seekTo((with.getValue("to").jsonPrimitive.double * 1000).toLong().coerceAtLeast(0)) }
            }
            // A phone's volume is the phone's own buttons'.
            "volume" -> REFUSED
            "jump" -> {
                val uid = entry?.let { uidAt(place() + 1 + it) } ?: return REFUSED
                // (It starts the entry playing: asked first, so that one that may not
                // start does not move the queue to it either.)
                if (!mayStart()) return REFUSED
                moved { player.jumpTo(uid) }
            }
            "remove" -> {
                val uid = entry?.let { uidAt(place() + 1 + it) } ?: return REFUSED
                changedTheList { player.removeEntry(uid) }
            }
            "move" -> {
                val from = entry ?: return REFUSED
                val base = place() + 1
                val uid = uidAt(base + from) ?: return REFUSED
                val queued = with.getValue("queued").jsonPrimitive.boolean
                val last = (queue.order.size - base - 1).coerceAtLeast(0)
                val to = with.getValue("to").jsonPrimitive.int.coerceIn(0, last)
                if (to == from && queuedAt(base + from) == queued) return SO_ALREADY
                // Where it goes is said as the entry it comes after, among the others.
                val others = (base until queue.order.size).filter { it != base + from }
                val after = if (to == 0) null else uidAt(others[to - 1])
                changedTheList { player.moveEntry(uid, after, queued) }
            }
            "clear" -> {
                val queued = with.getValue("queued").jsonPrimitive.boolean
                val nothing = if (queued) queue.queuedAhead() == 0 else toCome() - queue.queuedAhead() <= 0
                if (queue.isEmpty) REFUSED else if (nothing) SO_ALREADY else changedTheList { player.clearAhead(queued) }
            }
            "shuffle" -> {
                val on = with.getValue("on").jsonPrimitive.boolean
                if (queue.shuffle == on) SO_ALREADY else {
                    player.setShuffle(on)
                    CHANGED
                }
            }
            "repeat" -> {
                val mode = with.getValue("mode").jsonPrimitive.int.coerceIn(Repeat.OFF, Repeat.ONE)
                if (queue.repeat == mode) SO_ALREADY else {
                    player.setRepeat(mode)
                    CHANGED
                }
            }
            "add" -> {
                val ids = (ready as? JsonObject)?.get("songs")?.jsonArray ?: return REFUSED
                val songs = ids.mapNotNull { songFor(it.jsonPrimitive.content)?.takeIf { song -> song.radioUrl.isEmpty() } }
                if (songs.isEmpty()) return REFUSED
                val was = queue.revision
                val whole = player.whole
                player.enqueue(songs, playNext = with["next"]?.jsonPrimitive?.booleanOrNull == true)
                if (queue.revision != was || player.whole != whole) CHANGED else REFUSED
            }
            "adopt" -> {
                val given = (ready as? JsonObject)?.get("queue")?.jsonArray ?: return REFUSED
                val whole = player.whole
                install(given[0].jsonArray, given[1].jsonPrimitive.int, with.getValue("position").jsonPrimitive.double, with["context"]?.jsonPrimitive?.content.orEmpty())
                // (Whether it is in is the queue's number, which the library reads too.)
                if (player.whole != whole) CHANGED else REFUSED
            }
            else -> REFUSED
        }
    }

    private inline fun changedTheList(act: () -> Unit): Int {
        val was = queue.revision
        act()
        return if (queue.revision != was) CHANGED else REFUSED
    }

    /** `{"songs":[[id,queued],…],"begin","position","context"}`: put in the player, playing.
     *  Whether it is in is what [queueNumber] says afterwards. */
    fun take(queue: JsonObject) {
        install(queue.getValue("songs").jsonArray, queue.getValue("begin").jsonPrimitive.int, queue.getValue("position").jsonPrimitive.double, queue["context"]?.jsonPrimitive?.content.orEmpty())
    }

    /**
     * Builds a queue of these songs and puts it in the player, playing from `begin` at
     * `position` seconds: a start like any other, with the newest place in the order of the
     * person's choices (so every start still being looked up gives way to it).
     *
     * **The whole of it or nothing that can be told from nothing.** A song the phone
     * cannot make is left out, and the rest keep their order. The entries marked as
     * queued by hand in the run straight after the one to begin with are queued by hand
     * here ("Next in queue"), in that order. If the one to begin with could not be made,
     * it begins with the next that could (or, failing that, the last before it), from
     * its top. If none can be made, or the phone
     * may not start playing now ([mayPlay], asked before anything is put in), nothing is
     * put in and the queue's number does not move. When it has moved, the queue is in the
     * player and in ExoPlayer both ([QueuePlayer.whole]).
     */
    private fun install(pairs: JsonArray, begin: Int, position: Double, context: String) {
        var plain = ArrayList<Song>(pairs.size)
        var byHand = ArrayList<Song>()
        var start = 0
        var begun = false
        var exact = false
        var run = true
        for ((at, pair) in pairs.withIndex()) {
            val song = songFor(pair.jsonArray[0].jsonPrimitive.content)?.takeIf { it.radioUrl.isEmpty() }
            if (at < begin) {
                song?.let { plain += it }
            } else if (!begun) {
                // The one to begin with; or, if it cannot be made, the next that can.
                if (song == null) continue
                start = plain.size
                exact = at == begin
                begun = true
                plain += song
            } else {
                // Queued by hand: the run of entries marked so straight after that one.
                val inRun = run && pair.jsonArray[1].jsonPrimitive.boolean
                if (!inRun) run = false
                if (song == null) continue
                if (inRun) byHand += song else plain += song
            }
        }
        if (!begun) {
            // (Nothing from the one to begin with on could be made: the last before it.)
            start = plain.size - 1
            exact = false
        }
        if (plain.isEmpty() || !mayStart()) return
        start = start.coerceIn(0, plain.size - 1)
        // Handed over in a song's last moments, it starts from the top instead (as on
        // Windows); and a place is never before the start, nor in another song.
        val length = plain[start].duration
        val from = if (!exact || !position.isFinite() || position < 0 || (length > 0 && position >= length - 1)) 0.0 else position
        val next = player.prepare(plain, start, context = context, shuffle = false)
        next.queueUp(byHand, playNext = false)
        val was = player.whole
        player.play(next, (from * 1000).toLong(), playNow = true)
        // The move this queue was put in for waits on the sound of the entry it begins
        // with, and of nothing else.
        proof = if (player.whole != was) currentUid()?.let { Proof(player.whole, it) } else null
    }

    companion object {
        /** Which of the things compared differ between two moments, by name. */
        fun differing(was: Known, now: Known): String = listOfNotNull(
            "the entry".takeIf { was.entry != now.entry },
            "play or pause".takeIf { was.playing != now.playing },
            "whether it sounds".takeIf { was.sounds != now.sounds },
            "the same entry again".takeIf { was.again != now.again },
            "the list".takeIf { was.revision != now.revision },
            "shuffle".takeIf { was.shuffle != now.shuffle },
            "repeat".takeIf { was.repeat != now.repeat },
            "the whole queue".takeIf { was.whole != now.whole },
            "a choice".takeIf { was.choice != now.choice },
        ).joinToString(", ")

        /** How long the mark of a queue handed on is waited for to be in the queue's file,
         *  on the player's thread, before it is said not to be there. */
        const val MARK_WAIT_MILLIS = 250L

        /** How far a place in a song may be from where the song playing on would have
         *  taken it and still be the song playing on. */
        const val PLAYED_ON_SLACK_MILLIS = 150L

        const val REFUSED = 0
        const val CHANGED = 1
        const val SO_ALREADY = 2

        /** What `taken` is while the queue is one this phone has handed to another device
         *  and not used since: the shared core's `handover::HANDED`, letter for letter. */
        const val HANDED = "handed to another device"

        /** As many entries to come as are handed over for a device steering this one to
         *  show (the library cuts them to what one message holds). */
        const val MOST_SHOWN = 200

        /** The most ids handed over for another device to carry on with: more than one
         *  message carries (the library cuts the list to that). */
        const val MOST_HANDED = 12_000
    }
}
