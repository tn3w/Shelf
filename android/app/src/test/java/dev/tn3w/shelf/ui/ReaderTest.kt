package dev.tn3w.shelf.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTest {
    @Test
    fun countsSinglePageTurnPastFurthest() {
        assertTrue(isNewPageRead(previous = 4, settled = 5, furthest = 4))
    }

    @Test
    fun ignoresJumps() {
        assertFalse(isNewPageRead(previous = 4, settled = 40, furthest = 4))
    }

    @Test
    fun countsReadingOnAfterJump() {
        assertTrue(isNewPageRead(previous = 40, settled = 41, furthest = 40))
    }

    @Test
    fun ignoresRereadAndBackwardTurns() {
        assertFalse(isNewPageRead(previous = 4, settled = 5, furthest = 9))
        assertFalse(isNewPageRead(previous = 5, settled = 4, furthest = 5))
    }
}
