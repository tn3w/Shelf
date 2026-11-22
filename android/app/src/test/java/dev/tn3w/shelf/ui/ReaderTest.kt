package dev.tn3w.shelf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import dev.tn3w.shelf.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val SCREEN = Size(1000f, 2000f)

class ReaderTest {
    @Test
    fun keepsLineSpacingBetweenParagraphs() {
        val style = readerStyle(Settings(), Color.Black, FontFamily.Serif)

        assertEquals(LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
    }

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
    fun estimatesTimeLeftFromPageTurns() {
        val pace = listOf(30L, 60L, 90L).fold(Pace()) { pace, seconds ->
            pace.turned(seconds)
        }

        assertEquals(600L, pace.secondsFor(10))
    }

    @Test
    fun waitsForEnoughTurnsAndIgnoresSkimmingAndBreaks() {
        val pace = Pace().turned(60).turned(1).turned(3600).turned(60)

        assertNull(pace.secondsFor(10))
        assertEquals(600L, pace.turned(60).secondsFor(10))
    }

    @Test
    fun chapterEndsAtNextChapterOrBookEnd() {
        assertEquals(20, chapterEnd(listOf(0, 20, 40), page = 5, count = 60))
        assertEquals(60, chapterEnd(listOf(0, 20, 40), page = 45, count = 60))
    }

    @Test
    fun wideMiddleOpensMenuAndEdgesTurnPages() {
        assertEquals(TapAction.Previous, tapAction(50f, 1000, menuShown = false))
        assertEquals(TapAction.Menu, tapAction(300f, 1000, menuShown = false))
        assertEquals(TapAction.Menu, tapAction(700f, 1000, menuShown = false))
        assertEquals(TapAction.Next, tapAction(950f, 1000, menuShown = false))
    }

    @Test
    fun rightToLeftSwapsTurnZones() {
        assertEquals(TapAction.Next, tapAction(50f, 1000, false, rightToLeft = true))
        assertEquals(TapAction.Previous, tapAction(950f, 1000, false, rightToLeft = true))
    }

    @Test
    fun pinchKeepsThePointUnderTheFingers() {
        val centroid = Offset(300f, 800f)
        val zoom = Zoom().by(centroid, 2f, Offset.Zero, SCREEN)

        assertEquals(2f, zoom.scale)
        assertEquals(centroid, centroid * zoom.scale + zoom.offset)
    }

    @Test
    fun zoomStaysWithinLimitsAndPageEdges() {
        val zoom = Zoom().by(Offset.Zero, 100f, Offset(500f, 500f), SCREEN)
        val panned = zoom.by(Offset.Zero, 1f, Offset(-99_999f, -99_999f), SCREEN)

        assertEquals(Zoom(5f, Offset.Zero), zoom)
        assertEquals(Offset(-4000f, -8000f), panned.offset)
        assertEquals(Zoom(), panned.by(Offset.Zero, 0.01f, Offset.Zero, SCREEN))
    }

    @Test
    fun doubleTapZoomsInAndBackOut() {
        val zoomed = Zoom().toggled(Offset(500f, 1000f), SCREEN)

        assertTrue(zoomed.zoomed)
        assertEquals(Zoom(), zoomed.toggled(Offset.Zero, SCREEN))
    }

    @Test
    fun anyTapClosesOpenMenu() {
        assertEquals(TapAction.Menu, tapAction(50f, 1000, menuShown = true))
        assertEquals(TapAction.Menu, tapAction(950f, 1000, menuShown = true))
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

    @Test
    fun swipeUpBrightens() {
        val brightness = swipedBrightness(0.5f, dragged = -250f, height = 1000)
        assertEquals(0.75f, brightness, 0.001f)
    }

    @Test
    fun keepsBrightnessInRange() {
        assertEquals(1f, swipedBrightness(0.9f, dragged = -500f, height = 1000))
        assertTrue(swipedBrightness(0.1f, dragged = 500f, height = 1000) > 0f)
    }
}
