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

/**
 * The shared core: the part of the app that is the Windows app's own code, compiled into a
 * library the app carries (`libnexus_core_android.so`; the Rust side of this file is
 * `native/src/lib.rs`, and docs/DESIGN.md, "The shared core", says how it gets here).
 *
 * **This object is the only thing in the app that knows the library exists.** Everything
 * else calls the functions below, which take and give Kotlin values (`CoreTypes.kt`). Across
 * the boundary each call is one argument and one answer, both JSON as UTF-8 bytes; whatever
 * goes wrong there (an argument the library doesn't take, an answer this side can't read, a
 * panic in the library) is a [CoreException] with the reason in words.
 *
 * The library is loaded at the first call, **never on the main thread** ([warm] loads it as
 * the app starts, off the main thread; a call from the main thread never loads it). It opens
 * no connections, keeps no files, starts no threads and reads no clock: what it is given is
 * all it knows. A call is a few thousandths of a second at most, and most are made off the
 * main thread anyway; the one a screen makes as it draws is [notesDates].
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
    @JvmStatic private external fun stop(input: ByteArray): ByteArray?
}
