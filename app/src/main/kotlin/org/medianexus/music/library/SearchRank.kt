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
    /** Lower-case, without accents or punctuation, so "Beyoncé" and "beyonce" are the same
     *  (`fold`). */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text.lowercase()) {
            when (c) {
                'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> out.append('a')
                'ç', 'ć', 'č' -> out.append('c')
                'ď', 'đ' -> out.append('d')
                'è', 'é', 'ê', 'ë', 'ē', 'ė', 'ę', 'ě' -> out.append('e')
                'ğ' -> out.append('g')
                'ì', 'í', 'î', 'ï', 'ī', 'į', 'ı' -> out.append('i')
                'ł' -> out.append('l')
                'ñ', 'ń', 'ň' -> out.append('n')
                'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ő' -> out.append('o')
                'ř' -> out.append('r')
                'ś', 'š', 'ş' -> out.append('s')
                'ť', 'ţ' -> out.append('t')
                'ù', 'ú', 'û', 'ü', 'ū', 'ů', 'ű', 'ų' -> out.append('u')
                'ý', 'ÿ' -> out.append('y')
                'ź', 'ż', 'ž' -> out.append('z')
                'æ' -> out.append("ae")
                'œ' -> out.append("oe")
                'ß' -> out.append("ss")
                // "don't" and "dont" are the same word.
                '\'', '’', '‘', '`' -> {}
                else -> out.append(if (c.isLetterOrDigit()) c else ' ')
            }
        }
        return out.toString()
    }

    /** The words of a name, folded (`musicbrainz::words`). */
    fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /** How many single-letter slips separate two words; over `limit` comes back as
     *  `limit + 1` (`slips`). `bLength` takes only the start of `b` (Windows' `&word[..n]`),
     *  without making a string of it: this is asked of every word in the library at each
     *  keystroke. */
    fun slips(a: String, b: String, limit: Int, bLength: Int = b.length): Int {
        if (kotlin.math.abs(a.length - bLength) > limit) return limit + 1
        var before = IntArray(bLength + 1)
        var previous = IntArray(bLength + 1) { it }
        var current = IntArray(bLength + 1)
        for (i in 1..a.length) {
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
    fun likeness(typed: String, word: String): Float {
        if (typed == word) return 1f
        if (word.startsWith(typed)) return 0.85f + 0.1f * typed.length / word.length
        val limit = when (typed.length) {
            in 0..3 -> return 0f
            in 4..7 -> 1
            else -> 2
        }
        // A short word is only taken for a mistyping of one that starts the same way.
        if (typed.length <= 5 && typed.firstOrNull() != word.firstOrNull()) return 0f
        val whole = slips(typed, word, limit)
        if (whole <= limit) return if (whole == 1) 0.75f else 0.6f
        // A mistyped start of a longer word: "masi" for "maisie".
        var best = limit + 1
        val from = (typed.length - 1).coerceAtLeast(0)
        val to = minOf(typed.length + 1, (word.length - 1).coerceAtLeast(0))
        for (length in from..to) best = minOf(best, slips(typed, word, limit, length))
        return when {
            best == 1 -> 0.4f
            best == 2 && limit == 2 -> 0.3f
            else -> 0f
        }
    }

    /** One thing's match: overall, and by its name alone (0 if a typed word isn't in it). */
    data class Match(val score: Float, val name: Float)

    /** Scores a name, and the other words it answers to with their weights, against the
     *  typed words (`rate`). Null if some typed word matches nothing. */
    fun rate(typed: List<String>, name: List<String>, others: List<Pair<List<String>, Float>> = emptyList()): Match? {
        if (typed.isEmpty()) return null
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
            for ((words, weight) in others) for (part in words) best = maxOf(best, likeness(word, part) * weight)
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

    /** The playlists whose name has every typed word, or the start of it
     *  (`playlists_matching`). */
    fun playlistsMatching(query: String, playlists: List<Playlist>): List<Playlist> {
        val typed = words(query)
        if (typed.isEmpty()) return emptyList()
        return playlists.filter { playlist ->
            val name = words(playlist.name)
            typed.all { word -> name.any { it.startsWith(word) } }
        }
    }
}
