package org.medianexus.music.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SharedResourceTest {
    private var opened = 0
    private var closed = 0
    private fun shared() = SharedResource({ opened++; "cache" }, { closed++ })

    @Test
    fun isOpenedOnFirstUseAndClosedOnceTheLastReaderIsDone() {
        val cache = shared()
        assertEquals(0, opened)
        assertNotNull(cache.acquire())
        assertNotNull(cache.acquire())
        assertEquals(1, opened)
        // Signing out while two songs are still being read: not closed under them.
        cache.close()
        assertEquals(0, closed)
        cache.release()
        assertEquals(0, closed)
        cache.release()
        assertEquals(1, closed)
    }

    @Test
    fun nobodyNewGetsItOnceItIsClosing() {
        val cache = shared()
        assertNotNull(cache.acquire())
        cache.close()
        assertNull(cache.acquire())
        cache.release()
        assertEquals(1, closed)
        // Nor after: it is not opened again.
        assertNull(cache.acquire())
        assertEquals(1, opened)
    }

    @Test
    fun closingWithNoReadersClosesAtOnceAndNeverOpenedClosesNothing() {
        val used = shared()
        used.acquire()
        used.release()
        used.close()
        assertEquals(1, closed)
        val unused = shared()
        unused.close()
        assertEquals(1, closed)
    }

    @Test
    fun aResourceThatCannotBeOpenedIsGoneWithout() {
        var ready = false
        val cache = SharedResource({ if (ready) "cache" else null }, { closed++ })
        assertNull(cache.acquire())
        // Nothing was handed out, so there is nothing to give back; later it can be had.
        ready = true
        assertNotNull(cache.acquire())
        cache.release()
        cache.close()
        assertEquals(1, closed)
    }
}
