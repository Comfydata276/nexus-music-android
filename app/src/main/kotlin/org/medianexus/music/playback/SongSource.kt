package org.medianexus.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

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
fun streamForm(native: Boolean, cap: Int, suffix: String): StreamForm =
    if (native && cap == 0) StreamForm(0, suffix) else StreamForm(if (cap == 0) 320 else cap, "mp3")

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
    /** The song's length in seconds, if known: a conversion's length in bytes is worked out
     *  from it. */
    val seconds: Int = 0,
    val stream: (offsetSecs: Int) -> String,
)

/** Decides, when a song is opened, where its bytes come from. */
fun interface SongResolver {
    /** Throws IOException if the song can't be had (nobody signed in). */
    fun resolve(songId: String): SongWhere
}

/**
 * The data source the player reads every song through. It turns `nexus://song/<id>` into
 * real bytes at the moment the song is opened: this is the one place that decides where a
 * song comes from (the server now; a downloaded copy and fetching ahead come in later
 * versions, in [SongResolver]).
 *
 * Originals go through the kept songs, and can be read from any byte, as the server
 * honours ranges for them. A song the server converts can't be: Navidrome streams a fresh
 * conversion with no length and ignores ranges, answering any request from the start. So a
 * conversion opened part-way (a seek, or carrying on after the connection dropped) is
 * asked for from the right second instead (`timeOffset`, which Navidrome supports), and the
 * bytes before the exact place are skipped. The player reads MP3 conversions as constant
 * bit rate, so a place in bytes is a place in time.
 *
 * A conversion also comes with no length, which the player takes for a live stream that
 * can't be sought in at all. So its length is given as what the song's length comes to at
 * that bit rate, rounded up by a second ([conversionLength]); a conversion never runs
 * longer, and one that ends sooner simply ends.
 */
@OptIn(UnstableApi::class)
class SongDataSource(
    private val resolver: SongResolver,
    /** The server, through the kept songs (or straight to the server if none are kept). */
    private val kept: DataSource,
    /** The server, kept out of the kept songs. */
    private val direct: DataSource,
    private val cache: Cache?,
) : DataSource {
    private var current: DataSource? = null
    /** Bytes still to throw away before the place asked for, after the tag at the start. */
    private var toSkip = 0L
    private var tagChecked = true
    /** Bytes that may still be given, when a length was promised; -1 otherwise. */
    private var allowed = -1L

    override fun addTransferListener(transferListener: TransferListener) {
        kept.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val id = songIdOf(dataSpec.uri)
        if (id == null) {
            current = direct
            return direct.open(dataSpec)
        }
        val where = resolver.resolve(id)
        val position = dataSpec.position
        val held = cache?.isCached(where.key, position, 1) == true
        val promised = if (where.form.converted) conversionLength(where.seconds, where.form.bitrate) else C.LENGTH_UNSET.toLong()
        if (!where.form.converted || position == 0L || held) {
            current = kept
            val length = kept.open(dataSpec.buildUpon().setUri(where.stream(0)).setKey(where.key).build())
            if (length != C.LENGTH_UNSET.toLong() || promised == C.LENGTH_UNSET.toLong()) return length
            allowed = (promised - position).coerceAtLeast(0)
            return allowed
        }
        val start = startFor(position, where.form.bitrate)
        if (org.medianexus.music.BuildConfig.DEBUG) {
            android.util.Log.d("NexusPlayback", "conversion of $id opened at byte $position: from second ${start.seconds}, skipping ${start.skip}")
        }
        toSkip = start.skip
        tagChecked = false
        current = direct
        direct.open(DataSpec.Builder().setUri(where.stream(start.seconds)).setHttpRequestHeaders(dataSpec.httpRequestHeaders).build())
        if (promised == C.LENGTH_UNSET.toLong()) return promised
        allowed = (promised - position).coerceAtLeast(0)
        return allowed
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = current ?: throw IOException("Not open")
        if (!tagChecked) {
            tagChecked = true
            toSkip += skipTag(source)
        }
        while (toSkip > 0) {
            val scratch = ByteArray(minOf(toSkip, 16_384L).toInt())
            val got = source.read(scratch, 0, scratch.size)
            if (got == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            toSkip -= got
        }
        if (allowed == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (allowed > 0) minOf(length.toLong(), allowed).toInt() else length
        val got = source.read(buffer, offset, wanted)
        if (got > 0 && allowed > 0) allowed -= got
        return got
    }

    /** Reads past an ID3 tag at the start of a conversion, if it has one, and returns how
     *  many of the bytes read were audio after all (none: a tag is always first). */
    private fun skipTag(source: DataSource): Long {
        val head = ByteArray(10)
        var have = 0
        while (have < head.size) {
            val got = source.read(head, have, head.size - have)
            if (got == C.RESULT_END_OF_INPUT) break
            have += got
        }
        if (have < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
            // No tag: those bytes were audio, and count towards what's skipped. (The player
            // finds the next whole frame by itself.)
            return -have.toLong()
        }
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return id3Size(head) + footer
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
            toSkip = 0
            tagChecked = true
            allowed = -1
        }
    }

    /** Makes one for each read the player starts. */
    class Factory(
        private val resolver: SongResolver,
        private val upstream: DataSource.Factory,
        private val cache: () -> Cache?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val cache = cache()
            val kept = if (cache != null) {
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    // A kept file the system has cleared away is read from the server instead.
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            } else {
                upstream.createDataSource()
            }
            return SongDataSource(resolver, kept, upstream.createDataSource(), cache)
        }
    }

    companion object {
        /** The length in bytes to give for a conversion of a song this many seconds long:
         *  its audio at the bit rate, a second over (the server rounds the length down), and
         *  room for the tag at the start. Unknown (-1) if the song's length isn't. */
        fun conversionLength(seconds: Int, bitrate: Int): Long =
            if (seconds <= 0) C.LENGTH_UNSET.toLong() else (seconds + 1L) * bitrate * 125 + TAG_ROOM

        private const val TAG_ROOM = 4_096L

        /** Where to start a conversion so as to arrive at byte `position` of it: the whole
         *  second before, and how many bytes of audio after that to throw away. */
        fun startFor(position: Long, bitrate: Int): Start {
            val bytesPerSecond = bitrate * 125.0
            val seconds = position / bytesPerSecond
            val whole = floor(seconds).toInt()
            return Start(whole, ((seconds - whole) * bytesPerSecond).toLong())
        }

        /** The size of an ID3v2 tag after its 10-byte header ("syncsafe": 7 bits a byte). */
        fun id3Size(head: ByteArray): Long =
            (6..9).fold(0L) { size, i -> (size shl 7) or (head[i].toLong() and 0x7f) }
    }

    data class Start(val seconds: Int, val skip: Long)
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
