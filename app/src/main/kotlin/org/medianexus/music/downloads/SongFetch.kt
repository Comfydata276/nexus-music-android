package org.medianexus.music.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.medianexus.music.net.Song
import org.medianexus.music.playback.NATIVE_SUFFIXES
import org.medianexus.music.playback.leastBytes

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

/** Why a download failed, in words a person is shown (on the Downloads list, never in a log
 *  or a notification). `network`: the server couldn't be reached, which is worth trying
 *  again by itself when it is back. */
class DownloadFailure(val reason: String, val network: Boolean = false, cause: Throwable? = null) : IOException(reason, cause)

/** The fetch was called off ([SongFetch.cancel]): not a failure, and only ever this. (A read
 *  that timed out is an `InterruptedIOException` too, and is a failure.) */
class Cancelled : InterruptedIOException("Cancelled")

/**
 * Fetches one song into the downloads folder, whole or not at all.
 *
 * It arrives in a file of its own beside where it will go (".<id>.<kind>.part"), and only
 * once it has come to a clean end, and is all there, is it put in its place and entered in
 * the [Catalogue], which is what makes it a download. All there means: for the file as it is,
 * every byte the server said it has; for a conversion, which comes with no length, at least
 * as much as a whole conversion of the song must come to (the same rule kept songs follow:
 * [leastBytes]). Anything short of that is never played and is cleared away.
 *
 * The file as it is can be picked up where it stopped (the app stopped, the network gone): the
 * part already here is kept, and the rest asked for as a byte range, if the server takes them,
 * which it says by answering with exactly the bytes asked for. A conversion is a fresh
 * stream each time, which no range can join onto: it starts again from nothing.
 *
 * Plain Kotlin and OkHttp, so it can be tested against a stand-in server. Blocking: run it on
 * a thread of its own (Media3's download manager gives each download one).
 */
class SongFetch(
    private val http: OkHttpClient,
    private val catalogue: Catalogue,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
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
     * known). Returns the catalogue's new entry; throws [DownloadFailure], or an
     * [Cancelled] if called off.
     */
    @Throws(IOException::class)
    fun fetch(song: Song, form: SavedForm, url: String, progress: (Long, Long) -> Unit = { _, _ -> }): Downloaded {
        catalogue.entry(song.id)?.let { if (File(catalogue.dir, it.file).isFile) return it }
        val stem = stem(song.id)
        if (stem.isEmpty()) throw DownloadFailure("The server gave this song an id the app can't use.")
        val name = "$stem.${form.suffix.ifEmpty { "bin" }}"
        val target = File(catalogue.dir, name)
        val part = File(catalogue.dir, partName(name))
        // A conversion can't be picked up part-way, nor can a part of a different kind.
        catalogue.dir.listFiles()?.forEach { if (it.name != part.name && stemOfPart(it.name) == stem) it.delete() }
        if (form.converted) part.delete()
        // A file left at the final name without an entry (the app stopped between the two):
        // not trusted, fetched again.
        target.delete()

        var have = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val response = try {
            open(request)
        } catch (e: IOException) {
            throw failure(e)
        }
        response.use {
            if (response.code == 416 && have > 0) {
                // Nothing after the part: it may be the whole file. Judged below, by size.
                return finish(song, form, part, target, name, have, total = song.size)
            }
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
            val range = response.header("Content-Range")
            val resumed = have > 0 && response.code == 206 && range != null && range.trim().lowercase().removePrefix("bytes").trim().startsWith("$have-")
            if (!resumed) have = 0
            val length = response.body.contentLength()
            // How long the whole file is: from the range's total, or the answer's length.
            val total = when {
                resumed -> range?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: if (length >= 0) have + length else -1
                else -> length
            }
            val expected = if (total > 0) total else expectedBytes(song, form.bitrate.takeIf { form.converted } ?: 0).takeIf { it > 0 } ?: -1
            try {
                FileOutputStream(part, resumed).use { out ->
                    val source = response.body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    var arrived = have
                    var told = 0L
                    while (true) {
                        if (cancelled) throw Cancelled()
                        val got = source.read(buffer)
                        if (got < 0) break
                        out.write(buffer, 0, got)
                        arrived += got
                        if (arrived - told >= TELL_EVERY) {
                            told = arrived
                            progress(arrived, expected)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                    have = arrived
                }
            } catch (e: IOException) {
                throw failure(e)
            }
            progress(have, expected)
            return finish(song, form, part, target, name, have, total)
        }
    }

    /** Judges what arrived, and if it is whole puts it in place and enters it. */
    private fun finish(song: Song, form: SavedForm, part: File, target: File, name: String, bytes: Long, total: Long): Downloaded {
        if (cancelled) throw Cancelled()
        val whole = when {
            bytes <= 0 -> throw cut(part, "The server sent nothing.")
            // The file as it is: every byte the server said there is.
            !form.converted -> total <= 0 && song.size <= 0 || bytes == (if (total > 0) total else song.size)
            // A conversion: as much as a whole one must come to.
            else -> bytes >= leastBytes(song.duration, form.bitrate, song.bitRate)
        }
        if (!whole) throw cut(part, "The download was cut short.")
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw failure(e)
        }
        val entry = Downloaded(song.copy(queued = false), name, bytes, clock(), form.suffix, form.bitrate)
        try {
            // The mark: only now is it a download.
            catalogue.add(entry)
        } catch (e: IOException) {
            target.delete()
            throw failure(e)
        }
        return entry
    }

    private fun cut(part: File, reason: String): DownloadFailure {
        part.delete()
        return DownloadFailure(reason)
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
        if (e is DownloadFailure || e is Cancelled) return e
        val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
        val full = chain.any { (it.message.orEmpty()).contains("ENOSPC") || it.message.orEmpty().contains("No space left", ignoreCase = true) }
        return when {
            full -> DownloadFailure("The phone is out of space.")
            chain.any { it is java.net.SocketTimeoutException } -> DownloadFailure("The server took too long to answer.", network = true)
            chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketException || it is java.net.NoRouteToHostException } ->
                DownloadFailure("Can't reach the server.", network = true)
            chain.any { it is javax.net.ssl.SSLException } -> DownloadFailure("The secure connection to the server failed.")
            else -> DownloadFailure("It couldn't be saved (${e.javaClass.simpleName}).", network = true)
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val TELL_EVERY = 128 * 1024L

        /** A song id as part of a file name: letters, digits, '-' and '_' (Windows' rule). */
        fun stem(id: String): String = id.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

        fun partName(name: String): String = ".$name.part"

        /** The stem a part file belongs to, or null if it isn't one. */
        fun stemOfPart(fileName: String): String? =
            if (fileName.startsWith(".") && fileName.endsWith(".part")) fileName.removePrefix(".").removeSuffix(".part").substringBeforeLast('.') else null
    }
}
