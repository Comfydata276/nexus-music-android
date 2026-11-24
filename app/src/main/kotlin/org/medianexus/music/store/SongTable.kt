package org.medianexus.music.store

import java.io.IOException
import org.medianexus.music.net.Credit
import org.medianexus.music.net.ReplayGain
import org.medianexus.music.net.Song

/**
 * Every song of the library, kept compactly: what the phone's copy of the library holds
 * (see `LibraryCopy`). A song as the server sends it is some thirty fields, a dozen of them
 * text, and 50,000 of them as objects come to well over a hundred megabytes of the app's
 * memory. Here a song is a place, and each field a column: numbers in arrays of numbers,
 * times as numbers, and every piece of text that songs share (an album's name, an artist, a
 * genre, a format, the list of who is credited) kept once and referred to by number. Only a
 * song's id and title are its own; they are what every row and search result shows.
 *
 * A [Song] is made when one is wanted ([song]): for a row as it is drawn, a result, the
 * queue. What is made is what the server said, with one difference: the times (when it was
 * added, last played, liked) are kept to the millisecond and given back in the standard form
 * ("2024-03-09T10:15:00.123Z"), not character for character as the server wrote them. The
 * rare time that can't be read as one is kept as it was.
 *
 * Immutable once made; made by [Builder], read and written by [write] and [read].
 */
class SongTable private constructor(
    val size: Int,
    private val ids: Array<String>,
    private val titles: Array<String>,
    /** The text songs share, by number. */
    private val texts: Array<String>,
    private val credits: Array<List<Credit>>,
    /** Numbers into [texts], [TEXTS] to a song. */
    private val textRefs: IntArray,
    /** [NUMBERS] to a song. */
    private val numbers: IntArray,
    /** [TIMES] to a song: the file's size, then times in milliseconds ([NO_TIME] for none). */
    private val times: LongArray,
    /** Four to a song: track and album gain and peak; NaN where the server gave none. */
    private val gains: FloatArray,
    /** Times that couldn't be read as times, as they were: by song and field. */
    private val oddTimes: Map<Long, String>,
) {
    fun id(i: Int): String = ids[i]
    fun title(i: Int): String = titles[i]
    fun album(i: Int): String = texts[textRefs[i * TEXTS + ALBUM]]
    fun albumId(i: Int): String = texts[textRefs[i * TEXTS + ALBUM_ID]]
    fun coverArt(i: Int): String = texts[textRefs[i * TEXTS + COVER]]
    fun artistLabel(i: Int): String = texts[textRefs[i * TEXTS + DISPLAY_ARTIST]].ifEmpty { texts[textRefs[i * TEXTS + ARTIST]] }
    fun track(i: Int): Int = numbers[i * NUMBERS + TRACK]
    fun disc(i: Int): Int = numbers[i * NUMBERS + DISC]
    fun duration(i: Int): Int = numbers[i * NUMBERS + DURATION]

    /** When it was added to the library, in milliseconds since 1970; 0 if not known. */
    fun createdMillis(i: Int): Long = times[i * TIMES + CREATED].let { if (it == NO_TIME) 0 else it }

    /** How many songs have been made of the table (for the tests; not exact across threads). */
    internal var made = 0L

    /** The song at `i`, whole. */
    fun song(i: Int): Song {
        made++
        val t = i * TEXTS
        val n = i * NUMBERS
        val g = i * 4
        val starred = timeText(i, STARRED)
        return Song(
            id = ids[i],
            title = titles[i],
            album = texts[textRefs[t + ALBUM]],
            artist = texts[textRefs[t + ARTIST]],
            albumId = texts[textRefs[t + ALBUM_ID]],
            artistId = texts[textRefs[t + ARTIST_ID]],
            displayArtist = texts[textRefs[t + DISPLAY_ARTIST]],
            artists = credits[textRefs[t + CREDITS]],
            track = numbers[n + TRACK],
            discNumber = numbers[n + DISC],
            year = numbers[n + YEAR],
            coverArt = texts[textRefs[t + COVER]],
            duration = numbers[n + DURATION],
            suffix = texts[textRefs[t + SUFFIX]],
            size = times[i * TIMES + SIZE],
            bitRate = numbers[n + BIT_RATE],
            samplingRate = numbers[n + SAMPLING_RATE],
            bitDepth = numbers[n + BIT_DEPTH],
            genre = texts[textRefs[t + GENRE]],
            starred = if (times[i * TIMES + STARRED] == NO_TIME && starred.isEmpty()) null else starred,
            created = timeText(i, CREATED),
            playCount = numbers[n + PLAY_COUNT],
            played = timeText(i, PLAYED),
            userRating = numbers[n + RATING],
            replayGain = if (numbers[n + FLAGS] and HAS_GAIN == 0) null else ReplayGain(
                gains[g].orNull(), gains[g + 1].orNull(), gains[g + 2].orNull(), gains[g + 3].orNull(),
            ),
        )
    }

    private fun timeText(i: Int, field: Int): String {
        val at = times[i * TIMES + field]
        if (at != NO_TIME) return java.time.Instant.ofEpochMilli(at).toString()
        return oddTimes[i.toLong() * TIMES + field].orEmpty()
    }

    /** Writes the table (see [read]). */
    @Throws(IOException::class)
    fun write(out: Packer) {
        out.int(size)
        out.int(texts.size)
        for (text in texts) writeText(out, text)
        out.int(credits.size)
        for (list in credits) {
            out.int(list.size)
            for (credit in list) {
                writeText(out, credit.id)
                writeText(out, credit.name)
            }
        }
        for (i in 0 until size) {
            writeText(out, ids[i])
            writeText(out, titles[i])
        }
        for (i in 0 until size * TEXTS) out.int(textRefs[i])
        for (i in 0 until size * NUMBERS) out.int(numbers[i])
        for (i in 0 until size * TIMES) out.long(times[i])
        for (i in 0 until size * 4) out.float(gains[i])
        out.int(oddTimes.size)
        for ((key, text) in oddTimes) {
            out.long(key)
            writeText(out, text)
        }
    }

    /** Puts a table together a song at a time. Text that songs share is kept once. With
     *  `distinct` (a library), a song whose id is in already is not added again; without (a
     *  queue, which may hold a song twice), every song is. */
    class Builder(private val distinct: Boolean = true) {
        private var size = 0
        private var ids = arrayOfNulls<String>(INITIAL)
        private var titles = arrayOfNulls<String>(INITIAL)
        private val textIds = HashMap<String, Int>()
        private var texts = arrayOfNulls<String>(256)
        private var textCount = 0
        private val creditIds = HashMap<List<Credit>, Int>()
        private var credits = arrayOfNulls<List<Credit>>(64)
        private var creditCount = 0
        private var textRefs = IntArray(INITIAL * TEXTS)
        private var numbers = IntArray(INITIAL * NUMBERS)
        private var times = LongArray(INITIAL * TIMES)
        private var gains = FloatArray(INITIAL * 4)
        private val oddTimes = HashMap<Long, String>()
        private val seen = HashSet<String>()

        val count: Int get() = size

        // (Grown by copying into a new array, never written in place below the count: a table
        // already handed out by [snapshot] reads the old array, and is never disturbed.)
        private fun text(value: String): Int = textIds.getOrPut(value) {
            if (textCount == texts.size) texts = texts.copyOf(textCount * 2)
            texts[textCount] = value
            textCount++
        }

        private fun creditsOf(value: List<Credit>): Int = creditIds.getOrPut(value) {
            if (creditCount == credits.size) credits = credits.copyOf(creditCount * 2)
            credits[creditCount] = value
            creditCount++
        }

        private fun room() {
            if (size < ids.size) return
            val grown = ids.size * 2
            ids = ids.copyOf(grown)
            titles = titles.copyOf(grown)
            textRefs = textRefs.copyOf(grown * TEXTS)
            numbers = numbers.copyOf(grown * NUMBERS)
            times = times.copyOf(grown * TIMES)
            gains = gains.copyOf(grown * 4)
        }

        /** Adds a song, unless one with its id is in already (the server's library moved
         *  while it was being read a page at a time, and a song came round twice). */
        fun add(song: Song): Boolean {
            if (distinct && (song.id.isEmpty() || !seen.add(song.id))) return false
            room()
            val i = size
            ids[i] = song.id
            titles[i] = song.title
            val t = i * TEXTS
            textRefs[t + ALBUM] = text(song.album)
            textRefs[t + ARTIST] = text(song.artist)
            textRefs[t + ALBUM_ID] = text(song.albumId)
            textRefs[t + ARTIST_ID] = text(song.artistId)
            textRefs[t + DISPLAY_ARTIST] = text(song.displayArtist)
            textRefs[t + COVER] = text(song.coverArt)
            textRefs[t + SUFFIX] = text(song.suffix)
            textRefs[t + GENRE] = text(song.genre)
            textRefs[t + CREDITS] = creditsOf(song.artists)
            val n = i * NUMBERS
            numbers[n + TRACK] = song.track
            numbers[n + DISC] = song.discNumber
            numbers[n + YEAR] = song.year
            numbers[n + DURATION] = song.duration
            numbers[n + BIT_RATE] = song.bitRate
            numbers[n + SAMPLING_RATE] = song.samplingRate
            numbers[n + BIT_DEPTH] = song.bitDepth
            numbers[n + PLAY_COUNT] = song.playCount
            numbers[n + RATING] = song.userRating
            numbers[n + FLAGS] = if (song.replayGain != null) HAS_GAIN else 0
            val m = i * TIMES
            times[m + SIZE] = song.size
            times[m + CREATED] = time(i, CREATED, song.created)
            times[m + PLAYED] = time(i, PLAYED, song.played)
            times[m + STARRED] = time(i, STARRED, song.starred.orEmpty())
            val g = i * 4
            val gain = song.replayGain
            gains[g] = gain?.trackGain ?: Float.NaN
            gains[g + 1] = gain?.albumGain ?: Float.NaN
            gains[g + 2] = gain?.trackPeak ?: Float.NaN
            gains[g + 3] = gain?.albumPeak ?: Float.NaN
            size++
            return true
        }

        private fun time(i: Int, field: Int, text: String): Long {
            if (text.isEmpty()) return NO_TIME
            val at = millisOf(text)
            if (at == null) oddTimes[i.toLong() * TIMES + field] = text
            return at ?: NO_TIME
        }

        /** The table as it stands, in arrays of its own, no bigger than it needs; the builder
         *  can go on being added to. */
        fun build(): SongTable = SongTable(
            size,
            ids.copyOf(size).requireNoNulls(),
            titles.copyOf(size).requireNoNulls(),
            texts.copyOf(textCount).requireNoNulls(),
            credits.copyOf(creditCount).requireNoNulls(),
            textRefs.copyOf(size * TEXTS),
            numbers.copyOf(size * NUMBERS),
            times.copyOf(size * TIMES),
            gains.copyOf(size * 4),
            HashMap(oddTimes),
        )

        /**
         * The table as it stands, sharing the builder's arrays rather than copying them: for
         * showing a library as it arrives, a second at a time, without copying everything that
         * has arrived each time. Safe while the builder goes on: it only ever writes past what
         * a snapshot reads, and when it needs more room it moves to new arrays, leaving the
         * old ones to the snapshots that have them.
         */
        @Suppress("UNCHECKED_CAST")
        fun snapshot(): SongTable = SongTable(
            size, ids as Array<String>, titles as Array<String>, texts as Array<String>, credits as Array<List<Credit>>,
            textRefs, numbers, times, gains, HashMap(oddTimes),
        )
    }

    companion object {
        private const val INITIAL = 1024

        // The text columns, by place among a song's TEXTS.
        private const val ALBUM = 0
        private const val ARTIST = 1
        private const val ALBUM_ID = 2
        private const val ARTIST_ID = 3
        private const val DISPLAY_ARTIST = 4
        private const val COVER = 5
        private const val SUFFIX = 6
        private const val GENRE = 7
        private const val CREDITS = 8
        private const val TEXTS = 9

        private const val TRACK = 0
        private const val DISC = 1
        private const val YEAR = 2
        private const val DURATION = 3
        private const val BIT_RATE = 4
        private const val SAMPLING_RATE = 5
        private const val BIT_DEPTH = 6
        private const val PLAY_COUNT = 7
        private const val RATING = 8
        private const val FLAGS = 9
        private const val NUMBERS = 10
        private const val HAS_GAIN = 1

        private const val SIZE = 0
        private const val CREATED = 1
        private const val PLAYED = 2
        private const val STARRED = 3
        private const val TIMES = 4

        /** No time given (an empty field). 0 is a time: 1970. */
        const val NO_TIME = Long.MIN_VALUE

        val EMPTY: SongTable = Builder().build()

        private fun Float.orNull(): Float? = if (isNaN()) null else this

        /** A time as the server writes it ("2024-03-09T10:15:00Z", with or without parts of
         *  a second or an offset), in milliseconds; null if it isn't one. */
        fun millisOf(text: String): Long? = try {
            java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

        /**
         * Reads a table written by [write], refusing anything that doesn't add up (a number
         * of songs or a reference out of range) rather than making songs of it: a file
         * damaged in a way its checksum missed is still never more than refused.
         */
        @Throws(IOException::class)
        fun read(input: Unpacker): SongTable {
            val size = count(input)
            val texts = Array(count(input)) { readText(input) }
            val credits = Array(count(input)) {
                List(count(input)) { Credit(readText(input), readText(input)) }
            }
            val ids = arrayOfNulls<String>(size)
            val titles = arrayOfNulls<String>(size)
            for (i in 0 until size) {
                ids[i] = readText(input)
                titles[i] = readText(input)
            }
            val textRefs = IntArray(size * TEXTS) { input.int() }
            for (i in textRefs.indices) {
                val limit = if (i % TEXTS == CREDITS) credits.size else texts.size
                if (textRefs[i] !in 0 until limit) throw IOException("damaged: a song names text that isn't there")
            }
            val numbers = IntArray(size * NUMBERS) { input.int() }
            val times = LongArray(size * TIMES) { input.long() }
            val gains = FloatArray(size * 4) { input.float() }
            val odd = HashMap<Long, String>()
            repeat(count(input)) { odd[input.long()] = readText(input) }
            return SongTable(size, ids.requireNoNulls(), titles.requireNoNulls(), texts, credits, textRefs, numbers, times, gains, odd)
        }

        /** How many of something follow, refusing a number no library comes near. */
        @Throws(IOException::class)
        internal fun count(input: Unpacker): Int {
            val n = input.int()
            if (n !in 0..MOST) throw IOException("damaged: $n of something")
            return n
        }

        private const val MOST = 5_000_000

        internal fun writeText(out: Packer, text: String) = out.text(text)

        @Throws(IOException::class)
        internal fun readText(input: Unpacker): String = input.text()

    }
}
