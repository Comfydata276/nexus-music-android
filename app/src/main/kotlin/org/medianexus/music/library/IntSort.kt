package org.medianexus.music.library

/** How two places compare, without boxing either (a `(Int, Int) -> Int` lambda boxes both). */
fun interface IntOrder {
    fun compare(a: Int, b: Int): Int
}

/** A growing list of places, without boxing them. */
class IntList(capacity: Int = 16) {
    private var items = IntArray(capacity.coerceAtLeast(1))
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun toIntArray(): IntArray = items.copyOf(size)
}

/**
 * Sorts places in a stable order (equal ones stay as they were, as Rust's `sort_by` and
 * Kotlin's own sorts keep them), with a merge sort on the array itself: the mixes and "Your
 * stats" sort tens of thousands of songs, and a list of boxed places to sort is a megabyte
 * of the heap each time.
 */
fun IntArray.sortStably(order: IntOrder) {
    if (size < 2) return
    val spare = IntArray(size)
    mergeSort(this, spare, 0, size, order)
}

private fun mergeSort(a: IntArray, spare: IntArray, from: Int, to: Int, order: IntOrder) {
    if (to - from <= 16) {
        // Short runs by insertion, which is stable.
        for (i in from + 1 until to) {
            val v = a[i]
            var j = i - 1
            while (j >= from && order.compare(a[j], v) > 0) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = v
        }
        return
    }
    val middle = (from + to) ushr 1
    mergeSort(a, spare, from, middle, order)
    mergeSort(a, spare, middle, to, order)
    if (order.compare(a[middle - 1], a[middle]) <= 0) return
    System.arraycopy(a, from, spare, from, to - from)
    var i = from
    var j = middle
    var k = from
    while (i < middle && j < to) a[k++] = if (order.compare(spare[j], spare[i]) < 0) spare[j++] else spare[i++]
    while (i < middle) a[k++] = spare[i++]
    while (j < to) a[k++] = spare[j++]
}
