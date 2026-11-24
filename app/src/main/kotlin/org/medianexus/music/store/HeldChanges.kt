package org.medianexus.music.store

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.medianexus.music.net.ApiError

/**
 * Something to tell the server once it can be reached, as Windows holds it (`Pending` in
 * src/offline.rs): a like or unlike, a rating, a play and when it happened. `at` is when it
 * was made (milliseconds since 1970; 0 in a file from before it was recorded).
 */
@Serializable
sealed interface Pending {
    /** What the change is about. Changes to the same thing reach the server in order. */
    val id: String

    /** `song`: a liked song as it was shown, so that Liked songs can list it while the like
     *  is held, across a restart too (the server's list doesn't have it until it is sent). */
    @Serializable @SerialName("star")
    data class Star(
        val kind: String,
        override val id: String,
        val starred: Boolean,
        val at: Long = 0,
        val song: org.medianexus.music.net.Song? = null,
    ) : Pending

    @Serializable @SerialName("rating")
    data class Rating(override val id: String, val rating: Int, val at: Long = 0) : Pending

    /** A song that was played, and when. */
    @Serializable @SerialName("played")
    data class Played(override val id: String, val at: Long) : Pending

    /** A change to a playlist (`id` is the playlist's), described by the songs themselves so
     *  that it is carried out on the playlist as the server has it when it is sent (see
     *  `PlaylistEdit`). Changes to one playlist reach the server in the order made. */
    @Serializable @SerialName("playlist")
    data class PlaylistChange(override val id: String, val edit: org.medianexus.music.library.PlaylistEdit, val at: Long = 0) : Pending
}

/**
 * One account's changes for the server (likes, ratings, plays), and the **one sender** they
 * all go through: written to the phone first (`held.json` in the account's data folder),
 * then sent, in the order they were made.
 *
 * Nothing goes to the server any other way, and so nothing can overtake a change that is
 * still waiting: a like made in offline mode and an unlike made the moment it is switched
 * off reach the server as a like and then an unlike.
 *
 * **Two rules, and everything else follows from them.**
 *
 * - **A change is in the queue only once it is on the disk.** [submit] writes the queue with
 *   the change added (written, synced, its name in the folder synced) and only then puts it
 *   in the queue, where a pass can see it, count it and send it. If the phone won't take the
 *   write, the change never joins the queue and its maker is told it didn't save. Nothing is
 *   ever queued, seen or sent that an app killed in that instant would not find again.
 * - **Nothing takes a change out of the queue but its own answer**: the server took it, or
 *   refused it for good. A pass that ends, for whatever reason, tells the makers of what is
 *   left that it is held, and removes nothing.
 *
 * (It used to be in the queue from the moment it was made, with its write following, and a
 * pass that ended dropped whatever it took to be unwritable. Two rounds of fixes taught the
 * pass to tell "not written yet" from "couldn't be written"; this takes the question away.)
 *
 * - **Off the disk as soon as its own answer arrives**, and **durably gone before the next
 *   is sent**: the moment the server answers for a change, the list without it is written,
 *   and only then is the next sent. If that write fails, sending stops there (said once in
 *   Diagnostics) and is tried again later: otherwise an older like could still be on the disk
 *   after a newer unlike had reached the server, and be sent again, after it, at the next
 *   start.
 * - **What an answer means.** Taken: gone. Refused for good, which is only an error that
 *   says something definite about that change (the thing isn't there: 70; the account may
 *   not do it: 50): gone, and its maker told. **Any other error from the server says nothing
 *   about the change**: it waits, later changes to the same thing wait behind it, the rest go
 *   on, and it is tried again later, **for as long as it takes**: a like or a rating waits
 *   as a play does (it used to be given up a day after it was made, with nobody told). A
 *   proxy's error page in the server's place: the same wait (three such pages running:
 *   everything waits). No answer, or a sign-in the server won't take: everything waits. (Windows' `send_all` takes every error
 *   of the server's as final; its tests are ported with that one difference.)
 * - **A file that can't be read is set aside** (`held.damaged.json`) and said, as the
 *   settings file is; it is never read as "nothing held" and written over. One that can't be
 *   set aside either stays as it is, and until it can be moved nothing can be written, so
 *   nothing is taken on.
 * - **One sender per account for the whole process.** Everything here but the way to the
 *   server belongs to the *file* ([Shared]), not to this object: signing out and straight
 *   back in to the same account makes a second `HeldChanges` while the first may still be
 *   part-way through a pass, and the two are one queue, one writer and one pass at a time.
 *
 * **Plays, and what can't be promised.** The Subsonic interface has no way to make a
 * submission safe to repeat, so if the app is killed in the instant between the server
 * accepting a play and the phone recording that it did, the play is sent again and counted
 * twice. That instant is one small write, begun the moment the answer arrives and not
 * interruptible by the account going; it is not nothing. Likes and ratings sent twice come
 * to the same thing. **A play the phone won't write is lost**, and that is written in
 * Diagnostics (nobody is watching a play to be told).
 */
class HeldChanges(
    private val file: File,
    private val io: CoroutineDispatcher,
    /** Sends one change to the server; throws the request's [ApiError] if it didn't go. */
    private val send: suspend (Pending) -> Unit = {},
    /** Nothing is to be asked of the server now (offline mode, or it is out of reach). */
    private val offline: () -> Boolean = { false },
    /** The server stopped answering part-way, or something answered in its place. */
    private val lostTouch: () -> Unit = {},
    private val note: (String) -> Unit = {},
    /** Something is waiting that only another try can move (the phone wouldn't take a write
     *  the sender depends on; the server answered with an error that says nothing): worth a
     *  pass in a while. */
    private val tryLater: () -> Unit = {},
    /** A change that had been held has had its answer: the server took it (`refusal` null), or
     *  will never have it, and said why. Told before the change leaves [queued]. */
    private val answered: (change: Pending, refusal: ApiError?) -> Unit = { _, _ -> },
    /** [queued] is not what it was, without its maker's doing: what an earlier run left has
     *  been read, or a change that had been held has gone. */
    private val moved: () -> Unit = {},
) {
    /** How a change stood when [submit] returned. */
    enum class Outcome {
        /** The server has it. */
        TAKEN,

        /** It is on the phone, and goes when the server can next be asked. */
        HELD,
    }

    private sealed interface Settled {
        data object Taken : Settled
        data object Held : Settled
        data class Failed(val error: ApiError) : Settled
    }

    /** A change in the queue, which is to say on the disk. `parked`: it has had to wait
     *  (whoever made it was told it was held). `settled` tells whoever is waiting on it. */
    private class Entry(val change: Pending, val settled: CompletableDeferred<Settled>?) {
        @Volatile var parked = false
    }

    /**
     * Everything about one file of held changes, for the whole process: its queue, its one
     * writer and its one pass at a time. Every `HeldChanges` made for that file shares it.
     */
    private class Shared {
        val lock = Any()
        var queue: MutableList<Entry>? = null
        val writes = Mutex()
        val passes = Mutex()
        val count = MutableStateFlow(0)
        val queued = MutableStateFlow<List<Pending>>(emptyList())
        val broken = MutableStateFlow(false)
        var unsavedSaid = false
        var unsureSaid = false

        /** The file on the disk may still hold changes the server has answered for (the
         *  write that takes them off has not succeeded): nothing more is sent until it has. */
        @Volatile var dirty = false
        var dirtySaid = false

        /** The file couldn't be read, and couldn't be moved out of the way: it must not be
         *  written over. */
        @Volatile var inTheWay = false

        /** Changes that had waited and have now been dealt with, not yet counted by [flush]. */
        val caughtUp = java.util.concurrent.atomic.AtomicInteger()

        /** How many changes that had waited the server has **accepted**, in all. */
        val accepted = MutableStateFlow(0L)
    }

    private val shared: Shared = synchronized(all) { all.getOrPut(file.absolutePath) { Shared() } }
    private val lock get() = shared.lock

    /** How many changes are waiting (or being sent), for the screens and Diagnostics. */
    val waiting: StateFlow<Int> = shared.count.asStateFlow()

    /** What is waiting (or being sent), oldest first, once the file has been read: what is
     *  liked is shown with these on top of what the server last said (see `Likes`). */
    val queued: StateFlow<List<Pending>> = shared.queued.asStateFlow()

    /**
     * How many changes that had been held the server has since **accepted**, in all, for as
     * long as the process has run: only ever goes up. What is said about held changes having
     * been sent is said from this (each sayer keeps its own place in the count), so that it
     * counts what the server took, not what it turned down for good, and counts it once,
     * whichever of the passes sent it. ([flush]'s own number is taken by whoever asks first.)
     */
    val accepted: StateFlow<Long> = shared.accepted.asStateFlow()

    /** The file of held changes couldn't be read, and was set aside (see the class). */
    val damaged: StateFlow<Boolean> = shared.broken.asStateFlow()

    /** Reads what was held, the first time it's needed. */
    private fun loaded(): MutableList<Entry> = synchronized(lock) {
        shared.queue ?: read().mapTo(ArrayList()) { Entry(it, null).apply { parked = true } }.also {
            shared.queue = it
            changed()
        }
    }

    /** The queue has gained or lost a change (under the lock). */
    private fun changed() {
        val queue = shared.queue ?: return
        // What is held first, then how many: whoever waits for the count to change finds the
        // list already as it says (what is shown is worked out from the list).
        shared.queued.value = queue.map { it.change }
        shared.count.value = queue.size
    }

    private fun read(): List<Pending> {
        if (!file.isFile) return emptyList()
        try {
            return json.decodeFromString(LIST, file.readText())
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        }
        // Not read as "nothing held" and written over: set aside, and said.
        val moved = setAside()
        note("held changes: the file of them couldn't be read" + if (moved) "; it was set aside, and what it held was not sent" else ", nor set aside: it is left as it is, and nothing is written over it")
        shared.inTheWay = !moved
        shared.broken.value = true
        return emptyList()
    }

    /** Moves the file out of the way (`held.damaged.json`); false if it couldn't be. */
    private fun setAside(): Boolean = try {
        java.nio.file.Files.move(file.toPath(), siblingWithExtension(file, "damaged.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (_: IOException) {
        false
    }

    /** What is waiting, oldest first. */
    fun all(): List<Pending> = synchronized(lock) { loaded().map { it.change } }

    /** Reads what an earlier run left held, so that it is counted and shown before anything
     *  asks for a pass. */
    suspend fun open() {
        withContext(io) { loaded() }
        moved()
    }

    /**
     * Makes a change: on the disk first, then to the server in its turn (`written` is told the
     * moment it is on the disk and in the queue, before it is sent). Returns once it is
     * settled for now: the server has it ([Outcome.TAKEN]), or it is safely held for later
     * ([Outcome.HELD]: offline, the server out of reach, or something earlier in its way).
     *
     * Throws the server's refusal ([ApiError.Server]) if it is there and said no for good;
     * and an [ApiError.Network] if the phone would not take the change, which is the one
     * case in which it is lost, and the caller must say so.
     */
    suspend fun submit(change: Pending, written: () -> Unit = {}): Outcome {
        val entry = Entry(change, CompletableDeferred())
        if (!write(entry)) {
            // Nobody is watching a play to be told it didn't save: it is written down instead.
            if (change is Pending.Played) note("held changes: a play could not be kept on the phone, and is lost")
            throw ApiError.Network("The change couldn't be kept on the phone to send later.")
        }
        // In the queue, in its place: whoever makes changes that must keep their order can
        // let the next one go.
        written()
        pass()
        return when (val settled = entry.settled!!.await()) {
            Settled.Taken -> Outcome.TAKEN
            Settled.Held -> Outcome.HELD
            is Settled.Failed -> throw settled.error
        }
    }

    /**
     * Sends whatever is waiting, if the server may be asked. Returns how many changes that
     * had been held have been dealt with since this was last asked (taken, or refused for
     * good), whichever pass sent them.
     */
    suspend fun flush(): Int {
        open()
        pass()
        return shared.caughtUp.getAndSet(0)
    }

    /**
     * One pass over the queue, one at a time and in order (see the class). Every change left
     * in the queue at its end has had its maker told it is held.
     */
    private suspend fun pass() = shared.passes.withLock {
        var strange = 0
        /** The server stopped answering, or something answered in its place. */
        var lost = false
        /** The phone wouldn't take the write that takes a sent change off the disk. */
        var stuck = false
        /** The server answered for something with an error that says nothing about it. */
        var unsure = false
        val behind = HashSet<String>()
        val tried = HashSet<Entry>()
        try {
            while (!offline()) {
                // Something the server has answered for may still be on the disk: off it
                // first. Nothing is sent after it until it is durably gone (see the class).
                if (shared.dirty && !write()) {
                    stuck = true
                    break
                }
                val entry = synchronized(lock) { loaded().firstOrNull { it !in tried && it.change.id !in behind } } ?: break
                val error = try {
                    send(entry.change)
                    null
                } catch (e: ApiError) {
                    e
                }
                when {
                    error == null || (error is ApiError.Server && error.code in FINAL) -> {
                        // Dealt with: it leaves the queue and the disk now, before anything
                        // else is done, so that a play the server has counted is not there
                        // to be sent again. (Whether it has left the disk is asked at the
                        // top of the loop, before the next is sent.)
                        if (entry.parked) answered(entry.change, error)
                        synchronized(lock) {
                            loaded().remove(entry)
                            changed()
                            shared.dirty = true
                        }
                        write()
                        strange = 0
                        if (entry.parked) {
                            moved()
                            shared.caughtUp.incrementAndGet()
                            if (error == null) shared.accepted.value++
                        }
                        entry.settled?.complete(if (error == null) Settled.Taken else Settled.Failed(error))
                    }
                    error is ApiError.Server && !error.signInRefused -> {
                        // The server is there, and its error says nothing about this change
                        // (a fault of its own, a kind of error this app doesn't know): it
                        // waits, and so does every later change to the same thing.
                        unsure = true
                        behind += entry.change.id
                        tried += entry
                    }
                    error is ApiError.NotSubsonic && strange < 2 -> {
                        // Something answered in the server's place: this one waits, and so
                        // does every later change to the same thing.
                        strange++
                        lost = true
                        behind += entry.change.id
                        tried += entry
                    }
                    else -> {
                        lost = true
                        break
                    }
                }
            }
        } finally {
            // Whatever is left waits; whoever is waiting on any of it is told so. (Also when
            // the pass is cut short because the account has gone: it is all still on file.)
            park()
        }
        if (stuck) {
            if (!shared.dirtySaid) note("held changes: one the server has taken could not be taken off the phone; nothing more is sent until it can be")
            shared.dirtySaid = true
        }
        if (unsure) {
            if (!shared.unsureSaid) note("held changes: the server answered one with an error that says nothing about it; it is kept, and tried again later")
            shared.unsureSaid = true
        }
        if (stuck || unsure) tryLater()
        if (lost) lostTouch()
    }

    /** Tells the makers of everything still queued that it is held. Nothing is removed. */
    private fun park() = synchronized(lock) {
        for (entry in loaded()) {
            entry.parked = true
            entry.settled?.complete(Settled.Held)
        }
    }

    /**
     * Writes the queue as it stands, with `adding` at its end, durably, one write at a time;
     * true if it is on the disk, and only then is `adding` in the queue. Not interrupted by
     * the account going: a change the server has just answered for must leave the file.
     */
    private suspend fun write(adding: Entry? = null): Boolean = withContext(NonCancellable + io) {
        shared.writes.withLock {
            val now = synchronized(lock) { loaded().toList() } + listOfNotNull(adding)
            try {
                // A file that couldn't be read is still where this one goes: moved out of
                // the way first, and if it can't be, nothing is written over it.
                if (shared.inTheWay) {
                    if (!setAside()) throw IOException("The damaged file is in the way")
                    shared.inTheWay = false
                }
                if (now.isNotEmpty() || file.exists()) writeAtomically(file, json.encodeToString(LIST, now.map { it.change }).toByteArray(), durable = true)
                synchronized(lock) {
                    // On the disk: now, and not before, it is in the queue. (Only this
                    // writer adds, so nothing has come between.)
                    if (adding != null) {
                        loaded() += adding
                        changed()
                    }
                    // What was written is the queue as it stood when this began: anything
                    // dealt with since is still to come off.
                    if (loaded().toList() == now) shared.dirty = false
                }
                if (!shared.dirty) shared.dirtySaid = false
                shared.unsavedSaid = false
                true
            } catch (_: IOException) {
                // Said once.
                if (!shared.unsavedSaid) note("held changes: they couldn't be written to the phone")
                shared.unsavedSaid = true
                false
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        private val LIST = ListSerializer(Pending.serializer())

        /** The server's errors that say something definite about one change: the account may
         *  not do it (50), the thing isn't there (70). */
        private val FINAL = setOf(50L, 70L)

        /** Each file's one sender, for as long as the process lives. */
        private val all = HashMap<String, Shared>()

        /** As if the process had been started again: what was known of `file` in memory is
         *  forgotten, and the next `HeldChanges` for it reads the disk. For tests. */
        internal fun restarted(file: File) {
            synchronized(all) { all.remove(file.absolutePath) }
        }
    }
}
