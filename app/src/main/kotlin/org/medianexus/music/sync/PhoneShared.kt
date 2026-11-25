package org.medianexus.music.sync

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.PriorityQueue
import kotlinx.serialization.json.JsonElement
import org.medianexus.music.core.Core
import org.medianexus.music.core.Device
import org.medianexus.music.core.Plan
import org.medianexus.music.core.Play
import org.medianexus.music.core.PlayFrom
import org.medianexus.music.net.Song
import org.medianexus.music.store.Added
import org.medianexus.music.store.PlayLog
import org.medianexus.music.store.Plays
import org.medianexus.music.store.RecentlyPlayed
import org.medianexus.music.store.SettingsStore

/**
 * The phone's side of an exchange, over its own stores ([StepPhone]): the settings (which
 * hold the shared core's choices for the account), the notes of when songs were added,
 * Recently played, and the log of plays. No rule about what is shared is here: the core has
 * them; this is what is read from the phone's stores, and what is kept in them, at each of
 * the core's steps.
 */
class PhoneShared(
    private val settings: SettingsStore,
    /** The account's key on the phone: the shared choices in the settings must be its own,
     *  and the changes made here wait under it and are only ever sent for it. (It is a hash
     *  of exactly the server's address and the username: two accounts never share one.) */
    private val account: String,
    /** The account's name on the server. */
    private val username: String,
    private val added: Added,
    private val history: RecentlyPlayed,
    private val log: PlayLog,
    /** Recently played and the log of plays have been read from the phone for this account. */
    private val read: () -> Boolean,
    /** The ids of the account's playlists as the phone keeps them; null if no list of them
     *  is kept. */
    private val playlists: suspend () -> Set<String>?,
    /** The songs with these ids, for the ones that can be found. */
    private val songs: suspend (List<String>) -> Map<String, Song>,
    /**
     * Where the record's text is kept as this version first found it, before the first
     * write this version makes: a file in the account's own data, named for the version.
     * (One for each account, then, and for each version that writes.)
     */
    private val keptFirst: File,
    /** The dates on screen may have changed. */
    private val datesChanged: () -> Unit = {},
    private val note: (String) -> Unit = {},
) : StepPhone {
    /** The core's choices as the last exchange left them in the settings: a difference from
     *  this is a change made here, to pass on. */
    @Volatile var settled: JsonElement? = null
        private set

    override suspend fun device(): Device? {
        val now = settings.value
        // Not before the choices in the settings are this account's own, nor before its
        // history and its log have been read from the phone: an empty list that means "not
        // read yet" is never sent as "none". (The notes of when songs were added are read
        // from the phone the first time they are asked for, which is here.)
        if (!Belongs.ready(now, account) || !read()) return null
        // Before the playlists have loaded there's no telling which still exist.
        val known = playlists()?.takeIf { it.isNotEmpty() }
        // (The plays and their sources from one and the same moment of the log.)
        val (plays, sources) = newest(log.state.value)
        return Device(
            username = username, account = account, choices = Chosen.choices(now),
            notes = added.shared(known),
            history = history.state.value.map { Play(it.song.id, it.at) },
            plays = plays, playSources = sources,
        )
    }

    override suspend fun keep(plays: List<Play>, sources: List<PlayFrom>) {
        if (!Belongs.ready(settings.value, account)) return
        // Plays counted on other devices go into this one's log, so the stats cover them all.
        log.absorb(plays.map { it.song to it.at }, sources.map { Triple(it.song, it.at, it.from) })
        // On the disk, with everything else the log holds in memory, or this throws: every
        // time, whether or not anything was new. (Asked only when something was new, a write
        // that failed left the plays in memory; the next exchange then found them "already
        // here", asked nothing, and they were never written.)
        log.flushed()
    }

    override suspend fun take(plan: Plan): Boolean {
        // Only into settings that are still this account's: an exchange that comes back
        // after the account has gone changes nothing. Kept before they show.
        val kept = settings.commit { now ->
            if (!Belongs.ready(now, account)) now else Chosen.with(now, Core.take(Chosen.choices(now), plan.take)).also { settled = it.choices }
        }
        if (!kept) return false
        if (!Belongs.ready(settings.value, account)) return true
        // (Each of these is on the disk when it returns, or throws.)
        if (added.take(plan.notes)) datesChanged()
        // Recently played: what is here now, with what the other devices played.
        history.take(plan.history, songs)
        return true
    }

    override fun keepFirst(found: String): Boolean = try {
        // What a kill left of an earlier try is not the copy, and goes.
        val part = File(keptFirst.path + ".part")
        if (keptFirst.isFile) {
            // The file is its own proof: it only ever comes to its name whole (below), and
            // is never opened for writing.
            part.delete()
            true
        } else {
            keptFirst.parentFile?.mkdirs()
            // Written whole under another name beside its place, and made sure of there...
            FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(found.encodeToByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            // ...and only then given its name, if nothing has it: so whatever is at that
            // name is the whole record as it was found, or is not there. (One exchange at
            // a time for an account, in the whole process: nothing else makes it.)
            if (keptFirst.exists()) throw IOException("Something that is not the copy has its name")
            Files.move(part.toPath(), keptFirst.toPath(), StandardCopyOption.ATOMIC_MOVE)
            keptFirst.parentFile?.let { org.medianexus.music.store.syncDirOrFail(it) }
            true
        }
    } catch (_: IOException) {
        false
    }

    override suspend fun sending(write: JsonElement): Boolean? {
        var fits = false
        val kept = settings.commit { now ->
            fits = false
            if (!Belongs.ready(now, account)) return@commit now
            val out = Core.sending(Chosen.choices(now), write)
            fits = out.fits
            if (out.fits) now.copy(choices = out.choices).also { settled = it.choices } else now
        }
        return if (kept) fits else null
    }

    override suspend fun written(write: JsonElement): Boolean {
        val kept = settings.commit { now ->
            if (!Belongs.ready(now, account)) now else Chosen.with(now, Core.written(Chosen.choices(now), write)).also { settled = it.choices }
        }
        // (Not kept: the changes stay as tried, and the next exchange makes them again over
        // their own result, which alters nothing.)
        if (!kept) note("in step: what was shared couldn't be noted on the phone as shared; it will be offered again")
        return kept
    }

    companion object {
        /** How many of the log's plays are shared (the core's `PLAYS_SHARED`; `CoreNamesTest`
         *  holds this to the core's own). */
        const val PLAYS_SHARED = 500

        /**
         * The plays of the log that could be among its newest [PLAYS_SHARED]: **every play at
         * least as new as the 500th newest**, with where those made from a playlist were made
         * from. Which of them are the newest, each once, is the core's to say ([Core.settle]);
         * this only spares carrying a log of 200,000 plays across to it at every exchange.
         * From one reading of the log (`log` is the log as it stood at one moment).
         */
        fun newest(log: Plays): Pair<List<Play>, List<PlayFrom>> {
            var from = Long.MIN_VALUE
            if (log.size > PLAYS_SHARED) {
                // The 500th greatest time: the least of the 500 greatest.
                val greatest = PriorityQueue<Long>(PLAYS_SHARED + 1)
                for (i in 0 until log.size) {
                    greatest.add(log.at(i))
                    if (greatest.size > PLAYS_SHARED) greatest.poll()
                }
                from = greatest.peek() ?: Long.MIN_VALUE
            }
            val plays = ArrayList<Play>(minOf(log.size, PLAYS_SHARED))
            val sources = ArrayList<PlayFrom>()
            for (i in 0 until log.size) {
                if (log.at(i) < from) continue
                plays += Play(log.song(i), log.at(i))
                log.from(i).let { if (it.isNotEmpty()) sources += PlayFrom(log.song(i), log.at(i), it) }
            }
            return plays to sources
        }
    }
}
