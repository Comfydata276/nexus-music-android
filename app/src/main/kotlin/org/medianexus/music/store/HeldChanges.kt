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
 * src/offline.rs): a like or unlike, a rating, a play and when it happened.
 */
@Serializable
sealed interface Pending {
    /** What the change is about. Changes to the same thing reach the server in order. */
    val id: String

    @Serializable @SerialName("star")
    data class Star(val kind: String, override val id: String, val starred: Boolean) : Pending

    @Serializable @SerialName("rating")
    data class Rating(override val id: String, val rating: Int) : Pending

    /** A song that was played, and when (milliseconds since 1970). */
    @Serializable @SerialName("played")
    data class Played(override val id: String, val at: Long) : Pending
}

/**
 * One account's changes for the server (likes, ratings, plays), and the **one sender** they
 * all go through: written to the phone first (`held.json` in the account's data folder),
 * then sent, in the order they were made.
 *
 * Nothing goes to the server any other way, and so nothing can overtake a change that is
 * still waiting: a like made in offline mode and an unlike made the moment it is switched
 * off reach the server as a like and then an unlike. (They used to be two paths: held
 * changes sent in rounds, and a new change sent straight away if the server seemed to be
 * there. The unlike went straight away, the round delivered the like after it, and the like
 * won.)
 *
 * - **On the disk before it is acknowledged.** [submit] returns only once the change is
 *   written, synced, and its name in the folder synced. If it can't be written it is still
 *   sent if the server is there now; if it can be neither kept nor sent, the caller is told
 *   it didn't save (an error), never that it did.
 * - **Off the disk as soon as its own answer arrives**, not at the end of a round: the
 *   moment the server answers for a change, the list without it is written. The app killed
 *   later in the same pass sends nothing twice.
 * - **Windows' rules** for what an answer means (`send_all` in src/offline.rs, whose tests
 *   are ported with it): taken, or refused for good (the server is there and said no), it is
 *   dealt with; a proxy's error page in the server's place means try again later, and later
 *   changes to the same thing wait behind it while the rest go on (three such pages running:
 *   everything waits); no answer, or a sign-in the server won't take, and everything waits.
 * - **A file that can't be read is set aside** (`held.damaged.json`) and said, as the
 *   settings file is; it is never read as "nothing held" and written over.
 *
 * **Plays, and what can't be promised.** The Subsonic interface has no way to make a
 * submission safe to repeat, so if the app is killed in the instant between the server
 * accepting a play and the phone recording that it did, the play is sent again and counted
 * twice. That instant is one small write, begun the moment the answer arrives and not
 * interruptible by the account going; it is not nothing. Likes and ratings sent twice come
 * to the same thing.
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

    /** A change in the queue. `saved`: it is on the disk. `parked`: it has had to wait
     *  (whoever made it was told it was held). `settled` tells whoever is waiting on it. */
    private class Entry(val change: Pending, val settled: CompletableDeferred<Settled>?) {
        @Volatile var saved = false
        @Volatile var parked = false
    }

    private val lock = Any()
    private var queue: MutableList<Entry>? = null
    private val writes = Mutex()
    private val passes = Mutex()
    private val count = MutableStateFlow(0)
    private val broken = MutableStateFlow(false)
    private var unsavedSaid = false

    /** Changes that had waited and have now been dealt with, not yet counted by [flush]. */
    private val caughtUp = java.util.concurrent.atomic.AtomicInteger()

    /** How many changes are waiting (or being sent), for the screens and Diagnostics. */
    val waiting: StateFlow<Int> = count.asStateFlow()

    /** The file of held changes couldn't be read, and was set aside (see the class). */
    val damaged: StateFlow<Boolean> = broken.asStateFlow()

    /** Reads what was held, the first time it's needed. */
    private fun loaded(): MutableList<Entry> = synchronized(lock) {
        queue ?: read().mapTo(ArrayList()) { Entry(it, null).apply { saved = true; parked = true } }.also {
            queue = it
            count.value = it.size
        }
    }

    private fun read(): List<Pending> {
        if (!file.isFile) return emptyList()
        try {
            return json.decodeFromString(LIST, file.readText())
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        }
        // Not read as "nothing held" and written over: set aside, and said.
        val aside = siblingWithExtension(file, "damaged.json")
        val moved = try {
            java.nio.file.Files.move(file.toPath(), aside.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            false
        }
        note("held changes: the file of them couldn't be read" + if (moved) "; it was set aside, and what it held was not sent" else ", nor set aside")
        broken.value = true
        return emptyList()
    }

    /** What is waiting, oldest first. */
    fun all(): List<Pending> = synchronized(lock) { loaded().map { it.change } }

    /**
     * Makes a change: on the disk first, then to the server in its turn. Returns once it is
     * settled for now: the server has it ([Outcome.TAKEN]), or it is safely held for later
     * ([Outcome.HELD]: offline, the server out of reach, or something earlier in its way).
     *
     * Throws the server's refusal ([ApiError.Server]) if it is there and said no; and an
     * [ApiError.Network] if the change could be neither written to the phone nor sent now,
     * which is the one case in which it is lost, and the caller must say so.
     */
    suspend fun submit(change: Pending): Outcome {
        val entry = Entry(change, CompletableDeferred())
        withContext(io) { loaded() }
        synchronized(lock) {
            loaded() += entry
            count.value = loaded().size
        }
        save()
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
        withContext(io) { loaded() }
        pass()
        return caughtUp.getAndSet(0)
    }

    /**
     * One pass over the queue, one at a time and in order (see the class). Every change left
     * in the queue at its end has had its maker told it is held.
     */
    private suspend fun pass() = passes.withLock {
        var strange = 0
        /** The server stopped answering, or something answered in its place. */
        var lost = false
        val behind = HashSet<String>()
        val tried = HashSet<Entry>()
        try {
            while (!offline()) {
                val entry = synchronized(lock) { loaded().firstOrNull { it !in tried && it.change.id !in behind } } ?: break
                val error = try {
                    send(entry.change)
                    null
                } catch (e: ApiError) {
                    e
                }
                when {
                    error == null || (error is ApiError.Server && !error.signInRefused) -> {
                        // Dealt with: it leaves the queue and the disk now, before anything
                        // else is done, so that a play the server has counted is not there
                        // to be sent again.
                        synchronized(lock) {
                            loaded().remove(entry)
                            count.value = loaded().size
                        }
                        save()
                        strange = 0
                        if (entry.parked) caughtUp.incrementAndGet()
                        entry.settled?.complete(if (error == null) Settled.Taken else Settled.Failed(error))
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
        if (lost) lostTouch()
    }

    /** Tells the makers of everything still queued that it is held; a change that could not
     *  be written to the phone is not held but lost, and its maker is told that instead. */
    private fun park() {
        val lostNow = ArrayList<Entry>()
        synchronized(lock) {
            val each = loaded().iterator()
            while (each.hasNext()) {
                val entry = each.next()
                if (entry.saved) {
                    entry.parked = true
                    entry.settled?.complete(Settled.Held)
                } else if (entry.settled?.isCompleted == false) {
                    each.remove()
                    lostNow += entry
                }
            }
            count.value = loaded().size
        }
        lostNow.forEach { it.settled?.complete(Settled.Failed(ApiError.Network("The change couldn't be kept on the phone to send later."))) }
    }

    /**
     * Writes the queue as it stands, durably, one write at a time; true if it is on the disk.
     * Not interrupted by the account going: a change the server has just answered for must
     * leave the file.
     */
    private suspend fun save(): Boolean = withContext(NonCancellable + io) {
        writes.withLock {
            val now = synchronized(lock) { loaded().toList() }
            try {
                if (now.isNotEmpty() || file.exists()) writeAtomically(file, json.encodeToString(LIST, now.map { it.change }).toByteArray(), durable = true)
                now.forEach { it.saved = true }
                unsavedSaid = false
                true
            } catch (_: IOException) {
                // Said once. What couldn't be written is sent if the server is there now;
                // if not, its maker is told it didn't save (see park).
                if (!unsavedSaid) note("held changes: they couldn't be written to the phone")
                unsavedSaid = true
                false
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        val LIST = ListSerializer(Pending.serializer())
    }
}
