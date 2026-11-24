package org.medianexus.music.library

import org.junit.Assert.assertEquals
import org.junit.Test

class IntSortTest {
    @Test
    fun sortsAsKotlinsStableSortDoes() {
        val random = java.util.Random(3)
        for (size in listOf(0, 1, 2, 15, 16, 17, 100, 1000, 5000)) {
            val keys = IntArray(size) { random.nextInt(20) }
            val places = IntArray(size) { it }
            places.sortStably { a, b -> keys[b].compareTo(keys[a]) }
            // Equal keys keep their order, as Rust's `sort_by` keeps them.
            assertEquals((0 until size).sortedWith { a, b -> keys[b].compareTo(keys[a]) }, places.toList())
        }
    }
}
