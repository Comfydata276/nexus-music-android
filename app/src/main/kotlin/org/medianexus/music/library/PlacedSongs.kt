package org.medianexus.music.library

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import org.medianexus.music.net.Song
import org.medianexus.music.store.SongTable

/**
 * A list of songs that holds most of them as places in a [SongTable] rather than as songs: a
 * queue of the 50,000 songs of the Songs page is 50,000 numbers into the phone's copy of the
 * library, not 50,000 objects of thirty fields each. A song is made when one is asked for
 * (`get`): for what is shown or played, and let go after. An entry that a table can't hold
 * (a song queued by hand, a radio station, or one from another table) is a song of its own.
 *
 * Asking for the same entry twice makes two equal songs, not the same one: nothing here may
 * rely on a song's identity. What arranging, saving or following a list needs of every entry
 * (its id, its title) is read from the table without making songs ([id], [columns]).
 */
class PlacedSongs private constructor(
    private var table: SongTable?,
    private var places: IntArray,
    private var objects: Array<Song?>,
    private var count: Int,
) : AbstractMutableList<Song>(), RandomAccess {
    constructor() : this(null, IntArray(0), arrayOfNulls(0), 0)

    override val size: Int get() = count

    override fun get(index: Int): Song {
        check(index)
        return objects[index] ?: table!!.song(places[index])
    }

    /** The id of the entry at `index`, without making a song of it. */
    fun id(index: Int): String {
        check(index)
        return objects[index]?.id ?: table!!.id(places[index])
    }

    /** Whether the entry at `index` was queued by hand (only a song of its own can be). */
    fun isQueued(index: Int): Boolean {
        check(index)
        return objects[index]?.queued == true
    }

    override fun set(index: Int, element: Song): Song {
        val old = get(index)
        objects[index] = element
        places[index] = -1
        return old
    }

    override fun add(index: Int, element: Song) {
        if (index !in 0..count) throw IndexOutOfBoundsException("$index of $count")
        room(count + 1)
        System.arraycopy(places, index, places, index + 1, count - index)
        System.arraycopy(objects, index, objects, index + 1, count - index)
        places[index] = -1
        objects[index] = element
        count++
        modCount++
    }

    override fun removeAt(index: Int): Song {
        val old = get(index)
        System.arraycopy(places, index + 1, places, index, count - index - 1)
        System.arraycopy(objects, index + 1, objects, index, count - index - 1)
        count--
        objects[count] = null
        modCount++
        return old
    }

    override fun clear() {
        objects.fill(null, 0, count)
        count = 0
        table = null
        modCount++
    }

    /** Keeps the first `n` entries. */
    fun truncate(n: Int) {
        if (n >= count) return
        objects.fill(null, n, count)
        count = n
        modCount++
    }

    /** Puts the entry at `from` at `to` as well (as it is: a place stays a place). */
    fun copyEntry(from: Int, to: Int) {
        check(from)
        check(to)
        places[to] = places[from]
        objects[to] = objects[from]
    }

    /** Adds the entry at `index` of `other` to the end, as it is there: a place stays a place
     *  if both lists are of the same table (or this one has none yet). */
    fun addFrom(other: PlacedSongs, index: Int) {
        other.check(index)
        val song = other.objects[index]
        // (A list with no table holds no places, so it can take the other's.)
        if (song == null && (table == null || table === other.table)) {
            table = other.table
            room(count + 1)
            places[count] = other.places[index]
            objects[count] = null
            count++
            modCount++
        } else {
            add(song ?: other[index])
        }
    }

    /** A copy that later changes to this list don't reach: two small arrays, no songs. */
    fun snapshot(): PlacedSongs = PlacedSongs(table, places.copyOf(count), objects.copyOf(count), count)

    /** These entries (`rows`, places in this list), in that order. */
    fun select(rows: IntArray): PlacedSongs {
        val out = PlacedSongs(table, IntArray(rows.size), arrayOfNulls(rows.size), rows.size)
        for ((i, row) in rows.withIndex()) {
            check(row)
            out.places[i] = places[row]
            out.objects[i] = objects[row]
        }
        return out
    }

    /** What arranging the list needs, read from the table without making songs. */
    fun columns(): SongColumns = object : SongColumns {
        override val size get() = count
        override fun title(i: Int) = objects[i]?.title ?: table!!.title(places[i])
        override fun artistLabel(i: Int) = objects[i]?.artistLabel ?: table!!.artistLabel(places[i])
        override fun album(i: Int) = objects[i]?.album ?: table!!.album(places[i])
        override fun disc(i: Int) = objects[i]?.discNumber ?: table!!.disc(places[i])
        override fun track(i: Int) = objects[i]?.track ?: table!!.track(places[i])
        override fun duration(i: Int) = objects[i]?.duration ?: table!!.duration(places[i])
    }

    /** When each was added to the library, in seconds (0 if not known), without making songs. */
    fun createdSeconds(i: Int): Long = objects[i]?.let { Arrange.parseTime(it.created) } ?: Math.floorDiv(table!!.createdMillis(places[i]), 1000L)

    private fun check(index: Int) {
        if (index !in 0 until count) throw IndexOutOfBoundsException("$index of $count")
    }

    private fun room(n: Int) {
        if (n <= places.size) return
        val size = maxOf(n, places.size * 2, 16)
        places = places.copyOf(size)
        objects = objects.copyOf(size)
    }

    /**
     * Puts a list together a song at a time (a saved queue as it is read), keeping each
     * in a table of its own: what is read from a file of 50,000 songs is a table, and the
     * songs made to read it are let go one by one. A song a table can't hold as it is
     * (queued by hand, a radio station) is kept as a song.
     */
    class Builder {
        private val table = SongTable.Builder(distinct = false)
        private var places = IntArray(1024)
        private val objects = ArrayList<Song?>()

        fun add(song: Song) {
            if (objects.size == places.size) places = places.copyOf(places.size * 2)
            if (song.queued || song.radioUrl.isNotEmpty() || song.id.isEmpty()) {
                places[objects.size] = -1
                objects += song
            } else {
                places[objects.size] = table.count
                table.add(song)
                objects += null
            }
        }

        fun build(): PlacedSongs = PlacedSongs(table.build(), places.copyOf(objects.size), objects.toTypedArray(), objects.size)
    }

    companion object {
        /** The songs at these places of `table`, in this order. */
        fun of(table: SongTable, places: IntArray) = PlacedSongs(table, places, arrayOfNulls(places.size), places.size)

        /** `songs` as a list of this kind: shared as places if it is one, else as songs. */
        fun from(songs: List<Song>, map: (Song) -> Song = { it }): PlacedSongs {
            if (songs is PlacedSongs) {
                val out = songs.snapshot()
                for (i in 0 until out.count) out.objects[i]?.let { out.objects[i] = map(it) }
                return out
            }
            return PlacedSongs(null, IntArray(songs.size) { -1 }, Array(songs.size) { map(songs[it]) }, songs.size)
        }

    }
}

/** The id of the song at `i`, without making a song of it where the list can say. */
fun List<Song>.idAt(i: Int): String = if (this is PlacedSongs) id(i) else this[i].id

/** What arranging this list needs, without making songs where the list can say. */
fun List<Song>.arrangeable(): SongColumns = if (this is PlacedSongs) columns() else columns()

/** The entries at `rows`, in that order, as places where the list holds places. */
fun List<Song>.pick(rows: IntArray): List<Song> = if (this is PlacedSongs) select(rows) else rows.map { this[it] }

/**
 * A saved queue's songs, written as Windows writes them (a list of songs, each made as it is
 * written and let go) and read back into a [PlacedSongs] of a table of its own, rather than as
 * 50,000 songs at once.
 */
object PlacedSongsSerializer : KSerializer<List<Song>> {
    private val element = Song.serializer()
    private val list = ListSerializer(element)
    override val descriptor = list.descriptor

    override fun serialize(encoder: Encoder, value: List<Song>) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<Song> {
        val builder = PlacedSongs.Builder()
        decoder.decodeStructure(descriptor) {
            if (decodeSequentially()) {
                repeat(decodeCollectionSize(descriptor)) { builder.add(decodeSerializableElement(descriptor, it, element)) }
            } else {
                while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    builder.add(decodeSerializableElement(descriptor, index, element))
                }
            }
        }
        return builder.build()
    }
}
