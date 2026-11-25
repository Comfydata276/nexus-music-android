package org.medianexus.music.ui.parts

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import org.medianexus.music.Accounts

/**
 * A cover that is a **web address**, chosen by Lidarr or the Cover Art Archive: the picture of
 * an album or artist that isn't in the library. (The music server's covers are [CoverArt]s,
 * by their id, and never come this way.)
 */
data class WebCover(val url: String)

/**
 * Where a web cover may be fetched from. The address is chosen by a server (Lidarr, or
 * MusicBrainz through it), so a hostile or broken one can point anywhere: at the music
 * server, at the phone itself, at something on the home network. The rule:
 *
 * - only `https://` addresses, or one on exactly the scheme, host and port the person typed
 *   for Lidarr (its own pictures, wherever it is);
 * - never a loopback, link-local or private address, unless it is that Lidarr's own: an
 *   address written as a number is judged here, a name by what it turns out to be
 *   ([publicOnly], when it is looked up);
 * - never the music server's origin (its scheme, host and port), whatever else is true;
 * - each redirect is judged the same way, and there are at most [MAX_HOPS] of them.
 *
 * A cover the rule turns away is simply the placeholder.
 */
object CoverRule {
    const val MAX_HOPS = 5

    /** Whether `url` may be asked for. `lidarr` is Lidarr's address as the person typed it,
     *  `music` the music server's; either may be null. */
    fun allows(url: HttpUrl, lidarr: HttpUrl?, music: HttpUrl?): Boolean {
        if (music != null && sameOrigin(url, music)) return false
        if (lidarrOwn(url, lidarr)) return true
        if (url.scheme != "https") return false
        // A name is judged when it is looked up; a number now (OkHttp never looks one up).
        val literal = literalAddress(url.host) ?: return true
        return publicAddress(literal)
    }

    /** It is on exactly the scheme, host and port the person typed for Lidarr. */
    fun lidarrOwn(url: HttpUrl, lidarr: HttpUrl?): Boolean = lidarr != null && sameOrigin(url, lidarr)

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    /** `host` as an address, if it is written as one (HttpUrl keeps an IPv6 one without its
     *  brackets); null for a name. Never looks a name up. */
    fun literalAddress(host: String): InetAddress? {
        val numeric = host.contains(':') || IPV4.matches(host)
        if (!numeric) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    /** Somewhere out on the internet: not this phone, not the network it is on, nothing
     *  kept for special use. */
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0].toInt() and 0xff
                val b = bytes[1].toInt() and 0xff
                when {
                    a == 0 || a >= 240 -> false
                    // Shared by carriers, and by Tailscale for a person's own machines.
                    a == 100 && b in 64..127 -> false
                    a == 192 && b == 0 && (bytes[2].toInt() and 0xff) == 0 -> false
                    a == 198 && b in 18..19 -> false
                    else -> true
                }
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xff
                when {
                    // Unique local (fc00::/7): a home network's own.
                    first and 0xfe == 0xfc -> false
                    // An IPv4 address written as IPv6 (::ffff:a.b.c.d, and the old ::a.b.c.d).
                    bytes.copyOfRange(0, 10).all { it.toInt() == 0 } -> {
                        val tail = bytes.copyOfRange(12, 16)
                        tail.all { it.toInt() == 0 }.not() && publicAddress(InetAddress.getByAddress(tail))
                    }
                    else -> true
                }
            }
            else -> false
        }
    }

    /** Looks a name up, and refuses it if any address it has is not a public one: a name
     *  can be made to stand for the home network as easily as a number. */
    fun publicOnly(lookup: Dns = Dns.SYSTEM): Dns = Dns { host ->
        val all = lookup.lookup(host)
        if (all.isEmpty() || !all.all(::publicAddress)) throw UnknownHostException("not an address a cover is fetched from")
        all
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/**
 * The loader for web covers: **a client of its own**, which carries nothing of the music
 * server's (no sign-in, no cookies, no interceptors, none of its connections), goes through
 * no proxy (a proxy would look the name up where [CoverRule] can't see it), follows no
 * redirect by itself ([fetch] judges each), and reads [LIMIT] bytes at most. What it fetches
 * is kept in a small disk cache of its own ([CACHE_BYTES], least used first out), so a page
 * seen again shows its covers at once.
 */
class WebCovers(
    dir: File,
    /** Looks a name up (a test's own). */
    lookup: Dns = Dns.SYSTEM,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(FETCHES_AT_ONCE),
    cacheBytes: Long = CACHE_BYTES,
) {
    private val pool = ConnectionPool(2, 30, TimeUnit.SECONDS)

    private fun client(dns: Dns) = OkHttpClient.Builder()
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .connectionPool(pool)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** For anywhere out on the internet. */
    private val outward by lazy { client(CoverRule.publicOnly(lookup)) }

    /** For Lidarr's own address, which may well be on the home network. */
    private val lidarrs by lazy { client(lookup) }

    private val cache: DiskCache by lazy {
        DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(cacheBytes).cleanupDispatcher(Dispatchers.IO).build()
    }

    /**
     * The picture at `address` as a file in the cache, fetching it first if it isn't there;
     * null if [CoverRule] turns it (or a redirect of it) away, or it can't be had. The
     * snapshot is the caller's to close.
     */
    suspend fun fetch(address: String, lidarr: String?, music: String?): DiskCache.Snapshot? = withContext(io) {
        val key = keyOf(address)
        cache.openSnapshot(key)?.let { return@withContext it }
        val lidarrAt = lidarr?.trim()?.let(::addressOf)
        val musicAt = music?.trim()?.let(::addressOf)
        var at = addressOf(address) ?: return@withContext null
        for (hop in 0..CoverRule.MAX_HOPS) {
            if (!CoverRule.allows(at, lidarrAt, musicAt)) return@withContext null
            val client = if (CoverRule.lidarrOwn(at, lidarrAt)) lidarrs else outward
            val request = Request.Builder().url(at).header("User-Agent", NAME).header("Accept", "image/*").build()
            val response = try {
                client.newCall(request).await()
            } catch (_: IOException) {
                return@withContext null
            }
            response.use {
                if (response.isRedirect) {
                    at = response.header("Location")?.let { at.resolve(it) } ?: return@withContext null
                    return@use
                }
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                if (body.contentLength() > LIMIT) return@withContext null
                return@withContext keep(key, body.source())
            }
        }
        // (More redirects than are followed.)
        null
    }

    private fun keep(key: String, from: okio.BufferedSource): DiskCache.Snapshot? {
        val editor = cache.openEditor(key) ?: return null
        try {
            var total = 0L
            FileSystem.SYSTEM.sink(editor.data).buffer().use { into ->
                val chunk = okio.Buffer()
                while (true) {
                    val read = from.read(chunk, 8192)
                    if (read < 0) break
                    total += read
                    if (total > LIMIT) throw IOException("too large")
                    into.write(chunk, read)
                }
            }
            return editor.commitAndOpenSnapshot()
        } catch (_: IOException) {
            editor.abort()
            return null
        }
    }

    companion object {
        /** The most a cover may be: the largest Lidarr hands on are posters of a megabyte
         *  or two. Anything bigger is given up as it arrives, and nothing of it is kept. */
        const val LIMIT = 4L * 1024 * 1024

        /** What the cache keeps at most on the phone. */
        const val CACHE_BYTES = 24L * 1024 * 1024

        /** Covers fetched at once: of their own, so a slow site never holds up the music
         *  server's covers. */
        const val FETCHES_AT_ONCE = 4

        private val NAME = "NexusMusic/${org.medianexus.music.BuildConfig.VERSION_NAME} (Android)"

        private fun addressOf(text: String): HttpUrl? = text.toHttpUrlOrNull()

        private fun keyOf(address: String): String =
            MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A call, waited for without holding a thread, and cut off if whoever waits goes. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { going ->
    going.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            going.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            going.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Coil's way to a [WebCover]: through [WebCovers], with Lidarr's address and the music
 *  server's as the signed-in account has them now. */
internal class WebCoverFetcher(private val cover: WebCover, private val covers: WebCovers, private val accounts: Accounts) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val account = accounts.state.value
        val snapshot = covers.fetch(cover.url, account?.requests?.settings?.value?.address, account?.account?.server)
            // Coil shows nothing for a failure, and the cover's stand-in stays.
            ?: throw IOException("No cover")
        return SourceFetchResult(ImageSource(snapshot.data, FileSystem.SYSTEM, null, snapshot, null), null, DataSource.NETWORK)
    }

    class Factory(private val covers: WebCovers, private val accounts: Accounts) : Fetcher.Factory<WebCover> {
        override fun create(data: WebCover, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.url.isEmpty()) null else WebCoverFetcher(data, covers, accounts)
    }
}

/** Held in memory by address and the width it was decoded at (see `CoverKeyer`). */
internal object WebCoverKeyer : Keyer<WebCover> {
    override fun key(data: WebCover, options: Options): String {
        val width = (options.size.width as? coil3.size.Dimension.Pixels)?.px ?: 0
        return "web/${data.url}/$width"
    }
}
