package org.medianexus.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.medianexus.music.store.WholeMark

/** The form a song's audio is fetched in: the bit rate asked of the server (0 for the file
 *  as it is) and the kind of file that comes back. */
data class StreamForm(val bitrate: Int, val suffix: String) {
    /** The server converts the song for this form; its stream can't be read part-way by
     *  bytes, only started at a time. */
    val converted: Boolean get() = bitrate > 0
}

/** The form a song would be streamed in now, by its kind alone (not knowing whether this
 *  phone turned out not to play its file): for asking whether it is kept, with no player. */
fun streamFormOf(song: org.medianexus.music.net.Song, cap: Int): StreamForm {
    val suffix = song.suffix.lowercase()
    return streamForm(suffix.isEmpty() || suffix in NATIVE_SUFFIXES, cap, suffix, song.bitRate)
}

/** File types the phone plays as they are; anything else is converted by the server. */
val NATIVE_SUFFIXES = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "mka", "webm")

/**
 * The form a song is streamed in, as Windows chooses it (`stream_form`): the file as it is if
 * the phone can play it and no limit is set, otherwise MP3 at the limit (320 kbps when it's a
 * file the phone can't play and there is no limit).
 */
fun streamForm(native: Boolean, cap: Int, suffix: String, fileKbps: Int = 0): StreamForm = when {
    native && cap == 0 -> StreamForm(0, suffix)
    // An MP3 already at or under the limit: asked for "MP3 at the limit", Navidrome sends the
    // file as it is (it converts nothing), with its length, and takes no notice of a time to
    // start from. So it is asked for as what it will be, the file, and read like any other
    // file. (Windows asks for a conversion here; this is where the phone differs.)
    native && suffix == "mp3" && fileKbps in 1..cap -> StreamForm(0, suffix)
    else -> StreamForm(if (cap == 0) 320 else cap, "mp3")
}

/** What a song's audio is kept under among the kept songs: its id and the form, never the
 *  address (which carries a sign-in token that changes). */
fun cacheKey(songId: String, form: StreamForm): String = "song:$songId:${form.bitrate}"

/** A song's address inside the app: what a media item carries instead of a server address. */
fun songUri(id: String): String = "$SCHEME://song/${Uri.encode(id)}"

/** The song id in an address made by [songUri], or null if it isn't one. */
fun songIdOf(uri: Uri): String? =
    if (uri.scheme == SCHEME && uri.host == "song") uri.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() } else null

const val SCHEME = "nexus"

/**
 * Where one song's bytes come from right now. `stream(offsetSecs)` is the server's address
 * for the song in `form`, started that many seconds in (only a conversion can be).
 */
class SongWhere(
    val form: StreamForm,
    val key: String,
    /** The fewest bytes a whole conversion of the song can come to (0 for a file as it is,
     *  whose length the server gives): one that ends short of this was cut off, and isn't
     *  kept. See [leastBytes]. */
    val least: Long = 0,
    /** The song's downloaded copy, which is read first if there is one: whole, as only a
     *  whole download is ever entered (see `downloads.Catalogue`). */
    val downloaded: java.io.File? = null,
    /** The bit rate the downloaded copy was converted to when it was saved (0: the file as
     *  it is on the server). */
    val downloadedKbps: Int = 0,
    /** Whether the server may be asked for the song in this form now: asked at every request
     *  a read makes, and as its first bytes arrive. False only for a form above the lower of
     *  two limits while the app isn't sure of an unmetered network (see `StreamQuality`). */
    val mayAsk: () -> Boolean = { true },
    val stream: (offsetSecs: Int) -> String,
)

/**
 * The copy of a song one read of it is of: its downloaded file, or the server's stream (or
 * the kept copy of that stream, which is the same bytes) in one form. `kbps`: what it was
 * converted to, 0 for the file as it is: what the format line says is playing.
 */
sealed interface SongCopy {
    val kbps: Int

    /** A downloaded file, as it was when it was opened: a file at the same name that is not
     *  that long, or was not put there then, is another copy. */
    data class Download(val file: java.io.File, val modified: Long, val length: Long, override val kbps: Int) : SongCopy

    data class Stream(val form: StreamForm) : SongCopy {
        @Volatile internal var fromKept = false
        override val kbps: Int get() = form.bitrate
    }
}

/**
 * The fewest bytes a whole converted song can come to, as Windows works it out
 * (`least_bytes` in src/playback.rs): most of what its length comes to at the bit rate asked
 * for, or the file's own if that is lower (the server doesn't convert up).
 */
fun leastBytes(seconds: Int, askedKbps: Int, fileKbps: Int): Long {
    val rate = if (fileKbps > 0) minOf(askedKbps, fileKbps) else askedKbps
    return seconds.toLong() * rate * 125 * 4 / 5
}

/**
 * What one read of a song is given by the account it is for: where the song's bytes come
 * from, and that account's kept songs. Held from the moment the read opens until it closes,
 * so the kept songs aren't closed under it.
 */
@OptIn(UnstableApi::class)
interface SongLease {
    /** Decides, as the song is opened, where its bytes come from. */
    fun resolve(songId: String): SongWhere

    /** The same, for the queue entry the read is for (null: fetching ahead). */
    fun resolve(songId: String, entry: Long?): SongWhere = resolve(songId)

    /** The account's kept songs, or null to go straight to the server. */
    val cache: Cache?

    /** Offline mode is on: nothing may be asked of the server. Asked again as the song is
     *  read, not only as it is opened: the switch can be turned while a stream is open. */
    val offline: Boolean

    /**
     * What this account's conversions have said about themselves, and which of them are
     * being kept right now. The account's own, like its kept songs: two servers can give two
     * different songs the same id, and what one server's conversion of "song 12 at 128" was
     * found to be (where its audio starts, its rate, whether it takes byte ranges) is no
     * guide to the other's.
     */
    val conversions: Conversions

    /** A read of the song has opened, on this copy of it: what is playing, for the format
     *  line, and what a failure is to be laid at. Called on a loading thread. */
    fun opened(songId: String, copy: SongCopy) {}

    /** The same, for the queue entry the read is for (`entry`, the entry's own number; null
     *  for a read that is no entry's: fetching ahead). What is playing is told by entry. */
    fun opened(songId: String, entry: Long?, copy: SongCopy) = opened(songId, copy)

    /** The read of the song kept under `key` has closed: whether it is kept whole now is
     *  worth noting (see `store.KeptIndex`). */
    fun closed(key: String) {}

    /** What is kept under `key` has been forgotten. */
    fun dropped(key: String) {}

    /** The read has closed. */
    fun release()
}

/** One account's conversions: see [SongLease.conversions]. Made with the account's turn at
 *  the player, and gone with it. */
class Conversions {
    val heads = ConversionHeads()
    val writers = ConversionWriters()
}

/**
 * Every sign-in is a new generation of the player's account. A read belongs to the
 * generation that was current when its data source was made, and is refused if it opens
 * after that account has gone: it must never meet another account's server or cache.
 */
interface SongLeases {
    /** The generation of the account signed in now; 0 when nobody is. */
    fun generation(): Long

    /** Throws IOException if `generation` is no longer the account signed in. */
    fun lease(generation: Long): SongLease
}

/**
 * The data source the player reads every song through. It turns `nexus://song/<id>` into
 * real bytes at the moment the song is opened: this is the one place that decides where a
 * song comes from (the server now; a downloaded copy and fetching ahead come in later
 * versions, in [SongLease.resolve]).
 *
 * How a song is read, and a converted one above all, is [SongReader]'s business; this only
 * stands Media3's HTTP and cache data sources behind it ([SongStreams]).
 *
 * Nothing private leaves it. The address it reports is the song's own (`nexus://…`), never
 * the server's, which carries the sign-in token; the cache is never told where a redirect
 * led; and what it throws says what kind of failure it was and nothing of where. Media3
 * hands all three on: to its load diagnostics, to the cache's index on disk, and, inside a
 * playback error, to every app with a controller.
 */
@OptIn(UnstableApi::class)
class SongDataSource(
    private val leases: SongLeases,
    /** The account generation this data source was made for. */
    private val generation: Long,
    private val upstream: DataSource.Factory,
    /** Waits before a conversion that failed is asked for again (see [SongReader]). */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var lease: SongLease? = null
    private var asked: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var reader: SongReader? = null
    private var where: SongWhere? = null
    /** Something that isn't one of our songs, read as it is; or a downloaded copy. */
    private var plain: DataSource? = null

    /**
     * The copy this data source first opened, and where it was resolved to then. One data
     * source serves one queue entry's turn in the player (Media3 makes one for each media
     * period, and opens it again at every seek and every retry), so this is "the copy the
     * entry started on": every later open is of the same copy. See [open].
     */
    private var first: SongWhere? = null
    private var copy: SongCopy? = null
    private var songId: String? = null
    private var unopenable = false
    /** The queue entry this read is for (from the media item: see `QueuePlayer`); null for
     *  one that is no entry's (fetching ahead). */
    private var entry: Long? = null
    /** The server has been asked for the song by this read (not only its kept copy read). */
    private var askedServer = false
    /** Bytes of the stream have been handed on. */
    private var handedOver = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    /**
     * Opens the song at a byte. **A byte position means something only in the copy it was
     * worked out from**: Media3 turns a seek into "byte N" using what it read of the file it
     * has been playing, and asks for that byte here. So whatever this data source opened
     * first is what it opens every time after: the same downloaded file, or the stream in
     * the same form. (It used to resolve the song afresh at each open. A streamed conversion
     * whose download finished mid-song was then handed the downloaded file at a byte that
     * belonged to the conversion, and a download removed while playing the reverse.)
     *
     * If that copy has gone (the file removed or replaced; the stream out of reach while a
     * download has arrived since), nothing is opened at that byte: the read fails as
     * [StreamFailure.moved], and the service makes the entry ready again from its *time*,
     * which resolves afresh, from byte 0, on whatever is best now.
     */
    override fun open(dataSpec: DataSpec): Long {
        asked = dataSpec.uri
        val id = songIdOf(dataSpec.uri)
        if (id == null) {
            // An internet radio station: in offline mode nothing is asked of the internet,
            // from the moment the switch is turned, as with any stream (see `read`).
            this.lease = leases.lease(generation)
            if (this.lease?.offline == true) throw StreamFailure.of(NotKeptOffline())
            val source = upstream.createDataSource().also { made -> listeners.forEach(made::addTransferListener) }
            plain = source
            return guarded { source.open(dataSpec) }.also {
                // An address that names no song is an internet radio station's: what it says
                // of itself (its ICY headers, which tell the player where the titles it announces
                // are in the stream) and its kind are passed on; nothing else of its answer.
                headers = source.responseHeaders.filterKeys { key -> key != null && (key.startsWith("icy-", ignoreCase = true) || key.equals("Content-Type", ignoreCase = true)) }
            }
        }
        // Refused here if the account this was made for has gone.
        val lease = leases.lease(generation).also { this.lease = it }
        songId = id
        entry = dataSpec.key?.takeIf { it.startsWith(ENTRY) }?.removePrefix(ENTRY)?.toLongOrNull()
        val began = first
        if (began == null) {
            val where = lease.resolve(id, entry)
            where.downloaded?.let { file ->
                // Downloaded: read from the phone, whatever the streaming quality, and with
                // the server gone. (A file that has gone since is streamed instead.)
                val pinned = SongCopy.Download(file, file.lastModified(), file.length(), where.downloadedKbps)
                val length = openFile(pinned, dataSpec)
                if (length != null) {
                    first = where
                    copy = pinned
                    lease.opened(id, entry, pinned)
                    return length
                }
                // It is there and can't be opened: this read streams, and doesn't go back
                // to look for it.
                unopenable = true
            }
            first = where
            copy = SongCopy.Stream(where.form).also { lease.opened(id, entry, it) }
            return openStream(lease, id, where, dataSpec)
        }
        return when (val pinned = copy) {
            is SongCopy.Download -> {
                // The same file, or nothing: not whatever is at that name now.
                val same = pinned.file.isFile && pinned.file.lastModified() == pinned.modified && pinned.file.length() == pinned.length
                if (!same) throw StreamFailure.moved()
                openFile(pinned, dataSpec) ?: throw StreamFailure.local()
            }
            else -> openStream(lease, id, began, dataSpec)
        }
    }

    /** Opens the downloaded file; null if it can't be opened. */
    private fun openFile(pinned: SongCopy.Download, dataSpec: DataSpec): Long? {
        val local = androidx.media3.datasource.FileDataSource().also { made -> listeners.forEach(made::addTransferListener) }
        return try {
            val length = local.open(dataSpec.buildUpon().setUri(Uri.fromFile(pinned.file)).build())
            if (org.medianexus.music.BuildConfig.DEBUG) android.util.Log.d("NexusPlayback", "a song opened from its download, at byte ${dataSpec.position}")
            plain = local
            headers = emptyMap()
            length
        } catch (_: IOException) {
            runCatching { local.close() }
            null
        }
    }

    /** Opens the stream (through the kept songs) in the form this data source began in. */
    private fun openStream(lease: SongLease, id: String, where: SongWhere, dataSpec: DataSpec): Long {
        this.where = where
        if (org.medianexus.music.BuildConfig.DEBUG) android.util.Log.d("NexusPlayback", "a song opened ${if (lease.cache == null) "without" else "through"} the kept songs, at byte ${dataSpec.position}")
        val streams = Streams(lease.cache, dataSpec)
        val reader = SongReader(streams, lease.conversions.heads, lease.conversions.writers, pause).also { this.reader = it }
        return streamed(lease, id) { reader.open(where, dataSpec.position) { lease.offline } }.also {
            (copy as? SongCopy.Stream)?.fromKept = reader.openedFromKept || streams.openedInCache
        }
    }

    /**
     * A step of reading the stream. If it fails and the song has a downloaded copy now (it
     * arrived while this was playing), the stream is not waited for or tried again: the
     * entry is made ready again on the download ([StreamFailure.moved]).
     */
    private inline fun <T> streamed(lease: SongLease, id: String, step: () -> T): T = try {
        step()
    } catch (e: IOException) {
        // (Called off, not failed: the player has moved on, and wants nothing made ready.)
        val calledOff = Thread.currentThread().isInterrupted ||
            generateSequence<Throwable>(e) { it.cause }.take(8).any { it is java.io.InterruptedIOException && it !is java.net.SocketTimeoutException }
        if (!calledOff && !unopenable && runCatching { lease.resolve(id).downloaded != null }.getOrDefault(false)) throw StreamFailure.moved()
        // Offline, and the copy this read is in isn't on the phone after all (cleared away
        // since it was chosen): if another form of the song is kept whole, the entry is made
        // ready again from where it is, and that is chosen. The index hears of it first.
        if (!calledOff && lease.offline && where?.let { gone(lease, it) } == true) {
            runCatching { lease.dropped(where!!.key) }
            if (runCatching { lease.resolve(id).key != where?.key }.getOrDefault(false)) throw StreamFailure.moved()
        }
        throw StreamFailure.of(e)
    }

    /** The copy kept under `where`'s key isn't whole (or there are no kept songs to read). */
    private fun gone(lease: SongLease, where: SongWhere): Boolean {
        val cache = lease.cache ?: return true
        return (if (where.form.converted) WholeMark.length(cache, where.key) else WholeMark.cached(cache, where.key)) < 0
    }

    /** The form this read is of, if it is a stream (for fetching ahead's failures). */
    val streamForm: StreamForm? get() = (copy as? SongCopy.Stream)?.form

    /** Whether a read of the stream may go on: no, once the server has been asked for a form
     *  it may no longer be asked for (fetching ahead lets go of it then; see `FetchAhead`). */
    fun mayGoOn(): Boolean = !askedServer || where?.mayAsk?.invoke() != false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val reader = reader
        val lease = lease
        val id = songId
        return when {
            reader != null && lease != null && id != null -> streamed(lease, id) { reader.read(buffer, offset, length).let { if (it < 0) C.RESULT_END_OF_INPUT else it } }
                .also { got ->
                    if (got > 0 && !handedOver) {
                        // A last look as the first bytes arrive, before any is handed on: a
                        // form asked for on an unmetered network that has since turned out
                        // not to be is let go, and the entry made ready again in the form for
                        // the network now. (After this, the song goes on in its form.)
                        if (askedServer && where?.mayAsk?.invoke() == false) throw StreamFailure.moved()
                        handedOver = true
                    }
                }
            // The downloaded file: a read of it that fails is that file's failure.
            copy is SongCopy.Download -> try {
                (plain ?: throw IOException("Not open")).read(buffer, offset, length)
            } catch (_: IOException) {
                throw StreamFailure.local()
            }
            else -> guarded {
                if (lease?.offline == true) throw NotKeptOffline()
                (plain ?: throw IOException("Not open")).read(buffer, offset, length)
            }
        }
    }

    /** The song's own address, as it was asked for: never the server's. */
    override fun getUri(): Uri? = asked

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        try {
            reader?.close()
            plain?.close()
        } catch (e: IOException) {
            throw StreamFailure.of(e)
        } finally {
            // Whether it is kept whole now: only once the reader has closed, as the cache
            // writes the last of a song as its writer closes.
            val key = where?.takeIf { reader != null }?.key
            if (key != null) runCatching { lease?.closed(key) }
            // (What it first opened is kept: the next open of this data source is of the
            // same copy.)
            where = null
            reader = null
            plain = null
            asked = null
            headers = emptyMap()
            // Only now may the account's kept songs be closed, if it has gone meanwhile.
            lease?.release()
            lease = null
        }
    }

    /** Runs a step, and lets nothing out of it but a [StreamFailure]. */
    private inline fun <T> guarded(step: () -> T): T = try {
        step()
    } catch (e: IOException) {
        throw StreamFailure.of(e)
    }

    /** Media3's data sources, as [SongReader] asks for them. */
    private inner class Streams(private val cache: Cache?, private val spec: DataSpec) : SongStreams {
        var openedInCache = false
            private set

        override fun server(url: String, position: Long): Opened {
            if (org.medianexus.music.BuildConfig.DEBUG) {
                // (Never the address itself: it carries the sign-in token.)
                val second = url.substringAfter("timeOffset=", "").substringBefore('&')
                if (second.isNotEmpty()) android.util.Log.d("NexusPlayback", "a conversion opened part-way, at byte ${spec.position}: asked of the server from second $second")
            }
            if (position == 0L) return open(http(), server(url, null, 0))
            // From a byte further in: asked for with a Range header of our own, and from
            // "byte 0" as far as Media3's HTTP data source is told. Told the place, it makes
            // up for a server that ignores the range by reading the answer from its start
            // and throwing the bytes away up to the place, inside `open`
            // (`OkHttpDataSource.open`, "manually skip to the requested position"): for a
            // conversion the server no longer holds whole, that is the song fetched up to
            // there, for nothing. Whether the range was honoured is read from the answer.
            val ranged = server(url, null, 0).buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + ("Range" to "bytes=$position-")).build()
            return try {
                open(http(), ranged, from = position)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                // "No such range": the place is at or past the end of what there is.
                if (e.responseCode != 416) throw e
                Opened(
                    object : ByteSource {
                        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
                        override fun close() {}
                    },
                    0, ranged = true,
                )
            }
        }

        override fun kept(url: String, key: String, position: Long): Opened? {
            val cache = cache ?: return null
            var hadUpstream = false
            val through = CacheDataSource.Factory()
                .setCache(cache)
                // The cache asks its upstream where it ended up, to note a redirect in its
                // index: it is only ever told the address it asked for.
                .setUpstreamDataSourceFactory { Discreet(http()) { hadUpstream = true } }
                // A kept file the system has cleared away is read from the server instead.
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            // Kept whatever its length, known or not: the player asks for "not kept if the
            // length isn't known", which is every fresh conversion. Whether one is worth
            // keeping is decided when it ends (see SongReader).
            val spec = server(url, key, position)
            return open(through, spec.buildUpon().setFlags(spec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv()).build()).also {
                openedInCache = !hadUpstream
            }
        }

        override fun keptOnly(key: String, position: Long): Opened {
            val cache = cache ?: throw IOException("Nothing is kept")
            // No upstream, and nothing written: a byte that isn't kept is an error.
            val only = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()
            return open(only, DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setPosition(position).build())
        }

        override fun wholeLength(key: String, converted: Boolean): Long {
            val cache = cache ?: return -1
            return if (converted) WholeMark.length(cache, key) else WholeMark.cached(cache, key)
        }

        override fun keep(key: String, length: Long): Boolean = cache?.let { WholeMark.put(it, key, length) } ?: false

        override fun drop(key: String) {
            cache?.let { WholeMark.drop(it, key) }
            lease?.dropped(key)
        }

        /** The server, asked only while it may be asked for this form ([SongWhere.mayAsk]).
         *  When it may not (a form above the lower limit, and the network not surely
         *  unmetered), nothing is asked: what is kept of this form isn't whole (else nothing
         *  would be asked), the index is told so, and the read fails as "moved", for the entry
         *  to be made ready again from where it is in the form chosen then. */
        private fun http(): DataSource {
            val made = upstream.createDataSource().also { made -> listeners.forEach(made::addTransferListener) }
            val where = where ?: return made
            return object : DataSource by made {
                override fun open(dataSpec: DataSpec): Long {
                    if (!where.mayAsk()) {
                        runCatching { lease?.dropped(where.key) }
                        throw StreamFailure.moved()
                    }
                    askedServer = true
                    return made.open(dataSpec)
                }

                // (A default method of the interface: `by` doesn't hand it on.)
                override fun getResponseHeaders(): Map<String, List<String>> = made.responseHeaders
            }
        }

        private fun server(url: String, key: String?, position: Long): DataSpec =
            DataSpec.Builder().setUri(url).setKey(key).setPosition(position).setHttpRequestHeaders(spec.httpRequestHeaders).setFlags(spec.flags).build()

        /** `from`: the byte asked for by a Range header of our own (0: none was). */
        private fun open(source: DataSource, spec: DataSpec, from: Long = 0): Opened {
            val length = try {
                source.open(spec)
            } catch (e: IOException) {
                runCatching { source.close() }
                throw e
            }
            // What kind of file it is, for the player to pick how to read it.
            val answered = source.responseHeaders
            headers = answered.filterKeys { it.equals("Content-Type", ignoreCase = true) }
            val ranged = answered.entries.any { (name, values) -> name.equals("Accept-Ranges", ignoreCase = true) && values.any { it.trim().equals("bytes", ignoreCase = true) } }
            // An answer to a range says which bytes it is ("Content-Range: bytes 720572-…").
            val honoured = from == 0L || answered.entries.any { (name, values) ->
                name.equals("Content-Range", ignoreCase = true) && values.any { it.trim().lowercase().removePrefix("bytes").trim().startsWith("$from-") }
            }
            return Opened(
                object : ByteSource {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        source.read(buffer, offset, length).let { if (it == C.RESULT_END_OF_INPUT) -1 else it }

                    override fun close() = source.close()
                },
                length,
                ranged,
                honoured,
            )
        }

        override fun mayPass(error: IOException): Boolean {
            // Called off (the player has moved on), not failed. (A stalled read is a kind of
            // "interrupted" to Java, and is a failure.)
            if (Thread.currentThread().isInterrupted) return false
            if (generateSequence<Throwable>(error) { it.cause }.take(8).any { it is java.io.InterruptedIOException && it !is java.net.SocketTimeoutException }) return false
            val kind = StreamFailure.of(error)
            return !kind.refused && !kind.offline && !kind.moved && !kind.signIn
        }
    }

    /** A data source that says it is wherever it was asked to go, whatever redirects it
     *  followed to get there. */
    private class Discreet(private val source: DataSource, private val opened: () -> Unit) : DataSource by source {
        private var asked: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            asked = dataSpec.uri
            opened()
            return source.open(dataSpec)
        }

        override fun getUri(): Uri? = asked
    }

    /** Makes one for each read the player starts, for the account signed in at that moment. */
    class Factory(private val leases: SongLeases, private val upstream: DataSource.Factory, private val pause: (Long) -> Unit = { Thread.sleep(it) }) : DataSource.Factory {
        override fun createDataSource(): SongDataSource = SongDataSource(leases, leases.generation(), upstream, pause)
    }

    companion object {
        /** How a queue entry names itself to its reads: the media item's custom cache key
         *  (`QueuePlayer`), which Media3 hands on as each read's `DataSpec.key`. */
        const val ENTRY = "entry:"
    }
}

/**
 * A read of a song that failed, with everything private taken out: what kind of failure it
 * was, the server's status if it gave one, and nothing else. The exception it stands for
 * (which names the server's address, and for an HTTP error carries the whole request, token
 * and all) is not kept, even as its cause: a playback error travels to every app that holds
 * a controller, cause and message included.
 */
@OptIn(UnstableApi::class)
class StreamFailure private constructor(message: String, reason: Int, val status: Int, val spent: Boolean = false) : DataSourceException(message, null, reason) {
    /** The server answered, and said no: asking again won't help. */
    val refused: Boolean get() = status in 400..499

    /** Offline mode is on and the song isn't kept: asking again won't help either. */
    val offline: Boolean get() = status == OFFLINE

    /** The copy this read began on has gone, or a better one has come: the entry is to be
     *  made ready again from its time (see [SongDataSource.open]). Not a failure of the song. */
    val moved: Boolean get() = status == MOVED

    /** The downloaded file itself couldn't be read: that file's failure, and nothing to do
     *  with the server. */
    val local: Boolean get() = status == LOCAL

    /** The music login is set aside (the music server refused it, here or anywhere): nothing is
     *  sent with it, and the entry waits, where it is, for the Nexus server's renewal. */
    val signIn: Boolean get() = status == SIGN_IN

    companion object {
        /** Stands in the status for "offline mode, and not kept": no server sends it. */
        private const val OFFLINE = -1
        private const val MOVED = -2
        private const val LOCAL = -3
        private const val SIGN_IN = -4

        fun moved() = StreamFailure("The copy of the song being played has changed", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, MOVED)

        fun local() = StreamFailure("The downloaded copy couldn't be read", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, LOCAL)

        fun of(error: IOException): StreamFailure {
            if (error is StreamFailure) return error
            // Every cause, as the HTTP data source wraps the real one.
            val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            // One of ours, wrapped on its way out (Media3's cache wraps what its upstream
            // throws): passed on as it is.
            chain.firstNotNullOfOrNull { it as? StreamFailure }?.let { return it }
            val status = chain.firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode } ?: 0
            // Asked for again several times already (see SongReader.again): `spent`.
            val spent = chain.any { it is GaveUp }
            return when {
                chain.any { it is NotKeptOffline } -> StreamFailure("Offline mode is on, and the song isn't kept on the phone", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, OFFLINE)
                chain.any { it is org.medianexus.music.net.RefusedLogin } -> StreamFailure("The music sign-in needs renewing", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, SIGN_IN)
                status != 0 -> StreamFailure("The server answered with status $status", PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status, spent)
                chain.any { it is java.net.SocketTimeoutException } ->
                    StreamFailure("The server took too long to answer", PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 0, spent)
                chain.any { it is HttpDataSource.HttpDataSourceException || it is java.net.SocketException || it is java.net.UnknownHostException } ->
                    StreamFailure("The connection to the server failed", PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 0, spent)
                else -> StreamFailure("The song couldn't be read (${error.javaClass.simpleName})", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, 0, spent)
            }
        }
    }
}

/**
 * Rides out a bad connection: a stream that fails is asked for again, a little further apart
 * each time, for about fifteen seconds, before the player stops to wait for the server (see
 * the service's `onPlayerError`). The server saying no (a 4xx) is not a connection problem
 * and isn't retried; nor is a conversion that has already been asked for again that many
 * times where only it can be (see [SongReader]).
 */
@OptIn(UnstableApi::class)
class RideOut : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        // A station: the service's rejoin is the one thing that tries it again.
        if (songIdOf(loadErrorInfo.loadEventInfo.dataSpec.uri) == null) return C.TIME_UNSET
        if (error is StreamFailure && (error.refused || error.offline || error.spent || error.moved || error.local || error.signIn)) return C.TIME_UNSET
        // A file that can't be read as audio (a damaged download, say) reads the same the
        // next time: not a connection to ride out. (Media3's own policy doesn't retry these;
        // as an IOException it was being asked for four more times, 15 seconds of silence.)
        if (error is androidx.media3.common.ParserException) return C.TIME_UNSET
        if (error !is IOException) return super.getRetryDelayMsFor(loadErrorInfo)
        return RETRY_WAITS.getOrElse(loadErrorInfo.errorCount - 1) { RETRY_WAITS.last() }
    }

    override fun getMinimumLoadableRetryCount(dataType: Int) = RETRY_WAITS.size

    companion object {
        /** Waits between tries at a stream that keeps failing, as Windows waits: after these,
         *  the song waits for the server. */
        val RETRY_WAITS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
    }
}

/**
 * How the player reads a song's file. Conversions are MP3 at a constant bit rate with no
 * index: seeking in them goes by bit rate, even before their length is known (see
 * [SongReader]).
 */
@OptIn(UnstableApi::class)
fun songExtractors(): androidx.media3.extractor.ExtractorsFactory {
    val media3 = androidx.media3.extractor.DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)
    // Media3's own, with an MP3's LAME header read beside its MP3 extractor (see Mp3Trim).
    return object : androidx.media3.extractor.ExtractorsFactory {
        override fun createExtractors() = wrap(media3.createExtractors())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>) = wrap(media3.createExtractors(uri, responseHeaders))

        private fun wrap(extractors: Array<androidx.media3.extractor.Extractor>): Array<androidx.media3.extractor.Extractor> =
            Array(extractors.size) { i -> extractors[i].let { if (it is androidx.media3.extractor.mp3.Mp3Extractor) Mp3HeaderReader(it) else it } }
    }
}

/**
 * Songs the phone turned out not to be able to play as they are (a file type it plays in
 * general, with something inside it doesn't): they're converted by the server from then on,
 * as Windows does (`needs_transcode`).
 */
class NeedsConverting {
    private val ids = ConcurrentHashMap.newKeySet<String>()
    operator fun contains(id: String) = id in ids
    /** Returns whether it wasn't already marked. */
    fun mark(id: String): Boolean = ids.add(id)
}
