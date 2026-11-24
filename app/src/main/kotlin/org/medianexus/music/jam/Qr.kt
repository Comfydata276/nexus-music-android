package org.medianexus.music.jam

/**
 * A QR code for a jam's link, drawn without a library (Windows draws its own from the
 * `qrcode` crate's modules: `qr_image`). Byte mode, error correction level M, versions 1 to 10
 * (up to 213 bytes: a jam's link is about 50), the mask chosen by the standard's penalty
 * rules. What comes back is the square of modules, true for dark; the screen draws it dark on
 * white with a margin, as Windows does.
 */
object Qr {
    /** Data codewords and error correction per block at level M, versions 1–10:
     *  (blocks in group 1, data per block, blocks in group 2, data per block, ec per block). */
    private val M = arrayOf(
        intArrayOf(1, 16, 0, 0, 10), intArrayOf(1, 28, 0, 0, 16), intArrayOf(1, 44, 0, 0, 26), intArrayOf(2, 32, 0, 0, 18),
        intArrayOf(2, 43, 0, 0, 24), intArrayOf(4, 27, 0, 0, 16), intArrayOf(4, 31, 0, 0, 18), intArrayOf(2, 38, 2, 39, 22),
        intArrayOf(3, 36, 2, 37, 22), intArrayOf(4, 43, 1, 44, 26),
    )
    private val ALIGN = arrayOf(intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50))

    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = (1..10).firstOrNull { v -> capacity(v) >= 4 + (if (v < 10) 8 else 16) + 8 * bytes.size }?.let { it } ?: return null
        val spec = M[version - 1]
        val dataLength = spec[0] * spec[1] + spec[2] * spec[3]
        // The bits: mode, count, the bytes, a terminator, padding.
        val bits = ArrayList<Boolean>()
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value shr i) and 1 == 1 }
        put(0b0100, 4)
        put(bytes.size, if (version < 10) 8 else 16)
        for (b in bytes) put(b.toInt() and 0xff, 8)
        repeat(minOf(4, dataLength * 8 - bits.size)) { bits += false }
        while (bits.size % 8 != 0) bits += false
        val data = IntArray(dataLength)
        for (i in 0 until bits.size / 8) data[i] = (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 }
        var pad = bits.size / 8
        var flip = true
        while (pad < dataLength) { data[pad++] = if (flip) 0xEC else 0x11; flip = !flip }
        // Blocks, each with its error correction, interleaved.
        val blocks = ArrayList<IntArray>()
        var at = 0
        for (g in 0..1) repeat(spec[g * 2]) {
            val n = spec[g * 2 + 1]
            blocks += data.copyOfRange(at, at + n)
            at += n
        }
        val ec = blocks.map { reedSolomon(it, spec[4]) }
        val codewords = ArrayList<Int>()
        for (i in 0 until blocks.maxOf { it.size }) for (b in blocks) if (i < b.size) codewords += b[i]
        for (i in 0 until spec[4]) for (e in ec) codewords += e[i]
        val size = 17 + 4 * version
        val dark = Array(size) { BooleanArray(size) }
        val fixed = Array(size) { BooleanArray(size) }
        fun set(r: Int, c: Int, on: Boolean) { if (r in 0 until size && c in 0 until size) { dark[r][c] = on; fixed[r][c] = true } }
        // Finders and their separators.
        for ((r0, c0) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            for (r in -1..7) for (c in -1..7) {
                val on = r in 0..6 && c in 0..6 && (r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4))
                set(r0 + r, c0 + c, on)
            }
        }
        // Alignment patterns.
        val centres = ALIGN[version - 1]
        for (r in centres) for (c in centres) {
            if (fixed[r][c]) continue
            for (dr in -2..2) for (dc in -2..2) set(r + dr, c + dc, maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1)
        }
        // Timing.
        for (i in 8 until size - 8) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        // The dark module, and room for the format (and version) information.
        set(size - 8, 8, true)
        for (i in 0..8) { if (!fixed[8][i]) set(8, i, false); if (!fixed[i][8]) set(i, 8, false) }
        for (i in 0..7) { set(8, size - 1 - i, false); set(size - 1 - i, 8, false) }
        if (version >= 7) {
            val info = versionBits(version)
            for (i in 0 until 18) {
                val on = (info shr i) and 1 == 1
                set(i / 3, size - 11 + i % 3, on)
                set(size - 11 + i % 3, i / 3, on)
            }
        }
        // The codewords, in the zigzag.
        val all = ArrayList<Boolean>()
        for (cw in codewords) for (i in 7 downTo 0) all += (cw shr i) and 1 == 1
        var index = 0
        var col = size - 1
        var up = true
        while (col > 0) {
            if (col == 6) col--
            for (k in 0 until size) {
                val r = if (up) size - 1 - k else k
                for (c in intArrayOf(col, col - 1)) {
                    if (fixed[r][c]) continue
                    dark[r][c] = index < all.size && all[index]
                    index++
                }
            }
            up = !up
            col -= 2
        }
        // The mask with the lowest penalty.
        var best: Array<BooleanArray>? = null
        var lowest = Int.MAX_VALUE
        for (mask in 0..7) {
            val tried = Array(size) { r -> BooleanArray(size) { c -> if (!fixed[r][c] && masked(mask, r, c)) !dark[r][c] else dark[r][c] } }
            format(tried, mask)
            val score = penalty(tried)
            if (score < lowest) { lowest = score; best = tried }
        }
        return best
    }

    private fun capacity(version: Int): Int { val s = M[version - 1]; return (s[0] * s[1] + s[2] * s[3]) * 8 }

    private fun masked(mask: Int, r: Int, c: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> (r / 2 + c / 3) % 2 == 0
        5 -> (r * c) % 2 + (r * c) % 3 == 0
        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
    }

    /** The 15 format bits for level M and a mask, placed twice. */
    private fun format(m: Array<BooleanArray>, mask: Int) {
        val size = m.size
        val value = (0b00 shl 3) or mask
        var rem = value
        repeat(10) { rem = (rem shl 1) xor ((rem shr 9) * 0x537) }
        val bits = ((value shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits shr i) and 1 == 1
        // As rows and columns: down column 8 and along row 8 beside the top-left finder,
        // then along row 8 by the top-right one and down column 8 by the bottom-left one.
        for (i in 0..5) m[i][8] = bit(i)
        m[7][8] = bit(6); m[8][8] = bit(7); m[8][7] = bit(8)
        for (i in 9..14) m[8][14 - i] = bit(i)
        for (i in 0..7) m[8][size - 1 - i] = bit(i)
        for (i in 8..14) m[size - 15 + i][8] = bit(i)
        m[size - 8][8] = true
    }

    private fun versionBits(version: Int): Int {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem shr 11) * 0x1F25) }
        return (version shl 12) or rem
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0
        for (line in 0 until n) for (across in listOf(true, false)) {
            var run = 1
            for (i in 1 until n) {
                val same = if (across) m[line][i] == m[line][i - 1] else m[i][line] == m[i - 1][line]
                if (same) { run++; if (i == n - 1 && run >= 5) score += run - 2 } else { if (run >= 5) score += run - 2; run = 1 }
            }
        }
        for (r in 0 until n - 1) for (c in 0 until n - 1) if (m[r][c] == m[r][c + 1] && m[r][c] == m[r + 1][c] && m[r][c] == m[r + 1][c + 1]) score += 3
        val pattern = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        for (r in 0 until n) for (c in 0..n - 11) for (across in listOf(true, false)) {
            val forward = (0 until 11).all { k -> (if (across) m[r][c + k] else m[c + k][r]) == pattern[k] }
            val back = (0 until 11).all { k -> (if (across) m[r][c + k] else m[c + k][r]) == pattern[10 - k] }
            if (forward) score += 40
            if (back) score += 40
        }
        val darkCount = m.sumOf { row -> row.count { it } }
        score += kotlin.math.abs(darkCount * 20 - n * n * 10) / (n * n) * 10
        return score
    }

    /** Reed–Solomon error correction over GF(256) with the QR polynomial. */
    private fun reedSolomon(data: IntArray, degree: Int): IntArray {
        val exp = IntArray(512)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) { exp[i] = x; log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
        var generator = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(generator.size + 1)
            for (j in generator.indices) { next[j] = next[j] xor generator[j]; next[j + 1] = next[j + 1] xor mul(generator[j], exp[i]) }
            generator = next
        }
        val rem = IntArray(degree)
        for (d in data) {
            val factor = d xor rem[0]
            for (i in 0 until degree - 1) rem[i] = rem[i + 1]
            rem[degree - 1] = 0
            for (i in 0 until degree) rem[i] = rem[i] xor mul(generator[i + 1], factor)
        }
        return rem
    }
}
