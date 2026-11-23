package org.medianexus.music.store

/**
 * Something that readers on other threads use and that must not be closed under them: it is
 * opened the first time it's wanted, and closed only once it has been told to close and the
 * last reader has finished with it. After it has been told to close, nobody new gets it.
 *
 * `open` may give null (it can't be had just now); then the reader goes without.
 */
class SharedResource<T : Any>(private val open: () -> T?, private val close: (T) -> Unit) {
    private var value: T? = null
    private var readers = 0
    private var isClosing = false

    /** The resource, for one reader, who must call [release] when done. Null if it is
     *  closing or can't be opened: then there is nothing to release. */
    @Synchronized
    fun acquire(): T? {
        if (isClosing) return null
        val now = value ?: open()?.also { value = it } ?: return null
        readers++
        return now
    }

    @Synchronized
    fun release() {
        if (readers > 0) readers--
        if (isClosing && readers == 0) shut()
    }

    /** Whether it has been told to close: nobody gets it from then on. */
    val closing: Boolean @Synchronized get() = isClosing

    /** No new readers from now; closed as soon as those it has are done. */
    @Synchronized
    fun close() {
        isClosing = true
        if (readers == 0) shut()
    }

    /** Runs `block` on the resource if it is open (and not closing), without opening it. */
    @Synchronized
    fun ifOpen(block: (T) -> Unit) {
        if (!isClosing) value?.let(block)
    }

    private fun shut() {
        value?.let(close)
        value = null
    }
}
