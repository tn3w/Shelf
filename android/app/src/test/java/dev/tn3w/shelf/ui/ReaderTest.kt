package dev.tn3w.shelf.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTest {
    @Test
    fun countsSinglePageTurnPastFurthest() {
        assertEquals(1, newPagesRead(previous = 4, settled = 5, furthest = 4))
    }

    @Test
    fun countsQuickTurnsSettlingTogether() {
        assertEquals(2, newPagesRead(previous = 4, settled = 6, furthest = 4))
        assertEquals(1, newPagesRead(previous = 8, settled = 10, furthest = 9))
    }

    @Test
    fun ignoresJumps() {
        assertEquals(0, newPagesRead(previous = 4, settled = 40, furthest = 4))
    }

    @Test
    fun countsReadingOnAfterJump() {
        assertEquals(1, newPagesRead(previous = 40, settled = 41, furthest = 40))
    }

    @Test
    fun ignoresRereadAndBackwardTurns() {
        assertEquals(0, newPagesRead(previous = 4, settled = 5, furthest = 9))
        assertEquals(0, newPagesRead(previous = 5, settled = 4, furthest = 5))
    }

    @Test
    fun keepsMarginOnPhones() {
        assertEquals(28.dp, sideMargin(width = 400.dp, margin = 28.dp))
    }

    @Test
    fun capsLineWidthOnTablets() {
        val width = 1200.dp
        val side = sideMargin(width, margin = 28.dp)
        assertTrue(side > 28.dp)
        assertEquals(sideMargin(width + 200.dp, 28.dp) - 100.dp, side)
    }
}
