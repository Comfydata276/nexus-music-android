package org.medianexus.music.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.medianexus.music.net.Song
import org.medianexus.music.playback.NATIVE_SUFFIXES
import org.medianexus.music.store.syncDirOrFail
import org.medianexus.music.store.writeAtomically

/** The form a song is downloaded in: the bit rate asked of the server (0 for the file as it
 *  is) and the kind of file that is saved. */
data class SavedForm(val bitrate: Int, val suffix: String) {
    val converted: Boolean get() = bitrate > 0
}

/**
 * The form a download is saved in, as Windows decides it (`fetch` in src/downloads.rs): the
 * file as it is when the original is asked for (`quality` 0) and the phone plays it, or when
 * it is already an MP3 within the limit; otherwise the server converts it to MP3 at the limit
 * (320 kbps for the original quality of a file the phone can't play).
 */
fun savedForm(song: Song, quality: Int): SavedForm {
    val suffix = song.suffix.lowercase()
    val playable = suffix in NATIVE_SUFFIXES
    val original = playable && (quality == 0 || (suffix == "mp3" && song.bitRate in 1..quality))
    return if (original) SavedForm(0, suffix) else SavedForm(if (quality == 0) 320 else quality, "mp3")
}

/** How big a download is expected to be, for the free-space check (Windows'
 *  `expected_bytes`): the server's size for the file as it is, or what the song's length
 *  comes to at the rate it is converted to. */
fun expectedBytes(song: Song, quality: Int): Long {
    val form = savedForm(song, quality)
    return if (!form.converted) song.size.coerceAtLeast(0) else song.duration.toLong() * form.bitrate * 125
}

/**
 * Why a download failed, in words a person is shown (on the Downloads list, never in a log
 * or a notification). `network`: the server couldn't be reached, which is worth trying
 * again by itself when it is back. `again`: what arrived was not the song, whole (it failed
 * a check): worth fetching afresh a few times, as the server's next answer may be right.
 *
 * It never carries the exception it was made from: Media3 writes a download's final failure
 * to the system log, and that exception's own words can name the server.
 */
class DownloadFailure(
    val reason: String,
    val network: Boolean = false,
    val again: Boolean = false,
    /** The file arrived whole and the phone has no decoder for what is in it: worth asking
     *  the server to convert it, and nothing else. */
    val undecodable: Boolean = false,
    /** What arrived came to a clean end, is a song the player reads, and has this wrong
     *  with it: if the next fetch brings the very same bytes, the flaw is the server's own
     *  copy's (see [SongFetch]). */
    val flawed: Flawed? = null,
) : IOException(reason)

/** What is wrong with a file that was to be a download (see [SongFetch.faultOf]). */
enum class Fault { NOT_A_SONG, CUT_SHORT, DAMAGED, WRONG_LENGTH, NO_DECODER }

/** A file that arrived whole and failed its reading-through: its length, its bytes' digest
 *  (SHA-256) and what was wrong. Two that are equal are the same bytes failing the same way. */
data class Flawed(val bytes: Long, val digest: String, val fault: Fault)

/** The network a fetch was going out on may no longer be used for it (with Wi-Fi only on:
 *  it has gone, or turned metered). Not a failure of the song's. */
class RouteGone : IOException("The network this was on may no longer be used")

/** The fetch was called off ([SongFetch.cancel]): not a failure, and only ever this. (A read
 *  that timed out is an `InterruptedIOException` too, and is a failure.) */
class Cancelled : InterruptedIOException("Cancelled")

/**
 * What identifies the copy of a song a part file is part of: the server's validator for it,
 * how long the whole file is, and how much of the part is known to have reached the disk.
 * Kept in a file of its own beside the part (".<name>.part.meta"), written before the part's
 * first byte.
 *
 * **The validator must be a strong one**, or two versions of a file could be joined. An
 * entity tag that isn't marked weak is strong. A modification date is strong only when it is
 * safely older than the date of the answer it came in (`date`): a file changed twice within
 * the same second has one `Last-Modified`, and a server answering in that second can't tell
 * the two apart (HTTP's rule for `If-Range`, RFC 9110 §8.8.2.2 and §13.1.5; the sixty
 * seconds are RFC 7232's, the more careful of the two). A date that close, or an answer with
 * no date of its own to hold it against, vouches for nothing: the song is fetched from its
 * start instead of carried on with.
 */
@Serializable
internal data class PartOf(
    val etag: String = "",
    val modified: String = "",
    val total: Long = 0,
    val synced: Long = 0,
    /** The `Date` of the answer the part began with (what `modified` is held against). */
    val date: String = "",
) {
    /** What a resume is made conditional on (`If-Range`); empty if nothing strong enough. */
    val validator: String get() = etag.ifEmpty { modified.takeIf { safelyOlder(it, date) }.orEmpty() }

    companion object {
        /** How much older than the answer's own date a modification date must be to count. */
        const val SAFELY_OLDER_SECONDS = 60L

        /** Whether the modification date `modified` is at least [SAFELY_OLDER_SECONDS]
         *  before `date`, both read as HTTP dates. Not if either can't be read. */
        fun safelyOlder(modified: String, date: String): Boolean {
            val changed = httpDate(modified) ?: return false
            val answered = httpDate(date) ?: return false
            return answered - changed >= SAFELY_OLDER_SECONDS
        }

        /** An HTTP date ("Mon, 23 Nov 2026 07:57:08 GMT") as seconds since 1970; null if it
         *  isn't one in the form servers are required to send. */
        private fun httpDate(text: String): Long? = try {
            java.time.ZonedDateTime.parse(text.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}

/**
 * Fetches one song into the downloads folder: the right song, whole, or not at all.
 *
 * It arrives in a file of its own beside where it will go (".<id>.<kind>.part"). Only once it
 * has come to a clean end **and been verified** is it put in its place and entered in the
 * [Catalogue], which is what makes it a download. What verified means depends on whether
 * anything says how long the file should be:
 *
 * - **The file as it is, the length the server stated for that answer, and the size the
 *   library gives the song: it is the server's file, complete.** (If the library's size
 *   disagrees the server is asked for the song again, once, and its newer word gone by.) It
 *   is not read through, timed, or checksummed: that cost twelve seconds for a two-hour
 *   FLAC, would have read 78 GB for a first download of 3,000 lossless songs, and refused
 *   good files (a large tag after the sound, a file in another container than its name
 *   says, which the player reads by what it is). Two things a length can't say are still
 *   asked, of its beginning ([AudioCheck.begins]): that the player's reader recognises it
 *   and finds sound in it, and that the phone can decode that sound; if it can't, the
 *   server's conversion is downloaded in its place ([DownloadFailure.undecodable]).
 * - **A conversion (which comes with no length), or a file the library gives no size for:
 *   nothing else says whether it is whole, so it is read through to its end**
 *   ([AudioCheck.inspect]): every sample, with no error and no end part-way through
 *   anything; a last frame that is whole; a running time that is not nothing, that agrees
 *   with what the container itself states, and that is the song's as the library gives it,
 *   within a small tolerance (the server's newer word, if the first disagrees).
 * - **The server's own copy may have the flaw.** When a file that is a song fails that
 *   reading-through, and a second complete fetch brings exactly the same bytes (the same
 *   length and digest) failing the same way, fetching again will bring nothing else: it is
 *   kept, marked downloaded, and the entry says what is wrong with it ([Downloaded.flaw]),
 *   which Diagnostics notes. One that fails differently each time is refused.
 *
 * **Picking up where it stopped.** The file as it is can be carried on with after the app
 * stopped or the network went, but only as part of the *same* file: the part's own record
 * ([PartOf]) says which copy of the server's it is part of, and the rest is asked for on
 * condition that the server's copy is still that one (`If-Range`), and taken only if the
 * answer is exactly the rest of it (the range from where the part stops to the end, of a file
 * of the same length, with the same validator). If the server's copy has changed, or the
 * server can't say (no validator, or none strong enough: see [PartOf]), or answers anything
 * that doesn't fit ("no such range" included), the part is thrown away and the song fetched
 * from nothing. A part is never made
 * a download on the strength of its size. A conversion is a fresh stream each time, which
 * nothing can join onto: it always starts again.
 *
 * Only what is known to have reached the disk is carried on from: the part is synced every
 * megabyte and its record says how far, so after the power went a resume starts from
 * there, never from bytes the disk may not hold.
 *
 * Plain Kotlin and OkHttp but for the audio check, so it can be tested against a stand-in
 * server. Blocking: run it on a thread of its own (Media3's download manager gives each
 * download one).
 */
class SongFetch(
    private val http: OkHttpClient,
    private val catalogue: Catalogue,
    private val check: AudioCheck,
    /** Asks the server for the song as its library has it now; null if it has none such.
     *  Throws an IOException if the server can't be asked. */
    private val refresh: (id: String) -> Song? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Whether the way this fetch goes out may still be used (asked as bytes arrive): once
     *  not, it stops with a [RouteGone], keeping what has arrived. */
    private val mayRun: () -> Boolean = { true },
    /** Whether the phone still has room for more (asked before it starts and as bytes
     *  arrive): once not, it stops with the plain reason, and what had arrived of it goes. */
    private val room: () -> Boolean = { true },
) {
    /** The network this fetch is bound to, if it is bound to one (its caller's note). */
    @Volatile var bound: Any? = null

    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false

    /** Stops the fetch from another thread: what has arrived stays for next time. */
    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    /**
     * Fetches `song` in `form` from `url` (the server's address for that form, from the
     * start). `progress` hears how far it has got (bytes, and the bytes expected; -1 if not
     * known). Returns the catalogue's new entry; throws [DownloadFailure], or a
     * [Cancelled] if called off.
     */
    @Throws(IOException::class)
    fun fetch(song: Song, form: SavedForm, url: String, before: Flawed? = null, progress: (Long, Long) -> Unit = { _, _ -> }): Downloaded {
        // (Unless what is there is to be replaced: see `Downloaded.stale`.)
        val standing = catalogue.entry(song.id)
        if (standing != null && !standing.stale && catalogue.wholeFileOf(song.id) != null) return standing
        val stem = stem(song.id)
        if (stem.isEmpty()) throw DownloadFailure("The server gave this song an id the app can't use.")
        val name = "$stem.${form.suffix.ifEmpty { "bin" }}"
        val target = File(catalogue.dir, name)
        val part = File(catalogue.dir, partName(name))
        val meta = File(catalogue.dir, metaName(name))
        // A part of a different kind is no use to this fetch.
        catalogue.dir.listFiles()?.forEach { if (it.name != part.name && it.name != meta.name && stemOfPart(it.name) == stem) it.delete() }
        // A conversion can't be picked up part-way.
        if (form.converted) discard(part, meta)
        // A file left at the final name without an entry (the app stopped between the two):
        // not trusted, fetched again. (One an entry names is a copy waiting to be replaced:
        // it stays until the new one is verified and takes its place.)
        if (standing?.file != name) target.delete()

        var from = resumable(part, meta)
        if (from == null) discard(part, meta)
        // Nothing is begun that the phone has no room for (the check made when it was
        // asked for was an estimate, made a while ago).
        if (!room()) {
            discard(part, meta)
            throw DownloadFailure(NO_ROOM)
        }
        // Twice at most: carrying on, and (if what came back doesn't fit) once from nothing.
        while (true) {
            val arrived = try {
                attempt(song, form, url, part, meta, from, progress)
            } catch (e: IOException) {
                throw failure(e)
            }
            if (arrived != null) return finish(song, form, part, meta, target, name, arrived, before)
            if (from == null) throw DownloadFailure("The server didn't send the whole song.", again = true)
            CrashPoints.say { "${song.id}: what the server sent doesn't fit its part: the part is thrown away, and it is fetched from nothing" }
            discard(part, meta)
            from = null
        }
    }

    /** What arrived: how many bytes the part now holds, and how long the server said the
     *  whole answer was (-1: it didn't say, as for a conversion). */
    private class Arrived(val bytes: Long, val said: Long)

    /** Where a fetch can carry on from: the part's record, and how much of the part to keep. */
    private class From(val of: PartOf, val have: Long)

    /** The part and its record, if together they can be carried on from. */
    private fun resumable(part: File, meta: File): From? {
        if (!part.isFile || !meta.isFile) return null
        val of = try {
            json.decodeFromString(PartOf.serializer(), meta.readText())
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Only what is known to be on the disk, of a copy the server can be asked about.
        val have = minOf(part.length(), of.synced)
        if (of.validator.isEmpty() || of.total <= 0 || have <= 0 || have >= of.total) return null
        return From(of, have)
    }

    private fun discard(part: File, meta: File) {
        part.delete()
        meta.delete()
    }

    /** Writes a part's record, **and its name in the folder**: a record that a power cut
     *  could swap for an older one (or for none) is no checkpoint. Throws if either can't be
     *  put on the disk. */
    private fun save(meta: File, of: PartOf) = writeAtomically(meta, json.encodeToString(PartOf.serializer(), of).toByteArray(), durable = true)

    /**
     * One request and what comes of it. Null: the answer didn't fit the part (the server's
     * copy has changed, or it has no such range): the part must go and the song be asked for
     * from nothing.
     */
    private fun attempt(song: Song, form: SavedForm, url: String, part: File, meta: File, from: From?, progress: (Long, Long) -> Unit): Arrived? {
        val request = Request.Builder().url(url)
            // The length a server states must be the length of what it sends: not a compressed
            // copy's, which OkHttp would ask for and unpack out of sight.
            .header("Accept-Encoding", "identity")
            .apply {
                if (from != null) {
                    header("Range", "bytes=${from.have}-")
                    // Only if its copy is still the one the part is part of.
                    header("If-Range", from.of.validator)
                }
            }.build()
        open(request).use { response ->
            if (response.code == 416 && from != null) return null
            if (!response.isSuccessful) {
                throw DownloadFailure(
                    if (response.code in 400..499) "The server refused it (HTTP ${response.code})." else "The server couldn't send it (HTTP ${response.code}).",
                    network = response.code >= 500,
                )
            }
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (type.startsWith("text/") || "json" in type || "xml" in type) {
                // An error page, or the server's own error in place of the song.
                throw DownloadFailure("The server didn't send the song.")
            }
            val length = response.body.contentLength()
            val said: Long
            /** The part's record as it stands; null when it can't be carried on from. */
            var record: PartOf?
            var arrived: Long
            when {
                response.code == 206 -> {
                    // Part of the file. Taken only if it was asked for, and is exactly the
                    // rest of the copy the part is part of.
                    if (from == null || !fits(response, from, length)) return null
                    CrashPoints.say { "${song.id}: carried on from byte ${from.have} of ${from.of.total} (the server's copy is the same one)" }
                    said = from.of.total
                    record = from.of
                    arrived = from.have
                }
                response.code == 200 -> {
                    // The whole of it, from its start (asked for so; or the server's copy has
                    // changed, or it takes no ranges): whatever was here goes.
                    said = length
                    arrived = 0
                    if (from != null) CrashPoints.say { "${song.id}: asked to carry on from byte ${from.have}, and sent the whole file: its part is thrown away" }
                    discard(part, meta)
                    val of = PartOf(strongTag(response), response.header("Last-Modified").orEmpty().trim(), length, 0, response.header("Date").orEmpty().trim())
                    // Its record first, on the disk, and only then its first byte. (Nothing
                    // to carry on with later if the server gave no validator or no length.)
                    record = of.takeIf { !form.converted && it.validator.isNotEmpty() && length > 0 }
                    record?.let {
                        try {
                            save(meta, it)
                        } catch (_: IOException) {
                            // The record may not be on the disk: then nothing vouches for
                            // this part, and it is not one to carry on from later. The song
                            // is still fetched, whole, now.
                            meta.delete()
                            record = null
                        }
                    }
                }
                // Any other "success" is not a song.
                else -> throw DownloadFailure("The server didn't send the song.")
            }
            val expected = if (said > 0) said else expectedBytes(song, form.bitrate.takeIf { form.converted } ?: 0).takeIf { it > 0 } ?: -1
            java.io.RandomAccessFile(part, "rw").use { out ->
                // Carried on from what the disk is known to hold, and no further.
                out.setLength(arrived)
                out.seek(arrived)
                var synced = arrived
                /** This much is on the disk now, and the record says so. */
                fun mark() {
                    val of = record ?: return
                    if (arrived == synced) return
                    out.fd.sync()
                    synced = arrived
                    try {
                        record = of.copy(synced = synced).also { save(meta, it) }
                    } catch (_: IOException) {
                        // This checkpoint didn't reach the disk for certain; whichever
                        // record is there (this one or the one before) says no more than
                        // the part, just synced, really holds. Carried on with.
                    }
                }
                try {
                    val source = response.body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    var told = 0L
                    while (true) {
                        if (cancelled) throw Cancelled()
                        // The network this is on has gone, or may no longer be used:
                        // nothing more is taken from it.
                        if (!mayRun()) throw RouteGone()
                        val got = source.read(buffer)
                        if (got < 0) break
                        out.write(buffer, 0, got)
                        arrived += got
                        if (arrived - told >= TELL_EVERY) {
                            told = arrived
                            progress(arrived, expected)
                            // Nobody else watches the room left while bytes arrive: stopped
                            // here, cleanly, before the phone is full.
                            if (!room()) throw OutOfRoom()
                        }
                        if (arrived - synced >= SYNC_EVERY) mark()
                    }
                    CrashPoints.reached("last-write")
                    out.fd.sync()
                } catch (e: OutOfRoom) {
                    // What had arrived is given back: the phone needs the room more.
                    runCatching { out.close() }
                    discard(part, meta)
                    throw DownloadFailure(NO_ROOM)
                } catch (e: IOException) {
                    // Stopped, or the connection went: what did arrive is kept to carry on
                    // from, once it is on the disk and the record says how much.
                    runCatching { mark() }
                    throw e
                }
            }
            progress(arrived, expected)
            return Arrived(arrived, said)
        }
    }

    /** Whether a `206` is exactly the rest of the copy the part is part of. */
    private fun fits(response: okhttp3.Response, from: From, length: Long): Boolean {
        // "bytes 1000-417072/417073"
        val range = response.header("Content-Range")?.trim()?.lowercase()?.removePrefix("bytes")?.trim() ?: return false
        val first = range.substringBefore('-').trim().toLongOrNull() ?: return false
        val last = range.substringAfter('-').substringBefore('/').trim().toLongOrNull() ?: return false
        val total = range.substringAfter('/', "").trim().toLongOrNull() ?: return false
        if (first != from.have || total != from.of.total || last != total - 1) return false
        if (length >= 0 && length != total - first) return false
        // And the server says, in this answer, that its copy is the one the part came from.
        // (One that ignores the condition would send the rest of whatever it has now.)
        return if (from.of.etag.isNotEmpty()) strongTag(response) == from.of.etag else response.header("Last-Modified").orEmpty().trim() == from.of.modified
    }

    /** The answer's `ETag` if it is a strong one (a weak one, `W/"…"`, can't vouch for
     *  bytes). */
    private fun strongTag(response: okhttp3.Response): String =
        response.header("ETag").orEmpty().trim().takeIf { it.startsWith("\"") && it.endsWith("\"") && it.length > 2 }.orEmpty()

    /** Verifies what arrived, and if it is the song, whole, puts it in place and enters it. */
    private fun finish(song: Song, form: SavedForm, part: File, meta: File, target: File, name: String, arrived: Arrived, before: Flawed?): Downloaded {
        if (cancelled) throw Cancelled()
        CrashPoints.reached("arrived")
        var flaw = ""
        val verified = try {
            verify(song, form, part, arrived)
        } catch (e: DownloadFailure) {
            val fault = e.flawed?.fault
            val now = if (fault != null) runCatching { Flawed(arrived.bytes, digest(part), fault) }.getOrNull() else null
            if (now != null && now == before) {
                // The same bytes as last time, failing the same way: the flaw is in the
                // server's own copy, and asking again brings nothing else. Kept as it is.
                CrashPoints.say { "${song.id}: the same bytes twice, failing the same way (${e.reason}): the server's copy has the flaw; kept" }
                flaw = e.reason
                song
            } else {
                // Not the song, whole: nothing of it is kept to be carried on from.
                CrashPoints.say { "${song.id}: failed its checks (${e.reason})" }
                discard(part, meta)
                throw if (now != null) DownloadFailure(e.reason, again = true, flawed = now) else e
            }
        }
        if (cancelled) throw Cancelled()
        CrashPoints.reached("verified")
        try {
            // (Over a copy that was waiting to be replaced, if there is one of that name: one
            // step, so there is a file of that name at every instant.)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            meta.delete()
            CrashPoints.reached("renamed")
            // Its name must be on the disk before the mark that vouches for it: if the
            // folder can't be synced, it is not marked.
            syncDirOrFail(catalogue.dir)
        } catch (e: IOException) {
            target.delete()
            throw failure(e)
        }
        CrashPoints.reached("placed")
        CrashPoints.say { "${song.id}: verified and in place, ${arrived.bytes} bytes" }
        val entry = Downloaded(verified.copy(queued = false), name, arrived.bytes, clock(), form.suffix, form.bitrate, target.lastModified(), flaw = flaw)
        try {
            // The mark: only now is it a download.
            catalogue.add(entry)
        } catch (e: IOException) {
            target.delete()
            throw failure(e)
        }
        return entry
    }

    /**
     * The checks a download passes before it is marked (see the class). Returns the song as
     * it is entered: as asked for, or as the server has it now if that had to be asked.
     */
    private fun verify(asked: Song, form: SavedForm, part: File, arrived: Arrived): Song {
        val bytes = arrived.bytes
        if (bytes <= 0 || part.length() != bytes) throw DownloadFailure("The server sent nothing.", again = true)
        if (arrived.said >= 0 && arrived.said != bytes) throw DownloadFailure("The download was cut short.", again = true)
        var song = asked
        var fresh = false
        /** The server's newer word on the song, asked for once. */
        fun newer(): Boolean {
            if (fresh) return false
            fresh = true
            val now = try {
                refresh(asked.id)
            } catch (e: IOException) {
                throw failure(e)
            } ?: return false
            song = now
            return true
        }
        if (!form.converted && song.size != bytes) {
            // Not the size the library gives it: perhaps the library's word here is old.
            newer()
            // (A library that gives the song no size at all can't disagree: then reading it
            // through to its end, below, is what vouches for it.)
            if (song.size > 0 && song.size != bytes) {
                throw DownloadFailure(
                    if (song.size > bytes) "The download was cut short." else "The server sent a different file from the one in its library.",
                    again = true,
                )
            }
        }
        // The file as it is, the length the server stated for this answer, and the size the
        // library gives the song: the server's file, complete. Only its beginning is looked at.
        val whole = !form.converted && arrived.said == bytes && song.size == bytes
        val seen = try {
            if (whole) check.begins(part, form.suffix) else check.inspect(part, form.suffix) { cancelled }
        } catch (e: Cancelled) {
            throw e
        } catch (e: IOException) {
            if (cancelled) throw Cancelled()
            Inspected(null, false, -1)
        }
        if (whole) {
            // (Final at once: the same file would come again.)
            if (seen.container == null || !seen.audio) throw DownloadFailure(NOT_A_SONG)
            if (!seen.decodable) throw DownloadFailure(NO_DECODER, undecodable = true)
            return song
        }
        var fault = faultOf(song, form, seen)
        // (The server's newer word on how long it runs, before it is refused for that.)
        if (fault == Fault.WRONG_LENGTH && newer() && !(!form.converted && song.size > 0 && song.size != bytes)) fault = faultOf(song, form, seen)
        when (fault) {
            null -> return song
            Fault.NOT_A_SONG -> throw DownloadFailure(NOT_A_SONG, again = true)
            Fault.NO_DECODER -> throw DownloadFailure(NO_DECODER, undecodable = true)
            else -> Unit
        }
        // It is a song the player reads, and something is wrong with it (see [Flawed]).
        val reason = when (fault) {
            Fault.DAMAGED -> "What arrived is damaged: it can't be read through to its end."
            Fault.WRONG_LENGTH -> if (seen.runsUs < song.duration * 1_000_000L) "The download was cut short." else "The server sent a different file from the one in its library."
            else -> "The download was cut short."
        }
        throw DownloadFailure(reason, again = true, flawed = Flawed(bytes, "", fault))
    }

    /** The SHA-256 of a file's bytes. */
    private fun digest(file: File): String {
        val sum = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                if (cancelled) throw Cancelled()
                val got = input.read(buffer)
                if (got < 0) break
                sum.update(buffer, 0, got)
            }
        }
        return sum.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(request: Request): okhttp3.Response {
        if (cancelled) throw Cancelled()
        val made = http.newCall(request)
        call = made
        if (cancelled) made.cancel()
        return made.execute()
    }

    /** Words for what went wrong; never the exception's own message, which can name the
     *  server's address. */
    private fun failure(e: IOException): IOException {
        if (cancelled) return Cancelled()
        if (e is DownloadFailure || e is Cancelled || e is RouteGone) return e
        val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
        val full = chain.any { (it.message.orEmpty()).contains("ENOSPC") || it.message.orEmpty().contains("No space left", ignoreCase = true) }
        return when {
            full -> DownloadFailure("The phone is out of space.")
            chain.any { it is java.net.SocketTimeoutException } -> DownloadFailure("The server took too long to answer.", network = true)
            chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketException || it is java.net.NoRouteToHostException } ->
                DownloadFailure(CANT_REACH, network = true)
            chain.any { it is javax.net.ssl.SSLException } -> DownloadFailure("The secure connection to the server failed.")
            else -> DownloadFailure("It couldn't be saved (${e.javaClass.simpleName}).", network = true)
        }
    }

    private class OutOfRoom : IOException()

    companion object {
        const val CANT_REACH = "Can't reach the server."
        const val NOT_A_SONG = "What the server sent isn't a song the phone can play."
        const val NO_DECODER = "This phone has no way to play that kind of file."

        /** Why a download was stopped, or not begun, for want of room. */
        const val NO_ROOM = "The phone is nearly out of space."

        private const val BUFFER = 64 * 1024
        private const val TELL_EVERY = 128 * 1024L

        /** How much may arrive between one sync of the part and the next: what a power cut
         *  can cost a download that is then carried on with. */
        const val SYNC_EVERY = 1024L * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Whether a file that says it runs `durationUs` is the whole of `song`: no more than
         * a second (and a two-hundredth of its length) short of what the library says, and
         * no more than two seconds (and a hundredth) over. The library gives a length in
         * whole seconds, rounded or cut down, and an encoder adds a few hundredths at the
         * ends; nothing else is allowed for. A song the library gives no length for can't be
         * judged by it.
         */
        fun runsAsLongAs(song: Song, durationUs: Long): Boolean {
            if (song.duration <= 0) return true
            val expected = song.duration * 1_000_000L
            return durationUs >= expected - 1_000_000L - expected / 200 && durationUs <= expected + 2_000_000L + expected / 100
        }

        /**
         * What is wrong with a file that is to be the download of `song` in `form`, from what
         * reading it through found (`seen`); null if nothing is. For a file whose length does
         * not vouch for it (see the class): every rule applies whatever its size.
         */
        fun faultOf(song: Song, form: SavedForm, seen: Inspected): Fault? {
            val container = seen.container
            if (container == null || !seen.audio) return Fault.NOT_A_SONG
            when (seen.broken) {
                Broken.ENDS_EARLY -> return Fault.CUT_SHORT
                Broken.DAMAGED -> return Fault.DAMAGED
                null -> Unit
            }
            if (!seen.decodable) return Fault.NO_DECODER
            // The reader stopped looking for sound before the file's end. If what it had
            // read by then is the song's length, the rest is a tag; if not (or the song's
            // length isn't known), the rest is sound that will never be played.
            if (seen.gaveUp && (song.duration <= 0 || !runsAsLongAs(song, seen.runsUs))) return Fault.DAMAGED
            // It stops part-way through a frame.
            if (seen.partial) return Fault.CUT_SHORT
            val runs = seen.runsUs
            // How long it runs is the only word on whether it is whole: it must run at all.
            if (runs <= 0) return Fault.CUT_SHORT
            // The container's own count of its samples, where it states one: sound that
            // stops well before it has lost its end, and sound that runs well past it is
            // not the file the header describes.
            if (seen.ranUs >= 0 && seen.durationUs > 0 && Media3AudioCheck.statesItsLength(container)) {
                if (seen.ranUs < seen.durationUs - SHORT_OF_ITSELF_US) return Fault.CUT_SHORT
                if (seen.ranUs > seen.durationUs + SHORT_OF_ITSELF_US) return Fault.DAMAGED
            }
            if (!runsAsLongAs(song, runs)) return Fault.WRONG_LENGTH
            return null
        }

        /** How far short of the length a container states its samples may stop (the last
         *  sample's own length is only estimated: see `Inspected.ranUs`). */
        private const val SHORT_OF_ITSELF_US = 500_000L

        /** A song id as part of a file name: letters, digits, '-' and '_' (Windows' rule). */
        fun stem(id: String): String = id.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

        fun partName(name: String): String = ".$name.part"

        /** The record beside a part file (see [PartOf]). */
        fun metaName(name: String): String = ".$name.part.meta"

        /** The stem a part file, or its record, belongs to; null if it is neither. */
        fun stemOfPart(fileName: String): String? {
            if (!fileName.startsWith(".")) return null
            val inner = when {
                fileName.endsWith(".part") -> fileName.removePrefix(".").removeSuffix(".part")
                fileName.endsWith(".part.meta") -> fileName.removePrefix(".").removeSuffix(".part.meta")
                else -> return null
            }
            return inner.substringBeforeLast('.')
        }
    }
}
