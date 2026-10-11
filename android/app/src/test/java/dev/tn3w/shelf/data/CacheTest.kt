package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheTest {
    @Test
    fun evictsLeastRecentlyUsedBeyondCapacity() {
        val cache = Cache<Int, Int>(2)
        var loads = 0
        fun read(key: Int) = cache.get(key) {
            loads++
            key
        }

        read(1)
        read(2)
        read(1)
        read(3)
        assertEquals(3, loads)

        read(1)
        assertEquals(3, loads)

        read(2)
        assertEquals(4, loads)
    }
}
