package org.medianexus.music.library

/**
 * A library made ready for searching, as the Windows app makes it (`Index` in
 * src/search.rs): every distinct word once, and each song, album and artist as the words of
 * its name. A search compares each typed word with each distinct word once, however many
 * songs use it, and then rates everything from those comparisons, with the same scores as
 * Windows ([SearchRank]), so both apps find the same things in the same order.
 *
 * What is indexed is said by position (the n-th song added), and so is what a search finds:
 * the caller holds the songs themselves, in whatever form suits it (the phone's copy of the
 * library keeps them compactly, see [SongTable]). Names that many songs share (an album, an
 * artist) are kept once. Plain Kotlin, so it can be tested on its own.
 */
class SearchIndex private constructor(
    /** Every distinct word, folded. */
    private val words: Array<String>,
    /** Each distinct name's words, as places in [words]: name `p` is
     *  `nameWords[nameAt[p] until nameAt[p + 1]]`. */
    private val nameAt: IntArray,
    private val nameWords: IntArray,
    private val songTitle: IntArray,
    private val songArtist: IntArray,
    private val songAlbum: IntArray,
    private val albumName: IntArray,
    private val albumArtist: IntArray,
    private val artistName: IntArray,
) {
    val songCount: Int get() = songTitle.size
    val albumCount: Int get() = albumName.size
    val artistCount: Int get() = artistName.size

    /** What a search found, best first, by position; `artistFirst` as Windows' `Results`:
     *  an artist matches what was typed at least as well as any song's title does. */
    class Hits(val artists: IntArray, val albums: IntArray, val songs: IntArray, val artistFirst: Boolean) {
        companion object {
            val NONE = Hits(IntArray(0), IntArray(0), IntArray(0), false)
        }
    }

    /**
     * Finds what matches `query`, best first, at most so many of each (Windows' `search`).
     * `liked` says whether the song at a place is liked: those come ahead of others that
     * match as well. The `*Allowed` tests, when given, leave out what isn't to be found at
     * all (offline, what doesn't play). `cancelled` is asked now and then: a search that
     * more typing has replaced stops part-way, and its answer is null.
     */
    fun search(
        query: String,
        liked: (Int) -> Boolean,
        artists: Int,
        albums: Int,
        songs: Int,
        songAllowed: ((Int) -> Boolean)? = null,
        albumAllowed: ((Int) -> Boolean)? = null,
        artistAllowed: ((Int) -> Boolean)? = null,
        cancelled: () -> Boolean = { false },
    ): Hits? {
        val typed = SearchRank.words(query)
        if (typed.isEmpty()) return Hits.NONE
        // Compare each typed word with each distinct word once, however many songs use it.
        val scores = Array(typed.size) { t -> FloatArray(words.size) { w -> SearchRank.likeness(typed[t], words[w]) } }
        if (cancelled()) return null
        // And each typed word's best in every distinct name, once: an album's name, or an
        // artist's, is shared by many songs.
        val names = nameAt.size - 1
        val best = Array(typed.size) { t ->
            val of = scores[t]
            FloatArray(names) { p ->
                var b = 0f
                for (k in nameAt[p] until nameAt[p + 1]) b = maxOf(b, of[nameWords[k]])
                b
            }
        }
        if (cancelled()) return null
        val rater = Rater(scores, best)

        var topTitle = 0f
        val songHits = Top(songs)
        for (index in songTitle.indices) {
            if (index and 2047 == 0 && cancelled()) return null
            if (songAllowed != null && !songAllowed(index)) continue
            if (!rater.rate(songTitle[index], songArtist[index], 0.9f, songAlbum[index], 0.8f)) continue
            topTitle = maxOf(topTitle, rater.name)
            songHits.offer(rater.score + if (liked(index)) 0.25f else 0f, index)
        }

        var topArtist = 0f
        val artistHits = Top(artists)
        for (index in artistName.indices) {
            if (artistAllowed != null && !artistAllowed(index)) continue
            if (!rater.rate(artistName[index], -1, 0f, -1, 0f)) continue
            topArtist = maxOf(topArtist, rater.score)
            artistHits.offer(rater.score, index)
        }

        val albumHits = Top(albums)
        for (index in albumName.indices) {
            if (albumAllowed != null && !albumAllowed(index)) continue
            if (!rater.rate(albumName[index], albumArtist[index], 0.85f, -1, 0f)) continue
            albumHits.offer(rater.score, index)
        }
        if (cancelled()) return null
        return Hits(artistHits.places(), albumHits.places(), songHits.places(), topArtist > 0f && topArtist >= topTitle)
    }

    /**
     * Scores one thing whose name is name `name`, which also answers to up to two other names
     * with how much each counts (an artist's or album's; -1 for none), against the typed
     * words (Windows' `rate`). Leaves the result in [score] and [name] (by the name alone,
     * 0 if some typed word isn't in it), and says whether it matched at all.
     */
    private inner class Rater(private val scores: Array<FloatArray>, private val best: Array<FloatArray>) {
        var score = 0f
        var name = 0f

        fun rate(name: Int, other: Int, otherWeight: Float, second: Int, secondWeight: Float): Boolean {
            val from = nameAt[name]
            val length = nameAt[name + 1] - from
            // Nothing to do for something one typed word doesn't match anywhere: most things.
            for (t in scores.indices) {
                val b = best[t]
                if (b[name] <= 0f && (other < 0 || b[other] * otherWeight <= 0f) && (second < 0 || b[second] * secondWeight <= 0f)) return false
            }
            var total = 0f
            var nameTotal = 0f
            var inName = true
            // Which of the name's words were what a typed word matched best.
            var used = 0L
            for (t in scores.indices) {
                val of = scores[t]
                var bestName = 0f
                var at = -1
                for (position in 0 until length) {
                    val s = of[nameWords[from + position]]
                    if (s > bestName) {
                        bestName = s
                        at = position
                    }
                }
                var b = bestName
                if (other >= 0) b = maxOf(b, best[t][other] * otherWeight)
                if (second >= 0) b = maxOf(b, best[t][second] * secondWeight)
                if (b <= 0f) return false
                total += b
                if (bestName > 0f) {
                    nameTotal += bestName
                    if (bestName >= b && at in 0 until 64) used = used or (1L shl at)
                } else {
                    inName = false
                }
            }
            val typed = scores.size.toFloat()
            // Bonuses for the typed words making up more of the name: all of it, or its start.
            val covered = if (length == 0) 0f else java.lang.Long.bitCount(used).toFloat() / length
            var inOrder = length >= scores.size
            if (inOrder) for (t in scores.indices) if (scores[t][nameWords[from + t]] < 0.85f) { inOrder = false; break }
            var exact = inOrder && length == scores.size
            if (exact) for (t in scores.indices) if (scores[t][nameWords[from + t]] < 1f) { exact = false; break }
            // ...which counts for less the shakier the match with the name is.
            val bonus = (0.3f * covered + (if (exact) 0.5f else 0f) + (if (inOrder) 0.2f else 0f)) * nameTotal / typed
            score = total / typed + bonus
            this.name = if (inName) nameTotal / typed + bonus else 0f
            return true
        }
    }

    /** The best `keep` offered, best first; equal scores in the order offered (the library's
     *  own order, as Windows keeps them). Most things offered don't make it, so each is
     *  weighed against the worst kept, not sorted with everything else. */
    private class Top(private val keep: Int) {
        private val scores = FloatArray(keep.coerceAtLeast(0))
        private val places = IntArray(keep.coerceAtLeast(0))
        private var size = 0

        fun offer(score: Float, place: Int) {
            if (keep <= 0) return
            if (size == keep && score <= scores[size - 1]) return
            // Where it goes: after every one at least as good (offered earlier, if equal).
            var at = if (size == keep) size - 1 else size
            while (at > 0 && scores[at - 1] < score) {
                if (at < keep) {
                    scores[at] = scores[at - 1]
                    places[at] = places[at - 1]
                }
                at--
            }
            scores[at] = score
            places[at] = place
            if (size < keep) size++
        }

        fun places(): IntArray = places.copyOf(size)
    }

    /** Puts an index together: songs, albums and artists, each in the order they are to be
     *  numbered. The words of a name many share are worked out once. */
    class Builder {
        private val wordIds = HashMap<String, Int>()
        private val words = ArrayList<String>()
        private val nameIds = HashMap<String, Int>()
        private var nameAt = IntArray(1024)
        private var names = 0
        private var nameWords = IntArray(4096)
        private var wordsUsed = 0
        private val songTitle = Ints()
        private val songArtist = Ints()
        private val songAlbum = Ints()
        private val albumName = Ints()
        private val albumArtist = Ints()
        private val artistName = Ints()

        private fun name(text: String): Int = nameIds.getOrPut(text) {
            val folded = SearchRank.words(text)
            if (names + 2 > nameAt.size) nameAt = nameAt.copyOf(nameAt.size * 2)
            if (wordsUsed + folded.size > nameWords.size) nameWords = nameWords.copyOf(maxOf(nameWords.size * 2, wordsUsed + folded.size))
            nameAt[names] = wordsUsed
            for (word in folded) {
                nameWords[wordsUsed++] = wordIds.getOrPut(word) { words += word; words.size - 1 }
            }
            names++
            nameAt[names] = wordsUsed
            names - 1
        }

        fun song(title: String, artist: String, album: String) = apply {
            songTitle += name(title)
            songArtist += name(artist)
            songAlbum += name(album)
        }

        /** An album, by its name and its artist (as its card names them). */
        fun album(name: String, artist: String) = apply {
            albumName += name(name)
            albumArtist += name(artist)
        }

        fun artist(name: String) = apply { artistName += name(name) }

        fun build(): SearchIndex = SearchIndex(
            words.toTypedArray(), nameAt.copyOf(names + 1), nameWords.copyOf(wordsUsed),
            songTitle.done(), songArtist.done(), songAlbum.done(), albumName.done(), albumArtist.done(), artistName.done(),
        )
    }

    /** A growing list of numbers, without a box for each. */
    private class Ints {
        private var all = IntArray(256)
        private var size = 0

        operator fun plusAssign(value: Int) {
            if (size == all.size) all = all.copyOf(size * 2)
            all[size++] = value
        }

        fun done(): IntArray = all.copyOf(size)
    }
}
