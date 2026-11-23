package org.medianexus.music.store

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
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

/** How a round of sending held changes went (`Sent` in src/offline.rs). */
data class Sent(
    /** How many the server dealt with. */
    val done: Int,
    /** The ones still to send, in the order they were made. */
    val kept: List<Pending>,
    /** The server stopped answering part-way. */
    val lostTouch: Boolean,
)

/**
 * Sends held changes in order, keeping every one the server hasn't dealt with: a port of
 * Windows' `send_all`, whose tests are this one's.
 *
 * - Taken, or refused for good (the server is there and said no: the song has gone), it is
 *   dealt with and not asked again.
 * - Answered for by something in front of the server (a proxy's error page): kept, and so is
 *   every later change to the same thing, which must wait behind it; the rest go on, unless
 *   such pages are all that come back (three in a row), when the lot is kept.
 * - No server to speak of (no connection; or a sign-in it no longer takes, which refuses
 *   nothing in particular): this one and the rest wait.
 */
suspend fun sendAll(waiting: List<Pending>, send: suspend (Pending) -> Unit): Sent {
    var done = 0
    val kept = ArrayList<Pending>()
    val held = HashSet<String>()
    var strange = 0
    for ((index, change) in waiting.withIndex()) {
        if (change.id in held) {
            kept += change
            continue
        }
        val error = try {
            send(change)
            null
        } catch (e: ApiError) {
            e
        }
        when {
            error == null -> { done++; strange = 0 }
            error is ApiError.Server && !error.signInRefused -> { done++; strange = 0 }
            error is ApiError.NotSubsonic && strange < 2 -> {
                strange++
                held += change.id
                kept += change
            }
            else -> {
                kept += waiting.subList(index, waiting.size)
                return Sent(done, kept, lostTouch = true)
            }
        }
    }
    return Sent(done, kept, lostTouch = false)
}

/**
 * One account's changes waiting for the server: likes, ratings and plays made while it
 * couldn't be reached, in the order they were made, on disk (`held.json` in the account's
 * data folder), so closing the app, or the phone restarting, loses none of them.
 *
 * What a round is sending stays on file until the server has answered for it (as Windows
 * keeps `sending` beside `pending`): the app killed part-way through a round sends that
 * round again, and nothing is lost. The cost is that a change the server took just before
 * the app was killed is sent twice, which for a like is nothing and for a play is one more.
 */
class HeldChanges(private val file: File, private val io: CoroutineDispatcher) {
    private val lock = Any()
    private var pending: MutableList<Pending>? = null
    private var sending: List<Pending> = emptyList()
    private val writes = Mutex()
    private val rounds = Mutex()
    private val count = MutableStateFlow(0)

    /** How many changes are waiting (or being sent), for the screens and Diagnostics. */
    val waiting: StateFlow<Int> = count.asStateFlow()

    /** Reads what was held, the first time it's needed. */
    private fun loaded(): MutableList<Pending> = pending ?: read().toMutableList().also {
        pending = it
        count.value = it.size
    }

    private fun read(): List<Pending> = try {
        if (file.isFile) json.decodeFromString(LIST, file.readText()) else emptyList()
    } catch (_: IOException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        // A file the app can't read is no use to anyone; it is replaced at the next change.
        emptyList()
    }

    /** What is waiting, oldest first: being sent, then the rest. */
    fun all(): List<Pending> = synchronized(lock) { sending + loaded() }

    /** Keeps a change for later. */
    suspend fun hold(change: Pending) {
        synchronized(lock) {
            loaded() += change
            count.value = sending.size + loaded().size
        }
        save()
    }

    /**
     * Sends everything held, one round at a time, in order; what the server hasn't dealt with
     * stays, ahead of anything held meanwhile. Returns the round's outcome, or null if there
     * was nothing to send or a round is already going.
     */
    suspend fun flush(send: suspend (Pending) -> Unit): Sent? {
        if (!rounds.tryLock()) return null
        try {
            val round = synchronized(lock) {
                val now = loaded()
                if (now.isEmpty()) return null
                sending = now.toList()
                now.clear()
                sending
            }
            val sent = try {
                sendAll(round, send)
            } catch (e: Throwable) {
                // Cancelled (the account went): everything of the round is still on file, and
                // put back here in front of what came after.
                synchronized(lock) {
                    loaded().addAll(0, sending)
                    sending = emptyList()
                }
                throw e
            }
            synchronized(lock) {
                loaded().addAll(0, sent.kept)
                sending = emptyList()
                count.value = loaded().size
            }
            save()
            return sent
        } finally {
            rounds.unlock()
        }
    }

    /** Writes what's held (being sent included), newest state only, one write at a time. */
    private suspend fun save() = withContext(io) {
        writes.withLock {
            val now = synchronized(lock) { sending + loaded() }
            try {
                if (now.isEmpty() && !file.exists()) return@withLock
                writeAtomically(file, json.encodeToString(LIST, now).toByteArray())
            } catch (_: IOException) {
                // Tried again at the next change; meanwhile it is held in memory.
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        val LIST = ListSerializer(Pending.serializer())
    }
}
