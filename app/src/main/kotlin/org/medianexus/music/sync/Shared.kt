package org.medianexus.music.sync

/**
 * What is shared between copies of the app signed in to the same account, as the Windows app
 * keeps it (`Shared` in its src/sync.rs): the text in the comment of the playlist named
 * [PLAYLIST_NAME]. The Windows app on the person's PCs reads this text and trusts it, so the
 * field names, the shapes and the kinds of number here are Windows', exactly.
 */
data class Shared(
    /** Marks the text as this app's and says which layout it is in (`nexus_music_sync`). A
     *  whole number of 32 bits on Windows. */
    val layout: Long = 0,
    val pins: List<String> = emptyList(),
    val pinsAt: Long = 0,
    val theme: String = "",
    val themeAt: Long = 0,
    /** By route key, e.g. "playlist:abc". */
    val lists: Map<String, SharedPrefs> = emptyMap(),
    /** Playlist id, then song id. */
    val added: Map<String, Map<String, SharedNote>> = emptyMap(),
    /** Song id and when it was played, newest first. */
    val history: List<Play> = emptyList(),
    /** The latest plays that counted, from every device, newest first. */
    val plays: List<Play> = emptyList(),
    /** For those of `plays` that were made from a playlist: the play, and that playlist's
     *  route key. */
    val playSources: List<PlayFrom> = emptyList(),
    /** Fields of the text this version doesn't know, each with its value exactly as it was
     *  written. Not part of what is merged or compared (Windows drops them); the phone puts
     *  the server's back when it writes (see [KotlinSettler]). */
    val unknown: List<Pair<String, String>> = emptyList(),
    /** The text held something inside a list's choices or a note that this version doesn't
     *  know, and so could not write back as it found it. Such a record is read and never
     *  written (see [KotlinSettler]). */
    val more: Boolean = false,
)

/** One list's choices, as shared (Windows' `ListPrefs`). `sort` is as wide as Windows' own
 *  number: a value past anything either app offers is still carried, unchanged. */
data class SharedPrefs(
    val shuffle: Boolean = false,
    val sort: ULong = 0u,
    val descending: Boolean = false,
    val sortChosen: Boolean = false,
    val at: Long = 0,
)

/** When one song was added to one playlist, as shared (Windows' `Note`). */
data class SharedNote(val times: List<Long> = emptyList(), val at: Long = 0)

/** A play: a song and when (seconds since 1970). Windows writes it as a pair. */
data class Play(val song: String, val at: Long)

/** A play made from a playlist, and that playlist's route key. Windows writes it as a triple. */
data class PlayFrom(val song: String, val at: Long, val from: String)

/** The playlist whose comment holds the shared data. */
const val PLAYLIST_NAME = "Nexus Music sync"

/** The layout of the shared data this version reads and writes. A copy written by a later
 *  layout is left alone. */
const val LAYOUT = 1L

/** Songs remembered for "Recently played". */
const val HISTORY_LENGTH = 100

/** How many of the latest plays are passed between devices for "Your stats". */
const val PLAYS_SHARED = 500

/** How much of a long queue is passed on: this many songs before the current one... */
const val QUEUE_BEHIND = 50

/** ...and this many from it onwards. */
const val QUEUE_AHEAD = 450

/** Stands in for "some time ago" on choices that have no time, so they're passed on but lose
 *  to anything chosen since. */
const val LONG_AGO = 1L

/** A removed song's note is kept this long, so other devices hear that it went. */
const val KEEP_REMOVED = 90L * 24 * 3600

/**
 * The record as text: read exactly as the Windows app reads it (serde, with every field
 * optional), and written exactly as it writes it.
 *
 * **Reading is neither kinder nor stricter than Windows.** A text Windows reads as a record,
 * the phone reads as the same record; a text Windows can't read (it takes it for "nothing
 * shared yet" and writes over it) the phone can't read either (and leaves alone: see
 * [KotlinSettler]). A general JSON reader would differ both ways (taking `"12"` or `1e3` for
 * a number, a field named twice, half a pair of escapes), so this is a reader of its own,
 * written against what serde does and held to cases the Windows code wrote
 * (`InStepCasesTest`). Among what serde allows that is easy to miss: a record, a list's
 * choices or a note written as a list of its fields in order; any field left out; fields it
 * doesn't know, of any shape.
 */
object SharedText {
    /** The record this text holds, or null if Windows couldn't read it as one. */
    fun read(text: String): Shared? = try {
        val reader = Reader(text)
        val shared = reader.shared()
        reader.end()
        shared
    } catch (_: Unreadable) {
        null
    }

    /** The record as Windows writes it (serde_json's `to_string`), field for field. Windows
     *  writes a map's entries in no particular order; here they go by key, so the same record
     *  is always the same text. Fields this version doesn't know follow, as they were. */
    fun write(shared: Shared): String = buildString(4096) {
        append("{\"nexus_music_sync\":").append(shared.layout)
        append(",\"pins\":[")
        shared.pins.forEachIndexed { i, pin -> if (i > 0) append(','); text(pin) }
        append("],\"pins_at\":").append(shared.pinsAt)
        append(",\"theme\":"); text(shared.theme)
        append(",\"theme_at\":").append(shared.themeAt)
        append(",\"lists\":{")
        shared.lists.entries.sortedWith { a, b -> bytewise(a.key, b.key) }.forEachIndexed { i, (key, prefs) ->
            if (i > 0) append(',')
            text(key)
            append(":{\"shuffle\":").append(prefs.shuffle).append(",\"sort\":").append(prefs.sort.toString())
            append(",\"descending\":").append(prefs.descending).append(",\"sort_chosen\":").append(prefs.sortChosen)
            append(",\"at\":").append(prefs.at).append('}')
        }
        append("},\"added\":{")
        shared.added.entries.sortedWith { a, b -> bytewise(a.key, b.key) }.forEachIndexed { i, (playlist, notes) ->
            if (i > 0) append(',')
            text(playlist)
            append(":{")
            notes.entries.sortedWith { a, b -> bytewise(a.key, b.key) }.forEachIndexed { j, (song, note) ->
                if (j > 0) append(',')
                text(song)
                append(":{\"times\":[")
                note.times.forEachIndexed { k, time -> if (k > 0) append(','); append(time) }
                append("],\"at\":").append(note.at).append('}')
            }
            append('}')
        }
        append("},\"history\":[")
        shared.history.forEachIndexed { i, play -> if (i > 0) append(','); play(play) }
        append("],\"plays\":[")
        shared.plays.forEachIndexed { i, play -> if (i > 0) append(','); play(play) }
        append("],\"play_sources\":[")
        shared.playSources.forEachIndexed { i, source ->
            if (i > 0) append(',')
            append('['); text(source.song); append(',').append(source.at).append(','); text(source.from); append(']')
        }
        append(']')
        for ((name, value) in shared.unknown) {
            append(','); text(name); append(':').append(value)
        }
        append('}')
    }

    private fun StringBuilder.play(play: Play) {
        append('['); text(play.song); append(',').append(play.at).append(']')
    }

    /** Text as serde_json writes it: only the quote, the backslash and the control
     *  characters are escaped. */
    private fun StringBuilder.text(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000c' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                else -> append(c)
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"

    /**
     * Compares text as Rust does, by its bytes as UTF-8. Kotlin's own comparison goes by
     * UTF-16 units, which puts a character outside the basic plane before some inside it;
     * ids and names are plain letters today, which is what would make that a trap.
     * (Comparing by code point is the same as comparing UTF-8 bytes.)
     */
    fun bytewise(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private class Unreadable : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Reads the text as serde_json reads it into Windows' `Shared`. */
    private class Reader(private val s: String) {
        private var i = 0

        /** A field this version doesn't know was passed over inside a list's choices or a note. */
        private var more = false

        private fun fail(): Nothing = throw Unreadable()

        /** JSON's four kinds of space, and no others. */
        private fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++
        }

        private fun peek(): Char {
            space()
            return if (i < s.length) s[i] else fail()
        }

        private fun take(c: Char) {
            if (peek() != c) fail()
            i++
        }

        fun end() {
            space()
            if (i != s.length) fail()
        }

        // ---- the record and its parts ----------------------------------------------------

        fun shared(): Shared {
            var layout: Long? = null
            var pins: List<String>? = null
            var pinsAt: Long? = null
            var theme: String? = null
            var themeAt: Long? = null
            var lists: Map<String, SharedPrefs>? = null
            var added: Map<String, Map<String, SharedNote>>? = null
            var history: List<Play>? = null
            var plays: List<Play>? = null
            var sources: List<PlayFrom>? = null
            val unknown = ArrayList<Pair<String, String>>(0)
            // A field named twice is a text serde refuses.
            fun <T> once(had: T?): Unit = if (had != null) fail() else Unit
            fields(
                count = 10,
                named = { name ->
                    when (name) {
                        "nexus_music_sync" -> { once(layout); layout = whole(U32_MOST, negative = false) }
                        "pins" -> { once(pins); pins = list { string() } }
                        "pins_at" -> { once(pinsAt); pinsAt = time() }
                        "theme" -> { once(theme); theme = string() }
                        "theme_at" -> { once(themeAt); themeAt = time() }
                        "lists" -> { once(lists); lists = map { prefs() } }
                        "added" -> { once(added); added = map { map { note() } } }
                        "history" -> { once(history); history = list { play() } }
                        "plays" -> { once(plays); plays = list { play() } }
                        "play_sources" -> { once(sources); sources = list { source() } }
                        else -> {
                            space()
                            val from = i
                            skip()
                            unknown += name to s.substring(from, i)
                        }
                    }
                },
                placed = { place ->
                    when (place) {
                        0 -> layout = whole(U32_MOST, negative = false)
                        1 -> pins = list { string() }
                        2 -> pinsAt = time()
                        3 -> theme = string()
                        4 -> themeAt = time()
                        5 -> lists = map { prefs() }
                        6 -> added = map { map { note() } }
                        7 -> history = list { play() }
                        8 -> plays = list { play() }
                        else -> sources = list { source() }
                    }
                },
            )
            return Shared(
                layout ?: 0, pins.orEmpty(), pinsAt ?: 0, theme.orEmpty(), themeAt ?: 0, lists.orEmpty(), added.orEmpty(),
                history.orEmpty(), plays.orEmpty(), sources.orEmpty(), unknown, more,
            )
        }

        private fun prefs(): SharedPrefs {
            var shuffle: Boolean? = null
            var sort: ULong? = null
            var descending: Boolean? = null
            var chosen: Boolean? = null
            var at: Long? = null
            fun <T> once(had: T?): Unit = if (had != null) fail() else Unit
            fields(
                count = 5,
                named = { name ->
                    when (name) {
                        "shuffle" -> { once(shuffle); shuffle = bool() }
                        "sort" -> { once(sort); sort = unsigned() }
                        "descending" -> { once(descending); descending = bool() }
                        "sort_chosen" -> { once(chosen); chosen = bool() }
                        "at" -> { once(at); at = time() }
                        else -> { skip(); more = true }
                    }
                },
                placed = { place ->
                    when (place) {
                        0 -> shuffle = bool()
                        1 -> sort = unsigned()
                        2 -> descending = bool()
                        3 -> chosen = bool()
                        else -> at = time()
                    }
                },
            )
            return SharedPrefs(shuffle ?: false, sort ?: 0u, descending ?: false, chosen ?: false, at ?: 0)
        }

        private fun note(): SharedNote {
            var times: List<Long>? = null
            var at: Long? = null
            fun <T> once(had: T?): Unit = if (had != null) fail() else Unit
            fields(
                count = 2,
                named = { name ->
                    when (name) {
                        "times" -> { once(times); times = list { time() } }
                        "at" -> { once(at); at = time() }
                        else -> { skip(); more = true }
                    }
                },
                placed = { place -> if (place == 0) times = list { time() } else at = time() },
            )
            return SharedNote(times.orEmpty(), at ?: 0)
        }

        /** A pair: exactly a song and a time, as a list. */
        private fun play(): Play {
            take('[')
            val song = string()
            take(',')
            val at = time()
            take(']')
            return Play(song, at)
        }

        private fun source(): PlayFrom {
            take('[')
            val song = string()
            take(',')
            val at = time()
            take(',')
            val from = string()
            take(']')
            return PlayFrom(song, at, from)
        }

        /**
         * Something with named fields, as serde reads one: an object, each field by name
         * (`named`: it reads the value, or passes over one it doesn't know), or a list of the
         * fields in their order, as many as are given and no more than there are (`placed`).
         */
        private inline fun fields(count: Int, named: (String) -> Unit, placed: (Int) -> Unit) {
            when (peek()) {
                '{' -> {
                    i++
                    if (peek() == '}') {
                        i++
                        return
                    }
                    while (true) {
                        if (peek() != '"') fail()
                        val name = string()
                        take(':')
                        named(name)
                        when (peek()) {
                            ',' -> i++
                            '}' -> { i++; return }
                            else -> fail()
                        }
                    }
                }
                '[' -> {
                    i++
                    if (peek() == ']') {
                        i++
                        return
                    }
                    var place = 0
                    while (true) {
                        if (place >= count) fail()
                        placed(place++)
                        when (peek()) {
                            ',' -> i++
                            ']' -> { i++; return }
                            else -> fail()
                        }
                    }
                }
                else -> fail()
            }
        }

        private inline fun <T> list(item: () -> T): List<T> {
            take('[')
            if (peek() == ']') {
                i++
                return emptyList()
            }
            val out = ArrayList<T>()
            while (true) {
                out += item()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail()
                }
            }
        }

        /** A map by text. A key given twice keeps its last value, as Windows' does. */
        private inline fun <T> map(value: () -> T): Map<String, T> {
            take('{')
            if (peek() == '}') {
                i++
                return emptyMap()
            }
            val out = LinkedHashMap<String, T>()
            while (true) {
                if (peek() != '"') fail()
                val key = string()
                take(':')
                out[key] = value()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail()
                }
            }
        }

        // ---- values ------------------------------------------------------------------------

        private fun bool(): Boolean = when {
            peek() == 't' && s.startsWith("true", i) -> { i += 4; true }
            s[i] == 'f' && s.startsWith("false", i) -> { i += 5; false }
            else -> fail()
        }

        /** A time: a whole number that fits 64 bits with a sign (Windows' `i64`). */
        private fun time(): Long {
            space()
            val negative = i < s.length && s[i] == '-'
            val size = wholeSize(if (negative) I64_LEAST_SIZE else I64_MOST, negative)
            return if (negative) (0uL - size).toLong() else size.toLong()
        }

        /** A whole number of 64 bits without a sign (Windows' `usize`). */
        private fun unsigned(): ULong = wholeSize(ULong.MAX_VALUE, negative = false)

        private fun whole(most: ULong, negative: Boolean): Long = wholeSize(most, negative).toLong()

        /**
         * The size of a whole number written as JSON writes one, no greater than `most`. A
         * number with a fraction or an exponent is not a whole number to serde, however round
         * (`12.0`, `1e3`); nor is `-0`, nor one too long for 64 bits.
         */
        private fun wholeSize(most: ULong, negative: Boolean): ULong {
            space()
            if (i < s.length && s[i] == '-') {
                if (!negative) fail()
                i++
            } else if (negative) {
                fail()
            }
            val from = i
            while (i < s.length && s[i] in '0'..'9') i++
            val digits = i - from
            if (digits == 0 || (digits > 1 && s[from] == '0') || digits > 20) fail()
            if (i < s.length && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) fail()
            val size = s.substring(from, i).toULongOrNull() ?: fail()
            if (size > most || (negative && size == 0uL)) fail()
            return size
        }

        /** Text in quotes, with JSON's escapes; a character outside the basic plane written
         *  as escapes must be both halves of its pair. */
        private fun string(): String {
            take('"')
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val unit = hex4()
                                when {
                                    unit.isHighSurrogate() -> {
                                        if (!s.startsWith("\\u", i)) fail()
                                        i += 2
                                        val low = hex4()
                                        if (!low.isLowSurrogate()) fail()
                                        out.append(unit).append(low)
                                    }
                                    unit.isLowSurrogate() -> fail()
                                    else -> out.append(unit)
                                }
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    // Half a pair on its own is not text Rust could have been handed.
                    c.isHighSurrogate() -> {
                        if (i >= s.length || !s[i].isLowSurrogate()) fail()
                        out.append(c).append(s[i++])
                    }
                    c.isLowSurrogate() -> fail()
                    else -> out.append(c)
                }
            }
        }

        private fun hex4(): Char {
            if (i + 4 > s.length) fail()
            var value = 0
            repeat(4) {
                // The sixteen digits of plain text only, as serde takes them: Java's own
                // reading of a digit also takes the full-width ones and those of other scripts.
                val digit = when (val c = s[i++]) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> fail()
                }
                value = value * 16 + digit
            }
            return value.toChar()
        }

        // ---- a value of any shape, passed over -----------------------------------------------

        /** Passes over one value, however deep it goes (no recursion: serde has no limit
         *  here either), holding it only to JSON's own rules. */
        private fun skip() {
            // What is open, innermost last: '[' or '{'.
            val open = StringBuilder()
            while (true) {
                // A value.
                when (val c = peek()) {
                    '[', '{' -> {
                        i++
                        val close = if (c == '[') ']' else '}'
                        if (peek() == close) {
                            i++
                        } else {
                            open.append(c)
                            if (c == '{') key()
                            continue
                        }
                    }
                    '"' -> { i++; skipString() }
                    't' -> word("true")
                    'f' -> word("false")
                    'n' -> word("null")
                    else -> skipNumber()
                }
                // What follows it: more of what is open, or its end.
                while (true) {
                    if (open.isEmpty()) return
                    val inside = open[open.length - 1]
                    val next = peek()
                    if (next == ',') {
                        i++
                        if (inside == '{') key()
                        break
                    }
                    if (next != (if (inside == '[') ']' else '}')) fail()
                    i++
                    open.setLength(open.length - 1)
                }
            }
        }

        private fun key() {
            take('"')
            skipString()
            take(':')
        }

        private fun word(word: String) {
            if (!s.startsWith(word, i)) fail()
            i += word.length
        }

        /** Text that is passed over: its escapes must be ones there are, but half a pair is
         *  let by, as serde lets it by where it keeps nothing. */
        private fun skipString() {
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}
                            'u' -> hex4()
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                }
            }
        }

        private fun skipNumber() {
            if (i < s.length && s[i] == '-') i++
            val from = i
            while (i < s.length && s[i] in '0'..'9') i++
            val digits = i - from
            if (digits == 0 || (digits > 1 && s[from] == '0')) fail()
            if (i < s.length && s[i] == '.') {
                i++
                val fraction = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == fraction) fail()
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val exponent = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == exponent) fail()
            }
        }

        private companion object {
            const val U32_MOST = 4294967295uL
            val I64_MOST = Long.MAX_VALUE.toULong()
            /** How far below nought a time may go: one further than above it. */
            val I64_LEAST_SIZE = Long.MAX_VALUE.toULong() + 1uL
        }
    }
}
