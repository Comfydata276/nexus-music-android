package org.medianexus.music.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The shared core: the part of the app that is the Windows app's own code, compiled into a
 * library the app carries (`libnexus_core_android.so`; the Rust side of this file is
 * `native/src/lib.rs`, and docs/DESIGN.md, "The shared core", says how it gets here).
 *
 * **This object is the only thing in the app that knows the library exists.** Everything
 * else calls the functions below, which take and give Kotlin values (`CoreTypes.kt`). Across
 * the boundary each call is one argument and one answer, both JSON as UTF-8 bytes; whatever
 * goes wrong there (an argument the library doesn't take, an answer this side can't read, a
 * panic in the library) is a [CoreException] with the reason in words; for a panic, that it
 * stopped and nothing of what the panic itself said (which can quote what it was holding).
 *
 * The library is loaded at the first call, **never on the main thread** ([warm] loads it as
 * the app starts, off the main thread; a call from the main thread never loads it). It opens
 * no connections, keeps no files, starts no threads and reads no clock: what it is given is
 * all it knows. A call is a few thousandths of a second at most, and most are made off the
 * main thread anyway; the one a screen makes as it draws is [notesDates].
 *
 * The exception is requests, which want the network. The library still opens no connection:
 * it asks the phone to make each request, through [CoreHttp], and waits for the answer. The
 * functions that do are the `suspend` ones, under "Requests" below; they run on threads kept
 * for them and can take as long as the network does.
 *
 * One function here for each operation of the core the phone uses, under the name the core
 * has for it where it has one. What each decides is written where it is decided, in the
 * Windows repository's `core/src/` (`step.rs`, `notes.rs`, `handover.rs`); here is only what
 * goes in and what comes out.
 */
object Core {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        // The core's names for things: `play_sources`, where Kotlin has `playSources`.
        namingStrategy = JsonNamingStrategy.SnakeCase
        classDiscriminator = "kind"
    }

    @Volatile private var ready = false
    @Volatile private var loading = false
    private val loadLock = Any()

    /**
     * Loads the library if it isn't loaded: **never on the main thread**. A call from the
     * main thread before the library is loaded waits for a load another thread has begun,
     * and is refused if none has ([warm] begins one as the app starts, so that by the time
     * a screen asks, it is there).
     */
    private fun load() {
        if (ready) return
        if (onMainThread() && !loading) throw CoreException("The shared library was asked for on the main thread before it had been loaded.")
        synchronized(loadLock) {
            if (ready) return
            if (onMainThread()) throw CoreException("The shared library was asked for on the main thread before it had been loaded.")
            loading = true
            try {
                try {
                    System.loadLibrary(LIBRARY)
                } catch (e: UnsatisfiedLinkError) {
                    System.load(secondCopy(e) ?: throw e)
                }
                ready = true
            } catch (e: UnsatisfiedLinkError) {
                // (The app was built without it, or for another kind of processor: the
                // build refuses both, so this is a build that didn't come from it.)
                throw CoreException("The shared library isn't in this build of the app: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    private const val LIBRARY = "nexus_core_android"

    /**
     * On a computer only, where the unit tests run: a Java machine loads a library once for
     * each class loader, and the tests that run on Robolectric's copy of Android have a
     * class loader of their own, so whichever kind of test asks second is refused the
     * library the first has. It is given the same library from a copy of its file, which is
     * a second library as far as the machine is concerned. Null unless that is what
     * happened. (On a phone the app has one class loader, and this is never reached.)
     */
    private fun secondCopy(refused: UnsatisfiedLinkError): String? {
        if (refused.message?.contains("already loaded in another classloader") != true) return null
        val name = System.mapLibraryName(LIBRARY)
        val found = System.getProperty("java.library.path").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it, name) }.firstOrNull { it.isFile } ?: return null
        val copy = java.io.File.createTempFile("nexus-core-", "-$name")
        copy.deleteOnExit()
        found.copyTo(copy, overwrite = true)
        return copy.path
    }

    /** Loads the library, so that it is there when first wanted. Off the main thread, as the
     *  app starts (`Graph`). */
    fun warm() = load()

    /** One call across: `given` written out, `native` run on it, its answer read. */
    private fun <In, Out> across(given: In, takes: KSerializer<In>, gives: KSerializer<Out>, native: (ByteArray) -> ByteArray?): Out {
        val answer = try {
            load()
            native(json.encodeToString(takes, given).encodeToByteArray())
        } catch (e: UnsatisfiedLinkError) {
            throw CoreException("The shared library isn't in this build of the app: ${e.message}")
        } ?: throw CoreException("The shared library gave no answer.")
        return try {
            json.decodeFromString(gives, answer.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw CoreException("The shared library's answer couldn't be read: ${e.message}")
        }
    }

    /** Whether this is the phone's main thread. (Where there is no Android, in the unit
     *  tests, there is none to be on.) */
    private fun onMainThread(): Boolean = try {
        val main = android.os.Looper.getMainLooper()
        main != null && main.thread === Thread.currentThread() && !underRobolectric
    } catch (_: RuntimeException) {
        false
    }

    /** Robolectric's copy of Android runs a test on what it calls the main thread. */
    private val underRobolectric: Boolean by lazy { android.os.Build.FINGERPRINT == "robolectric" }

    private val choices = JsonElement.serializer()
    private val plays = ListSerializer(Play.serializer())
    private val notes = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Note.serializer()))

    // ---- Keeping devices in step: an exchange (the core's `step.rs`) ----

    @Serializable
    private class FindIn(val listed: List<Stated>, val username: String)

    @Serializable
    private class FindOut(val kind: String, val id: String = "")

    /** `find`: which playlist holds the shared record. Asked afresh at every exchange. */
    fun find(listed: List<Stated>, username: String): Found {
        val out = across(FindIn(listed, username), FindIn.serializer(), FindOut.serializer(), ::find)
        return when (out.kind) {
            "ours" -> Found.Ours(out.id)
            "none" -> Found.None
            "not_stated" -> Found.NotStated
            else -> throw CoreException("The shared library's answer couldn't be read: ${out.kind}")
        }
    }

    /** `settle`, with `clock` beside it: what an exchange is to do (steps 2 to 4). */
    fun settle(device: Device, answers: Answers): Settled {
        val given = SettleIn(
            device.username, device.account, device.choices, device.notes, device.history, device.plays, device.playSources,
            answers.listed, answers.playlist, answers.date, answers.received,
        )
        return across(given, SettleIn.serializer(), Settled.serializer(), ::settle)
    }

    @Serializable
    private class SettleIn(
        val username: String, val account: String, val choices: JsonElement, val notes: Notes, val history: List<Play>, val plays: List<Play>, val playSources: List<PlayFrom>,
        val listed: List<Stated>, val playlist: Stated?, val date: Long?, val received: Long,
    )

    @Serializable
    private class TakeIn(val choices: JsonElement, val take: JsonElement)

    /** `Choices::take` (step 6): what other devices shared, taken into the choices. */
    fun take(choices: JsonElement, take: JsonElement): JsonElement = across(TakeIn(choices, take), TakeIn.serializer(), this.choices, ::take)

    @Serializable
    private class WriteIn(val choices: JsonElement, val write: JsonElement)

    /** `Choices::sending` (step 7.2): whether the write still fits the choices, which then
     *  note that it is being tried. */
    fun sending(choices: JsonElement, write: JsonElement): Sending = across(WriteIn(choices, write), WriteIn.serializer(), Sending.serializer(), ::sending)

    /** `Choices::written` (step 7.4): the server has answered that it took the write. */
    fun written(choices: JsonElement, write: JsonElement): JsonElement = across(WriteIn(choices, write), WriteIn.serializer(), this.choices, ::written)

    @Serializable
    private class ChangeIn(val choices: JsonElement, val account: String, val change: Change)

    /** `Choices::change`: the person, signed in as `account`, changed something shared. */
    fun change(choices: JsonElement, account: String, change: Change): Changed = across(ChangeIn(choices, account, change), ChangeIn.serializer(), Changed.serializer(), ::change)

    @Serializable
    private class ShownIn(val choices: JsonElement, val account: String)

    /** `Choices::shown`: what was agreed, with this account's waiting changes on top. */
    fun shown(choices: JsonElement, account: String): Shown = across(ShownIn(choices, account), ShownIn.serializer(), Shown.serializer(), ::shown)

    @Serializable
    private class SortBackIn(val choices: JsonElement, val account: String, val key: String)

    /** The person chose, for the list `key`, an order only this phone offers: a sort for
     *  that list still waiting to be shared under `account` is taken back (one already
     *  tried stays: its write may have arrived). The choices as they then are. */
    fun sortTakenBack(choices: JsonElement, account: String, key: String): JsonElement =
        across(SortBackIn(choices, account, key), SortBackIn.serializer(), this.choices, ::sortTakenBack)

    @Serializable
    private class AdoptIn(val agreed: AgreedFormerly, val waiting: List<WaitingFormerly>, val account: String, val demote: Boolean, val usualTheme: String)

    /**
     * The choices a build from before the shared core kept, as the core keeps them. Once,
     * at the upgrade.
     *
     * `waiting` are the changes that build still had waiting. One to something it had never
     * agreed with the server (all of them, on a phone from 0.4.0, which never exchanged) is
     * not kept as a change: a theme, a list's shuffle and its sort become what the phone
     * holds as chosen "long ago", offered and losing to anything chosen since on any
     * device; a pin stays a pin of that one playlist, to be added to whatever pins the
     * server has; an unpin is dropped. One to something that had been agreed, at the
     * record's own time, is a change still.
     *
     * `demote`: the times on `agreed` are not the record's (an earlier build still stamped
     * whole values with "now"), so each of those is kept as chosen "long ago" too
     * (`Agreed::from_stamped`). `usualTheme` is the theme nobody chose.
     */
    fun adopt(agreed: AgreedFormerly, waiting: List<WaitingFormerly>, account: String, demote: Boolean = false, usualTheme: String = ""): JsonElement =
        across(AdoptIn(agreed, waiting, account, demote, usualTheme), AdoptIn.serializer(), choices, ::adopt)

    @Serializable
    private class HistoryIn(val mine: List<Play>, val theirs: List<Play>)

    /** `merge_history`: Recently played here and as other devices have it, as one. */
    fun history(mine: List<Play>, theirs: List<Play>): List<Play> = across(HistoryIn(mine, theirs), HistoryIn.serializer(), plays, ::history)

    /** What the phone has to know by name (the record's playlist, and so on). */
    fun names(): Names = across(JsonNull, JsonElement.serializer(), Names.serializer(), ::names)

    /** `reserved`: whether a playlist of the person's own may not have this name. */
    fun reserved(name: String): Boolean = across(name, String.serializer(), Boolean.serializer(), ::reserved)

    // ---- When songs were added to playlists (the core's `notes.rs`) ----

    @Serializable
    private class NoticeIn(val notes: Map<String, Note>, val seen: Boolean, val ids: List<String>, val now: Long)

    /** `Kept::notice`: a copy of the playlist has been read. `told`: something to save. */
    fun notesNotice(one: OnePlaylist, ids: List<String>, now: Long): OneTold = across(NoticeIn(one.notes, one.seen, ids, now), NoticeIn.serializer(), OneTold.serializer(), ::notesNotice)

    @Serializable
    private class ChangedIn(val notes: Map<String, Note>, val seen: Boolean, val before: List<String>, val after: List<String>, val removed: List<Int>?, val now: Long)

    /**
     * `Kept::changed`, or `Kept::edited` for a removal: the person changed the playlist on
     * this phone and the server has taken the change. `before` is the playlist as the
     * change was worked out from; `after` what the change made of it; for a change that took
     * songs out, `removed` says where in `before` they were. `told`: worth sharing.
     */
    fun notesChanged(one: OnePlaylist, before: List<String>, after: List<String>, removed: List<Int>?, now: Long): OneTold =
        across(ChangedIn(one.notes, one.seen, before, after, removed, now), ChangedIn.serializer(), OneTold.serializer(), ::notesChanged)

    @Serializable
    private class AddedIn(val notes: Map<String, Note>, val seen: Boolean, val ids: List<String>, val fresh: Boolean, val now: Long)

    /** `Kept::added`, or `Kept::create` for a playlist the app has just made (`fresh`):
     *  songs the app has just put in the playlist. */
    fun notesAdded(one: OnePlaylist, ids: List<String>, fresh: Boolean, now: Long): OneTold =
        across(AddedIn(one.notes, one.seen, ids, fresh, now), AddedIn.serializer(), OneTold.serializer(), ::notesAdded)

    @Serializable
    private class DatesIn(val notes: Map<String, Note>, val ids: List<String>)

    /** `Kept::dates`: when each of these songs was added, in order; 0 where it isn't known. */
    fun notesDates(notes: Map<String, Note>, ids: List<String>): List<Long> = across(DatesIn(notes, ids), DatesIn.serializer(), ListSerializer(Long.serializer()), ::notesDates)

    @Serializable
    private class SharedIn(val lists: Notes, val playlists: List<String>?)

    /** `Kept::shared`: the notes worth sharing, for playlists that still exist. */
    fun notesShared(lists: Notes, playlists: Set<String>?): Notes = across(SharedIn(lists, playlists?.toList()), SharedIn.serializer(), notes, ::notesShared)

    @Serializable
    private class NotesTakeIn(val lists: Notes, val theirs: Notes)

    /** `notes::take`: notes from other devices taken in. */
    fun notesTake(lists: Notes, theirs: Notes): NotesTaken = across(NotesTakeIn(lists, theirs), NotesTakeIn.serializer(), NotesTaken.serializer(), ::notesTake)

    // ---- The play queue between devices (the core's `handover.rs`) ----

    @Serializable
    private class WindowIn(val songs: List<QueueSong>, val cursor: Int, val position: Double)

    /** `window`: what of a queue (in the order it plays) is saved on the server; null with
     *  nothing at the cursor, or a radio station there. */
    fun queueWindow(songs: List<QueueSong>, cursor: Int, positionSeconds: Double): QueueSaved? =
        across(WindowIn(songs, cursor, positionSeconds), WindowIn.serializer(), QueueSaved.serializer().nullable, ::queueWindow)

    /** `first`: whether a save begins by reading the server's saved queue. */
    fun queueReadsFirst(here: Here): Boolean = readsFirst(across(here, Here.serializer(), String.serializer(), ::queueFirst))

    /** The library's answer to that, read: one this side has no name for is an error, and
     *  is never taken for "save without reading". */
    internal fun readsFirst(said: String): Boolean = when (said) {
        "read" -> true
        "save" -> false
        else -> throw CoreException("The shared library's answer couldn't be read: $said")
    }

    @Serializable
    private class SaveIn(val theirs: Theirs, val here: Here)

    /** `save`: whether this phone's queue is still the newest thing that happened. */
    fun queueSave(theirs: Theirs, here: Here): SaveVerdict = when (val said = across(SaveIn(theirs, here), SaveIn.serializer(), String.serializer(), ::queueSave)) {
        "save" -> SaveVerdict.SAVE
        "not_newest" -> SaveVerdict.NOT_NEWEST
        "not_now" -> SaveVerdict.NOT_NOW
        else -> throw CoreException("The shared library's answer couldn't be read: $said")
    }

    @Serializable
    private class LookIn(val theirs: Theirs, val here: Here, val now: Long)

    /** `look`: what a look at the server's saved queue leads to. */
    fun queueLook(theirs: Theirs, here: Here, now: Long): Looked = across(LookIn(theirs, here, now), LookIn.serializer(), Looked.serializer(), ::queueLook)

    /** `place`: where to pick a saved queue up ("Continue here"). */
    fun queuePlace(theirs: Theirs): Place = across(theirs, Theirs.serializer(), Place.serializer(), ::queuePlace)

    /** `device_name`: the name this device saves the queue under. */
    fun deviceName(id: String): String = across(id, String.serializer(), String.serializer(), ::deviceName)

    // ---- The Nexus server (the core's `nexus::proto`) ----------------------------------------

    @Serializable
    private class HelloIn(val status: Int, val body: List<Int>, val whole: Boolean)

    /** Whether an answer to the Nexus server's `hello` is the Nexus server's ([backend]); if
     *  not, why in a word ("redirect", "status", "empty", "too_large", "cut", "not_hello"). */
    @Serializable
    class HelloFound(val backend: Boolean, val why: String)

    /**
     * `nexus::proto::classify`: whether an answer to `GET /nexus/api/hello` is the backend's,
     * from its status and its body alone. `body` is what the transport read of the body: **to
     * its end, or to one byte past [HELLO_MAX_BYTES], and never cut shorter**; `whole` is false
     * if reading it failed before its end. No header is looked at, and a redirect is an answer.
     */
    fun nexusHello(status: Int, body: ByteArray, whole: Boolean): HelloFound =
        across(HelloIn(status, body.map { it.toInt() and 0xFF }, whole), HelloIn.serializer(), HelloFound.serializer(), ::nexusHello)

    /** Whether the library is loaded. Takes no lock and loads nothing. */
    val isReady: Boolean get() = ready

    /**
     * [nexusHello], **only if the library is already loaded**: null otherwise, having neither
     * loaded it nor waited for a load (the Nexus server's client never takes the load's lock,
     * which the next sign-in may need). Null too if the library refused.
     */
    fun nexusHelloIfReady(status: Int, body: ByteArray, whole: Boolean): HelloFound? {
        if (!ready) return null
        return try {
            nexusHello(status, body, whole)
        } catch (e: CoreException) {
            null
        }
    }

    /** The most of a `hello`'s body the core reads (`nexus::proto::HELLO_MAX_BYTES`). */
    const val HELLO_MAX_BYTES = 4096

    // ---- The Nexus server's part of the app (the core's `nexus::client`) -----------------------
    //
    // **One device's dealings with the backend are the core's machine** (the backend design's
    // 5.5): signing in, what is kept, a refused music login, what is shown and in what words.
    // The phone makes one, and drives it by its four calls, each a JSON value in and a list of
    // things to do out (`nexus/Nexus.kt`). Everything here can hold a secret (a password typed,
    // a session, a music login): nothing given or answered is ever quoted.

    /** A new machine: its number, for [nexusCall]. */
    fun nexusMachine(): Long = acrossPrivately(JsonObject(emptyMap()), JsonObject.serializer(), Long.serializer(), ::nexusMachine)

    /** One of the machine's calls, `{"machine": n, "call": <the core's envelope>}` (see
     *  `client/json.rs` in the core): gives what the core wrote, `{"generation", "do"}`. */
    fun nexusCall(input: JsonObject): JsonObject = acrossPrivately(input, JsonObject.serializer(), JsonObject.serializer(), ::nexusCall)

    /** The phone's storage adapter for the core's journal format and retention rules. */
    internal fun nexusJournal(input: JsonObject): JsonElement =
        acrossPrivately(input, JsonObject.serializer(), JsonElement.serializer(), ::nexusJournal)

    /** Closed observations, canonical report text and the core's device switch rule. */
    internal fun nexusDiagnostics(input: JsonObject): JsonElement =
        acrossPrivately(input, JsonObject.serializer(), JsonElement.serializer(), ::nexusDiagnostics)

    /** `client::carries_over`: whether the folder this phone had goes to the person `music`
     *  (a music login as the machine handed it) was handed to. */
    fun nexusCarriesOver(beforeAddress: String, beforeUser: String, music: JsonObject): Boolean =
        acrossPrivately(JsonObject(mapOf("before_address" to kotlinx.serialization.json.JsonPrimitive(beforeAddress), "before_user" to kotlinx.serialization.json.JsonPrimitive(beforeUser), "music" to music)), JsonObject.serializer(), Boolean.serializer(), ::nexusCarriesOver)

    @Serializable
    internal class StandInOut(val status: Int, val body: List<Int>) {
        val bytes: ByteArray get() = ByteArray(body.size) { body[it].toByte() }
    }

    /** **For the tests' stand-in for the Nexus server only** (`native/src/standin.rs`): a body
     *  the backend answers with, and its status, written by the core's own encoder. Nothing in
     *  the app calls it. */
    internal fun nexusStandIn(input: JsonObject): StandInOut = acrossPrivately(input, JsonObject.serializer(), StandInOut.serializer(), ::nexusStandIn)

    // ---- Requests: what waits on the network (the core's `gate.rs`, `lidarr.rs`, `musicbrainz.rs`) ----
    //
    // Each of these asks the library for something it gets from Lidarr, the sign-in in front
    // of it or MusicBrainz, through the phone's own client for them ([CoreHttp], which is
    // where to read how one is stopped). **They suspend**: the library's call runs on a
    // thread kept for the purpose, never the caller's and never the main one, and cancelling
    // the caller stops it.

    /** Tells the library, once, which object makes its requests. */
    private val wired: Unit by lazy {
        load()
        wire(CoreHttp)
    }

    @Serializable
    private class Out<T>(val kind: String, val value: T? = null, val why: String = "")

    /** One call that waits on the network: as [across], and its answer read as an [Outcome].
     *  What it is handed and what it answers can hold a secret, so neither is ever quoted
     *  in an exception's words. */
    private fun <In, T> over(given: In, takes: KSerializer<In>, gives: KSerializer<T>, native: (ByteArray) -> ByteArray?): Outcome<T> {
        wired
        val out = acrossPrivately(given, takes, Out.serializer(gives), native)
        return when (out.kind) {
            "got" -> Outcome.Got(out.value ?: throw CoreException("The shared library's answer couldn't be read."))
            "failed" -> Outcome.Failed(out.why)
            "stopped" -> Outcome.Stopped
            else -> throw CoreException("The shared library's answer couldn't be read.")
        }
    }

    /** [across], for what can hold a secret: an answer that can't be read is not quoted. */
    private fun <In, Out> acrossPrivately(given: In, takes: KSerializer<In>, gives: KSerializer<Out>, native: (ByteArray) -> ByteArray?): Out {
        val answer = try {
            load()
            native(json.encodeToString(takes, given).encodeToByteArray())
        } catch (e: UnsatisfiedLinkError) {
            throw CoreException("The shared library isn't in this build of the app: ${e.message}")
        } ?: throw CoreException("The shared library gave no answer.")
        return try {
            json.decodeFromString(gives, answer.decodeToString())
        } catch (_: IllegalArgumentException) {
            throw CoreException("The shared library's answer couldn't be read.")
        }
    }

    @Serializable
    private class GateFindIn(val operation: Long, val address: String, val home: String?)

    /**
     * `gate::find`: what answers at Lidarr's address. Null if it is Lidarr itself; otherwise
     * the sign-in in front of it.
     */
    suspend fun gateFind(address: String, home: String? = null): Outcome<SignInDoor?> = orBusy { CoreHttp.looking { operation ->
        wired
        val out = acrossPrivately(GateFindIn(operation, address, home), GateFindIn.serializer(), Out.serializer(SignInDoor.serializer().nullable), ::gateFind)
        when (out.kind) {
            "got" -> Outcome.Got(out.value)
            "failed" -> Outcome.Failed(out.why)
            "stopped" -> Outcome.Stopped
            else -> throw CoreException("The shared library's answer couldn't be read.")
        }
    } }

    /** Whatever waits on the network, asked for when there is no thread for it and no
     *  place left in the queue: told so as its outcome, in words, like any other failure. */
    private inline fun <T> orBusy(ask: () -> Outcome<T>): Outcome<T> = try {
        ask()
    } catch (_: CoreHttp.Busy) {
        Outcome.Failed(CoreHttp.BUSY)
    }

    @Serializable
    private class GateLinkIn(val door: SignInDoor, val back: String, val state: String, val secret: String)

    /** `gate::sign_in_link`: the page the browser is sent to. `back` is where the service
     *  sends it back to with the code, `state` what it brings back with it, and `secret`
     *  what only this app knows. */
    fun gateLink(door: SignInDoor, back: String, state: String, secret: String): String =
        acrossPrivately(GateLinkIn(door, back, state, secret), GateLinkIn.serializer(), String.serializer(), ::gateLink)

    @Serializable
    private class GateParamIn(val query: String, val name: String)

    /** `gate::param`: one named value from the part of an address after the "?". */
    fun gateParam(query: String, name: String): String? =
        acrossPrivately(GateParamIn(query, name), GateParamIn.serializer(), String.serializer().nullable, ::gateParam)

    /**
     * **The place Lidarr's address is, as the client that will connect to it takes it**
     * (scheme, host and port: [CoreHttp.originOf] of the address as the library uses it).
     * Null for an address the client can't use. A key belongs to this, is kept with it, and
     * is sent nowhere else.
     */
    fun lidarrPlace(address: String): String? = CoreHttp.originOf(lidarrTidy(address))

    @Serializable
    private class Judged(val scheme: String = "", val host: String = "", val port: Int = 0, val why: String = "")

    /**
     * Why an address is not one Lidarr can be typed at; null if it may be used.
     *
     * **The library's rule** (`lidarr::origin`, the Windows app's): only the scheme, the
     * host and the port are judged, strictly: a name and password in the address, a
     * backslash, a space, letters beyond plain ASCII, a port that isn't a number, a scheme
     * that isn't http or https are each refused, in the library's own sentence. **And the
     * app's client must take the address for that very place** ([CoreHttp.readsAs]): where
     * the bytes go is what a key is kept for.
     */
    fun lidarrRefused(address: String): String? {
        val judged = across(lidarrTidy(address), String.serializer(), Judged.serializer(), ::lidarrOrigin)
        if (judged.why.isNotEmpty()) return judged.why
        return if (CoreHttp.readsAs(lidarrTidy(address), judged.scheme, judged.host, judged.port)) null else Words.addressUnusable
    }

    @Serializable
    private class RefusedIn(val door: SignInDoor, val lidarr: String)

    /**
     * `gate::Found::trusted`: why the sign-in at `door` is **not one to believe** for a
     * Lidarr at `lidarr` (its address, as kept); null if it is believed. A sign-in is
     * believed on https, or, with Lidarr on plain http, on Lidarr's own host: anywhere
     * else, signing in would cross the network unprotected. Asks nobody.
     */
    fun gateRefused(door: SignInDoor, lidarr: String): String? =
        across(RefusedIn(door, lidarr), RefusedIn.serializer(), String.serializer().nullable, ::gateRefused)

    /** The same for a sign-in service: where its code, its check and its token may go. */
    fun servicePlace(service: String): String? = CoreHttp.originOf(service)

    /** Whether an address has a name or a password written into it. */
    fun namesSomeone(address: String): Boolean = CoreHttp.namesSomeone(lidarrTidy(address))

    @Serializable
    private class GateFinishIn(val operation: Long, val door: SignInDoor, val code: String, val back: String, val secret: String, val bound: String?, val home: String?)

    /**
     * `gate::finish`: collects the token once the browser has come back with `code`.
     * **The caller has checked the browser's `state` itself**: a code that came with any
     * other is someone else's and is never handed to this. It never waits behind other
     * changes, and each of its requests has a few seconds and no more
     * ([CoreHttp.finishing]): the browser is waiting for its page.
     */
    suspend fun gateFinish(door: SignInDoor, code: String, back: String, secret: String, home: String? = null): Outcome<SignedIn> = orBusy { CoreHttp.finishing { operation ->
        over(GateFinishIn(operation, door, code, back, secret, servicePlace(door.service), home), GateFinishIn.serializer(), SignedIn.serializer(), ::gateFinish)
    } }

    @Serializable
    private class GateOpenIn(val door: SignInDoor, val token: String, val bound: String?, val home: String?)

    /**
     * `gate::Session::new`: a sign-in that's been done, kept open in the library under the
     * number handed back ([LidarrAt.session]). `keep` is called with the token each time
     * the service replaces it, to put it away, and with "" when the service has stopped
     * honouring it; it is called on one of the library's threads. `bound` is the place the
     * token was given for (kept with it): the token, and the passes it buys, are sent to
     * the service there and nowhere else.
     */
    suspend fun gateOpen(door: SignInDoor, token: String, bound: String? = servicePlace(door.service), home: String? = null, keep: (String) -> Unit): Long = CoreHttp.aside {
        wired
        acrossPrivately(GateOpenIn(door, token, bound, home), GateOpenIn.serializer(), Long.serializer(), ::gateOpen).also { CoreHttp.keeping(it, keep) }
    }

    /** `gate::Session::close`: ends the sign-in here and hands back its token, for handing
     *  back to the service ([gateGiveUp]); "" if it had none left. Waits for a renewal that
     *  is under way, so that the token handed back is the newest. */
    suspend fun gateClose(session: Long): String = CoreHttp.aside {
        wired
        acrossPrivately(session, Long.serializer(), String.serializer(), ::gateClose).also { CoreHttp.forget(session) }
    }

    @Serializable
    private class GateGiveUpIn(val operation: Long, val service: String, val token: String, val bound: String?, val home: String?)

    /** `gate::give_up_token`: tells the service a token is no longer wanted. Best effort,
     *  **and never long**: it never waits its turn behind other changes and has a few
     *  seconds and no more ([CoreHttp.finishing]), because until a sign-out's goodbye is
     *  done nobody is handed any account's requests (`RequestsOwners`). `bound` is the
     *  place the token was given for: it is handed back there or not at all. */
    suspend fun gateGiveUp(door: SignInDoor, token: String, bound: String? = servicePlace(door.service), home: String? = null) {
        try {
            CoreHttp.finishing { operation ->
                wired
                acrossPrivately(GateGiveUpIn(operation, door.service, token, bound, home), GateGiveUpIn.serializer(), Boolean.serializer(), ::gateGiveUp)
            }
        } catch (_: CoreHttp.Busy) {
            // (Best effort, as it always was: the token is forgotten here all the same.)
        }
    }

    @Serializable
    private class Of(val operation: Long, val lidarr: LidarrAt)

    /** `Lidarr::version`: checks the address and the key (and the sign-in, if there is one)
     *  by asking Lidarr which version it is. */
    suspend fun lidarrVersion(lidarr: LidarrAt): Outcome<String> = orBusy { CoreHttp.looking { operation ->
        over(Of(operation, lidarr), Of.serializer(), String.serializer(), ::lidarrVersion)
    } }

    @Serializable
    private class SayIn(val what: String, val with: List<String>)

    /** One of the library's own sentences or pages, by the library's name for it: see
     *  [Words], which is what to call. */
    internal fun say(what: String, with: List<String> = emptyList()): String =
        across(SayIn(what, with), SayIn.serializer(), String.serializer(), ::say)

    /**
     * The check of the connection, as the Windows app makes it: [lidarrVersion], and if the
     * answer is that a sign-in is needed, [gateFind] for which.
     */
    suspend fun lidarrCheck(lidarr: LidarrAt): Outcome<Checked> = orBusy { CoreHttp.looking { operation ->
        over(Of(operation, lidarr), Of.serializer(), Checked.serializer(), ::lidarrCheck)
    } }

    /** `lidarr::tidy`: an address as it is used, whatever was typed around it. */
    fun lidarrTidy(address: String): String = across(address, String.serializer(), String.serializer(), ::lidarrTidy)

    /** `lidarr::host`: the host an address is on, which is what a key belongs to. */
    fun lidarrHost(address: String): String = across(address, String.serializer(), String.serializer(), ::lidarrHost)

    /** `lidarr::mask`: a key as it may be shown. */
    fun lidarrMask(key: String): String = acrossPrivately(key, String.serializer(), String.serializer(), ::lidarrMask)

    /** `gate::Found::host`: the sign-in service's name, for showing. */
    fun gateHost(service: String): String = across(service, String.serializer(), String.serializer(), ::gateHost)

    @Serializable
    private class RequestIn(val operation: Long, val lidarr: LidarrAt, val place: Long, val wanted: Offer)

    /**
     * `Lidarr::request`, for an offer handed over whole: puts an album (or an EP, a single,
     * a soundtrack) on Lidarr's list and has Lidarr start looking for it. Can take a minute
     * and a half. Stopped, it still puts back what it changed on Lidarr to do it. **For the
     * tests of the boundary; the app asks through [request].** The way back is written
     * down by `place`'s keeper, as for every request.
     */
    internal suspend fun lidarrRequest(lidarr: LidarrAt, place: Long, wanted: Offer): Outcome<Boolean> = orBusy { CoreHttp.seenThrough { operation ->
        over(RequestIn(operation, lidarr, place, wanted), RequestIn.serializer(), Boolean.serializer(), ::lidarrRequest)
    } }

    /** `Lidarr::pending`: what is on its way, those being fetched first. */
    suspend fun lidarrPending(lidarr: LidarrAt): Outcome<List<Pending>> = orBusy { CoreHttp.looking { operation ->
        over(Of(operation, lidarr), Of.serializer(), ListSerializer(Pending.serializer()), ::lidarrPending)
    } }

    @Serializable
    private class CancelIn(val operation: Long, val lidarr: LidarrAt, val id: Long)

    /** `Lidarr::cancel`: takes an album off Lidarr's list ([Pending.id]). */
    suspend fun lidarrCancel(lidarr: LidarrAt, id: Long): Outcome<Boolean> = orBusy { CoreHttp.changing { operation ->
        over(CancelIn(operation, lidarr, id), CancelIn.serializer(), Boolean.serializer(), ::lidarrCancel)
    } }

    /** [lidarrCancel], **begun and not waited for** (see [request]): whoever begins it must
     *  then forget, here, what Lidarr has taken off its list, whoever has gone away
     *  meanwhile. */
    internal fun cancelling(lidarr: LidarrAt, id: Long): CoreHttp.Begun<Outcome<Boolean>> = CoreHttp.changingThrough { operation ->
        over(CancelIn(operation, lidarr, id), CancelIn.serializer(), Boolean.serializer(), ::lidarrCancel)
    }

    @Serializable
    private class TracksIn(val operation: Long, val album: String)

    /** `MusicBrainz::album_tracks`: what is on an album (`album` is MusicBrainz's name for
     *  it, which is Lidarr's too). */
    suspend fun albumTracks(album: String): Outcome<List<TrackName>> = orBusy { CoreHttp.looking { operation ->
        over(TracksIn(operation, album), TracksIn.serializer(), ListSerializer(TrackName.serializer()), ::albumTracks)
    } }

    // ---- The pages and actions of requests ----
    //
    // Each is the library's own function of the same name (`requests::search`, `artist`,
    // `album`, `asking`, `request`, `on_their_way`; `asked::`; `discover::`), asked with what
    // one connection to Lidarr has by it: its *place* (see `native/src/pages.rs`), which
    // holds what has been offered while the connection lasts and the three things the
    // library reads at the moment it needs them, set here whenever they change.

    /**
     * A connection to Lidarr begins: its place, under the number handed back.
     *
     * `wayBack` is **who writes down what must be put back on Lidarr** for this place (see
     * [CoreHttp.wayBackKeptBy]): the library writes it before it changes an artist's
     * setting, and changes nothing if it is told it couldn't be written. With nobody to
     * write it (the default), nothing that needs one is changed.
     */
    fun placeOpen(wayBack: (batch: ByteArray?, surely: Boolean) -> Boolean = { _, _ -> false }): Long =
        across(true, Boolean.serializer(), Long.serializer(), ::placeOpen).also { CoreHttp.wayBackKeptBy(it, wayBack) }

    /** The connection is over: what was offered while it lasted is forgotten. */
    fun placeClose(place: Long) {
        across(place, Long.serializer(), Boolean.serializer(), ::placeClose)
        CoreHttp.wayBackForgotten(place)
    }

    @Serializable
    private class PlaceWaysIn(val place: Long, val written: String)

    /** `bulk::Job::load`: what an earlier run wrote down of changes to Lidarr still to be
     *  put back (`asking.json`, as it reads), taken up as the connection begins. False if
     *  the library can make nothing of it. */
    fun placeWays(place: Long, written: String): Boolean = across(PlaceWaysIn(place, written), PlaceWaysIn.serializer(), Boolean.serializer(), ::placeWays)

    /** `bulk::Job::unfinished`: whether anything written down is still to be put back **on
     *  this Lidarr**. What is written down for another Lidarr is not, and is left as it is. */
    fun waysLeft(lidarr: LidarrAt, place: Long): Boolean = acrossPrivately(PageIn(0, lidarr, place, ""), PageIn.serializer(), Boolean.serializer(), ::waysLeft)

    /**
     * `bulk::Job::work_through`: **puts back what an earlier run left changed on this
     * Lidarr**, and crosses it off. It waits for Lidarr to have finished with the artist,
     * looking every few seconds, so it can take minutes. **Begun, not waited for**:
     * stopped, what it had not put back is still written down, and `done` is how it ended
     * once the library's worker has ended. The value is whether nothing is left for this
     * Lidarr.
     */
    internal fun putBack(lidarr: LidarrAt, place: Long): CoreHttp.Begun<Outcome<Boolean>> = CoreHttp.puttingBack { operation ->
        over(PageIn(operation, lidarr, place, ""), PageIn.serializer(), Boolean.serializer(), ::putBack)
    }

    @Serializable
    private class PlaceLibraryIn(val place: Long, val library: LibraryLists)

    /** The phone's copy of the library, handed over whenever it changes. (Large: off the
     *  main thread.) */
    fun placeLibrary(place: Long, library: LibraryLists) {
        across(PlaceLibraryIn(place, library), PlaceLibraryIn.serializer(), Boolean.serializer(), ::placeLibrary)
    }

    @Serializable
    private class PlaceAskedIn(val place: Long, val asked: List<Asked>)

    /** What has been asked for, as the phone kept it: handed over as the connection begins. */
    fun placeAsked(place: Long, asked: List<Asked>) {
        across(PlaceAskedIn(place, asked), PlaceAskedIn.serializer(), Boolean.serializer(), ::placeAsked)
    }

    @Serializable
    private class PlaceSinglesIn(val place: Long, val on: Boolean)

    /** The "singles and EPs" setting, handed over whenever it changes. */
    fun placeSingles(place: Long, on: Boolean) {
        across(PlaceSinglesIn(place, on), PlaceSinglesIn.serializer(), Boolean.serializer(), ::placeSingles)
    }

    @Serializable
    private class PageIn(val operation: Long, val lidarr: LidarrAt, val place: Long, val what: String, val name: String = "")

    /**
     * `requests::search`: songs, artists and albums matching `query` that aren't in the
     * library. `soFar` is handed what has been found each time more of it is known (on one
     * of the library's threads, never after a stop); all of it is the answer.
     */
    suspend fun pageSearch(lidarr: LidarrAt, place: Long, query: String, soFar: (Outside) -> Unit): Outcome<Outside> =
        orBusy { CoreHttp.looking(published = { found -> soFar(json.decodeFromString(Outside.serializer(), found.decodeToString())) }) { operation ->
            over(PageIn(operation, lidarr, place, query), PageIn.serializer(), Outside.serializer(), ::pageSearch)
        } }

    /** `requests::artist`: an artist as the wider world knows them; `id` is MusicBrainz's
     *  name for them (an [ArtistOffer]'s, a [SongOffer]'s `artistId`). */
    suspend fun pageArtist(lidarr: LidarrAt, place: Long, id: String): Outcome<OutsideArtist> = orBusy { CoreHttp.looking { operation ->
        over(PageIn(operation, lidarr, place, id), PageIn.serializer(), OutsideArtist.serializer(), ::pageArtist)
    } }

    /** `requests::artist_popular`: the songs an artist is most played for that the library
     *  doesn't have, found after their page since it takes a few more questions; `name` is
     *  what the page calls them. No songs in it: there are none to show. */
    suspend fun pageArtistPopular(lidarr: LidarrAt, place: Long, id: String, name: String): Outcome<Popular> = orBusy { CoreHttp.looking { operation ->
        over(PageIn(operation, lidarr, place, id, name), PageIn.serializer(), Popular.serializer(), ::pageArtistPopular)
    } }

    /** `requests::album`: an album that isn't in the library; `id` is Lidarr's name for it. */
    suspend fun pageAlbum(lidarr: LidarrAt, place: Long, id: String): Outcome<AlbumPage> = orBusy { CoreHttp.looking { operation ->
        when (val out = over(PageIn(operation, lidarr, place, id), PageIn.serializer(), JsonElement.serializer(), ::pageAlbum)) {
            is Outcome.Got -> Outcome.Got(albumPage(out.value))
            is Outcome.Failed -> out
            Outcome.Stopped -> Outcome.Stopped
        }
    } }

    private fun albumPage(answer: JsonElement): AlbumPage {
        val one = (answer as? JsonObject)?.entries?.singleOrNull() ?: throw CoreException("The shared library's answer couldn't be read.")
        return when (one.key) {
            "Found" -> AlbumPage.Found(json.decodeFromJsonElement(OutsideAlbum.serializer(), one.value))
            "Unavailable" -> AlbumPage.Unavailable(json.decodeFromJsonElement(String.serializer(), one.value))
            else -> throw CoreException("The shared library's answer couldn't be read.")
        }
    }

    /** `requests::on_their_way`: what has arrived lately, and what Lidarr has still to get. */
    suspend fun pageOnTheirWay(lidarr: LidarrAt, place: Long): Outcome<Waiting> = orBusy { CoreHttp.looking { operation ->
        over(PageIn(operation, lidarr, place, ""), PageIn.serializer(), Waiting.serializer(), ::pageOnTheirWay)
    } }

    @Serializable
    private class AskingIn(val place: Long, val album: String, val title: String, val by: String, val song: String, val kind: String, val connection: Long)

    @Serializable
    private class AskWords(val heading: String, val text: String)

    /** `requests::asking`: whether to ask for an album, and how the question is put: Lidarr's
     *  name for it, its title, whose it is (may be empty), the song that led here if it was
     *  one, and what kind of release it is if the caller knows. `connection` is the one it
     *  was offered under: the question is put from what that connection was offered. Asks
     *  nobody. */
    fun asking(place: Long, album: String, title: String, by: String = "", song: String = "", kind: String = "", connection: Long = 0): Asking {
        val answer = across(AskingIn(place, album, title, by, song, kind, connection), AskingIn.serializer(), JsonElement.serializer(), ::asking)
        val one = (answer as? JsonObject)?.entries?.singleOrNull() ?: throw CoreException("The shared library's answer couldn't be read.")
        return when (one.key) {
            "Already" -> Asking.Already(json.decodeFromJsonElement(String.serializer(), one.value))
            "Ask" -> json.decodeFromJsonElement(AskWords.serializer(), one.value).let { Asking.Ask(it.heading, it.text) }
            else -> throw CoreException("The shared library's answer couldn't be read.")
        }
    }

    /**
     * `requests::request`: asks Lidarr for an album that has been offered; the value is who
     * it is by. **Begun, not waited for**: once it has started to change Lidarr it runs to
     * its end whoever has gone away meanwhile, and whoever begins it must then write down
     * what was asked for ([noted], then [marked]).
     */
    internal fun request(lidarr: LidarrAt, place: Long, album: String): CoreHttp.Begun<Outcome<String>> = CoreHttp.seeingThrough { operation ->
        over(PageIn(operation, lidarr, place, album), PageIn.serializer(), String.serializer(), ::request)
    }

    /**
     * `Lidarr::add_artist`, begun in the same way (see [request]): has Lidarr get
     * everything by an artist (`artist` is Lidarr's name for them). The value is how many
     * of their albums were newly asked for; none for an artist new to Lidarr, which learns
     * their albums in its own time. It takes its turn with every other change to an
     * artist's record on Lidarr, which is the place's to keep.
     */
    internal fun addArtist(lidarr: LidarrAt, place: Long, artist: String, name: String): CoreHttp.Begun<Outcome<Int>> = CoreHttp.seeingThrough { operation ->
        over(PageIn(operation, lidarr, place, artist, name), PageIn.serializer(), Int.serializer(), ::addArtist)
    }

    @Serializable
    private class NotedIn(val place: Long, val album: String, val title: String, val artist: String, val song: String, val now: Long)

    /**
     * Once Lidarr has an album on its list: the note of it, put with the rest
     * (`requests::asked_for`, `asked::put`). The answer is everything asked for, **to be
     * kept; only once it is kept is the offer marked** ([marked]): the order the library
     * asks of every app.
     */
    fun noted(place: Long, album: String, title: String, artist: String, song: String, now: Long): List<Asked> =
        across(NotedIn(place, album, title, artist, song, now), NotedIn.serializer(), ListSerializer(Asked.serializer()), ::noted)

    @Serializable
    private class MarkedIn(val place: Long, val album: String, val requested: Boolean, val connection: Long)

    /** `Offers::mark`: an album has been asked for (or no longer is), so it is shown that
     *  way, among what the connection it was asked under has been offered. */
    fun marked(place: Long, album: String, requested: Boolean, connection: Long = 0) {
        across(MarkedIn(place, album, requested, connection), MarkedIn.serializer(), Boolean.serializer(), ::marked)
    }

    @Serializable
    private class AddingIn(val name: String, val open: Int)

    /** `requests::adding`: the question before Lidarr is set to keep track of an artist, its
     *  heading and its text; `open` is how many of their albums could be asked for. */
    fun adding(name: String, open: Int): Pair<String, String> = across(AddingIn(name, open), AddingIn.serializer(), TwoNames, ::adding)

    @Serializable
    private class ForgetIn(val place: Long, val album: String, val title: String, val artist: String)

    /** `asked::forget`: stops watching for an album that has been taken off Lidarr's list,
     *  **by what it is**: Lidarr's own name for it (`album`) where both have one, and
     *  otherwise its title and who it is by. */
    fun askedForget(place: Long, title: String, artist: String, album: String = ""): AskedChange =
        across(ForgetIn(place, album, title, artist), ForgetIn.serializer(), AskedChange.serializer(), ::askedForget)

    @Serializable
    private class NowIn(val place: Long, val now: Long)

    /** `asked::tidy`: drops what has been listed long enough, and what has been waited for
     *  too long. */
    fun askedTidy(place: Long, now: Long): AskedChange = across(NowIn(place, now), NowIn.serializer(), AskedChange.serializer(), ::askedTidy)

    /** Looks through the phone's copy of the library (as last handed over) for what was
     *  asked for and is still awaited, marks what is there now, and says which are news.
     *  Asks nobody. */
    fun askedArrivals(place: Long, now: Long): Arrivals = across(NowIn(place, now), NowIn.serializer(), Arrivals.serializer(), ::askedArrivals)

    /** `requests::arrived_news`: what to say of albums that were asked for and have arrived
     *  (their notes as they were when that was found); empty for none. Asks nobody. */
    fun arrivedNews(told: List<Asked>): String = across(told, ListSerializer(Asked.serializer()), String.serializer(), ::arrivedNews)

    /** A song of the library's, as far as choosing favourites needs it. */
    @Serializable
    class Played(val artistId: String, val artist: String, val plays: Int)

    /** `discover::favourites`: the artists played most, favourite first, as the music
     *  server's id for each and their name. */
    fun discoverFavourites(songs: List<Played>): List<Pair<String, String>> =
        across(songs, ListSerializer(Played.serializer()), ListSerializer(TwoNames), ::discoverFavourites)

    @Serializable
    private class SuggestIn(val operation: Long, val lidarr: LidarrAt, val place: Long, val similar: List<@Serializable(with = NameAndNames::class) Pair<String, List<String>>>, val now: Long)

    /** `discover::look`: suggestions looked for afresh, from who the music server says each
     *  favourite is like (in the favourites' order, most alike first). **Kept only if it is
     *  got**: a stopped look is no answer. Slow: a question to Lidarr for each. */
    suspend fun discoverLook(lidarr: LidarrAt, place: Long, similar: List<Pair<String, List<String>>>, now: Long): Outcome<KeptSuggestions> = orBusy { CoreHttp.looking { operation ->
        over(SuggestIn(operation, lidarr, place, similar, now), SuggestIn.serializer(), KeptSuggestions.serializer(), ::discoverLook)
    } }

    @Serializable
    private class KeptIn(val place: Long, val kept: KeptSuggestions, val now: Long, val forget: String)

    /** The suggestions that are kept, read as the library reads them: whether they will
     *  still do, which of them to show (less anyone the library has gained since), and, with
     *  `forget`, the same without that artist (once they have been added). */
    fun discoverKept(place: Long, kept: KeptSuggestions, now: Long, forget: String = ""): Suggested =
        across(KeptIn(place, kept, now, forget), KeptIn.serializer(), Suggested.serializer(), ::discoverKept)

    // ---- For the tests of the boundary itself ----

    /** The text handed across and back, read as text on the other side and written again. */
    internal fun echo(text: String): String = across(text, String.serializer(), String.serializer(), ::echo)

    /**
     * Makes the library panic, with these words: for the check that a panic reaches Kotlin
     * as a [CoreException] and the app carries on. Only the tests and the debug build's
     * check call it.
     */
    internal fun stopForTheTest(words: String) {
        across(words, String.serializer(), JsonElement.serializer(), ::stop)
    }

    /** What the library was handed, as bytes of this side's choosing: for the tests of what
     *  it makes of something it doesn't take. */
    internal fun findRaw(bytes: ByteArray): ByteArray? {
        load()
        return find(bytes)
    }

    /** One request made through the library's own way out, and what was made of its answer:
     *  for the tests of the boundary ([CoreHttp]). `then` is what is done with the answer:
     *  "text", "json", "drop" or "panic". */
    @Serializable
    internal class Probe(
        val line: String = "lidarr", val nobodys: Boolean = false, val method: String = "GET", val url: String,
        val headers: List<List<String>> = emptyList(), val body: String? = null, val patience: Long? = null, val pause: Long? = null, val then: String = "text",
        /** Time the library is busy for before the request, which no stop cuts short. */
        val sleep: Long? = null,
        /** When the library says "keep going", if it does: "first", or "after the wait". */
        val keepGoing: String? = null,
        /** Asked through a way out bound to this place, as one that carries a secret is. */
        val bound: String? = null,
        /** For an account whose music server is here; and as the way to Lidarr's own API at
         *  an address the person typed. */
        val home: String? = null,
        val typed: Boolean = false,
    )

    @Serializable
    internal class Probed(val waited: Boolean? = null, val status: Int = 0, val headers: List<List<String?>> = emptyList(), val body: String? = null, val unread: String? = null)

    @Serializable
    private class ProbeIn(
        val operation: Long, val line: String, val nobodys: Boolean, val method: String, val url: String,
        val headers: List<List<String>>, val body: String?, val patience: Long?, val pause: Long?, val then: String,
        val sleep: Long?, @kotlinx.serialization.SerialName("keep_going") val keepGoing: String?, val bound: String?, val home: String?, val typed: Boolean,
    )

    private fun probing(probe: Probe) = { operation: Long ->
        val given = ProbeIn(operation, probe.line, probe.nobodys, probe.method, probe.url, probe.headers, probe.body, probe.patience, probe.pause, probe.then, probe.sleep, probe.keepGoing, probe.bound, probe.home, probe.typed)
        over(given, ProbeIn.serializer(), Probed.serializer(), ::wireProbe)
    }

    internal suspend fun wireProbe(probe: Probe, changing: Boolean = false): Outcome<Probed> =
        orBusy { if (changing) CoreHttp.changing(probing(probe)) else CoreHttp.looking(probing(probe)) }

    /** The same, run as something that is seen through once the library says so. */
    internal fun wireProbeSeenThrough(probe: Probe): CoreHttp.Begun<Outcome<Probed>> = CoreHttp.seeingThrough(probing(probe))

    @JvmStatic private external fun wire(wire: Any)
    @JvmStatic private external fun gateFind(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateLink(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateParam(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateFinish(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateOpen(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateClose(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateGiveUp(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrVersion(input: ByteArray): ByteArray?
    @JvmStatic private external fun say(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrCheck(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrTidy(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrHost(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrMask(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateHost(input: ByteArray): ByteArray?
    @JvmStatic private external fun gateRefused(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrOrigin(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrRequest(input: ByteArray): ByteArray?
    @JvmStatic private external fun addArtist(input: ByteArray): ByteArray?
    @JvmStatic private external fun placeWays(input: ByteArray): ByteArray?
    @JvmStatic private external fun waysLeft(input: ByteArray): ByteArray?
    @JvmStatic private external fun putBack(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrPending(input: ByteArray): ByteArray?
    @JvmStatic private external fun lidarrCancel(input: ByteArray): ByteArray?
    @JvmStatic private external fun albumTracks(input: ByteArray): ByteArray?
    @JvmStatic private external fun placeOpen(input: ByteArray): ByteArray?
    @JvmStatic private external fun placeClose(input: ByteArray): ByteArray?
    @JvmStatic private external fun placeLibrary(input: ByteArray): ByteArray?
    @JvmStatic private external fun placeAsked(input: ByteArray): ByteArray?
    @JvmStatic private external fun placeSingles(input: ByteArray): ByteArray?
    @JvmStatic private external fun pageSearch(input: ByteArray): ByteArray?
    @JvmStatic private external fun pageArtist(input: ByteArray): ByteArray?
    @JvmStatic private external fun pageArtistPopular(input: ByteArray): ByteArray?
    @JvmStatic private external fun pageAlbum(input: ByteArray): ByteArray?
    @JvmStatic private external fun pageOnTheirWay(input: ByteArray): ByteArray?
    @JvmStatic private external fun asking(input: ByteArray): ByteArray?
    @JvmStatic private external fun request(input: ByteArray): ByteArray?
    @JvmStatic private external fun noted(input: ByteArray): ByteArray?
    @JvmStatic private external fun marked(input: ByteArray): ByteArray?
    @JvmStatic private external fun adding(input: ByteArray): ByteArray?
    @JvmStatic private external fun askedForget(input: ByteArray): ByteArray?
    @JvmStatic private external fun askedTidy(input: ByteArray): ByteArray?
    @JvmStatic private external fun askedArrivals(input: ByteArray): ByteArray?
    @JvmStatic private external fun discoverFavourites(input: ByteArray): ByteArray?
    @JvmStatic private external fun discoverLook(input: ByteArray): ByteArray?
    @JvmStatic private external fun discoverKept(input: ByteArray): ByteArray?
    @JvmStatic private external fun arrivedNews(input: ByteArray): ByteArray?
    @JvmStatic private external fun wireProbe(input: ByteArray): ByteArray?
    @JvmStatic private external fun find(input: ByteArray): ByteArray?
    @JvmStatic private external fun settle(input: ByteArray): ByteArray?
    @JvmStatic private external fun take(input: ByteArray): ByteArray?
    @JvmStatic private external fun sending(input: ByteArray): ByteArray?
    @JvmStatic private external fun written(input: ByteArray): ByteArray?
    @JvmStatic private external fun change(input: ByteArray): ByteArray?
    @JvmStatic private external fun shown(input: ByteArray): ByteArray?
    @JvmStatic private external fun adopt(input: ByteArray): ByteArray?
    @JvmStatic private external fun sortTakenBack(input: ByteArray): ByteArray?
    @JvmStatic private external fun history(input: ByteArray): ByteArray?
    @JvmStatic private external fun names(input: ByteArray): ByteArray?
    @JvmStatic private external fun reserved(input: ByteArray): ByteArray?
    @JvmStatic private external fun notesNotice(input: ByteArray): ByteArray?
    @JvmStatic private external fun notesChanged(input: ByteArray): ByteArray?
    @JvmStatic private external fun notesAdded(input: ByteArray): ByteArray?
    @JvmStatic private external fun notesDates(input: ByteArray): ByteArray?
    @JvmStatic private external fun notesShared(input: ByteArray): ByteArray?
    @JvmStatic private external fun notesTake(input: ByteArray): ByteArray?
    @JvmStatic private external fun queueWindow(input: ByteArray): ByteArray?
    @JvmStatic private external fun queueFirst(input: ByteArray): ByteArray?
    @JvmStatic private external fun queueSave(input: ByteArray): ByteArray?
    @JvmStatic private external fun queueLook(input: ByteArray): ByteArray?
    @JvmStatic private external fun queuePlace(input: ByteArray): ByteArray?
    @JvmStatic private external fun deviceName(input: ByteArray): ByteArray?
    @JvmStatic private external fun echo(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusHello(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusMachine(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusCall(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusJournal(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusDiagnostics(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusStandIn(input: ByteArray): ByteArray?
    @JvmStatic private external fun nexusCarriesOver(input: ByteArray): ByteArray?
    @JvmStatic private external fun stop(input: ByteArray): ByteArray?
}
