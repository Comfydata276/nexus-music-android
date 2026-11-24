package org.medianexus.music.library

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.medianexus.music.store.SongTable

/**
 * A mix made to rules of your own (Windows' `Rules` in src/mixes.rs, in its form in the
 * file). Every rule left at its starting value lets any song in.
 */
@Serializable
data class MixRules(
    /** Tells it apart from the others for good, whatever it's renamed to. */
    val id: Long = 0,
    val name: String = "",
    /** Part of a genre's name; empty for any. */
    val genre: String = "",
    /** At least this many stars; 0 for any. */
    val rating: Int = 0,
    /** Only songs you've liked. */
    val liked: Boolean = false,
    /** Released in these years; 0 for no limit at that end. */
    @SerialName("from_year") val fromYear: Int = 0,
    @SerialName("to_year") val toYear: Int = 0,
    /** A place in [Mixes.PLAYED]. */
    val played: Int = 0,
    /** A place in [Mixes.ADDED]. */
    val added: Int = 0,
    /** A place in [Mixes.ORDERS]. */
    val order: Int = 0,
    /** A place in [Mixes.SIZES]. */
    val size: Int = 0,
)

/**
 * One of the "Made for you" mixes: which it is, and its songs as places in the copy of the
 * library they were chosen from (a song is made only when one is shown or played).
 */
class Mix(
    /** Names the mix in its address, as on Windows: "on-repeat", "top", "rediscover",
     *  "genre:Rock", "rule:<id>". */
    val id: String,
    val kind: Kind,
    /** A genre mix's genre; a mix of one's own's name. */
    val name: String,
    /** A mix of one's own's rules. */
    val rules: MixRules?,
    val table: SongTable,
    val places: IntArray,
) {
    enum class Kind { RULES, ON_REPEAT, TOP, REDISCOVER, GENRE }

    val size: Int get() = places.size

    /** The songs, as a list the pages and the player take (places in the table). */
    val songs: PlacedSongs by lazy { PlacedSongs.of(table, places) }

    /** What Home's card shows: the first song's cover that there is (Windows' `home`). */
    val cardCover: String get() = places.firstOrNull { table.coverArt(it).isNotEmpty() }?.let { table.coverArt(it) }.orEmpty()

    /** What the mix's page shows: the first song's (Windows' `mix`). */
    val pageCover: String get() = places.firstOrNull()?.let { table.coverArt(it) }.orEmpty()

    /** The total length, in seconds. */
    val seconds: Long get() = places.sumOf { table.duration(it).toLong() }

    /** The same mix, with only the songs `keep` says yes to, each in its place (offline: what
     *  can be played). */
    fun only(keep: (Int) -> Boolean): Mix = Mix(id, kind, name, rules, table, places.filter(keep).toIntArray())
}

/**
 * The "Made for you" row on Home: lists of songs put together from what's been played, a
 * port of the Windows app's `src/mixes.rs` with its test, rule for rule, so the phone and the
 * PC show the same mix on the same day when they know the same plays.
 *
 * They're built from the copy of the library on the phone (which carries how often and when
 * each song was last played, as the server last said) and from Recently played, read as
 * columns of the packed table without making a song of any of them. Plain Kotlin, so it can be
 * tested on its own; worked out off the main thread (see `MixBook`).
 *
 * One thing differs from Windows, and only in the order "Newest first" of a mix of one's own:
 * Windows compares the server's time text, character by character; the phone keeps the time to
 * the millisecond, not its text, and compares that. The two agree except between songs added
 * in the same second whose times the server wrote with different numbers of decimals.
 */
object Mixes {
    const val DAY = 24 * 3600L

    /** When a song was last played: the fewest days ago for each choice (-1 for "never
     *  played", 0 for any). Windows' `PLAYED`; the names are `mix_played` in the strings. */
    val PLAYED = longArrayOf(0, -1, 30, 182, 365)

    /** When a song came into the library: the most days ago (0 for any). Windows' `ADDED`. */
    val ADDED = longArrayOf(0, 30, 182, 365)

    /** How many orders there are (Windows' `ORDERS`: shuffled, most played first, newest
     *  first, longest since played first). */
    const val ORDERS = 4

    /** Windows' `SIZES`. */
    val SIZES = intArrayOf(25, 50, 100, 200)

    /** What a mix is made from. `liked` is whether a song (by id) is liked, as the likes now
     *  stand; `recent` is Recently played (song and seconds, newest first); `now` is in
     *  seconds since 1970. */
    class From(
        val table: SongTable,
        val liked: (String) -> Boolean,
        val recent: List<Pair<String, Long>>,
        val rules: List<MixRules>,
        val now: Long,
    )

    /** Every mix there's enough listening to make: yours first, then the ones made from your
     *  listening (Windows' `all`). */
    fun all(from: From): List<Mix> {
        val genres = topGenres(from.table).map { "genre:$it" }
        val own = from.rules.map { "rule:${java.lang.Long.toUnsignedString(it.id)}" }
        return (own + listOf("on-repeat", "top", "rediscover") + genres).mapNotNull { one(from, it) }
    }

    /** The mix with this id, if there's enough to make it from (Windows' `one`). */
    fun one(from: From, id: String): Mix? {
        // One made to rules is there as soon as it has been made, even while nothing fits it.
        if (id.startsWith("rule:")) {
            val wanted = id.removePrefix("rule:").toULongOrNull()?.toLong() ?: return null
            val rules = from.rules.firstOrNull { it.id == wanted } ?: return null
            return Mix(id, Mix.Kind.RULES, rules.name, rules, from.table, songs(rules, from))
        }
        val table = from.table
        val (kind, name, places, least) = when (id) {
            "on-repeat" -> Made(Mix.Kind.ON_REPEAT, "", onRepeat(table, from.recent, from.now), 5)
            "top" -> Made(Mix.Kind.TOP, "", topSongs(table), 10)
            "rediscover" -> Made(Mix.Kind.REDISCOVER, "", rediscover(table, from.liked, from.now), 10)
            else -> {
                val genre = id.takeIf { it.startsWith("genre:") }?.removePrefix("genre:") ?: return null
                Made(Mix.Kind.GENRE, genre, genreMix(table, genre, from.now), 15)
            }
        }
        return if (places.size >= least) Mix(id, kind, name, null, table, places) else null
    }

    private data class Made(val kind: Mix.Kind, val name: String, val places: IntArray, val least: Int)

    // ---- the mixes made from listening ----------------------------------------------------------

    /** What's been played most lately: songs played twice or more in the last month (by
     *  Recently played), most first, then the latest played. */
    internal fun onRepeat(table: SongTable, plays: List<Pair<String, Long>>, now: Long): IntArray {
        // How often each song was played in the last month, and when last.
        val counts = HashMap<String, LongArray>()
        for ((id, at) in plays) {
            if (now - at >= 30 * DAY) continue
            val entry = counts.getOrPut(id) { longArrayOf(0, Long.MIN_VALUE) }
            entry[0]++
            if (at > entry[1]) entry[1] = at
        }
        if (counts.isEmpty()) return IntArray(0)
        val found = IntList()
        for (i in 0 until table.size) {
            val count = counts[table.id(i)] ?: continue
            if (count[0] >= 2) found.add(i)
        }
        val repeated = found.toIntArray()
        // (A stable sort, most first, as Rust's `sort_by` with the pair reversed.)
        repeated.sortStably { a, b ->
            val x = counts.getValue(table.id(a))
            val y = counts.getValue(table.id(b))
            if (y[0] != x[0]) y[0].compareTo(x[0]) else y[1].compareTo(x[1])
        }
        return repeated.copyOf(minOf(30, repeated.size))
    }

    /** Your most played of all time: the fifty most played, by the server's count. */
    internal fun topSongs(table: SongTable): IntArray {
        val found = IntList()
        for (i in 0 until table.size) if (table.playCount(i) > 0) found.add(i)
        val played = found.toIntArray()
        played.sortStably { a, b -> table.playCount(b).compareTo(table.playCount(a)) }
        return played.copyOf(minOf(50, played.size))
    }

    /** Favourites you haven't played in a while: played three times or more (once, if
     *  liked), not in the last sixty days; forty of them, shuffled for the day. */
    internal fun rediscover(table: SongTable, liked: (String) -> Boolean, now: Long): IntArray {
        val old = IntList()
        for (i in 0 until table.size) {
            val plays = table.playCount(i)
            if (!(plays >= 3 || (plays >= 1 && liked(table.id(i))))) continue
            val last = table.playedSeconds(i)
            if (last > 0 && now - last > 60 * DAY) old.add(i)
        }
        val places = old.toIntArray()
        shuffleForToday(places, "rediscover", now)
        return places.copyOf(minOf(40, places.size))
    }

    /** The genres played most, with enough songs to make a mix of: two at most. */
    internal fun topGenres(table: SongTable): List<String> {
        val genres = HashMap<String, LongArray>()
        for (i in 0 until table.size) {
            val genre = table.genre(i)
            if (genre.isEmpty()) continue
            val entry = genres.getOrPut(genre) { LongArray(2) }
            entry[0] += table.playCount(i).toLong()
            entry[1]++
        }
        return genres.entries.filter { it.value[0] > 0 && it.value[1] >= 15 }
            .sortedWith { a, b -> if (a.value[0] != b.value[0]) b.value[0].compareTo(a.value[0]) else rustOrder(a.key, b.key) }
            .take(2).map { it.key }
    }

    /** Forty songs of a genre (its name exactly as the server gives it), shuffled for the
     *  day. */
    internal fun genreMix(table: SongTable, genre: String, now: Long): IntArray {
        val songs = IntList()
        for (i in 0 until table.size) if (table.genre(i) == genre) songs.add(i)
        val places = songs.toIntArray()
        shuffleForToday(places, genre, now)
        return places.copyOf(minOf(40, places.size))
    }

    // ---- mixes of one's own -----------------------------------------------------------------

    /** The songs these rules pick out, in the order asked for (Windows' `Rules::songs`). */
    fun songs(rules: MixRules, from: From): IntArray {
        val table = from.table
        val now = from.now
        val genre = SearchRank.fold(rules.genre.trim())
        val played = PLAYED[rules.played.coerceIn(0, PLAYED.size - 1)]
        val added = ADDED[rules.added.coerceIn(0, ADDED.size - 1)]
        // Whether each distinct genre has the words: asked once per genre, not per song.
        val genreFits = HashMap<String, Boolean>()
        val picked = IntList()
        for (i in 0 until table.size) {
            if (genre.isNotEmpty() && !genreFits.getOrPut(table.genre(i)) { SearchRank.fold(table.genre(i)).contains(genre) }) continue
            if (table.rating(i) < rules.rating) continue
            if (rules.liked && !from.liked(table.id(i))) continue
            val year = table.year(i)
            if (rules.fromYear != 0 && year < rules.fromYear) continue
            if (rules.toYear != 0 && !(year > 0 && year <= rules.toYear)) continue
            val fits = when (played) {
                0L -> true
                -1L -> table.playCount(i) == 0
                else -> table.playedSeconds(i).let { last -> last == 0L || now - last > played * DAY }
            }
            if (!fits) continue
            if (added != 0L && now - table.createdSeconds(i) > added * DAY) continue
            picked.add(i)
        }
        val places = picked.toIntArray()
        when (rules.order) {
            1 -> places.sortStably { a, b -> if (table.playCount(a) != table.playCount(b)) table.playCount(b).compareTo(table.playCount(a)) else rustOrder(table.title(a), table.title(b)) }
            2 -> places.sortStably { a, b -> if (table.createdMillis(a) != table.createdMillis(b)) table.createdMillis(b).compareTo(table.createdMillis(a)) else rustOrder(table.title(a), table.title(b)) }
            3 -> places.sortStably { a, b -> table.playedSeconds(a).compareTo(table.playedSeconds(b)) }
            else -> shuffleForToday(places, "rule:${java.lang.Long.toUnsignedString(rules.id)}", now)
        }
        val size = SIZES[rules.size.coerceIn(0, SIZES.size - 1)]
        return places.copyOf(minOf(size, places.size))
    }

    /** The words a mix of one's own's rules are put in, under its name (Windows'
     *  `Rules::summary`; the strings are the app's resources, Windows' words). */
    class RuleWords(
        /** "5 stars". */
        val fiveStars: String,
        /** "%d stars or more". */
        val starsOrMore: String,
        val liked: String,
        /** "from %d on". */
        val fromOn: String,
        /** "up to %d". */
        val upTo: String,
        /** "%1$d to %2$d". */
        val years: String,
        /** [PLAYED]'s names: "Any time", "Never played", … */
        val played: List<String>,
        /** "added %s". */
        val addedWhen: String,
        /** [ADDED]'s names. */
        val added: List<String>,
        /** The orders' names: "Shuffled", "Most played first", … */
        val orders: List<String>,
        /** "Any song, %s". */
        val anySong: String,
    )

    /** The rules in words, for under the mix's name: "rock, not in the last month, most
     *  played first"; "Any song, shuffled". */
    fun summary(rules: MixRules, words: RuleWords): String {
        val parts = ArrayList<String>()
        val genre = rules.genre.trim()
        if (genre.isNotEmpty()) parts += genre
        when (rules.rating) {
            0 -> {}
            5 -> parts += words.fiveStars
            else -> parts += String.format(Locale.ROOT, words.starsOrMore, rules.rating)
        }
        if (rules.liked) parts += words.liked
        val from = rules.fromYear
        val to = rules.toYear
        when {
            from == 0 && to == 0 -> {}
            to == 0 -> parts += String.format(Locale.ROOT, words.fromOn, from)
            from == 0 -> parts += String.format(Locale.ROOT, words.upTo, to)
            from == to -> parts += from.toString()
            else -> parts += String.format(Locale.ROOT, words.years, from, to)
        }
        if (rules.played > 0) parts += words.played[rules.played.coerceAtMost(words.played.size - 1)].lowercase(Locale.ROOT)
        if (rules.added > 0) parts += String.format(Locale.ROOT, words.addedWhen, words.added[rules.added.coerceAtMost(words.added.size - 1)].lowercase(Locale.ROOT))
        parts += words.orders[rules.order.coerceIn(0, words.orders.size - 1)].lowercase(Locale.ROOT)
        return if (parts.size == 1) String.format(Locale.ROOT, words.anySong, parts[0]) else parts.joinToString(", ")
    }

    /** The years typed for a mix's rule (Windows' `set_rule_text`): "1990-1999", "2010-",
     *  "-1985" or one year; anything that isn't a year of four figures is no limit (0). */
    fun years(text: String): Pair<Int, Int> {
        fun year(part: String) = part.trim().toIntOrNull()?.takeIf { it in 1000..9999 } ?: 0
        val trimmed = text.trim()
        val split = trimmed.indexOfFirst { it == '-' || it == '–' }
        return if (split < 0) year(trimmed) to year(trimmed) else year(trimmed.substring(0, split)) to year(trimmed.substring(split + 1))
    }

    // ---- the day's shuffle -------------------------------------------------------------------

    /**
     * Shuffles the same way all day (the day by the clock's UTC date, as Windows' `now / DAY`),
     * so a mix doesn't change each time it's opened: Windows' `shuffle_for_today`, which seeds
     * fastrand 2.5's generator with the day and the mix's id and shuffles with it. Both are
     * reproduced here bit for bit ([WyRand]), so the same songs come out in the same order.
     */
    fun shuffleForToday(places: IntArray, id: String, now: Long) {
        var seed = now / DAY
        for (byte in id.toByteArray(Charsets.UTF_8)) seed = seed * 31 + (byte.toLong() and 0xFF)
        val rng = WyRand(seed)
        // fastrand's `shuffle`: each place from the second on swapped with one at or before it.
        for (i in 1 until places.size) {
            val j = rng.below((i + 1).toLong()).toInt()
            val t = places[i]
            places[i] = places[j]
            places[j] = t
        }
    }

    /** Rust's order for text (`str::cmp`: by UTF-8 bytes, which is by code point), where
     *  Kotlin's compares UTF-16 units and puts some characters elsewhere. */
    fun rustOrder(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }
}

/**
 * fastrand 2.5's generator (wyrand), as Windows' mixes use it: `with_seed`, `gen_u64` and
 * `gen_mod_u64` (Lemire's way of taking a number below `n` without bias), in 64-bit unsigned
 * arithmetic on Kotlin's signed longs.
 */
internal class WyRand(private var state: Long) {
    fun next(): Long {
        val s = state + C0
        state = s
        val t = s xor C1
        return (s * t) xor mulHigh(s, t)
    }

    /** A number at least 0 and below `n` (unsigned; `n` above 0). */
    fun below(n: Long): Long {
        var r = next()
        var hi = mulHigh(r, n)
        var lo = r * n
        if (java.lang.Long.compareUnsigned(lo, n) < 0) {
            val t = java.lang.Long.remainderUnsigned(-n, n)
            while (java.lang.Long.compareUnsigned(lo, t) < 0) {
                r = next()
                hi = mulHigh(r, n)
                lo = r * n
            }
        }
        return hi
    }

    private companion object {
        const val C0 = 0x2d358dccaa6c78a5L
        val C1 = 0x8bb84b93962eacc9uL.toLong()

        /** The top 64 bits of the unsigned 128-bit product. */
        fun mulHigh(a: Long, b: Long): Long {
            val aLo = a and 0xFFFFFFFFL
            val aHi = a ushr 32
            val bLo = b and 0xFFFFFFFFL
            val bHi = b ushr 32
            val ll = aLo * bLo
            val lh = aLo * bHi
            val hl = aHi * bLo
            val mid = (ll ushr 32) + (lh and 0xFFFFFFFFL) + (hl and 0xFFFFFFFFL)
            return aHi * bHi + (lh ushr 32) + (hl ushr 32) + (mid ushr 32)
        }
    }
}
