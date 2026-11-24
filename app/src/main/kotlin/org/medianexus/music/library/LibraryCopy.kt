package org.medianexus.music.library

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.medianexus.music.net.Album
import org.medianexus.music.net.Artist
import org.medianexus.music.net.Found
import org.medianexus.music.net.ScanStatus
import org.medianexus.music.store.Packer
import org.medianexus.music.store.SongTable
import org.medianexus.music.store.Unpacker
import org.medianexus.music.store.writeAtomically

/** What the server said of its library when a copy was fetched, and when that was (seconds
 *  since 1970): Windows' `Library` `count`, `last_scan` and `fetched`. */
data class CopyStamp(val count: Long, val lastScan: String, val fetched: Long)

/**
 * The library as the phone has a copy of it, at one moment: every song ([SongTable]), album
 * and artist, in the order the server listed them. `complete` is false while the first copy
 * is still arriving: then it is what has arrived so far. Immutable; a newer copy replaces it
 * whole.
 */
class LibrarySnapshot(
    val songs: SongTable,
    val albums: List<Album>,
    val artists: List<Artist>,
    val stamp: CopyStamp,
    val complete: Boolean,
) {
    /** Made ready for searching (see [SearchIndex]), the first time it is wanted. */
    val index: SearchIndex by lazy {
        SearchIndex.Builder().apply {
            for (i in 0 until songs.size) song(songs.title(i), songs.artistLabel(i), songs.album(i))
            for (album in albums) album(album.name, album.artistLabel)
            for (artist in artists) artist(artist.name)
        }.build()
    }

    /**
     * Every song, the newest arrivals first: the Songs page's own order, as Windows'
     * `all_songs` sorts it. The album and then the track settle ties (a whole album arrives
     * at once). As places in [songs]; worked out the first time it is wanted.
     */
    val newestFirst: IntArray by lazy {
        val order = (0 until songs.size).toMutableList()
        order.sortWith(compareByDescending<Int> { songs.createdMillis(it) }.thenBy { songs.album(it) }.thenBy { songs.track(it) })
        order.toIntArray()
    }

    /** What matches `query`, best first, as Windows' search finds it; null if `cancelled`
     *  said to stop part-way. */
    fun find(query: String, limits: SearchLimits, liked: (String) -> Boolean, cancelled: () -> Boolean = { false }): Found? {
        val hits = index.search(query, { liked(songs.id(it)) }, limits.artists, limits.albums, limits.songs, cancelled = cancelled) ?: return null
        return Found(
            artists = hits.artists.map { artists[it] },
            albums = hits.albums.map { albums[it] },
            songs = hits.songs.map { songs.song(it) },
            artistFirst = hits.artistFirst,
        )
    }

    companion object {
        val EMPTY = LibrarySnapshot(SongTable.EMPTY, emptyList(), emptyList(), CopyStamp(0, "", 0), complete = false)
    }
}

/** The server, as far as the copy of the library needs it. */
interface LibraryServer {
    suspend fun scanStatus(): ScanStatus

    /** Up to this many of each kind, from `offset` on in each (`search3` with no words). */
    suspend fun page(artists: Int, albums: Int, songs: Int, offset: Int): Found
}

/** The first copy of the library is on its way, and how much of it has arrived. */
data class Fetching(val songs: Int, val first: Boolean)

/**
 * The account's copy of its library on the phone: every artist, album and song, fetched in
 * the background in pages as Windows fetches it (`Library::fetch`: 500 of each at a time),
 * kept in the account's own cache folder, and fetched again when the server says its library
 * has changed (its scan status, as Windows watches it). What it is for: search that forgives
 * typing mistakes and answers at once (`PhoneSearch`), and the Songs page.
 *
 * - **Ready while still loading.** The first time, what has arrived is published as it
 *   arrives (`complete` false), so the Songs page fills as it comes. Search asks the server
 *   until the first copy is whole, as Windows does: the server has everything meanwhile.
 * - **Survives being killed mid-fetch.** Each page is written to a file of its own as it
 *   arrives, whole or not at all, beside a note of what the server said its library was
 *   when the fetch began. Started again with the server saying the same, the fetch carries
 *   on from the first page it hasn't got; with anything else, it starts again from nothing.
 *   Once every page is in, the copy is written as one file (whole or not at all) and the
 *   pages go. Everything read back is checked (a checksum, and every count and reference
 *   in range); a file that doesn't pass is as good as not there, and is fetched again.
 * - **A newer copy** is fetched beside the one in use, which stays until the new one is
 *   whole.
 * - **One fetch at a time**, and none at all while the server is still scanning (half a
 *   library is no use).
 *
 * Belongs to one account: made with it, and its work is stopped with it (what was written
 * stays, for the next time that account signs in).
 */
class LibraryCopy(
    private val dir: File,
    private val server: LibraryServer,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Whether the phone is on a network that isn't metered: the daily refresh waits for one. */
    private val unmetered: () -> Boolean = { true },
    private val note: (String) -> Unit = {},
    private val pageSize: Int = PAGE,
    /** Told of each page as it is kept, for the rig to end the app at a chosen moment. */
    private val pageKept: (Int) -> Unit = {},
) {
    private val current = MutableStateFlow<LibrarySnapshot?>(null)

    /** The copy as it stands: null until the phone's has been read (or found not to be
     *  there and nothing has arrived yet). */
    val state: StateFlow<LibrarySnapshot?> = current.asStateFlow()

    private val fetchingNow = MutableStateFlow<Fetching?>(null)

    /** A fetch under way, and how far it has got. */
    val fetching: StateFlow<Fetching?> = fetchingNow.asStateFlow()

    private val opened = MutableStateFlow(false)

    /** The phone's copy has been read (or there was none): a page that waits for it can stop. */
    val read: StateFlow<Boolean> = opened.asStateFlow()

    private val lock = Mutex()

    /** The copy, whole, for searching: null until there is one. (A copy that came back with
     *  no songs from a server that says it has some is not one: that server doesn't list
     *  its library to an empty search, and its own search is the better.) */
    val ready: LibrarySnapshot?
        get() = current.value?.takeIf { it.complete && (it.songs.size > 0 || it.stamp.count == 0L) }

    private val copyFile = File(dir, "copy.bin")
    private val pagesDir = File(dir, "fetching")
    private val beganFile = File(pagesDir, "began.bin")

    /** Reads the phone's copy, if it hasn't been yet: the whole one, or failing that the
     *  pages of a first fetch that was cut short. */
    suspend fun open() = lock.withLock { openLocked() }

    private suspend fun openLocked() {
        if (opened.value) return
        val found = withContext(io) {
            org.medianexus.music.store.sweepTemporaries(dir)
            org.medianexus.music.store.sweepTemporaries(pagesDir)
            CopyFile.read(copyFile) ?: run {
                if (copyFile.exists()) {
                    note("library copy: the copy on the phone couldn't be read, so it is fetched again")
                    copyFile.delete()
                }
                // A first fetch cut short: what it got so far.
                val began = CopyFile.readBegan(beganFile) ?: return@run null
                val parts = readPages(began.second)
                if (parts.isEmpty()) null else assemble(parts, began.first, complete = false)
            }
        }
        if (found != null && current.value == null) current.value = found
        opened.value = true
    }

    /**
     * Asks the server how its library stands and, if the copy is out of date (or there is
     * none), fetches it. Returns whether a copy was fetched. Throws what asking the server
     * threw; whatever pages had arrived stay on the phone for the next try.
     */
    suspend fun check(): Boolean = lock.withLock {
        openLocked()
        val status = server.scanStatus()
        val have = current.value
        val stale = if (have == null || !have.complete) !status.scanning else isStale(have.stamp, status, now(), unmetered())
        if (!stale) return@withLock false
        fetch(status, first = have == null || !have.complete)
        true
    }

    private suspend fun fetch(status: ScanStatus, first: Boolean) {
        val started = System.nanoTime()
        // Carried on from where an earlier fetch stopped, if the library is as it was then.
        val began = withContext(io) { CopyFile.readBegan(beganFile) }
        val same = began != null && began.second == pageSize && began.first.count == status.count && began.first.lastScan == status.lastScan
        val stamp = if (same) began!!.first else CopyStamp(status.count, status.lastScan, now())
        val parts = ArrayList<Part>()
        withContext(io) {
            if (same) {
                parts += readPages(pageSize)
            } else {
                pagesDir.deleteRecursively()
                CopyFile.writeBegan(beganFile, stamp, pageSize)
            }
        }
        val carriedOn = parts.size
        fetchingNow.value = Fetching(parts.sumOf { it.found.songs.size }, first)
        var lastShown = System.nanoTime()
        try {
            while (true) {
                val last = parts.lastOrNull()
                val want = if (last == null) Asked(pageSize, pageSize, pageSize) else last.next(pageSize)
                if (want.none) break
                val page = parts.size
                val found = server.page(want.artists, want.albums, want.songs, page * pageSize)
                val part = Part(page, want, found)
                withContext(io) { CopyFile.writePage(pageFile(page), stamp, part) }
                pageKept(page)
                parts += part
                fetchingNow.value = Fetching(fetchingNow.value?.songs?.plus(found.songs.size) ?: found.songs.size, first)
                // The first time, what has arrived is there to be shown, a second at a time.
                if (first && System.nanoTime() - lastShown > SHOW_EVERY_NANOS) {
                    current.value = withContext(io) { assemble(parts, stamp, complete = false) }
                    lastShown = System.nanoTime()
                }
            }
            val whole = withContext(io) {
                val copy = assemble(parts, stamp, complete = true)
                CopyFile.write(copyFile, copy)
                pagesDir.deleteRecursively()
                copy
            }
            current.value = whole
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            note(
                "library copy: ${whole.songs.size} songs, ${whole.albums.size} albums, ${whole.artists.size} artists in ${"%.1f".format(seconds)} s" +
                    (if (carriedOn > 0) ", carrying on after $carriedOn pages kept from before" else "") +
                    ", ${copyFile.length() / 1024} KB on the phone",
            )
            // Ready for the first search, made now rather than at the first keystroke.
            withContext(kotlinx.coroutines.Dispatchers.Default) { whole.index }
        } finally {
            fetchingNow.value = null
        }
    }

    private fun pageFile(page: Int) = File(pagesDir, "page-%05d.bin".format(page))

    /** The pages of a fetch kept so far, in order, up to the first that's missing or can't be
     *  read; that one and any after it are cleared away. */
    private fun readPages(size: Int): List<Part> {
        val parts = ArrayList<Part>()
        while (true) {
            val part = CopyFile.readPage(pageFile(parts.size)) ?: break
            if (part.page != parts.size || part.asked.largest > size) break
            parts += part
        }
        pagesDir.listFiles()?.forEach { file ->
            val n = file.name.removePrefix("page-").removeSuffix(".bin").toIntOrNull()
            if (n != null && n >= parts.size) file.delete()
        }
        return parts
    }

    private fun assemble(parts: List<Part>, stamp: CopyStamp, complete: Boolean): LibrarySnapshot {
        val songs = SongTable.Builder()
        val albums = LinkedHashMap<String, Album>()
        val artists = LinkedHashMap<String, Artist>()
        for (part in parts) {
            for (song in part.found.songs) songs.add(song)
            for (album in part.found.albums) if (album.id.isNotEmpty()) albums.putIfAbsent(album.id, album.copy(songs = emptyList()))
            for (artist in part.found.artists) if (artist.id.isNotEmpty()) artists.putIfAbsent(artist.id, artist.copy(albums = emptyList()))
        }
        return LibrarySnapshot(songs.build(), albums.values.toList(), artists.values.toList(), stamp, complete)
    }

    /** How many of each kind a page asked for. */
    data class Asked(val artists: Int, val albums: Int, val songs: Int) {
        val none: Boolean get() = artists == 0 && albums == 0 && songs == 0
        val largest: Int get() = maxOf(artists, albums, songs)
    }

    /** One page of a fetch: what it asked for, and what came. */
    class Part(val page: Int, val asked: Asked, val found: Found) {
        /** What the page after it asks for: more of each kind only while each came full. */
        fun next(size: Int) = Asked(
            if (asked.artists > 0 && found.artists.size >= asked.artists) size else 0,
            if (asked.albums > 0 && found.albums.size >= asked.albums) size else 0,
            if (asked.songs > 0 && found.songs.size >= asked.songs) size else 0,
        )
    }

    companion object {
        /** How many of each kind to ask the server for at a time (Windows' `PAGE`). */
        const val PAGE = 500

        /** A scan that didn't change the number of songs may still have changed tags, but
         *  isn't worth fetching everything again for more often than this (seconds). */
        const val RESCAN_AFTER = 15 * 60L

        /** The copy is fetched afresh this often regardless (seconds), to keep play counts
         *  current: on a network that isn't metered. */
        const val REFRESH_AFTER = 24 * 3600L

        private const val SHOW_EVERY_NANOS = 1_000_000_000L

        /**
         * Whether the server's library has changed since this copy was fetched (Windows'
         * `is_stale`). Never while it is scanning: half a library is no use. One difference
         * from Windows: the daily refresh, which only brings play counts up to date, waits
         * for a network that isn't metered; a change in the library is fetched on any.
         */
        fun isStale(copy: CopyStamp, status: ScanStatus, now: Long, unmetered: Boolean): Boolean {
            if (status.scanning) return false
            return status.count != copy.count ||
                (status.lastScan != copy.lastScan && now - copy.fetched > RESCAN_AFTER) ||
                (unmetered && now - copy.fetched > REFRESH_AFTER)
        }
    }
}

/**
 * The copy's files: the whole copy, the pages of a fetch, and the note of what a fetch began
 * with. Each is written whole or not at all (a file beside, then into place), and read back
 * defensively: a mark at the start, a checksum at the end over everything before it, and
 * every count and reference checked as it is read. Anything that doesn't pass reads as
 * nothing.
 */
internal object CopyFile {
    private const val MAGIC = 0x4E584C43 // "NXLC"
    private const val VERSION = 1
    private const val COPY = 1
    private const val PAGE = 2
    private const val BEGAN = 3

    fun write(file: File, copy: LibrarySnapshot) = writeChecked(file, COPY) { out ->
        writeStamp(out, copy.stamp)
        copy.songs.write(out)
        writeAlbums(out, copy.albums)
        writeArtists(out, copy.artists)
    }

    fun read(file: File): LibrarySnapshot? = readChecked(file, COPY) { input ->
        val stamp = readStamp(input)
        val songs = SongTable.read(input)
        LibrarySnapshot(songs, readAlbums(input), readArtists(input), stamp, complete = true)
    }

    fun writePage(file: File, stamp: CopyStamp, part: LibraryCopy.Part) = writeChecked(file, PAGE) { out ->
        writeStamp(out, stamp)
        out.int(part.page)
        out.int(part.asked.artists)
        out.int(part.asked.albums)
        out.int(part.asked.songs)
        val songs = SongTable.Builder().apply { part.found.songs.forEach { add(it) } }.build()
        songs.write(out)
        writeAlbums(out, part.found.albums)
        writeArtists(out, part.found.artists)
    }

    fun readPage(file: File): LibraryCopy.Part? = readChecked(file, PAGE) { input ->
        readStamp(input)
        val page = input.int()
        val asked = LibraryCopy.Asked(SongTable.count(input), SongTable.count(input), SongTable.count(input))
        val table = SongTable.read(input)
        val songs = List(table.size) { table.song(it) }
        // In the order written: albums, then artists.
        val albums = readAlbums(input)
        LibraryCopy.Part(page, asked, Found(artists = readArtists(input), albums = albums, songs = songs))
    }

    fun writeBegan(file: File, stamp: CopyStamp, pageSize: Int) = writeChecked(file, BEGAN) { out ->
        writeStamp(out, stamp)
        out.int(pageSize)
    }

    fun readBegan(file: File): Pair<CopyStamp, Int>? = readChecked(file, BEGAN) { input -> readStamp(input) to input.int() }

    private fun writeChecked(file: File, kind: Int, body: (Packer) -> Unit) {
        val out = Packer()
        out.int(MAGIC)
        out.int(VERSION)
        out.int(kind)
        body(out)
        val crc = CRC32().apply { update(out.bytes, 0, out.size) }
        out.int(crc.value.toInt())
        writeAtomically(file) { raw -> raw.write(out.bytes, 0, out.size) }
    }

    /** Read whole (the copy is a few megabytes), checked once, then taken apart in memory. */
    private fun <T> readChecked(file: File, kind: Int, body: (Unpacker) -> T): T? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 16) return null
            val end = bytes.size - 4
            val sum = CRC32().apply { update(bytes, 0, end) }.value.toInt()
            if (Unpacker(bytes, end).int() != sum) return null
            val input = Unpacker(bytes, 0, end)
            if (input.int() != MAGIC || input.int() != VERSION || input.int() != kind) return null
            val value = body(input)
            if (input.done) value else null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // Something in it that no file written here could hold: as good as not there.
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun writeStamp(out: Packer, stamp: CopyStamp) {
        out.long(stamp.count)
        SongTable.writeText(out, stamp.lastScan)
        out.long(stamp.fetched)
    }

    private fun readStamp(input: Unpacker) = CopyStamp(input.long(), SongTable.readText(input), input.long())

    private fun writeAlbums(out: Packer, albums: List<Album>) {
        out.int(albums.size)
        for (a in albums) {
            for (text in listOf(a.id, a.name, a.artist, a.artistId, a.displayArtist, a.coverArt, a.genre, a.starred.orEmpty(), a.played)) SongTable.writeText(out, text)
            out.bool(a.starred != null)
            out.int(a.songCount)
            out.int(a.duration)
            out.int(a.year)
            out.int(a.releaseTypes.size)
            for (type in a.releaseTypes) SongTable.writeText(out, type)
        }
    }

    private fun readAlbums(input: Unpacker): List<Album> = List(SongTable.count(input)) {
        val t = List(9) { SongTable.readText(input) }
        val starred = input.bool()
        Album(
            id = t[0], name = t[1], artist = t[2], artistId = t[3], displayArtist = t[4], coverArt = t[5], genre = t[6],
            starred = if (starred) t[7] else null, played = t[8],
            songCount = input.int(), duration = input.int(), year = input.int(),
            releaseTypes = List(SongTable.count(input)) { SongTable.readText(input) },
        )
    }

    private fun writeArtists(out: Packer, artists: List<Artist>) {
        out.int(artists.size)
        for (a in artists) {
            for (text in listOf(a.id, a.name, a.coverArt, a.starred.orEmpty())) SongTable.writeText(out, text)
            out.bool(a.starred != null)
            out.int(a.albumCount)
        }
    }

    private fun readArtists(input: Unpacker): List<Artist> = List(SongTable.count(input)) {
        val t = List(4) { SongTable.readText(input) }
        val starred = input.bool()
        Artist(id = t[0], name = t[1], coverArt = t[2], starred = if (starred) t[3] else null, albumCount = input.int())
    }
}
