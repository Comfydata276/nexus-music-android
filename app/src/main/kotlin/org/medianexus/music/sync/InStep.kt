package org.medianexus.music.sync

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.medianexus.music.net.ApiError
import org.medianexus.music.net.StatedPlaylist
import org.medianexus.music.net.Timed

/** What an exchange asks of the server: the account's playlists as the server states them
 *  (the sync playlist among them), and the one write, of the record. */
interface StepServer {
    /** The account's playlists, and the server's clock as it answered. */
    suspend fun playlists(): Timed<List<StatedPlaylist>>

    /** One playlist, and the server's clock as it answered. */
    suspend fun playlist(id: String): Timed<StatedPlaylist>

    /** A new, empty playlist of this name: its id. */
    suspend fun create(name: String): String

    /** Replaces the playlist's comment (and keeps the playlist private). The whole value:
     *  sent again, it comes to the same. */
    suspend fun setComment(id: String, comment: String)
}

/** What an exchange asks of the phone: what it has to share, and to take in what came back. */
interface StepPhone {
    /**
     * What this phone has to share (Windows' `shared_here`), or null while any part of it has
     * not been read from the phone for this account. An exchange with part of the phone's
     * record missing would say "none" for what is only "not read yet", so there is none.
     */
    suspend fun record(): Shared?

    /** Takes in what came back from the server, where it's newer than what's here (Windows'
     *  `take_shared`). Throws `IOException` if the phone couldn't keep it. */
    suspend fun take(theirs: Shared)
}

/**
 * Keeping the phone in step with the person's other devices: the exchange of the shared
 * record with the server, a port of `exchange`, `share_soon` and `share_now` in the Windows
 * app's src/sync.rs. (The play queue goes another way: see [QueueStep].)
 *
 * **One exchange at a time for an account**, in the whole process: an exchange asked for
 * while one is under way waits for nothing and does nothing but note that another is wanted
 * when this one is back (Windows' `sync_again`). What the phone has to share is read as the
 * exchange begins; a change made here while it is out is neither lost nor overwritten by what
 * comes back ([StepPhone.take] compares with what is here then, not with what was sent), and
 * goes out with the next.
 *
 * **What the phone is more careful about than Windows** (its PCs can't defend themselves):
 * it takes no part until everything it shares has been read from the phone
 * ([StepPhone.record]); it leaves alone a comment it can't read ([KotlinSettler]); it uses a
 * playlist only if the server says it is the account's own, the lowest id of them at every
 * exchange; it takes nothing the server left out of an answer for empty; and it makes the
 * playlist, or fills an empty one, only when it has something of the person's to put there.
 */
class InStep(
    private val server: StepServer,
    private val phone: StepPhone,
    /** The account's name on the server: the sync playlist is the one it owns. */
    private val username: String,
    /** The account's key on the phone: what "one at a time" goes by. */
    private val account: String,
    /** Sharing is switched on and the server may be asked now. */
    private val may: () -> Boolean,
    private val scope: CoroutineScope,
    private val settler: Settler = KotlinSettler,
    /** Seconds since 1970. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Something for the Diagnostics page (no names, no addresses, nothing of the record). */
    private val note: (String) -> Unit = {},
) {
    /** How an exchange ended. */
    enum class Outcome {
        /** The record on the server and on the phone are in step. */
        DONE,
        /** Sharing is off, or the server may not be asked: nothing was read or written. */
        NOT_NOW,
        /** Part of what the phone shares hasn't been read from the phone yet: nothing was sent. */
        NOT_READ_YET,
        /** One is already under way; another follows it. */
        BUSY,
        /** The server's record is in a later layout: taken in as far as understood, not written. */
        NEWER_LAYOUT,
        /** The server's record holds, inside a list's choices or a note, something this version
         *  doesn't know and couldn't write back: taken in as far as understood, not written. */
        MORE_THAN_KNOWN,
        /** The comment on the server isn't a record this app can read: left exactly as it is. */
        UNREADABLE,
        /** The server didn't say whose the playlist is, or what its comment is: nothing is
         *  read into that, and nothing written on the strength of it. */
        NOT_STATED,
        /** The server couldn't be asked, or refused: tried again at the next look. */
        FAILED,
    }

    private val again = AtomicBoolean(false)
    private var soon: Job? = null

    /** Something that's shared changed here: pass it on once things have settled, so a burst
     *  of changes goes as one (Windows' `share_soon`). */
    fun shareSoon() {
        if (!may()) return
        synchronized(this) {
            soon?.cancel()
            // The wait can be called off by a newer change; an exchange once begun is not.
            soon = scope.launch {
                pause(SETTLE_MILLIS)
                scope.launch { exchange() }
            }
        }
    }

    /**
     * Reads what's on the server, combines it with what this phone has, writes the result
     * back if that adds anything, and takes it in (Windows' `share_now` and `exchange`).
     */
    suspend fun exchange(): Outcome {
        if (!may()) return Outcome.NOT_NOW
        val turn = turnOf(account)
        if (!turn.tryLock()) {
            // One exchange at a time; go again when this one is back.
            again.set(true)
            return Outcome.BUSY
        }
        try {
            return once()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            return Outcome.FAILED
        } catch (_: java.io.IOException) {
            // The phone couldn't keep what it was given: not taken in, and asked for again.
            note("in step: what other devices shared couldn't be kept on the phone; it will be asked for again")
            return Outcome.FAILED
        } finally {
            turn.unlock()
            if (again.getAndSet(false)) shareSoon()
        }
    }

    private suspend fun once(): Outcome {
        val local = phone.record() ?: return Outcome.NOT_READ_YET
        val now = clock()
        val listed = server.playlists()
        val named = listed.value.filter { it.id.isNotEmpty() && it.name == PLAYLIST_NAME }
        // The lowest id that is the account's own, looked for afresh at every exchange (by
        // bytes, as Windows compares ids). Nothing is remembered between exchanges: a phone
        // that went on using the id it knew, after a lower one appeared (two devices that
        // each made one, having found none), would never meet the devices that use the
        // lower again.
        val id = named.filter(::isOurs).map { it.id }.minWithOrNull { a, b -> SharedText.bytewise(a, b) }
        // What the server did not say is not "empty". With no playlist known to be ours, one
        // of that name whose owner the server left out may be ours or anyone's: nothing is
        // made beside it, and nothing is written into it.
        if (id == null && named.any { it.owner == null }) return notStated("whose the playlist is")
        val read = id?.let { server.playlist(it) }
        if (read != null && !(read.value.id == id && read.value.name == PLAYLIST_NAME && isOurs(read.value))) {
            return if (read.value.owner == null) notStated("whose the playlist is") else Outcome.FAILED
        }
        // A comment that isn't in the answer is not known: read as empty, it would be a
        // record with nothing in it, and the phone's own would be written over everything.
        val comment = if (read == null) "" else read.value.comment ?: return notStated("what the playlist's comment is")
        return when (val settled = settler.settle(comment, SharedText.write(local), now)) {
            Settled.Unreadable -> {
                note("in step: the shared record on the server isn't one this version can read; it was left as it is")
                Outcome.UNREADABLE
            }
            is Settled.ReadOnly -> {
                // Written by a newer version of the app, or holding more than this one could
                // write back: read what's understood, change nothing.
                if (!settled.laterLayout) note("in step: the shared record holds more than this version knows; it is read and not written")
                SharedText.read(settled.text)?.let { phone.take(it) }
                if (settled.laterLayout) Outcome.NEWER_LAYOUT else Outcome.MORE_THAN_KNOWN
            }
            is Settled.Merged -> {
                val merged = SharedText.read(settled.text) ?: return Outcome.FAILED
                // A record is made on the server, or an empty one filled, only when the phone
                // has something of the person's to put in it: a phone with nothing to say
                // writes nothing.
                if (settled.write && (comment.isNotEmpty() || merged.says)) {
                    // (Switched off, or the server gone, while this was being worked out.)
                    if (!may()) return Outcome.NOT_NOW
                    val into = id ?: server.create(PLAYLIST_NAME).ifEmpty { throw ApiError.NotSubsonic("The new playlist came back without an id.") }
                    server.setComment(into, settled.text)
                }
                phone.take(merged)
                Outcome.DONE
            }
        }
    }

    private fun notStated(what: String): Outcome {
        note("in step: the server didn't say $what; nothing was read into that, and nothing written")
        return Outcome.NOT_STATED
    }

    /** Whether a playlist is the account's own: the server must say so. One whose owner it
     *  doesn't state is not taken to be (Windows takes it to be). */
    private fun isOurs(playlist: StatedPlaylist): Boolean = playlist.owner != null && playlist.owner.lowercase() == username.lowercase()

    companion object {
        /** How long after a change before it's passed on, so a burst of changes goes as one. */
        const val SETTLE_MILLIS = 3_000L

        /** How often to look for changes made elsewhere. */
        const val LOOK_MILLIS = 60_000L

        private val turns = HashMap<String, Mutex>()

        /** One exchange at a time for an account, whichever of its objects asks: signing out
         *  and straight back in makes a second set while the first may still be writing. */
        private fun turnOf(account: String): Mutex = synchronized(turns) { turns.getOrPut(account) { Mutex() } }
    }
}
