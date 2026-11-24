package org.medianexus.music.jam

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.medianexus.music.net.Song

/** The phone's own player, as a session hosted here needs it (see [OwnPlayer]). Called on
 *  the main thread. */
interface HostPlayer {
    /** How things stand now. */
    fun now(): HostNow

    fun skip()
    fun previous()
    fun setPlaying(on: Boolean)
    fun seek(seconds: Double)
    fun jump(uid: Long)
    /** Moves the entry `uid` to straight after `after` (null: after the song playing). */
    fun move(uid: Long, after: Long?, queued: Boolean)
    fun clear(queued: Boolean)
    fun remove(uid: Long)
    fun shuffle(on: Boolean)
    fun repeat(mode: Int)
    /** The phone's media volume, 0 to 1. */
    fun volume(to: Double)

    /** Queues songs in "Next in queue": at its top with `next`, else at its foot. With
     *  nothing in the queue at all, plays them. */
    fun enqueue(songs: List<Song>, next: Boolean)

    /** What is playing and everything after it, in order, for another device to carry on with. */
    fun fromNowOn(): List<Song>
}

/** The phone's player at one moment: the song, what is to come (the nearest of it), and the
 *  rest of what a host tells the relay. */
data class HostNow(
    val song: Song?,
    val ahead: List<Song>,
    /** Each of `ahead`'s entry numbers. */
    val uids: List<Long>,
    val queuedAhead: Int,
    /** How many are to come in all (more than `ahead` holds, for a long queue). */
    val totalAhead: Int,
    val playing: Boolean,
    val position: Double,
    val context: String,
    val shuffle: Boolean,
    val repeat: Int,
    val volume: Double,
)

/** What a guest asked for, as the host's app hears of it. */
data class Ask(val id: Long, val guest: JamGuest, val action: String, val data: JsonElement?)

/** A song this phone knows by id, and a search of what it knows: its copy of the library. */
interface HostLibrary {
    fun song(id: String): Song?
    fun search(query: String): List<Song>
}

/**
 * The host's answers (Windows' `jam_asked`, `jam_add_for`, `jam_remove_for`, `jam_control`,
 * `jam_entry`, `handed_songs` and `queue_to_hand_over`), as plain code over a [HostPlayer]
 * and the words to say a refusal in. Guests are strangers (rule 5): every request is bounded
 * as Windows bounds it (the queue shown, the songs found, the songs added at once, the songs
 * handed over), a song is added only if this library has it, and nothing is answered that
 * Windows wouldn't answer.
 */
class Answers(private val player: HostPlayer, private val library: HostLibrary, private val words: AnswerWords) {
    /** Who added each song still to come, while it is to come: song id to the guest. */
    val added = LinkedHashMap<String, JamGuest>()

    /** Guests that proved to be the person's own devices. */
    val owners = HashSet<String>()

    sealed interface Outcome {
        data class Done(val data: JsonObject = JsonObject(emptyMap())) : Outcome
        data class No(val why: String) : Outcome
        /** Something for the caller to do off the main thread first: a queue to load. */
        data class Load(val songs: List<Song>, val index: Int, val positionMs: Long, val playing: Boolean, val context: String) : Outcome
    }

    /**
     * Answers one ask. `word` is the word that proves a guest is one of the person's own
     * devices (null if this phone has none); `guestsSteer` is the setting. `found` are the
     * songs an "add" named that this library has, looked up beforehand off the main thread.
     */
    fun answer(ask: Ask, word: String?, guestsSteer: Boolean, found: List<Song> = emptyList()): Outcome {
        val data = ask.data as? JsonObject ?: JsonObject(emptyMap())
        val own = word != null && data["owner"].str() == word
        if (own) owners += ask.guest.id
        // Adding songs, and taking your own back out, is what a jam is for. The rest is the
        // host's to allow.
        val control = own || guestsSteer
        return when (ask.action) {
            "add" -> add(ask.guest, found, control && data["next"].bool() == true, own, asked = wanted(data).size)
            "remove" -> remove(ask.guest, data, control)
            "hello", "queue", "load", "volume" -> if (!own) Outcome.No(words.ownOnly) else when (ask.action) {
                "hello" -> Outcome.Done()
                "queue" -> Outcome.Done(handOver())
                "load" -> {
                    val (songs, index) = handed(data)
                    if (songs.isEmpty()) Outcome.No(words.noneOfThose) else Outcome.Load(songs, index, ((data["position"].f64() ?: 0.0).coerceAtLeast(0.0) * 1000).toLong(), data["playing"].bool() != false, shownText(data["context"].str()))
                }
                else -> {
                    player.volume((data["to"].f64() ?: 1.0).coerceIn(0.0, 1.0))
                    Outcome.Done()
                }
            }
            in STEERING -> if (!control) Outcome.No(words.hostChooses) else control(ask.action, data)
            else -> Outcome.No(words.notForGuests)
        }
    }

    /** The ids an "add" names: one, or a list of them, at most [MOST_ADDED]. */
    fun wanted(data: JsonElement?): List<String> {
        val ids = data.at("ids")
        val list = if (ids is JsonArray) ids.mapNotNull { it.str() } else listOfNotNull(data.at("id").str())
        return list.asSequence().filter(::plainId).take(MOST_ADDED).toList()
    }

    /** The songs of a queue handed over from another device, as this library knows them, and
     *  where in them the one to start with is. Songs this library doesn't have are left out. */
    fun handed(data: JsonElement?): Pair<List<Song>, Int> {
        val start = data.at("index").u64()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val songs = ArrayList<Song>()
        var begin = 0
        for ((place, entry) in data.at("songs").list().asSequence().take(MOST_HANDED).withIndex()) {
            if (place == start) begin = songs.size
            val id = entry.at("id").str()?.takeIf(::plainId) ?: continue
            library.song(id)?.let { songs += it.copy(queued = entry.at("queued").bool() == true) }
        }
        return songs to begin.coerceAtMost((songs.size - 1).coerceAtLeast(0))
    }

    /** What this phone is playing and has queued, for another device to carry on with. */
    fun handOver(): JsonObject {
        val now = player.now()
        val songs = player.fromNowOn().asSequence().filter { it.radioUrl.isEmpty() && it.id.isNotEmpty() }.take(MOST_HANDED).map { song ->
            buildJsonObject {
                put("id", song.id)
                put("queued", song.queued)
            }
        }.toList()
        return buildJsonObject {
            put("songs", JsonArray(songs))
            put("index", 0)
            put("position", now.position)
            put("playing", now.playing && now.song != null)
            put("context", now.context)
        }
    }

    /** Which entry after the one playing a guest means: the one at `at`, if it's still the
     *  song they saw there, or else the nearest copy of that song. */
    fun entry(at: JsonElement?, id: JsonElement?): Int? {
        val place = at.u64()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        val song = id.str().orEmpty()
        val ahead = player.now().ahead
        if (ahead.getOrNull(place)?.id == song) return place
        return ahead.indices.filter { ahead[it].id == song }.minByOrNull { kotlin.math.abs(it.toLong() - place) }
    }

    private fun control(action: String, data: JsonObject): Outcome {
        val now = player.now()
        if (now.song == null) return Outcome.No(words.nothingPlaying)
        when (action) {
            "skip" -> player.skip()
            "previous" -> player.previous()
            "pause" -> player.setPlaying(false)
            "play" -> player.setPlaying(true)
            "seek" -> player.seek((data["to"].f64() ?: 0.0).coerceAtLeast(0.0))
            "jump" -> player.jump(now.uids[entry(data["at"], data["id"]) ?: return Outcome.No(words.gone)])
            "move" -> {
                val from = entry(data["from"], data["id"]) ?: return Outcome.No(words.gone)
                val last = (now.totalAhead - 1).coerceAtLeast(0).coerceAtMost(now.ahead.size - 1)
                val to = (data["to"].u64()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0).coerceAtMost(last)
                // The entry goes to place `to` of what's to come once it has left its own:
                // straight after the entry that will then be before it.
                val rest = now.uids.filterIndexed { i, _ -> i != from }
                player.move(now.uids[from], if (to == 0) null else rest.getOrNull(to - 1), data["queued"].bool() == true)
            }
            "clear" -> player.clear(data["queued"].bool() == true)
            "shuffle" -> player.shuffle(data["on"].bool() == true)
            else -> player.repeat((data["mode"].u64() ?: 0L).coerceAtMost(2L).toInt())
        }
        return Outcome.Done()
    }

    private fun add(guest: JamGuest, songs: List<Song>, next: Boolean, own: Boolean, asked: Int): Outcome {
        val now = player.now()
        val waiting = HashSet<String>()
        now.song?.let { waiting += it.id }
        now.ahead.take(now.queuedAhead).forEach { waiting += it.id }
        // A song that's already waiting in the queue, or playing, isn't queued a second time.
        val fresh = songs.filter { it.radioUrl.isEmpty() && waiting.add(it.id) }
        if (fresh.isEmpty()) return Outcome.No(if (asked == 0 || songs.isEmpty()) words.notInLibrary else words.already)
        // What the person queued from another of their devices is theirs, not a guest's.
        if (!own) fresh.forEach { added[it.id] = guest }
        player.enqueue(fresh, next)
        return Outcome.Done(buildJsonObject { put("added", fresh.size) })
    }

    private fun remove(guest: JamGuest, data: JsonObject, control: Boolean): Outcome {
        val place = entry(data["at"], data["id"]) ?: return Outcome.No(words.gone)
        if (!control) {
            val queued = place < player.now().queuedAhead
            val theirs = added[data["id"].str().orEmpty()]?.id == guest.id
            if (!(queued && theirs)) return Outcome.No(words.ownSongs)
        }
        player.remove(player.now().uids[place])
        return Outcome.Done()
    }

    /**
     * What's playing and queued, for the relay (Windows' `jam_sync`'s state): at most [SHOWN]
     * songs of the queue, who queued each still to come, and how many more there are.
     */
    fun state(guestsSteer: Boolean): JsonObject {
        val now = player.now()
        // Who added a song matters only until it has played or been taken out.
        if (added.isNotEmpty()) {
            val around = HashSet<String>().apply { now.song?.let { add(it.id) }; now.ahead.forEach { add(it.id) } }
            added.keys.retainAll(around)
        }
        val queue = now.ahead.asSequence().take(SHOWN).mapIndexed { i, song ->
            val entry = songJson(song)
            if (i < now.queuedAhead) {
                val by = added[song.id]
                JsonObject(entry + buildJsonObject {
                    put("queued", true)
                    if (by != null) {
                        put("by", by.name)
                        put("by_id", by.id)
                    }
                })
            } else {
                entry
            }
        }.toList()
        return buildJsonObject {
            put("playing", now.playing && now.song != null)
            put("position", now.position)
            put("control", guestsSteer)
            put("now", now.song?.let(::songJson) ?: JsonNull)
            put("queue", JsonArray(queue))
            put("more", (now.totalAhead - SHOWN).coerceAtLeast(0))
            put("context", now.context)
            put("shuffle", now.shuffle)
            put("repeat", now.repeat)
            put("volume", now.volume)
        }
    }

    companion object {
        val STEERING = setOf("skip", "previous", "pause", "play", "seek", "jump", "move", "clear", "shuffle", "repeat")
    }
}

/** The words a host answers guests in (Windows' own). */
interface AnswerWords {
    val ownOnly: String
    val noneOfThose: String
    val hostChooses: String
    val notForGuests: String
    val nothingPlaying: String
    val notInLibrary: String
    val already: String
    val ownSongs: String
    val gone: String
}

/**
 * The host's side of the relay, as plain suspending functions on a [RelayLine] (Windows'
 * `open_session`, `host_loop`, `send_loop`, `send_cover`).
 */
object HostSide {
    sealed interface Started {
        data class Ok(val jam: String, val secret: String) : Started
        /** The relay's words, or null for Windows' own. */
        data class Refused(val said: String?) : Started
        data object KeyRefused : Started
        data object NotUnderstood : Started
        data object Unreachable : Started
    }

    suspend fun start(line: RelayLine, key: String, name: String): Started =
        when (val answer = line.post("api/host/start", key, buildJsonObject { put("name", name) })) {
            RelayLine.Answer.Unreachable -> Started.Unreachable
            is RelayLine.Answer.Got -> when (answer.status) {
                200 -> {
                    val jam = answer.body.at("jam").str()?.takeIf { it.length in 1..64 && it.all(Char::isLetterOrDigit) }
                    val secret = answer.body.at("secret").str()?.takeIf { it.length in 1..128 }
                    if (jam != null && secret != null) Started.Ok(jam, secret) else Started.NotUnderstood
                }
                401 -> Started.KeyRefused
                else -> Started.Refused(answer.body.at("error").str()?.let { shownText(it) }?.ifEmpty { null })
            }
        }

    /** What a wait on the relay brought: asks, covers wanted, who's here, and who arrived. */
    data class News(val asks: List<Ask>, val covers: List<Pair<String, Boolean>>, val guests: List<JamGuest>, val arrivals: List<String>)

    /**
     * Waits on the relay for things to do, for as long as the caller's coroutine runs, handing
     * each batch to `news`. Two failures in a row are said once (`trouble`). Ends when the
     * relay no longer has the jam (it restarted, or the jam ran out).
     */
    suspend fun serve(
        line: RelayLine,
        jam: String,
        secret: String,
        news: suspend (News) -> Unit,
        trouble: suspend (Boolean) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        var after = 0L
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val answer = line.get("api/host/$jam/poll?after=$after", secret)
            if (!currentCoroutineContext().isActive) return false
            if (answer is RelayLine.Answer.Got && (answer.status == 401 || answer.status == 404)) return true
            if (answer !is RelayLine.Answer.Got || answer.status != 200) {
                failures++
                if (failures == 2) trouble(true)
                pause(failures.coerceAtMost(5) * 1000L)
                continue
            }
            if (failures >= 2) trouble(false)
            failures = 0
            val asks = ArrayList<Ask>()
            val covers = ArrayList<Pair<String, Boolean>>()
            val arrivals = ArrayList<String>()
            for (event in answer.body.at("events").list().take(MOST_EVENTS)) {
                after = maxOf(after, event.at("seq").u64() ?: 0L)
                when (event.at("kind").str()) {
                    "joined" -> arrivals += shownText(event.at("guest").at("name").str(), 24).ifEmpty { "Someone" }
                    "ask" -> {
                        val id = event.at("id").u64() ?: continue
                        val action = event.at("action").str() ?: continue
                        val guest = JamGuest(event.at("guest").at("id").str()?.takeIf { it.length <= 64 && plainId(it) }.orEmpty(), shownText(event.at("guest").at("name").str(), 24).ifEmpty { "Someone" })
                        asks += Ask(id, guest, action, event.at("data"))
                    }
                    "cover" -> {
                        val cover = event.at("cover").str()?.takeIf(::plainId) ?: continue
                        covers += cover to (event.at("size").str() == "l")
                    }
                }
            }
            news(News(asks, covers, JamGuest.list(answer.body.at("guests")), arrivals))
        }
        return false
    }

    suspend fun reply(line: RelayLine, jam: String, secret: String, ask: Long, outcome: Answers.Outcome) {
        val body = when (outcome) {
            is Answers.Outcome.Done -> buildJsonObject { put("id", ask); put("ok", true); put("data", outcome.data) }
            is Answers.Outcome.No -> buildJsonObject { put("id", ask); put("ok", false); put("error", outcome.why) }
            is Answers.Outcome.Load -> buildJsonObject { put("id", ask); put("ok", true); put("data", JsonObject(emptyMap())) }
        }
        line.post("api/host/$jam/reply", secret, body)
    }

    suspend fun state(line: RelayLine, jam: String, secret: String, state: JsonObject) = line.post("api/host/$jam/state", secret, state)

    suspend fun removeGuest(line: RelayLine, jam: String, secret: String, guest: String) =
        line.post("api/host/$jam/remove", secret, buildJsonObject { put("guest", guest) })

    suspend fun end(line: RelayLine, jam: String, secret: String) =
        line.send("api/host/$jam/end", secret, "application/json", "{}".toByteArray(), waitSeconds = 3)

    /** A cover for guests, or word that there is none. Only the library's own covers. */
    suspend fun cover(line: RelayLine, jam: String, secret: String, cover: String, large: Boolean, bytes: ByteArray?, kind: String) {
        val path = "api/host/$jam/cover?cover=$cover&size=${if (large) "l" else "s"}"
        if (bytes == null) line.send("$path&missing=1", secret, "application/octet-stream", ByteArray(0)) else line.send(path, secret, kind, bytes, waitSeconds = 30)
    }

    /** The most events one batch is read for: the relay keeps 400. */
    private const val MOST_EVENTS = 400
}

/** A JSON value from a whole number, for a Kotlin `put` that has none for Long in a list. */
internal fun number(n: Long) = JsonPrimitive(n)
