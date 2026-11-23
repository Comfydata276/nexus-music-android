package org.medianexus.music.playback

import org.medianexus.music.BuildConfig
import org.medianexus.music.store.CoverProvider
import org.medianexus.music.store.CoverStore

/**
 * Keeps every folder the car can open small enough to arrive whole.
 *
 * A car asks for a folder the old way, with no paging, and Media3 answers by asking the app
 * for everything and then keeping only as many items as fit in 256 KiB once written out for
 * the journey to another app (`MediaUtils.truncateListBySize`). What doesn't fit is dropped
 * without a word: a long playlist shows a plausible first few hundred songs and no way to
 * the rest. So nothing longer than fits is ever handed over. A list that is too long is
 * shown as numbered ranges ("1–200", "201–400"), each a folder that does fit, and when
 * there are too many ranges for one folder, as ranges of ranges.
 *
 * How big an item is on that journey is worked out here ([bytes]), from how Android writes a
 * media item into a parcel, erring on the large side; and the budget leaves a quarter of the
 * limit spare on top of that.
 */
object CarFolders {
    /** Media3's limit for a list of items sent to a MediaBrowserService client. */
    const val LIMIT = 256 * 1024

    /** What a folder's items may come to by this file's reckoning: the limit, less a quarter
     *  of it for whatever the reckoning has missed. (190,000 until a cover's address began
     *  to carry its account's key, 33 characters more for every item with a cover: two
     *  hundred ordinary songs must still make one folder.) */
    const val BUDGET = 196_000

    /** Items in one folder at most, however small: a list much longer than this is no use on
     *  a car's screen anyway. */
    const val MOST = 200

    /** One stretch of a list too long for a folder: the entries from `from` up to `to`. */
    data class Range(val from: Int, val to: Int)

    /** Whether these can all be handed over in one folder. */
    fun fits(entries: List<CarEntry>, budget: Int = BUDGET): Boolean {
        if (entries.size > MOST) return false
        var total = 0
        for (entry in entries) {
            total += bytes(entry)
            if (total > budget) return false
        }
        return true
    }

    /** The longest run from the start of these that fits in one folder. */
    fun first(entries: List<CarEntry>, budget: Int = BUDGET): List<CarEntry> {
        var total = 0
        var count = 0
        for (entry in entries) {
            total += bytes(entry)
            if (count == MOST || total > budget) break
            count++
        }
        return if (count == entries.size) entries else entries.subList(0, count)
    }

    /**
     * How the entries from `from` up to `to` of a list are shown in one folder: null if they
     * all fit as they are; otherwise the ranges to show in their place, each of which fits,
     * or (when even the ranges are too many) is itself split the same way when opened. The
     * same list always splits the same way, so a range named in an id means the same songs
     * when the car comes back to open it.
     */
    fun ranges(entries: List<CarEntry>, from: Int = 0, to: Int = entries.size, budget: Int = BUDGET): List<Range>? {
        val start = from.coerceIn(0, entries.size)
        val end = to.coerceIn(start, entries.size)
        // The runs that fit a folder each.
        val leaves = mutableListOf<Range>()
        var at = start
        while (at < end) {
            var total = 0
            var next = at
            while (next < end && next - at < MOST) {
                total += bytes(entries[next])
                // Always at least one, however large it is.
                if (total > budget && next > at) break
                next++
            }
            leaves += Range(at, next)
            at = next
        }
        if (leaves.size <= 1) return null
        // Too many runs to list in one folder: runs of runs.
        var parts: List<Range> = leaves
        while (parts.size > MOST_RANGES) {
            val each = (parts.size + MOST_RANGES - 1) / MOST_RANGES
            parts = parts.chunked(each.coerceAtLeast(2)) { Range(it.first().from, it.last().to) }
        }
        return parts
    }

    /** Ranges listed in one folder at most. (That a folder of ranges fits is not taken on
     *  trust either: see [gathered].) */
    const val MOST_RANGES = 100

    /**
     * Ranges gathered into fewer, wider ones until their own entries, with whatever heads
     * the folder (`head`: "Shuffle"), fit in one folder. `entry` makes the entry a range is
     * shown as. A range's entry is small, but it is made of two titles from the list, and a
     * folder is only known to fit when it has been weighed.
     */
    fun gathered(ranges: List<Range>, head: List<CarEntry>, entry: (Range) -> CarEntry): List<Range> {
        var parts = ranges
        while (parts.size > 1 && !fits(head + parts.map(entry))) {
            parts = parts.chunked(2) { Range(it.first().from, it.last().to) }
        }
        return parts
    }


    /**
     * The bytes an entry comes to when Media3 hands it to a MediaBrowserService client:
     * `Parcel.writeParcelable` of a `MediaBrowserCompat.MediaItem`, which is the class's name,
     * its flags, and the platform's `MediaDescription` (id, title, subtitle, description,
     * icon, icon address, extras, media address). Strings are counted at whichever of
     * Android's two encodings is longer, and a tenth is added on top.
     */
    fun bytes(entry: CarEntry): Int {
        var total = CLASS_NAME + 4
        total += utf16(entry.id)
        // Title, then (in Media3's order of preference) up to two more of these.
        total += text(entry.title) + text(entry.subtitle)
        entry.song?.let { total += text(it.artistLabel) + text(it.album) }
        if (entry.song == null && entry.subtitle.isEmpty()) total += text("")
        total += text("")
        total += 4 // no icon bitmap
        val art = artChars(entry)
        total += if (art > 0) URI_CLASS_NAME + 4 + utf8(art) else 4
        // The extras: always the kind of thing it is (Media3 adds that), then what CarMedia
        // adds for the car's layout.
        var extras = 12 + key(52) + 4 + 8
        if (entry.group.isNotEmpty()) extras += key(51) + 4 + utf16(entry.group)
        if (entry.inside != null && entry.browsable) extras += key(49) + 8 + key(48) + 8
        if (entry.style != null) extras += key(51) + 8
        total += extras
        total += 4 // no media address
        return total + total / 10
    }

    /** `androidx.media3.session.legacy.MediaBrowserCompat$MediaItem`, as a parcel names it. */
    private val CLASS_NAME = utf16Chars(59)

    /** `android.net.Uri$StringUri`. */
    private val URI_CLASS_NAME = utf16Chars(25)

    private fun artChars(entry: CarEntry): Int = when {
        // (With the account's key in it: see CoverProvider.uriFor.)
        CoverStore.fileName(entry.cover) != null -> "content://".length + CoverProvider.AUTHORITY.length + 1 + CoverProvider.KEY_CHARS + 1 + entry.cover.length
        entry.icon != 0 -> "android.resource://".length + BuildConfig.APPLICATION_ID.length + 1 + 10
        else -> 0
    }

    /** A string as Parcel.writeString writes it: its length, two bytes a character and a
     *  terminator, rounded up to four. */
    private fun utf16Chars(chars: Int): Int = 4 + ((2 * (chars + 1) + 3) / 4) * 4

    private fun utf16(text: String): Int = utf16Chars(text.length)

    /** A string as Parcel.writeString8 writes it, given its length in bytes. */
    private fun utf8(bytes: Int): Int = 4 + ((bytes + 1 + 3) / 4) * 4

    /** A piece of text as TextUtils.writeToParcel writes it: a marker, then the string, in
     *  UTF-8 on newer versions of Android and UTF-16 on older; whichever is longer. */
    private fun text(text: String): Int = 4 + maxOf(utf16(text), utf8(text.toByteArray(Charsets.UTF_8).size))

    /** An extras key of this many characters. */
    private fun key(chars: Int): Int = utf16Chars(chars)
}
