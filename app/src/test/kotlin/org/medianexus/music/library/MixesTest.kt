package org.medianexus.music.library

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.medianexus.music.library.Mixes.DAY
import org.medianexus.music.net.Song
import org.medianexus.music.store.SongTable

/**
 * The mixes, against the Windows app's: its test of src/mixes.rs (`builds_mixes_from_listening`)
 * ported as it is, and the exact songs Windows chooses for one fixed library, set of plays and
 * day, in the exact order.
 *
 * Those answers were not worked out by hand: the Rust of src/mixes.rs, its functions copied as
 * they are onto a song of only the fields they read, was run with fastrand 2.5.0 (the version
 * Windows builds with, from its Cargo.lock) on the library made by [fixture], and printed
 * what it chose (the program is described in docs/TESTING.md, "Your stats and the mixes").
 */
class MixesTest {
    /** Windows' words for a mix's rules (`Rules::summary`). */
    private val words = Mixes.RuleWords(
        fiveStars = "5 stars", starsOrMore = "%d stars or more", liked = "liked", fromOn = "from %d on", upTo = "up to %d", years = "%1\$d to %2\$d",
        played = listOf("Any time", "Never played", "Not in the last month", "Not in the last six months", "Not in the last year"),
        addedWhen = "added %s", added = listOf("Any time", "In the last month", "In the last six months", "In the last year"),
        orders = listOf("Shuffled", "Most played first", "Newest first", "Longest since played first"),
        anySong = "Any song, %s",
    )

    private fun time(seconds: Long) = Instant.ofEpochSecond(seconds).toString()

    private fun table(songs: List<Song>) = SongTable.Builder().apply { songs.forEach { add(it) } }.build()

    private fun ids(mix: Mix?) = mix!!.places.map { mix.table.id(it) }

    private fun ids(table: SongTable, places: IntArray) = places.map { table.id(it) }

    @Test
    fun buildsMixesFromListening() {
        val now = 1_795_000_000L
        fun song(id: Int, genre: String, plays: Int, daysAgo: Long) = Song(
            id = id.toString(), title = "Song $id", genre = genre, playCount = plays,
            played = if (plays > 0) time(now - daysAgo * DAY) else "",
        )
        // Thirty rock songs played a lot long ago, twenty jazz songs played once this week,
        // and ten songs never played.
        val songs = (0 until 30).map { song(it, "Rock", 30 - it + 3, 90) } + (30 until 50).map { song(it, "Jazz", 1, 2) } + (50 until 60).map { song(it, "Folk", 0, 0) }
        val table = table(songs)
        var recent = emptyList<Pair<String, Long>>()
        var rules = emptyList<MixRules>()
        val from = { Mixes.From(table, { false }, recent, rules, now) }

        val top = Mixes.one(from(), "top")!!
        assertEquals(50, top.size)
        assertEquals("0", ids(top)[0])
        // Only what was played often and not lately is worth rediscovering.
        val old = Mixes.one(from(), "rediscover")!!
        assertEquals(30, old.size)
        assertTrue(old.places.all { table.genre(it) == "Rock" })
        // The same all day.
        assertEquals(ids(old), ids(Mixes.one(from(), "rediscover")))
        // Genres by how much they're played; one never played doesn't get a mix.
        assertEquals(listOf("Rock", "Jazz"), Mixes.topGenres(table))
        assertEquals(20, Mixes.one(from(), "genre:Jazz")!!.size)
        assertNull(Mixes.one(from(), "genre:Nothing"))

        // "On repeat" needs songs played more than once lately.
        assertNull(Mixes.one(from(), "on-repeat"))
        val plays = ArrayList<Pair<String, Long>>()
        for (id in 30 until 36) {
            plays += id.toString() to now - 100
            plays += id.toString() to now - 5000
        }
        plays += "36" to now - 50
        plays += "30" to now - 10
        // Old plays don't count.
        plays += "40" to now - 40 * DAY
        plays += "40" to now - 41 * DAY
        recent = plays
        val repeat = Mixes.one(from(), "on-repeat")!!
        assertEquals(6, repeat.size)
        assertEquals("30", ids(repeat)[0])
        assertEquals(listOf("on-repeat", "top", "rediscover", "genre:Rock", "genre:Jazz"), Mixes.all(from()).map { it.id })
        recent = emptyList()

        // A mix made to rules: rock not played in the last month, most played first.
        val rule = MixRules(id = 7, name = "Old rock", genre = "rock", played = 2, order = 1, size = 0)
        val picked = Mixes.songs(rule, from())
        assertEquals(25, picked.size)
        assertTrue(picked.all { table.genre(it) == "Rock" } && table.id(picked[0]) == "0")
        assertEquals("rock, not in the last month, most played first", Mixes.summary(rule, words))
        // Never played, any genre: the ten folk songs.
        val fresh = MixRules(played = 1)
        assertEquals(10, Mixes.songs(fresh, from()).size)
        assertEquals("never played, shuffled", Mixes.summary(fresh, words))
        assertEquals("Any song, shuffled", Mixes.summary(MixRules(), words))
        // Played in the last month counts as played lately; the jazz was two days ago.
        assertTrue(Mixes.songs(MixRules(genre = "jazz", played = 2), from()).isEmpty())
        // Once made it's among the mixes, ahead of the app's own, and has a page.
        rules = listOf(rule)
        assertEquals("rule:7" to "Old rock", Mixes.all(from()).first().let { it.id to it.name })
        assertEquals(rule, Mixes.one(from(), "rule:7")!!.rules)
        assertNull(Mixes.one(from(), "rule:8"))
    }

    @Test
    fun summariesInWindowsWords() {
        assertEquals("4 stars or more, liked, 1990 to 1999", Mixes.summary(MixRules(rating = 4, liked = true, fromYear = 1990, toYear = 1999, order = 0), words).removeSuffix(", shuffled"))
        assertEquals("5 stars, from 2010 on, added in the last six months, newest first", Mixes.summary(MixRules(rating = 5, fromYear = 2010, added = 2, order = 2), words))
        assertEquals("Jazz, up to 1985, not in the last year, longest since played first", Mixes.summary(MixRules(genre = " Jazz ", toYear = 1985, played = 4, order = 3), words))
        assertEquals("2001, shuffled", Mixes.summary(MixRules(fromYear = 2001, toYear = 2001), words))
        // (Windows says "1 stars or more", and so does the phone.)
        assertEquals("1 stars or more, shuffled", Mixes.summary(MixRules(rating = 1), words))
    }

    // ---- what Windows chooses ---------------------------------------------------------------

    private val now = 1_795_000_000L

    /** The library the Rust was run on, made the same way. */
    private fun fixture(): Pair<SongTable, (String) -> Boolean> {
        val genres = listOf("Rock", "Rock", "Jazz", "Indie Rock", "")
        val songs = (0 until 80).map { i ->
            val plays = (i * 7) % 11
            Song(
                id = "s%02d".format(i),
                title = "Song ${(i * 37) % 50}",
                genre = genres[i % 5],
                playCount = plays,
                played = if (plays > 0) time(now - ((i * 13) % 200) * DAY - i * 60L) else "",
                created = time(now - ((i * 17) % 400) * DAY - i),
                userRating = i % 6,
                year = if (i % 9 == 0) 0 else 1980 + (i * 3) % 45,
            )
        }
        val liked = songs.filterIndexed { i, _ -> i % 7 == 0 }.mapTo(HashSet()) { it.id }
        return table(songs) to { id: String -> id in liked }
    }

    private fun plays(): List<Pair<String, Long>> =
        (0 until 40).map { k -> "s%02d".format((k * 3) % 16) to now - k * 3000L } + listOf("s70" to now - 31 * DAY, "s70" to now - 32 * DAY)

    private fun list(text: String) = text.split(", ").map { it.trim('"') }

    @Test
    fun chosenAsWindowsChoosesThem() {
        val (table, liked) = fixture()
        val rules = listOf(
            MixRules(id = 7, name = "old rock", genre = "rock", played = 2, order = 1, size = 0),
            MixRules(id = 1_700_000_000_123, name = "any"),
            MixRules(id = 3, name = "rated newest", rating = 3, order = 2, size = 1),
            MixRules(id = 4, name = "years longest since", fromYear = 1990, toYear = 2010, added = 3, order = 3),
            MixRules(id = 5, name = "liked never", liked = true, played = 1),
            MixRules(id = 6, name = "jäzz", genre = " JÄZZ "),
        )
        val expected = mapOf(
            now to mapOf(
                "top" to "\"s03\", \"s14\", \"s25\", \"s36\", \"s47\", \"s58\", \"s69\", \"s06\", \"s17\", \"s28\", \"s39\", \"s50\", \"s61\", \"s72\", \"s09\", \"s20\", \"s31\", \"s42\", \"s53\", \"s64\", \"s75\", \"s01\", \"s12\", \"s23\", \"s34\", \"s45\", \"s56\", \"s67\", \"s78\", \"s04\", \"s15\", \"s26\", \"s37\", \"s48\", \"s59\", \"s70\", \"s07\", \"s18\", \"s29\", \"s40\", \"s51\", \"s62\", \"s73\", \"s10\", \"s21\", \"s32\", \"s43\", \"s54\", \"s65\", \"s76\"",
                "rediscover" to "\"s70\", \"s42\", \"s58\", \"s59\", \"s06\", \"s57\", \"s53\", \"s28\", \"s56\", \"s13\", \"s67\", \"s75\", \"s20\", \"s54\", \"s24\", \"s69\", \"s68\", \"s23\", \"s07\", \"s09\", \"s15\", \"s76\", \"s37\", \"s51\", \"s61\", \"s45\", \"s40\", \"s10\", \"s39\", \"s21\", \"s43\", \"s46\", \"s36\", \"s12\", \"s29\", \"s14\", \"s25\", \"s26\", \"s73\", \"s72\"",
                "on-repeat" to "\"s00\", \"s03\", \"s06\", \"s09\", \"s12\", \"s15\", \"s02\", \"s05\", \"s08\", \"s11\", \"s14\", \"s01\", \"s04\", \"s07\", \"s10\", \"s13\"",
                "genre:Rock" to "\"s01\", \"s46\", \"s76\", \"s55\", \"s35\", \"s41\", \"s16\", \"s65\", \"s20\", \"s51\", \"s05\", \"s25\", \"s75\", \"s00\", \"s31\", \"s56\", \"s26\", \"s50\", \"s66\", \"s06\", \"s15\", \"s10\", \"s61\", \"s30\", \"s60\", \"s36\", \"s11\", \"s70\", \"s21\", \"s45\", \"s40\", \"s71\"",
                "genre:Indie Rock" to "\"s73\", \"s33\", \"s58\", \"s03\", \"s53\", \"s23\", \"s13\", \"s43\", \"s78\", \"s63\", \"s28\", \"s18\", \"s38\", \"s08\", \"s48\", \"s68\"",
                "rule:7" to "\"s03\", \"s25\", \"s36\", \"s58\", \"s50\", \"s06\", \"s28\", \"s61\", \"s53\", \"s75\", \"s20\", \"s23\", \"s45\", \"s56\", \"s26\", \"s70\", \"s15\", \"s73\", \"s18\", \"s40\", \"s51\", \"s76\", \"s10\", \"s21\", \"s43\"",
                "rule:1700000000123" to "\"s50\", \"s15\", \"s60\", \"s52\", \"s48\", \"s25\", \"s53\", \"s24\", \"s56\", \"s49\", \"s35\", \"s78\", \"s59\", \"s37\", \"s77\", \"s67\", \"s40\", \"s28\", \"s73\", \"s32\", \"s31\", \"s21\", \"s14\", \"s64\", \"s22\"",
                "rule:3" to "\"s71\", \"s03\", \"s27\", \"s51\", \"s04\", \"s75\", \"s28\", \"s52\", \"s05\", \"s76\", \"s29\", \"s53\", \"s77\", \"s09\", \"s33\", \"s57\", \"s10\", \"s34\", \"s58\", \"s11\", \"s35\", \"s59\", \"s15\", \"s39\", \"s63\", \"s16\", \"s40\", \"s64\", \"s17\", \"s41\", \"s65\", \"s21\", \"s45\", \"s69\", \"s22\", \"s46\", \"s70\", \"s23\", \"s47\"",
                "rule:4" to "\"s55\", \"s66\", \"s10\", \"s25\", \"s40\", \"s24\", \"s39\", \"s08\", \"s38\", \"s07\", \"s53\", \"s68\", \"s37\", \"s06\", \"s52\", \"s21\", \"s67\", \"s05\", \"s51\", \"s20\", \"s35\", \"s04\", \"s50\", \"s19\", \"s65\"",
                "rule:5" to "\"s77\", \"s00\"",
                "rule:6" to "\"s27\", \"s22\", \"s57\", \"s37\", \"s67\", \"s52\", \"s62\", \"s32\", \"s72\", \"s02\", \"s12\", \"s77\", \"s47\", \"s17\", \"s07\", \"s42\"",
            ),
            // The next day, the shuffled ones are shuffled afresh; the rest are as they were.
            now + DAY to mapOf(
                "rediscover" to "\"s06\", \"s57\", \"s59\", \"s25\", \"s28\", \"s09\", \"s61\", \"s67\", \"s26\", \"s10\", \"s42\", \"s37\", \"s51\", \"s20\", \"s56\", \"s73\", \"s12\", \"s40\", \"s23\", \"s75\", \"s58\", \"s14\", \"s07\", \"s54\", \"s72\", \"s76\", \"s45\", \"s39\", \"s24\", \"s21\", \"s15\", \"s13\", \"s68\", \"s29\", \"s46\", \"s43\", \"s53\", \"s36\", \"s69\", \"s70\"",
                "genre:Rock" to "\"s76\", \"s75\", \"s30\", \"s66\", \"s10\", \"s25\", \"s45\", \"s40\", \"s31\", \"s65\", \"s11\", \"s50\", \"s20\", \"s61\", \"s06\", \"s01\", \"s55\", \"s15\", \"s21\", \"s71\", \"s26\", \"s56\", \"s46\", \"s36\", \"s51\", \"s60\", \"s41\", \"s05\", \"s35\", \"s16\", \"s00\", \"s70\"",
                "genre:Indie Rock" to "\"s48\", \"s63\", \"s13\", \"s23\", \"s53\", \"s18\", \"s43\", \"s58\", \"s08\", \"s03\", \"s68\", \"s33\", \"s38\", \"s28\", \"s73\", \"s78\"",
                "rule:1700000000123" to "\"s04\", \"s31\", \"s61\", \"s29\", \"s42\", \"s00\", \"s38\", \"s49\", \"s22\", \"s35\", \"s39\", \"s10\", \"s54\", \"s45\", \"s33\", \"s34\", \"s73\", \"s12\", \"s59\", \"s47\", \"s23\", \"s02\", \"s20\", \"s71\", \"s75\"",
                "rule:6" to "\"s67\", \"s07\", \"s72\", \"s12\", \"s42\", \"s62\", \"s02\", \"s17\", \"s57\", \"s32\", \"s47\", \"s22\", \"s52\", \"s37\", \"s77\", \"s27\"",
            ),
        )
        for ((day, mixes) in expected) {
            val from = Mixes.From(table, liked, plays(), rules, day)
            assertEquals(listOf("Rock", "Indie Rock"), Mixes.topGenres(table))
            for ((id, songs) in mixes) assertEquals("$id on day ${day / DAY}", list(songs), ids(Mixes.one(from, id)))
        }
        // In Windows' order: yours first, then On repeat, Your top songs, Rediscover, the genres.
        val all = Mixes.all(Mixes.From(table, liked, plays(), rules, now)).map { it.id }
        assertEquals(listOf("rule:7", "rule:1700000000123", "rule:3", "rule:4", "rule:5", "rule:6", "on-repeat", "top", "rediscover", "genre:Rock", "genre:Indie Rock"), all)
    }

    @Test
    fun theGeneratorIsFastrandsOwn() {
        val rng = WyRand(12345)
        assertEquals(listOf(-5289067109269188563L, -340019549039747806L, -3776373836737979653L, 4678979872595648440L), List(4) { rng.next() })
        val zero = WyRand(0)
        assertEquals(listOf(0L, 0L, 0L, 3L, 70L, 2L), listOf(1L, 2L, 3L, 10L, 1000L, 7L).map { zero.below(it) })
        val places = IntArray(20) { it }
        Mixes.shuffleForToday(places, "rediscover", now)
        assertEquals(listOf(6, 7, 9, 12, 0, 18, 17, 14, 3, 5, 16, 13, 8, 19, 11, 15, 4, 10, 1, 2), places.toList())
    }

    @Test
    fun yearsAreReadAsWindowsReadsThem() {
        assertEquals(1990 to 1999, Mixes.years("1990-1999"))
        assertEquals(2010 to 0, Mixes.years("2010-"))
        assertEquals(0 to 1985, Mixes.years("-1985"))
        assertEquals(2001 to 2001, Mixes.years(" 2001 "))
        assertEquals(1990 to 1999, Mixes.years("1990 – 1999"))
        assertEquals(0 to 0, Mixes.years(""))
        assertEquals(0 to 0, Mixes.years("nineties"))
        assertEquals(0 to 2000, Mixes.years("99-2000"))
    }

    @Test
    fun textIsInRustsOrder() {
        assertTrue(Mixes.rustOrder("a", "b") < 0)
        assertTrue(Mixes.rustOrder("ab", "a") > 0)
        assertEquals(0, Mixes.rustOrder("é", "é"))
        // A character past the surrogates (U+FF21, Ａ) comes before one that needs two
        // (U+1F600) by code point, as by UTF-8 bytes; by UTF-16 units it would come after.
        assertTrue(Mixes.rustOrder("Ａ", "😀") < 0)
        assertTrue("Ａ" > "😀")
    }
}
