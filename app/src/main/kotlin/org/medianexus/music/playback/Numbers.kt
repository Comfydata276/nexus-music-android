package org.medianexus.music.playback

/**
 * Lists of numbers kept as numbers, for a queue's play order, entry numbers and places: a
 * queue of 50,000 songs is three lists of 50,000, which as lists of boxed numbers are an
 * object each (about three megabytes held, and as much again at every save). A number is
 * boxed only when it is read.
 */
class IntList(capacity: Int = 16) : AbstractMutableList<Int>(), RandomAccess {
    private var items = IntArray(capacity.coerceAtLeast(4))
    private var count = 0

    constructor(from: Collection<Int>) : this(from.size) {
        if (from is IntList) {
            from.items.copyInto(items, 0, 0, from.count)
            count = from.count
        } else {
            for (n in from) items[count++] = n
        }
    }

    override val size: Int get() = count

    override fun get(index: Int): Int {
        if (index !in 0 until count) throw IndexOutOfBoundsException("$index of $count")
        return items[index]
    }

    override fun set(index: Int, element: Int): Int {
        val old = get(index)
        items[index] = element
        return old
    }

    override fun add(index: Int, element: Int) {
        if (index !in 0..count) throw IndexOutOfBoundsException("$index of $count")
        room(count + 1)
        System.arraycopy(items, index, items, index + 1, count - index)
        items[index] = element
        count++
        modCount++
    }

    override fun addAll(index: Int, elements: Collection<Int>): Boolean {
        if (index !in 0..count) throw IndexOutOfBoundsException("$index of $count")
        if (elements.isEmpty()) return false
        val n = elements.size
        room(count + n)
        System.arraycopy(items, index, items, index + n, count - index)
        var at = index
        for (e in elements) items[at++] = e
        count += n
        modCount++
        return true
    }

    override fun addAll(elements: Collection<Int>): Boolean = addAll(count, elements)

    override fun removeAt(index: Int): Int {
        val old = get(index)
        System.arraycopy(items, index + 1, items, index, count - index - 1)
        count--
        modCount++
        return old
    }

    override fun removeRange(fromIndex: Int, toIndex: Int) {
        System.arraycopy(items, toIndex, items, fromIndex, count - toIndex)
        count -= toIndex - fromIndex
        modCount++
    }

    override fun clear() {
        count = 0
        modCount++
    }

    private fun room(n: Int) {
        if (n > items.size) items = items.copyOf(maxOf(n, items.size * 2))
    }
}

/** As [IntList], for numbers that may be missing (null): a place in a list, or none. */
class PlaceList(capacity: Int = 16) : AbstractMutableList<Int?>(), RandomAccess {
    private val numbers = IntList(capacity)

    constructor(from: Collection<Int?>) : this(from.size) {
        if (from is PlaceList) numbers.addAll(from.numbers) else for (n in from) numbers.add(n ?: NONE)
    }

    override val size: Int get() = numbers.size
    override fun get(index: Int): Int? = numbers[index].takeIf { it != NONE }
    override fun set(index: Int, element: Int?): Int? = numbers.set(index, element ?: NONE).takeIf { it != NONE }
    override fun add(index: Int, element: Int?) = numbers.add(index, element ?: NONE)
    override fun removeAt(index: Int): Int? = numbers.removeAt(index).takeIf { it != NONE }
    override fun clear() = numbers.clear()
    override fun addAll(elements: Collection<Int?>): Boolean = numbers.addAll(elements.map { it ?: NONE })

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}

/** As [IntList], for a queue's entry numbers. */
class LongList(capacity: Int = 16) : AbstractMutableList<Long>(), RandomAccess {
    private var items = LongArray(capacity.coerceAtLeast(4))
    private var count = 0

    override val size: Int get() = count

    override fun get(index: Int): Long {
        if (index !in 0 until count) throw IndexOutOfBoundsException("$index of $count")
        return items[index]
    }

    override fun set(index: Int, element: Long): Long {
        val old = get(index)
        items[index] = element
        return old
    }

    override fun add(index: Int, element: Long) {
        if (index !in 0..count) throw IndexOutOfBoundsException("$index of $count")
        if (count == items.size) items = items.copyOf(items.size * 2)
        System.arraycopy(items, index, items, index + 1, count - index)
        items[index] = element
        count++
        modCount++
    }

    override fun removeAt(index: Int): Long {
        val old = get(index)
        System.arraycopy(items, index + 1, items, index, count - index - 1)
        count--
        modCount++
        return old
    }

    override fun clear() {
        count = 0
        modCount++
    }

    /** As a copy that later changes don't reach. */
    fun copy(): LongList = LongList(count).also { it.items = items.copyOf(maxOf(count, 4)); it.count = count }
}
