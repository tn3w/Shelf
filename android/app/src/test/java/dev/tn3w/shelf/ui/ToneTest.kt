package dev.tn3w.shelf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneTest {
    private val red = 0xFFB3261E.toInt()
    private val blue = 0xFF1E4FB3.toInt()
    private val grey = 0xFF808080.toInt()
    private val paperWhite = 0xFFFFFFFF.toInt()
    private val ink = 0xFF1E1915.toInt()
    private val lightInk = 0xFFF0E8DD.toInt()

    private fun cover(vararg areas: Pair<Int, Int>) =
        areas.flatMap { (color, pixels) -> List(pixels) { color } }.toIntArray()

    private fun hueDistance(first: Int, second: Int): Float {
        val apart = kotlin.math.abs(hslOf(first).hue - hslOf(second).hue)
        return minOf(apart, 360 - apart)
    }

    private fun hues() = (0 until 360 step 15).map { hsl(it.toFloat(), 0.8f, 0.5f) }

    @Test
    fun convertsToHslAndBack() {
        listOf(red, blue, grey, paperWhite, ink).forEach { color ->
            val tone = hslOf(color)
            val back = hsl(tone.hue, tone.saturation, tone.lightness)
            assertTrue(contrast(color, back) < 1.05f)
        }
    }

    @Test
    fun measuresContrastLikeWcag() {
        assertEquals(21f, contrast(paperWhite, 0xFF000000.toInt()), 0.01f)
        assertEquals(1f, contrast(red, red), 0.01f)
    }

    @Test
    fun takesTheLargestAreaAsPrimary() {
        val tone = coverTone(cover(red to 80, blue to 20))!!
        assertTrue(hueDistance(tone.primary, red) < 10f)
    }

    @Test
    fun takesADistinctColourAsAccent() {
        val tone = coverTone(cover(red to 80, blue to 20))!!
        assertTrue(hueDistance(tone.accent, blue) < 10f)
    }

    @Test
    fun ignoresSpecksWhenChoosingTheAccent() {
        val tone = coverTone(cover(red to 99, blue to 1))!!
        assertTrue(hueDistance(tone.accent, red) < 10f)
    }

    @Test
    fun keepsAColourlessCoverColourless() {
        val tone = coverTone(cover(grey to 50, paperWhite to 50))!!
        val tint = tintOf(tone, dark = false)
        assertTrue(hslOf(tint.paper).saturation < 0.05f)
        assertTrue(hslOf(tint.accent).saturation < 0.1f)
    }

    @Test
    fun prefersColourOverGreyForTheAccent() {
        val tone = coverTone(cover(grey to 90, blue to 10))!!
        assertTrue(hueDistance(tone.accent, blue) < 10f)
    }

    @Test
    fun hasNoToneWithoutOpaquePixels() {
        assertNull(coverTone(IntArray(16)))
        assertNull(coverTone(IntArray(0)))
    }

    @Test
    fun makesAnyColourReadable() {
        hues().forEach { color ->
            listOf(paperWhite, ink).forEach { background ->
                assertTrue(contrast(readable(color, background), background) >= 4.5f)
            }
        }
    }

    @Test
    fun keepsAReadableColourUntouched() {
        assertEquals(ink, readable(ink, paperWhite))
    }

    @Test
    fun tintsStayReadableForEveryHue() {
        for (color in hues() + grey + paperWhite + ink) {
            for (dark in listOf(false, true)) {
                val tint = tintOf(CoverTone(color, color), dark)
                val text = if (dark) lightInk else ink
                val surfaces =
                    listOf(tint.paper, tint.raised, tint.sunken, tint.glow, tint.wash)
                surfaces.forEach { assertTrue(contrast(text, it) >= 4.5f) }
                assertTrue(contrast(tint.accent, tint.paper) >= 4.5f)
                assertTrue(contrast(tint.onAccent, tint.accent) >= 4.5f)
            }
        }
    }

    @Test
    fun blackThemeKeepsItsBlackPaper() {
        val tint = tintOf(CoverTone(red, blue), dark = true, black = true)
        assertEquals(0xFF000000.toInt(), tint.paper)
        assertTrue(contrast(tint.accent, tint.paper) >= 4.5f)
    }
}
