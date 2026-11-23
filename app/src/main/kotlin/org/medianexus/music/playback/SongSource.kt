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
    val stream: (offsetSecs: Int) -> String,
)

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

    /** The account's kept songs, or null to go straight to the server. */
    val cache: Cache?

    /** Offline mode is on: nothing may be asked of the server. */
    val offline: Boolean

    /**
     * What this account's conversions have said about themselves, and which of them are
     * being kept right now. The account's own, like its kept songs: two servers can give two
     * different songs the same id, and what one server's conversion of "song 12 at 128" was
     * found to be (where its audio starts, its rate, whether it takes byte ranges) is no
     * guide to the other's.
     */
    val conversions: Conversions

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
    /** Something that isn't one of our songs, read as it is. */
    private var plain: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        asked = dataSpec.uri
        val id = songIdOf(dataSpec.uri)
        if (id == null) {
            val source = upstream.createDataSource().also { made -> listeners.forEach(made::addTransferListener) }
            plain = source
            return guarded { source.open(dataSpec) }
        }
        // Refused here if the account this was made for has gone.
        val lease = leases.lease(generation).also { this.lease = it }
        val where = lease.resolve(id)
        val reader = SongReader(Streams(lease.cache, dataSpec), lease.conversions.heads, lease.conversions.writers, pause).also { this.reader = it }
        return guarded { reader.open(where, dataSpec.position, lease.offline) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = guarded {
        val reader = reader
        when {
            reader != null -> reader.read(buffer, offset, length).let { if (it < 0) C.RESULT_END_OF_INPUT else it }
            else -> (plain ?: throw IOException("Not open")).read(buffer, offset, length)
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
        override fun server(url: String, position: Long): Opened {
            if (org.medianexus.music.BuildConfig.DEBUG) {
                // (Never the address itself: it carries the sign-in token.)
                val second = url.substringAfter("timeOffset=", "").substringBefore('&')
                if (second.isNotEmpty()) android.util.Log.d("NexusPlayback", "a conversion opened part-way, at byte ${spec.position}: asked of the server from second $second")
            }
            return open(http(), server(url, null, position))
        }

        override fun kept(url: String, key: String, position: Long): Opened? {
            val cache = cache ?: return null
            val through = CacheDataSource.Factory()
                .setCache(cache)
                // The cache asks its upstream where it ended up, to note a redirect in its
                // index: it is only ever told the address it asked for.
                .setUpstreamDataSourceFactory { Discreet(http()) }
                // A kept file the system has cleared away is read from the server instead.
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            // Kept whatever its length, known or not: the player asks for "not kept if the
            // length isn't known", which is every fresh conversion. Whether one is worth
            // keeping is decided when it ends (see SongReader).
            val spec = server(url, key, position)
            return open(through, spec.buildUpon().setFlags(spec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv()).build())
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
        }

        private fun http(): DataSource = upstream.createDataSource().also { made -> listeners.forEach(made::addTransferListener) }

        private fun server(url: String, key: String?, position: Long): DataSpec =
            DataSpec.Builder().setUri(url).setKey(key).setPosition(position).setHttpRequestHeaders(spec.httpRequestHeaders).setFlags(spec.flags).build()

        private fun open(source: DataSource, spec: DataSpec): Opened {
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
            return Opened(
                object : ByteSource {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        source.read(buffer, offset, length).let { if (it == C.RESULT_END_OF_INPUT) -1 else it }

                    override fun close() = source.close()
                },
                length,
                ranged,
            )
        }

        override fun mayPass(error: IOException): Boolean {
            // Called off (the player has moved on), not failed. (A stalled read is a kind of
            // "interrupted" to Java, and is a failure.)
            if (Thread.currentThread().isInterrupted) return false
            if (generateSequence<Throwable>(error) { it.cause }.take(8).any { it is java.io.InterruptedIOException && it !is java.net.SocketTimeoutException }) return false
            val kind = StreamFailure.of(error)
            return !kind.refused && !kind.offline
        }
    }

    /** A data source that says it is wherever it was asked to go, whatever redirects it
     *  followed to get there. */
    private class Discreet(private val source: DataSource) : DataSource by source {
        private var asked: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            asked = dataSpec.uri
            return source.open(dataSpec)
        }

        override fun getUri(): Uri? = asked
    }

    /** Makes one for each read the player starts, for the account signed in at that moment. */
    class Factory(private val leases: SongLeases, private val upstream: DataSource.Factory, private val pause: (Long) -> Unit = { Thread.sleep(it) }) : DataSource.Factory {
        override fun createDataSource(): DataSource = SongDataSource(leases, leases.generation(), upstream, pause)
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

    companion object {
        /** Stands in the status for "offline mode, and not kept": no server sends it. */
        private const val OFFLINE = -1

        fun of(error: IOException): StreamFailure {
            if (error is StreamFailure) return error
            // Every cause, as the HTTP data source wraps the real one.
            val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val status = chain.firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode } ?: 0
            // Asked for again several times already (see SongReader.again): `spent`.
            val spent = chain.any { it is GaveUp }
            return when {
                chain.any { it is NotKeptOffline } -> StreamFailure("Offline mode is on, and the song isn't kept on the phone", PlaybackException.ERROR_CODE_IO_UNSPECIFIED, OFFLINE)
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
        if (error is StreamFailure && (error.refused || error.offline || error.spent)) return C.TIME_UNSET
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
