package org.medianexus.music.library

import org.medianexus.music.net.Artist
import org.medianexus.music.net.Playlist
import org.medianexus.music.net.Song

/**
 * How well things match what was typed, as the Windows app judges it (src/search.rs): the
 * same folding of accents and punctuation, the same forgiveness of a slip or two, the same
 * scores. The server finds the results; this decides what Windows decides about them:
 * whether the artists go above the songs, and which playlists match. A port, so both apps
 * say the same about the same words. Plain Kotlin, so it can be tested on its own.
 */
object SearchRank {
    /**
     * Lower-case, without accents or punctuation, so "Beyoncé" and "beyonce" are the same
     * (`fold`). Exactly as Rust does it there: a character (a code point, not a half of one)
     * at a time, each lower-cased by itself (`char::to_lowercase`: Σ is σ wherever it stands,
     * and İ is "i" and a dot), and kept if Rust calls it alphanumeric (a letter, or a number
     * of any kind: ², ½, Ⅻ), else a space.
     */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            if (c == CAPITAL_I_WITH_DOT) {
                // Rust's one lower case of more than one character: "i" and a combining dot
                // (which is no letter, so a space).
                foldLower('i'.code, out)
                foldLower(COMBINING_DOT, out)
            } else {
                foldLower(Character.toLowerCase(c), out)
            }
        }
        return out.toString()
    }

    private const val CAPITAL_I_WITH_DOT = 0x130
    private const val COMBINING_DOT = 0x307

    /** One character, already lower-cased, folded onto the end of `out`. */
    private fun foldLower(c: Int, out: StringBuilder) {
        when (c) {
            'à'.code, 'á'.code, 'â'.code, 'ã'.code, 'ä'.code, 'å'.code, 'ā'.code, 'ă'.code, 'ą'.code -> out.append('a')
            'ç'.code, 'ć'.code, 'č'.code -> out.append('c')
            'ď'.code, 'đ'.code -> out.append('d')
            'è'.code, 'é'.code, 'ê'.code, 'ë'.code, 'ē'.code, 'ė'.code, 'ę'.code, 'ě'.code -> out.append('e')
            'ğ'.code -> out.append('g')
            'ì'.code, 'í'.code, 'î'.code, 'ï'.code, 'ī'.code, 'į'.code, 'ı'.code -> out.append('i')
            'ł'.code -> out.append('l')
            'ñ'.code, 'ń'.code, 'ň'.code -> out.append('n')
            'ò'.code, 'ó'.code, 'ô'.code, 'õ'.code, 'ö'.code, 'ø'.code, 'ō'.code, 'ő'.code -> out.append('o')
            'ř'.code -> out.append('r')
            'ś'.code, 'š'.code, 'ş'.code -> out.append('s')
            'ť'.code, 'ţ'.code -> out.append('t')
            'ù'.code, 'ú'.code, 'û'.code, 'ü'.code, 'ū'.code, 'ů'.code, 'ű'.code, 'ų'.code -> out.append('u')
            'ý'.code, 'ÿ'.code -> out.append('y')
            'ź'.code, 'ż'.code, 'ž'.code -> out.append('z')
            'æ'.code -> out.append("ae")
            'œ'.code -> out.append("oe")
            'ß'.code -> out.append("ss")
            // "don't" and "dont" are the same word.
            '\''.code, '’'.code, '‘'.code, '`'.code -> {}
            else -> if (alphanumeric(c)) out.appendCodePoint(c) else out.append(' ')
        }
    }

    /** Rust's `char::is_alphanumeric`: Unicode's Alphabetic property (letters, and the marks
     *  that are part of a letter, such as a Devanagari vowel sign), or a number of any kind
     *  (a digit, a letter-like number such as Ⅻ, or another such as ² or ½). */
    fun alphanumeric(c: Int): Boolean {
        if (Character.isAlphabetic(c)) return true
        return when (Character.getType(c)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    }

    /** The words of a name, folded (`musicbrainz::words`). */
    fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /** A word's characters, as Rust has them (`Vec<char>`): code points, not halves of them. */
    fun points(word: String): IntArray = word.codePoints().toArray()

    /** How many single-letter slips separate two words; over `limit` comes back as
     *  `limit + 1` (`slips`). `bLength` takes only the start of `b` (Windows' `&word[..n]`),
     *  without making a copy of it: this is asked of every word in the library at each
     *  keystroke. */
    fun slips(a: String, b: String, limit: Int): Int = slips(points(a), points(b), limit)

    fun slips(a: IntArray, b: IntArray, limit: Int, bLength: Int = b.size): Int {
        if (kotlin.math.abs(a.size - bLength) > limit) return limit + 1
        var before = IntArray(bLength + 1)
        var previous = IntArray(bLength + 1) { it }
        var current = IntArray(bLength + 1)
        for (i in 1..a.size) {
            current[0] = i
            var least = current[0]
            for (j in 1..bLength) {
                val cost = if (a[i - 1] != b[j - 1]) 1 else 0
                var best = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, before[j - 2] + 1)
                current[j] = best
                least = minOf(least, best)
            }
            if (least > limit) return limit + 1
            val swap = before
            before = previous
            previous = current
            current = swap
        }
        return minOf(previous[bLength], limit + 1)
    }

    /** How well a typed word matches a word of a name, from 0 to 1 (`likeness`). */
    fun likeness(typed: String, word: String): Float = likeness(points(typed), points(word))

    /** The same, of words as their characters ([points]): lengths are counted in characters,
     *  as Rust counts them. */
    fun likeness(typed: IntArray, word: IntArray): Float {
        if (typed.contentEquals(word)) return 1f
        if (startsWith(word, typed)) return 0.85f + 0.1f * typed.size / word.size
        val limit = when (typed.size) {
            in 0..3 -> return 0f
            in 4..7 -> 1
            else -> 2
        }
        // A short word is only taken for a mistyping of one that starts the same way.
        if (typed.size <= 5 && typed.firstOrNull() != word.firstOrNull()) return 0f
        val whole = slips(typed, word, limit)
        if (whole <= limit) return if (whole == 1) 0.75f else 0.6f
        // A mistyped start of a longer word: "masi" for "maisie".
        var best = limit + 1
        val from = (typed.size - 1).coerceAtLeast(0)
        val to = minOf(typed.size + 1, (word.size - 1).coerceAtLeast(0))
        for (length in from..to) best = minOf(best, slips(typed, word, limit, length))
        return when {
            best == 1 -> 0.4f
            best == 2 && limit == 2 -> 0.3f
            else -> 0f
        }
    }

    private fun startsWith(word: IntArray, start: IntArray): Boolean {
        if (start.size > word.size) return false
        for (i in start.indices) if (word[i] != start[i]) return false
        return true
    }

    /** One thing's match: overall, and by its name alone (0 if a typed word isn't in it). */
    data class Match(val score: Float, val name: Float)

    /** Scores a name, and the other words it answers to with their weights, against the
     *  typed words (`rate`). Null if some typed word matches nothing. */
    fun rate(typedWords: List<String>, nameWords: List<String>, others: List<Pair<List<String>, Float>> = emptyList()): Match? {
        if (typedWords.isEmpty()) return null
        val typed = typedWords.map(::points)
        val name = nameWords.map(::points)
        val othersPoints = others.map { (words, weight) -> words.map(::points) to weight }
        var total = 0f
        var nameTotal = 0f
        var inName = true
        var used = 0L
        for (word in typed) {
            var bestName = 0f
            var at = -1
            for ((position, part) in name.withIndex()) {
                val like = likeness(word, part)
                if (like > bestName) {
                    bestName = like
                    at = position
                }
            }
            var best = bestName
            for ((words, weight) in othersPoints) for (part in words) best = maxOf(best, likeness(word, part) * weight)
            if (best <= 0f) return null
            total += best
            if (bestName > 0f) {
                nameTotal += bestName
                if (bestName >= best && at in 0 until 64) used = used or (1L shl at)
            } else {
                inName = false
            }
        }
        val count = typed.size.toFloat()
        val covered = if (name.isEmpty()) 0f else java.lang.Long.bitCount(used).toFloat() / name.size
        val inOrder = name.size >= typed.size && typed.indices.all { likeness(typed[it], name[it]) >= 0.85f }
        val exact = inOrder && name.size == typed.size && typed.indices.all { likeness(typed[it], name[it]) >= 1f }
        val bonus = (0.3f * covered + (if (exact) 0.5f else 0f) + (if (inOrder) 0.2f else 0f)) * nameTotal / count
        return Match(total / count + bonus, if (inName) nameTotal / count + bonus else 0f)
    }

    /**
     * Whether an artist matches what was typed at least as well as any song's title does
     * (Windows' `artist_first`): then the artists go above the songs.
     */
    fun artistFirst(query: String, songs: List<Song>, artists: List<Artist>): Boolean {
        val typed = words(query)
        if (typed.isEmpty()) return false
        var topTitle = 0f
        for (song in songs) {
            val rated = rate(typed, words(song.title), listOf(words(song.artistLabel) to 0.9f, words(song.album) to 0.8f)) ?: continue
            topTitle = maxOf(topTitle, rated.name)
        }
        var topArtist = 0f
        for (artist in artists) topArtist = maxOf(topArtist, rate(typed, words(artist.name))?.score ?: 0f)
        return topArtist > 0f && topArtist >= topTitle
    }

    /** Playlists as Windows lists them (its sidebar, which its search looks through in that
     *  order: `show_playlists`): the pinned ones first, in the order they were pinned, then
     *  the rest by name. */
    fun playlistsInOrder(playlists: List<Playlist>, pinned: List<String>): List<Playlist> =
        playlists.sortedWith(compareBy<Playlist> { pinned.indexOf(it.id).let { at -> if (at < 0) Int.MAX_VALUE else at } }.thenBy { it.name.lowercase() })

    /** The playlists whose name has every typed word, or the start of it
     *  (`playlists_matching`), in the order given. */
    fun playlistsMatching(query: String, playlists: List<Playlist>): List<Playlist> {
        val typed = words(query)
        if (typed.isEmpty()) return emptyList()
        return playlists.filter { playlist ->
            val name = words(playlist.name)
            typed.all { word -> name.any { it.startsWith(word) } }
        }
    }
}
