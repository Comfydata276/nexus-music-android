package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.medianexus.music.net.Playlist

/**
 * Text is folded for search exactly as the Windows app folds it (`fold` in src/search.rs), a
 * character (a code point) at a time, so both apps find the same things. Every expected value
 * here is what Rust itself made of the same text: the Windows app's `fold`, `words` and
 * `likeness`, copied into a program of their own and run on these cases (November 2026, Rust
 * 1.100). Written as Rust writes them (`\u{20BB7}`), so they can be checked against it again.
 */
class FoldTest {
    private class Case(source: String, folded: String, words: String) {
        val source = rust(source)
        val folded = rust(folded)
        val words = if (words.isEmpty()) emptyList() else words.split('|').map(::rust)
    }

    private val cases = listOf(
        Case("\\u{20BB7}", "\\u{20BB7}", "\\u{20BB7}"),
        Case("\\u{20BB7}\\u{91CE}\\u{5BB6}", "\\u{20BB7}\\u{91CE}\\u{5BB6}", "\\u{20BB7}\\u{91CE}\\u{5BB6}"),
        Case("\\u{39F}\\u{3A3}", "\\u{3BF}\\u{3C3}", "\\u{3BF}\\u{3C3}"),
        Case("\\u{39F}\\u{394}\\u{39F}\\u{3A3} \\u{3A0}\\u{3A1}\\u{39F}\\u{3A3}", "\\u{3BF}\\u{3B4}\\u{3BF}\\u{3C3} \\u{3C0}\\u{3C1}\\u{3BF}\\u{3C3}", "\\u{3BF}\\u{3B4}\\u{3BF}\\u{3C3}|\\u{3C0}\\u{3C1}\\u{3BF}\\u{3C3}"),
        Case("\\u{3A3}\\u{3AF}\\u{3C3}\\u{3C5}\\u{3C6}\\u{3BF}\\u{3C2}", "\\u{3C3}\\u{3AF}\\u{3C3}\\u{3C5}\\u{3C6}\\u{3BF}\\u{3C2}", "\\u{3C3}\\u{3AF}\\u{3C3}\\u{3C5}\\u{3C6}\\u{3BF}\\u{3C2}"),
        Case("x\\u{B2}", "x\\u{B2}", "x\\u{B2}"),
        Case("H\\u{2082}O", "h\\u{2082}o", "h\\u{2082}o"),
        Case("\\u{216B} \\u{2163}", "\\u{217B} \\u{2173}", "\\u{217B}|\\u{2173}"),
        Case("\\u{BD} Price", "\\u{BD} price", "\\u{BD}|price"),
        Case("Beyonc\\u{E9} \\u{2014} D\\u{E9}j\\u{E0} Vu!", "beyonce   deja vu ", "beyonce|deja|vu"),
        Case("Don\\u{2019}t Stop", "dont stop", "dont|stop"),
        Case("\\u{130}stanbul", "i stanbul", "i|stanbul"),
        Case("D\\u{130}YARBAKIR \\u{131}l\\u{131}k", "di yarbakir ilik", "di|yarbakir|ilik"),
        Case("Stra\\u{DF}e", "strasse", "strasse"),
        Case("\\u{C6}on \\u{152}uvre", "aeon oeuvre", "aeon|oeuvre"),
        Case("\\u{1C4}emal", "\\u{1C6}emal", "\\u{1C6}emal"),
        Case("\\u{1C5}", "\\u{1C6}", "\\u{1C6}"),
        Case("\\u{24B6} \\u{24B7}", "\\u{24D0} \\u{24D1}", "\\u{24D0}|\\u{24D1}"),
        Case("\\u{FB01}ne \\u{FB02}ow", "\\u{FB01}ne \\u{FB02}ow", "\\u{FB01}ne|\\u{FB02}ow"),
        Case("\\u{C5}ngstr\\u{F6}m", "angstrom", "angstrom"),
        Case("\\u{FF26}\\u{FF55}\\u{FF4C}\\u{FF4C}\\u{FF57}\\u{FF49}\\u{FF44}\\u{FF54}\\u{FF48} \\u{FF11}\\u{FF12}\\u{FF13}", "\\u{FF46}\\u{FF55}\\u{FF4C}\\u{FF4C}\\u{FF57}\\u{FF49}\\u{FF44}\\u{FF54}\\u{FF48} \\u{FF11}\\u{FF12}\\u{FF13}", "\\u{FF46}\\u{FF55}\\u{FF4C}\\u{FF4C}\\u{FF57}\\u{FF49}\\u{FF44}\\u{FF54}\\u{FF48}|\\u{FF11}\\u{FF12}\\u{FF13}"),
        Case("\\u{939}\\u{93F}\\u{928}\\u{94D}\\u{926}\\u{940} \\u{917}\\u{93E}\\u{928}\\u{93E}", "\\u{939}\\u{93F}\\u{928} \\u{926}\\u{940} \\u{917}\\u{93E}\\u{928}\\u{93E}", "\\u{939}\\u{93F}\\u{928}|\\u{926}\\u{940}|\\u{917}\\u{93E}\\u{928}\\u{93E}"),
        Case("\\u{BA4}\\u{BAE}\\u{BBF}\\u{BB4}\\u{BCD}", "\\u{BA4}\\u{BAE}\\u{BBF}\\u{BB4} ", "\\u{BA4}\\u{BAE}\\u{BBF}\\u{BB4}"),
        Case("\\u{E44}\\u{E17}\\u{E22} \\u{E40}\\u{E1E}\\u{E25}\\u{E07}", "\\u{E44}\\u{E17}\\u{E22} \\u{E40}\\u{E1E}\\u{E25}\\u{E07}", "\\u{E44}\\u{E17}\\u{E22}|\\u{E40}\\u{E1E}\\u{E25}\\u{E07}"),
        Case("\\u{627}\\u{644}\\u{639}\\u{631}\\u{628}\\u{64A}\\u{629} \\u{661}\\u{662}\\u{663}", "\\u{627}\\u{644}\\u{639}\\u{631}\\u{628}\\u{64A}\\u{629} \\u{661}\\u{662}\\u{663}", "\\u{627}\\u{644}\\u{639}\\u{631}\\u{628}\\u{64A}\\u{629}|\\u{661}\\u{662}\\u{663}"),
        Case("\\u{5E2}\\u{5D1}\\u{5E8}\\u{5D9}\\u{5EA}", "\\u{5E2}\\u{5D1}\\u{5E8}\\u{5D9}\\u{5EA}", "\\u{5E2}\\u{5D1}\\u{5E8}\\u{5D9}\\u{5EA}"),
        Case("\\u{41F}\\u{440}\\u{438}\\u{432}\\u{435}\\u{442}, \\u{43C}\\u{438}\\u{440}!", "\\u{43F}\\u{440}\\u{438}\\u{432}\\u{435}\\u{442}  \\u{43C}\\u{438}\\u{440} ", "\\u{43F}\\u{440}\\u{438}\\u{432}\\u{435}\\u{442}|\\u{43C}\\u{438}\\u{440}"),
        Case("\\u{401}\\u{43B}\\u{43A}\\u{430} \\u{401}\\u{416}", "\\u{451}\\u{43B}\\u{43A}\\u{430} \\u{451}\\u{436}", "\\u{451}\\u{43B}\\u{43A}\\u{430}|\\u{451}\\u{436}"),
        Case("\\u{395}\\u{3BB}\\u{3BB}\\u{3B7}\\u{3BD}\\u{3B9}\\u{3BA}\\u{3AC} \\u{386}\\u{388}\\u{389}", "\\u{3B5}\\u{3BB}\\u{3BB}\\u{3B7}\\u{3BD}\\u{3B9}\\u{3BA}\\u{3AC} \\u{3AC}\\u{3AD}\\u{3AE}", "\\u{3B5}\\u{3BB}\\u{3BB}\\u{3B7}\\u{3BD}\\u{3B9}\\u{3BA}\\u{3AC}|\\u{3AC}\\u{3AD}\\u{3AE}"),
        Case("\\u{D55C}\\u{AD6D}\\u{C5B4} \\u{B178}\\u{B798}", "\\u{D55C}\\u{AD6D}\\u{C5B4} \\u{B178}\\u{B798}", "\\u{D55C}\\u{AD6D}\\u{C5B4}|\\u{B178}\\u{B798}"),
        Case("\\u{65E5}\\u{672C}\\u{8A9E}\\u{306E}\\u{6B4C}", "\\u{65E5}\\u{672C}\\u{8A9E}\\u{306E}\\u{6B4C}", "\\u{65E5}\\u{672C}\\u{8A9E}\\u{306E}\\u{6B4C}"),
        Case("\\u{30AB}\\u{30BF}\\u{30AB}\\u{30CA} \\u{FF76}\\u{FF80}\\u{FF76}\\u{FF85}", "\\u{30AB}\\u{30BF}\\u{30AB}\\u{30CA} \\u{FF76}\\u{FF80}\\u{FF76}\\u{FF85}", "\\u{30AB}\\u{30BF}\\u{30AB}\\u{30CA}|\\u{FF76}\\u{FF80}\\u{FF76}\\u{FF85}"),
        Case("\\u{1C5}ungla", "\\u{1C6}ungla", "\\u{1C6}ungla"),
        Case("\\u{1D504}\\u{1D51F}\\u{1D520}", "\\u{1D504}\\u{1D51F}\\u{1D520}", "\\u{1D504}\\u{1D51F}\\u{1D520}"),
        Case("\\u{1F600} smile", "  smile", "smile"),
        Case("e\\u{301}te", "e te", "e|te"),
        Case("\\u{1FBC}", "\\u{1FB3}", "\\u{1FB3}"),
        Case("\\u{1E9E}", "ss", "ss"),
        Case("\\u{13A0}\\u{13A1}", "\\u{AB70}\\u{AB71}", "\\u{AB70}\\u{AB71}"),
        Case("\\u{1C90}\\u{1C91}", "\\u{10D0}\\u{10D1}", "\\u{10D0}\\u{10D1}"),
        Case("\\u{212A}\\u{2126}", "k\\u{3C9}", "k\\u{3C9}"),
        Case("\\u{663}\\u{664}", "\\u{663}\\u{664}", "\\u{663}\\u{664}"),
        Case("\\u{3007}\\u{3021}", "\\u{3007}\\u{3021}", "\\u{3007}\\u{3021}"),
        Case("\\u{AA}\\u{BA}", "\\u{AA}\\u{BA}", "\\u{AA}\\u{BA}"),
        Case("\\u{2B0}\\u{2B1}", "\\u{2B0}\\u{2B1}", "\\u{2B0}\\u{2B1}"),
        Case("\\u{345}", "\\u{345}", "\\u{345}"),
        Case("x\\u{B9}\\u{B3}", "x\\u{B9}\\u{B3}", "x\\u{B9}\\u{B3}"),
        Case("\\u{2460}\\u{2461}", "\\u{2460}\\u{2461}", "\\u{2460}\\u{2461}"),
        Case("\\u{F20}", "\\u{F20}", "\\u{F20}"),
        Case("\\u{140A}\\u{1403}", "\\u{140A}\\u{1403}", "\\u{140A}\\u{1403}"),
        Case("\\u{1820}\\u{1821}", "\\u{1820}\\u{1821}", "\\u{1820}\\u{1821}"),
        Case("\\u{1F1EC}\\u{1F1E7}", "  ", ""),
        Case("\\u{10400}", "\\u{10428}", "\\u{10428}"),
        Case("Tsch\\u{FC}\\u{DF}!", "tschuss ", "tschuss"),
    )

    @Test
    fun foldsAsRustDoesAcrossScripts() {
        for (case in cases) {
            assertEquals("fold of ${show(case.source)}", show(case.folded), show(SearchRank.fold(case.source)))
            assertEquals("words of ${show(case.source)}", case.words.map(::show), SearchRank.words(case.source).map(::show))
        }
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneIsKept() {
        // UTF-16 has it as two halves, neither a letter: walked a half at a time it was lost.
        assertEquals("𠮷", SearchRank.fold("𠮷"))
        assertEquals(listOf("𠮷"), SearchRank.words("𠮷"))
    }

    @Test
    fun aFinalSigmaIsFoldedAsAnyOther() {
        // Lower-cased a character at a time, Σ is σ wherever it stands, as in Rust; lower-casing
        // the whole word made the last one ς, and "οσ" didn't find "ΟΣ".
        assertEquals("οσ", SearchRank.fold("ΟΣ"))
        assertEquals(1f, SearchRank.likeness("οσ", SearchRank.fold("ΟΣ")))
    }

    @Test
    fun numbersOfEveryKindAreKept() {
        // Rust's "alphanumeric" is a letter or any number: ² and ½ and Ⅻ, not only 0 to 9.
        assertEquals("x²", SearchRank.fold("x²"))
        assertEquals("½", SearchRank.fold("½"))
        assertEquals("ⅻ", SearchRank.fold("Ⅻ"))
    }

    @Test
    fun likenessCountsCodePoints() {
        // What Rust made of each pair (the typed word first).
        val pairs = listOf(
            Triple("\\u{20BB7}", "\\u{20BB7}\\u{91CE}\\u{5BB6}", 0.8833334f),
            Triple("\\u{20BB7}\\u{91CE}", "\\u{20BB7}\\u{91CE}\\u{5BB6}", 0.9166667f),
            Triple("\\u{3BF}\\u{3C3}", "\\u{3BF}\\u{3C3}", 1f),
            Triple("abcd", "abce", 0.75f),
            Triple("\\u{20BB7}\\u{20BB7}\\u{20BB7}\\u{20BB7}", "\\u{20BB7}\\u{20BB7}\\u{20BB7}\\u{20BB7}\\u{20BB7}", 0.93f),
            // Two characters are too few to forgive a slip in: four halves of UTF-16 were not.
            Triple("\\u{20BB7}\\u{20BB7}", "\\u{20BB7}\\u{20BB6}", 0f),
            Triple("\\u{20BB7}\\u{20BB7}\\u{20BB7}\\u{20BB7}", "\\u{20BB7}\\u{20BB7}\\u{20BB6}\\u{20BB7}", 0.75f),
            Triple("\\u{1D504}\\u{1D51F}\\u{1D520}\\u{1D521}", "\\u{1D504}\\u{1D51F}\\u{1D521}\\u{1D520}", 0.75f),
            Triple("abc\\u{1D521}", "abcd", 0.75f),
        )
        for ((typed, word, like) in pairs) assertEquals("${show(rust(typed))} for ${show(rust(word))}", like, SearchRank.likeness(rust(typed), rust(word)), 1e-6f)
    }

    @Test
    fun theIndexFindsWhatWindowsFinds() {
        val index = SearchIndex.Builder()
            .song("𠮷", "月光トリオ", "夜の散歩")
            .song("ΟΣ", "Kanon", "Kanon")
            .song("x² Remix", "Square", "Powers")
            .song("Something else", "Someone", "Somewhere")
            .build()
        fun titles(query: String) = index.search(query, { false }, 5, 5, 5)!!.songs.toList()
        assertEquals(listOf(0), titles("𠮷"))
        assertEquals(listOf(1), titles("οσ"))
        assertEquals(listOf(1), titles("ΟΣ"))
        assertEquals(listOf(2), titles("x²"))
    }

    @Test
    fun playlistsFoundAreListedPinnedFirstThenByName() {
        // As Windows lists them in its sidebar, which its search looks through in that order.
        val lists = listOf("Zebra mix", "alpha mix", "Mix tape", "Beta mix", "Other").map { Playlist(id = it, name = it) }
        val view = org.medianexus.music.ui.search.SearchView.of("mix", org.medianexus.music.net.Found(), lists, org.medianexus.music.ui.search.SearchKind.ALL, pinned = listOf("Mix tape", "Zebra mix"))
        assertEquals(listOf("Mix tape", "Zebra mix", "alpha mix", "Beta mix"), view.playlists.map { it.name })
        val only = org.medianexus.music.ui.search.SearchView.of("mix", org.medianexus.music.net.Found(), lists, org.medianexus.music.ui.search.SearchKind.PLAYLISTS)
        assertEquals(listOf("alpha mix", "Beta mix", "Mix tape", "Zebra mix"), only.playlists.map { it.name })
    }

    companion object {
        /** Text written as Rust prints it: `\u{...}` for each character outside plain ASCII. */
        fun rust(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                if (text.startsWith("\\u{", i)) {
                    val end = text.indexOf('}', i)
                    out.appendCodePoint(text.substring(i + 3, end).toInt(16))
                    i = end + 1
                } else {
                    out.append(text[i++])
                }
            }
            return out.toString()
        }

        /** Every character by its number, so a failure says what differed. */
        fun show(text: String): String = text.codePoints().toArray().joinToString(" ") { if (it in 0x21..0x7e) it.toChar().toString() else "U+%04X".format(it) }
    }
}
