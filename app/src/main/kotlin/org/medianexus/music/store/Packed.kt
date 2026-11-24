package org.medianexus.music.store

import java.io.IOException

/**
 * Numbers and text put one after another (big-endian, as Java's data streams write them),
 * for a file that is written whole. A stream that writes a number at a time through a buffer
 * costs several calls a number; the copy of a library of 50,000 songs is a few million
 * numbers. Into memory, or, given a `sink`, through a buffer of `initial` bytes into it, with
 * a checksum of everything that went through ([checksum]): the copy of a library is written
 * without first being made whole in memory (13 MB for 50,000 songs, and as much again while
 * its array grew).
 */
class Packer(initial: Int = 64 * 1024, private val sink: java.io.OutputStream? = null) {
    var bytes = ByteArray(initial)
        private set
    var size = 0
        private set
    private val crc = java.util.zip.CRC32()

    private fun room(n: Int) {
        if (size + n <= bytes.size) return
        if (sink != null) {
            flush()
            if (n <= bytes.size) return
        }
        bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    /** Given a sink: passes on what is buffered. */
    fun flush() {
        if (sink == null || size == 0) return
        crc.update(bytes, 0, size)
        sink.write(bytes, 0, size)
        size = 0
    }

    /** Given a sink: the checksum (CRC-32) of everything written so far, all of it passed on. */
    fun checksum(): Int {
        flush()
        return crc.value.toInt()
    }

    fun int(value: Int) {
        room(4)
        bytes[size] = (value ushr 24).toByte()
        bytes[size + 1] = (value ushr 16).toByte()
        bytes[size + 2] = (value ushr 8).toByte()
        bytes[size + 3] = value.toByte()
        size += 4
    }

    fun long(value: Long) {
        int((value ushr 32).toInt())
        int(value.toInt())
    }

    fun float(value: Float) = int(java.lang.Float.floatToRawIntBits(value))

    fun bool(value: Boolean) {
        room(1)
        bytes[size++] = if (value) 1 else 0
    }

    /** How many of something follow. Refused, as [Unpacker] would refuse it on the way back
     *  in, if it is more than [MOST_COUNT]: nothing is written that wouldn't read back. */
    fun count(n: Int) {
        if (n !in 0..MOST_COUNT) throw java.io.IOException("$n of something: more than can be read back")
        int(n)
    }

    /** Text as its length in bytes and then its UTF-8. Refused if it is longer than
     *  [MOST_TEXT] bytes, which [Unpacker] wouldn't read back. */
    fun text(value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        if (utf8.size > MOST_TEXT) throw java.io.IOException("text of ${utf8.size} bytes: more than can be read back")
        int(utf8.size)
        room(utf8.size)
        System.arraycopy(utf8, 0, bytes, size, utf8.size)
        size += utf8.size
    }
}

/**
 * Reads back what a [Packer] wrote, from `bytes[from until end]`, never past the end: a file
 * cut short is an [IOException], not a song made of whatever follows.
 */
class Unpacker(private val bytes: ByteArray, from: Int = 0, private val end: Int = bytes.size) {
    private var at = from

    /** Everything has been read. */
    val done: Boolean get() = at == end

    private fun need(n: Int) {
        if (n < 0 || end - at < n) throw IOException("damaged: it ends part-way")
    }

    fun int(): Int {
        need(4)
        val value = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        at += 4
        return value
    }

    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)

    fun float(): Float = java.lang.Float.intBitsToFloat(int())

    fun bool(): Boolean {
        need(1)
        return when (bytes[at++].toInt()) {
            0 -> false
            1 -> true
            else -> throw IOException("damaged: neither yes nor no")
        }
    }

    fun text(most: Int = MOST_TEXT): String {
        val n = int()
        if (n !in 0..most) throw IOException("damaged: text of $n bytes")
        need(n)
        val value = String(bytes, at, n, Charsets.UTF_8)
        at += n
        return value
    }

    private companion object {
        const val MOST_TEXT = org.medianexus.music.store.MOST_TEXT
    }
}

/** The most bytes a piece of text may have, written or read (a mebibyte). */
const val MOST_TEXT = 1 shl 20

/** The most of anything a count may say, written or read: no library comes near it. */
const val MOST_COUNT = 5_000_000
