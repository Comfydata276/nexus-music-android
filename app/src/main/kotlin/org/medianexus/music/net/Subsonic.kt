package org.medianexus.music.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/** Offline mode: while it's on, every request fails at once instead of going out. One switch
 *  is shared by every client the app makes, so a new sign-in starts in the same mode. */
class OfflineSwitch(initially: Boolean = false) {
    @Volatile var on: Boolean = initially
}

/**
 * A client for the Subsonic / OpenSubsonic REST API that Navidrome serves at `/rest`: a port
 * of the Windows app's `src/subsonic.rs`, so both apps ask the same things the same way.
 *
 * Every call suspends; the network and the reading of answers happen off the main thread.
 */
class Subsonic private constructor(
    server: String,
    val username: String,
    private val salt: String,
    private val token: String,
    private val http: OkHttpClient,
    private val offline: OfflineSwitch,
    private val io: CoroutineDispatcher,
) {
    constructor(
        server: String,
        username: String,
        password: String,
        http: OkHttpClient,
        offline: OfflineSwitch = OfflineSwitch(),
        salt: String = randomSalt(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(server, username, salt, md5Hex(password + salt), http, offline, io)

    /** The server's address, tidied (see `normalizeServerUrl`). */
    val server: String = normalizeServerUrl(server)

    /** The server's host name, kept out of error messages. */
    private val host: String? = this.server.toHttpUrlOrNull()?.host

    /** Told after each request whether the server answered (true) or couldn't be reached at
     *  all (false): how the app notices it has come back. Not told about offline mode. */
    @Volatile var onReached: ((Boolean) -> Unit)? = null

    /** The same sign-in, under another offline switch. */
    fun sharing(offline: OfflineSwitch): Subsonic = Subsonic(server, username, salt, token, http, offline, io)

    /** In offline mode nothing is sent: requests fail straight away. */
    fun setOffline(on: Boolean) {
        offline.on = on
    }

    private fun checkOnline() {
        if (offline.on) throw ApiError.Network("Offline mode is on.")
    }

    // ------------------------------------------------------------------------------------
    // Addresses
    // ------------------------------------------------------------------------------------

    /** `app` is the name the request gives for this app. */
    private fun url(endpoint: String, params: List<Pair<String, String>>, app: String = CLIENT_NAME): String {
        val url = StringBuilder()
            .append(server).append("/rest/").append(endpoint)
            .append("?u=").append(encode(username))
            .append("&t=").append(token)
            .append("&s=").append(salt)
            .append("&v=").append(API_VERSION)
            .append("&c=").append(encode(app))
            .append("&f=json")
        for ((key, value) in params) url.append('&').append(key).append('=').append(encode(value))
        return url.toString()
    }

    /** The address a song's audio is fetched from. `maxBitrate` of 0 asks for the original
     *  file; anything else has the server transcode to MP3 at that rate. */
    fun streamUrl(id: String, maxBitrate: Int, offsetSecs: Int = 0): String {
        val params = mutableListOf("id" to id)
        if (maxBitrate > 0) {
            params += "format" to "mp3"
            params += "maxBitRate" to maxBitrate.toString()
            if (offsetSecs > 0) params += "timeOffset" to offsetSecs.toString()
        } else {
            params += "format" to "raw"
        }
        return url("stream", params)
    }

    fun coverUrl(id: String, size: Int): String = url("getCoverArt", listOf("id" to id, "size" to size.toString()))

    // ------------------------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------------------------

    /**
     * Sends a request and reads `field` of the `subsonic-response` object with `serializer`,
     * once the server has said it's "ok"; `empty` if the answer leaves it out. With no
     * `field`, only whether it's "ok".
     *
     * The answer is read straight from the stream into the app's own models, as it arrives:
     * never by way of the whole answer as text, nor as a tree of JSON first. (A 12 MB
     * playlist as a tree took 80 to 100 MB more of the app's memory while it was read.) What
     * Windows makes of an answer still holds: an absent section is empty, one sent as null is
     * malformed, and anything the app doesn't know is passed over.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun <T> request(
        endpoint: String,
        params: List<Pair<String, String>>,
        field: String?,
        serializer: KSerializer<T>?,
        app: String = CLIENT_NAME,
    ): Answer<T> {
        val url = url(endpoint, params, app)
        // A long list of song ids can outgrow what servers and proxies accept in an address,
        // so those go in the body of a POST instead (the OpenSubsonic "formPost" extension).
        val question = url.indexOf('?')
        val request = if (url.length > FORM_POST_OVER && question >= 0) {
            buildRequest(url.substring(0, question)) {
                post(url.substring(question + 1).toByteArray().toRequestBody(FORM))
            }
        } else {
            buildRequest(url) { get() }
        }
        // Read and decoded together, off the caller's thread: an answer can be megabytes.
        val (status, answer) = send(request, Http.API_TIMEOUT_SECONDS * 1000) { response ->
            val reading = Answer<T>()
            try {
                response.body.byteStream().use { json.decodeFromStream(Envelope(field, serializer, reading), it) }
            } catch (e: IllegalArgumentException) {
                // Inside the section asked for: the server's answer is malformed. The
                // decoder's message quotes what the server sent; only the place it went
                // wrong (made of this app's own field names) is passed on.
                if (reading.inField) throw unexpected(endpoint, field, e)
                reading.readable = false
            }
            response.code to reading
        }
        if (!answer.readable || !answer.subsonic) throw ApiError.NotSubsonic("Check the address (HTTP status $status).")
        if ((answer.status as? JsonPrimitive)?.takeIf { it.isString }?.content != "ok") {
            val error = answer.error as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0
            val message = (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "unknown error"
            throw ApiError.Server(code, safeServerMessage(message))
        }
        if (answer.sentNull) throw ApiError.NotSubsonic("Unexpected response from $endpoint (at $).")
        return answer
    }

    /** Sends a request whose answer is only whether it was carried out. */
    private suspend fun request(endpoint: String, params: List<Pair<String, String>>, app: String = CLIENT_NAME) {
        request<Unit>(endpoint, params, field = null, serializer = null, app = app)
    }

    private fun unexpected(endpoint: String, field: String?, e: IllegalArgumentException): ApiError.NotSubsonic {
        val at = e.message?.let { PATH.find(it)?.groupValues?.get(1) }?.let { path ->
            // As the place inside the section: "$.songCount", not where the section sits.
            val section = "$.subsonic-response.$field"
            if (path.startsWith(section)) "$" + path.removePrefix(section) else path
        }
        return ApiError.NotSubsonic("Unexpected response from $endpoint${if (at != null) " (at $at)" else ""}.")
    }

    /** Asks for `endpoint` and reads `field` of the answer. An empty library section is
     *  simply left out of the answer, so a missing field is `empty`. One sent as null is a
     *  malformed answer, as Windows takes it, not an empty one. */
    private suspend fun <T> call(
        endpoint: String,
        params: List<Pair<String, String>>,
        field: String,
        serializer: KSerializer<T>,
        empty: () -> T,
    ): T {
        val answer = request(endpoint, params, field, serializer)
        @Suppress("UNCHECKED_CAST")
        return if (answer.present) answer.value as T else empty()
    }

    /** What the server said went wrong, unless it could give away the sign-in (the
     *  username counts: it is half of one), the address or a chunk of the request (some
     *  servers echo it back): then nothing. */
    private fun safeServerMessage(text: String): String {
        val giveaways = listOfNotNull(token, salt, host, username).filter { it.isNotEmpty() }
        val risky = text.length > 200 || "://" in text || '{' in text || '[' in text ||
            giveaways.any { text.contains(it, ignoreCase = true) }
        return if (risky) "" else text
    }

    private inline fun buildRequest(url: String, method: Request.Builder.() -> Request.Builder): Request = try {
        Request.Builder().url(url).method().build()
    } catch (_: IllegalArgumentException) {
        // OkHttp's message quotes the address, token and all.
        throw ApiError.Network("The server's address isn't one the app can use. Check it.")
    }

    /** Sends a request, giving up after `limitMillis` overall (none if null), and returns
     *  what `read` makes of the answer; `read` runs off the caller's thread. */
    private suspend fun <T> send(request: Request, limitMillis: Long?, read: (Response) -> T): T {
        checkOnline()
        val call = http.newCall(request)
        if (limitMillis != null) call.timeout().timeout(limitMillis, TimeUnit.MILLISECONDS)
        return try {
            call.fetch(io, read).also { onReached?.invoke(true) }
        } catch (e: IOException) {
            throw failed(e)
        }
    }

    /** A request that failed on its way: said to whoever follows whether the server answers
     *  (a secure connection it turned down means it did), and made the error to throw. */
    private fun failed(e: IOException): ApiError.Network {
        val answered = answeredOverTls(e)
        onReached?.invoke(answered)
        return ApiError.Network(networkHint(e), answered)
    }

    /** Opens connections ahead of need, so the first taps don't pay for setting them up. */
    suspend fun warmUp(connections: Int) = coroutineScope {
        repeat(connections) { launch { runCatching { ping() } } }
    }

    /** Times a request on a brand-new connection and one on a reused connection, in
     *  milliseconds. The gap between them is what connection reuse saves. */
    suspend fun measure(): Pair<Long, Long> {
        // The same sign-in, on a client with no connections open yet.
        val fresh = Subsonic(server, username, salt, token, http.newBuilder().connectionPool(ConnectionPool()).build(), OfflineSwitch(), io)
        var started = System.nanoTime()
        fresh.ping()
        val cold = (System.nanoTime() - started) / 1_000_000
        started = System.nanoTime()
        fresh.ping()
        return cold to (System.nanoTime() - started) / 1_000_000
    }

    /** Whether anything at all answers at this address within `limitMillis`. For trying an
     *  address that's only a guess without waiting long on it. */
    suspend fun answersWithin(limitMillis: Long): Boolean = try {
        // Read to the end, so the connection can be used again.
        send(buildRequest(url("ping", emptyList())) { get() }, limitMillis) { it.body.source().readAll(okio.blackholeSink()) }
        true
    } catch (_: ApiError) {
        false
    }

    // ------------------------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------------------------

    suspend fun ping(): ServerInfo {
        val answer = request("ping", emptyList(), "serverVersion", JsonElement.serializer())
        val version = (answer.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return ServerInfo(version)
    }

    suspend fun albumList(list: AlbumList, size: Int, offset: Int): List<Album> {
        val params = mutableListOf("size" to size.toString(), "offset" to offset.toString())
        val kind = when (list) {
            AlbumList.Newest -> "newest"
            AlbumList.Recent -> "recent"
            AlbumList.Frequent -> "frequent"
            AlbumList.Random -> "random"
            AlbumList.Starred -> "starred"
            AlbumList.AlphabeticalByName -> "alphabeticalByName"
            AlbumList.AlphabeticalByArtist -> "alphabeticalByArtist"
            AlbumList.ByYearDescending -> {
                params += "fromYear" to "9999"
                params += "toYear" to "0"
                "byYear"
            }
            is AlbumList.ByGenre -> {
                params += "genre" to list.genre
                "byGenre"
            }
        }
        params += "type" to kind
        return call("getAlbumList2", params, "albumList2", AlbumsWrap.serializer(), ::AlbumsWrap).album
    }

    suspend fun album(id: String): Album = call("getAlbum", listOf("id" to id), "album", Album.serializer(), ::Album)

    suspend fun artists(): List<Artist> =
        call("getArtists", emptyList(), "artists", IndexesWrap.serializer(), ::IndexesWrap).index.flatMap { it.artist }

    suspend fun artist(id: String): Artist = call("getArtist", listOf("id" to id), "artist", Artist.serializer(), ::Artist)

    suspend fun artistInfo(id: String): ArtistInfo =
        call("getArtistInfo2", listOf("id" to id, "count" to "12"), "artistInfo2", ArtistInfo.serializer(), ::ArtistInfo)

    /** The names of artists like this one that the library doesn't have. The server asks
     *  whatever it's been set up with (Last.fm, Deezer), so this can take seconds, and comes
     *  back empty if it has nowhere to ask. */
    suspend fun similarOutside(id: String): List<String> {
        val params = listOf("id" to id, "count" to "20", "includeNotPresent" to "true")
        val info = call("getArtistInfo2", params, "artistInfo2", ArtistInfo.serializer(), ::ArtistInfo)
        // One that isn't in the library has no id of its own: none, or "-1".
        return info.similar.filter { it.id.isEmpty() || it.id.startsWith('-') }.map { it.name }.filter { it.isNotEmpty() }
    }

    suspend fun topSongs(artistName: String, count: Int): List<Song> =
        call("getTopSongs", listOf("artist" to artistName, "count" to count.toString()), "topSongs", SongsWrap.serializer(), ::SongsWrap).song

    suspend fun similarSongs(id: String, count: Int): List<Song> =
        call("getSimilarSongs2", listOf("id" to id, "count" to count.toString()), "similarSongs2", SongsWrap.serializer(), ::SongsWrap).song

    /** Random songs, optionally from one genre. */
    suspend fun randomSongs(count: Int, genre: String? = null): List<Song> {
        val params = mutableListOf("size" to count.toString())
        if (!genre.isNullOrEmpty()) params += "genre" to genre
        return call("getRandomSongs", params, "randomSongs", SongsWrap.serializer(), ::SongsWrap).song
    }

    suspend fun genres(): List<Genre> = call("getGenres", emptyList(), "genres", GenresWrap.serializer(), ::GenresWrap).genre

    suspend fun playlists(): List<Playlist> =
        call("getPlaylists", emptyList(), "playlists", PlaylistsWrap.serializer(), ::PlaylistsWrap).playlist

    suspend fun playlist(id: String): Playlist = call("getPlaylist", listOf("id" to id), "playlist", Playlist.serializer(), ::Playlist)

    suspend fun createPlaylist(name: String, songIds: List<String>): Playlist {
        val params = listOf("name" to name) + songIds.map { "songId" to it }
        return call("createPlaylist", params, "playlist", Playlist.serializer(), ::Playlist)
    }

    /** Replaces a playlist's comment, and keeps the playlist private. */
    suspend fun setPlaylistComment(id: String, comment: String) {
        request("updatePlaylist", listOf("playlistId" to id, "comment" to comment, "public" to "false"))
    }

    suspend fun song(id: String): Song = call("getSong", listOf("id" to id), "song", Song.serializer(), ::Song)

    /** Saves the play queue on the server, where other devices can pick it up. `device` is
     *  recorded as who saved it. `position` is in milliseconds. */
    suspend fun savePlayQueue(device: String, ids: List<String>, current: String, position: Long) {
        val params = ids.map { "id" to it } + listOf("current" to current, "position" to position.toString())
        request("savePlayQueue", params, app = device)
    }

    /** The play queue last saved on the server; empty if there isn't one. */
    suspend fun playQueue(): PlayQueue = call("getPlayQueue", emptyList(), "playQueue", PlayQueue.serializer(), ::PlayQueue)

    suspend fun renamePlaylist(id: String, name: String) {
        request("updatePlaylist", listOf("playlistId" to id, "name" to name))
    }

    suspend fun addToPlaylist(id: String, songIds: List<String>) {
        request("updatePlaylist", listOf("playlistId" to id) + songIds.map { "songIdToAdd" to it })
    }

    /** Replaces a playlist's songs with `songIds`, in that order. */
    suspend fun replacePlaylist(id: String, songIds: List<String>) {
        request("createPlaylist", listOf("playlistId" to id) + songIds.map { "songId" to it })
    }

    /** Removes the songs at these positions from a playlist, in one request. */
    suspend fun removeManyFromPlaylist(id: String, positions: List<Int>) {
        request("updatePlaylist", listOf("playlistId" to id) + positions.map { "songIndexToRemove" to it.toString() })
    }

    suspend fun deletePlaylist(id: String) {
        request("deletePlaylist", listOf("id" to id))
    }

    suspend fun starred(): Found = call("getStarred2", emptyList(), "starred2", Found.serializer(), ::Found)

    /** Likes or unlikes a song, album or artist. */
    suspend fun setStarred(target: StarTarget, id: String, starred: Boolean) {
        request(if (starred) "star" else "unstar", listOf(target.param to id))
    }

    /** Records that a song was played at an earlier time (milliseconds since 1970). */
    suspend fun scrobbleAt(id: String, at: Long) {
        request("scrobble", listOf("id" to id, "submission" to "true", "time" to at.toString()))
    }

    /** Rates a song from 1 to 5 stars; 0 removes the rating. */
    suspend fun setRating(id: String, rating: Int) {
        request("setRating", listOf("id" to id, "rating" to rating.coerceIn(0, 5).toString()))
    }

    /** The internet radio stations set up on the server, as playable entries. */
    suspend fun radioStations(): List<Song> {
        val stations = call("getInternetRadioStations", emptyList(), "internetRadioStations", Stations.serializer(), ::Stations)
        return stations.internetRadioStation.filter { it.streamUrl.isNotEmpty() }.map { station ->
            // Shown where a song's artist would be: the station's site, without the clutter.
            val site = station.homePageUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
            Song(
                id = "radio:${station.id}",
                title = station.name,
                artist = site.ifEmpty { "Internet radio" },
                album = "Internet radio",
                radioUrl = station.streamUrl,
                suffix = "mp3",
            )
        }
    }

    suspend fun search(query: String, artists: Int, albums: Int, songs: Int): Found {
        val params = listOf(
            "query" to query,
            "artistCount" to artists.toString(),
            "albumCount" to albums.toString(),
            "songCount" to songs.toString(),
        )
        return call("search3", params, "searchResult3", Found.serializer(), ::Found)
    }

    /** One page of everything in the library: up to this many artists, albums and songs,
     *  starting `offset` in to each. */
    suspend fun libraryPage(artists: Int, albums: Int, songs: Int, offset: Int): Found {
        val o = offset.toString()
        val params = listOf(
            "query" to "",
            "artistCount" to artists.toString(),
            "albumCount" to albums.toString(),
            "songCount" to songs.toString(),
            "artistOffset" to o,
            "albumOffset" to o,
            "songOffset" to o,
        )
        return call("search3", params, "searchResult3", Found.serializer(), ::Found)
    }

    /** How many songs the server has, and when it last looked through its folders. */
    suspend fun scanStatus(): ScanStatus = call("getScanStatus", emptyList(), "scanStatus", ScanStatus.serializer(), ::ScanStatus)

    suspend fun scrobble(id: String, submission: Boolean) {
        request("scrobble", listOf("id" to id, "submission" to submission.toString()))
    }

    suspend fun lyrics(songId: String): Lyrics? {
        val wrap = call("getLyricsBySongId", listOf("id" to songId), "lyricsList", LyricsWrap.serializer(), ::LyricsWrap)
        // Prefer a synced set when the file carries both.
        return wrap.structuredLyrics
            .map { set -> Lyrics(set.synced, shifted(set.line, set.offset)) }
            .sortedBy { !it.synced }
            .firstOrNull { it.line.isNotEmpty() }
    }

    /** Fetches a small binary resource such as cover art, of at most `limit` bytes. */
    suspend fun fetchBytes(url: String, limit: Long): ByteArray {
        checkOnline()
        val call = http.newCall(buildRequest(url) { get() })
        call.timeout().timeout(Http.API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return try {
            call.fetch(io) {
                if (!it.isSuccessful) throw ApiError.Network("HTTP status ${it.code}.")
                val source = it.body.source()
                if (source.request(limit + 1)) throw ApiError.Network("(The answer was larger than $limit bytes.)")
                source.buffer.readByteArray()
            }.also { onReached?.invoke(true) }
        } catch (e: IOException) {
            throw failed(e)
        }
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "NexusMusic"

        /** Where a decoding error happened, as kotlinx.serialization puts it: `$.song[0].duration`. */
        private val PATH = Regex("""at path: (\$[A-Za-z0-9_.\[\]-]*)""")

        /** Requests with addresses longer than this go as a form POST. */
        internal const val FORM_POST_OVER = 1800

        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        /** Reads the server's answers as Windows does (serde with `#[serde(default)]`):
         *  fields it adds are ignored and fields it leaves out are empty, but a null where the
         *  model has no room for one (a list, a name) makes the answer malformed. Only the
         *  fields that may be absent in their own right (`starred`, `replayGain`, a lyric
         *  line's `start`) take null. */
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private val random = SecureRandom()
        private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun randomSalt(): String = buildString { repeat(16) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) } }

        internal fun md5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * What went wrong reaching the server, in words a person can act on: Windows' `network_hint`
 * for each kind of failure it names, and plain words of the same kind for the rest, where
 * Windows quotes the underlying error. The phone never does: the system's own text can name
 * the server's address, and a class name means nothing to anyone. OkHttp wraps the system's
 * error (a refused connection inside "Failed to connect"), so the causes and the attempts it
 * gave up on are read too.
 */
internal fun networkHint(error: IOException): String = read(error).first

/**
 * Whether a failure was the secure connection with a server that answered: its certificate
 * turned down, or TLS that failed once it had answered. The server is there, so the failure
 * is not "can't reach it", and isn't taken for a sign the local-network permission is missing.
 */
internal fun answeredOverTls(error: IOException): Boolean = read(error).second

/** What a failure says, in words, and whether it was the secure connection with a server that
 *  answered (the certificate and TLS cases below, unless something earlier came first). */
private fun read(error: IOException): Pair<String, Boolean> {
    // Every cause, and every attempt given up on with its own causes, a few dozen at most.
    val all = mutableListOf<Throwable>()
    val waiting = ArrayDeque<Throwable>().apply { add(error) }
    while (waiting.isNotEmpty() && all.size < 32) {
        val next = waiting.removeFirst()
        if (all.any { it === next }) continue
        all += next
        next.cause?.let { waiting += it }
        waiting += next.suppressed
    }
    val lower = all.joinToString(" ") { it.message.orEmpty() }.lowercase()
    fun any(kind: Class<*>) = all.any { kind.isInstance(it) }
    return when {
        any(InsecureRedirect::class.java) -> all.first { it is InsecureRedirect }.message!! to false
        // First among the rest: a server whose certificate was turned down was reached, even
        // if another of its addresses then refused (OkHttp tries them all). The system's own
        // words name the host and quote the certificate, so they aren't passed on.
        any(SSLPeerUnverifiedException::class.java) ->
            "The server's certificate is for a different address. Check the address." to true
        // Android says "trust anchor … not found"; the desktop JVM the tests run on says
        // "unable to find valid certification path".
        any(CertificateException::class.java) || any(CertPathValidatorException::class.java) ||
            any(CertPathBuilderException::class.java) || "trust anchor" in lower || "certification path" in lower ->
            "The server's certificate isn't one this phone trusts." to true
        any(UnknownHostException::class.java) || "dns" in lower || "resolve" in lower || "lookup" in lower ->
            "The server name couldn't be found. Check the address." to false
        "refused" in lower ->
            "The connection was refused. Check the port and that Navidrome is running." to false
        any(SocketTimeoutException::class.java) || "timeout" in lower || "timed out" in lower ->
            "The connection timed out. Check the address and your network." to false
        any(SSLException::class.java) || "certificate" in lower || "tls" in lower ->
            "The secure connection failed." to true
        // The phone itself wouldn't let the app connect (a permission, a firewall).
        "permission denied" in lower || "eperm" in lower || "eacces" in lower || "operation not permitted" in lower ->
            "The phone didn't allow the connection." to false
        // No way there from the network the phone is on.
        "unreachable" in lower || "no route to host" in lower || "enetunreach" in lower || "ehostunreach" in lower ->
            "There's no way to the server from this network. Check your network." to false
        // Cut off part-way ("unexpected end of stream", a reset): Windows' words for it.
        "end of stream" in lower || "reset" in lower || "broken pipe" in lower || "eof" in lower || "closed" in lower ||
            any(java.net.ProtocolException::class.java) ->
            "The connection dropped." to false
        // Couldn't connect, for no reason the system gave.
        any(java.net.ConnectException::class.java) || "failed to connect" in lower ->
            "Couldn't connect. Check the address and your network." to false
        else -> "The connection failed." to false
    }
}

/** What was read of one answer, as it was read. */
private class Answer<T> {
    /** It was JSON at all, and it had a `subsonic-response`. */
    var readable = true
    var subsonic = false
    var status: JsonElement? = null
    var error: JsonElement? = null
    /** The section asked for was there, and what it held; or it was there as null. */
    var present = false
    var value: T? = null
    var sentNull = false
    /** The section is being read now: what goes wrong is the section's fault. */
    var inField = false
}

/**
 * Reads `{"subsonic-response": {"status": …, "error": …, <field>: …}}` as it arrives, into
 * `answer`, with `inner` for the field and everything else passed over. One is made for each
 * answer.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private class Envelope<T>(private val field: String?, private val inner: KSerializer<T>?, private val answer: Answer<T>) :
    kotlinx.serialization.DeserializationStrategy<Answer<T>> {
    private val body = kotlinx.serialization.descriptors.buildClassSerialDescriptor("SubsonicResponse/${field.orEmpty()}") {
        element("status", JsonElement.serializer().descriptor, isOptional = true)
        element("error", JsonElement.serializer().descriptor, isOptional = true)
        if (field != null && inner != null) element(field, inner.descriptor.nullable, isOptional = true)
    }

    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("Subsonic/${field.orEmpty()}") {
        element("subsonic-response", body, isOptional = true)
    }

    private val bodyReader = object : kotlinx.serialization.DeserializationStrategy<Unit> {
        override val descriptor = body

        override fun deserialize(decoder: Decoder) {
            val reading = decoder.beginStructure(body)
            while (true) {
                when (val index = reading.decodeElementIndex(body)) {
                    kotlinx.serialization.encoding.CompositeDecoder.DECODE_DONE -> break
                    0 -> answer.status = reading.decodeSerializableElement(body, 0, JsonElement.serializer())
                    1 -> answer.error = reading.decodeSerializableElement(body, 1, JsonElement.serializer())
                    2 -> {
                        answer.inField = true
                        @Suppress("UNCHECKED_CAST")
                        val value = reading.decodeNullableSerializableElement(body, 2, (inner as KSerializer<Any>).nullable)
                        answer.inField = false
                        @Suppress("UNCHECKED_CAST")
                        if (value == null) answer.sentNull = true else answer.value = value as T
                        answer.present = value != null
                    }
                    else -> throw kotlinx.serialization.SerializationException("Unexpected index $index")
                }
            }
            reading.endStructure(body)
        }
    }

    override fun deserialize(decoder: Decoder): Answer<T> {
        val reading = decoder.beginStructure(descriptor)
        while (true) {
            when (reading.decodeElementIndex(descriptor)) {
                kotlinx.serialization.encoding.CompositeDecoder.DECODE_DONE -> break
                0 -> {
                    answer.subsonic = true
                    reading.decodeSerializableElement(descriptor, 0, bodyReader)
                }
                else -> throw kotlinx.serialization.SerializationException("Unexpected element")
            }
        }
        reading.endStructure(descriptor)
        return answer
    }
}

// The wrappers the server puts lists in.

@Serializable private class AlbumsWrap(val album: List<Album> = emptyList())
@Serializable private class SongsWrap(val song: List<Song> = emptyList())
@Serializable private class GenresWrap(val genre: List<Genre> = emptyList())
@Serializable private class PlaylistsWrap(val playlist: List<Playlist> = emptyList())
@Serializable private class Index(val artist: List<Artist> = emptyList())
@Serializable private class IndexesWrap(val index: List<Index> = emptyList())

@Serializable
private class Station(
    val id: String = "",
    val name: String = "",
    val streamUrl: String = "",
    val homePageUrl: String = "",
)

@Serializable private class Stations(val internetRadioStation: List<Station> = emptyList())

/** A set of lyrics as the server sends it, with the shift a lyrics file can ask for. */
@Serializable
private class LyricsSet(
    val synced: Boolean = false,
    val line: List<LyricLine> = emptyList(),
    /** In milliseconds: positive shows every line that much sooner. */
    val offset: Long = 0,
)

@Serializable private class LyricsWrap(val structuredLyrics: List<LyricsSet> = emptyList())
