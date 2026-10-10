package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.roundToInt

private const val HEIGHT = 48

private class LayerSpec(
    val inputs: Int,
    val outputs: Int,
    val kernelHeight: Int,
    val strideHeight: Int,
    val activation: Int,
    val weights: List<Int>,
    val bias: List<Float> = List(outputs) { 0f },
    val residual: Int = 0,
)

private sealed interface Spec

private class ConvSpec(val layer: LayerSpec) : Spec

private class SqueezeSpec(val reduce: LayerSpec, val expand: LayerSpec) : Spec

private class PoolSpec(val height: Int, val width: Int) : Spec

private fun ByteBuffer.putConv(layer: LayerSpec) {
    val spec = listOf(layer.inputs, layer.outputs, layer.kernelHeight, 1)
    (spec + listOf(layer.strideHeight, 1, 1)).forEach(::putInt)
    putInt(layer.activation).putInt(layer.residual).putInt(8)
    repeat(layer.outputs) { putFloat(1f / 127) }
    layer.bias.forEach(::putFloat)
    layer.weights.forEach { put(it.toByte()) }
}

private fun model(alphabet: String, layers: List<Spec>): ByteArray {
    val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("SCAN".toByteArray()).putInt(3).putInt(HEIGHT).putInt(alphabet.length)
    buffer.put(alphabet.toByteArray()).putInt(layers.size)
    for (layer in layers) {
        when (layer) {
            is ConvSpec -> buffer.putInt(0).putConv(layer.layer)

            is SqueezeSpec -> buffer.putInt(1).apply {
                putConv(layer.reduce)
                putConv(layer.expand)
            }

            is PoolSpec -> buffer.putInt(2).putInt(layer.height).putInt(layer.width)
        }
    }
    return buffer.array().copyOf(buffer.position())
}

private val split = ConvSpec(
    LayerSpec(1, 2, 1, HEIGHT, activation = 1, weights = listOf(127, -127)),
)

private fun classes(activation: Int = 0) = ConvSpec(
    LayerSpec(
        2, 3, 1, 1, activation, weights = listOf(0, 0, 127, 0, 0, 127),
        bias = listOf(0.1f, 0f, 0f),
    ),
)

private val layers = listOf(split, classes())

private val recognizer = Recognizer(model("ab", layers))

private val identity = ConvSpec(
    LayerSpec(3, 3, 1, 1, activation = 0, weights = List(9) { 0 }, residual = 1),
)

private fun columns(vararg values: Float) = Gray(
    values.size, HEIGHT,
    FloatArray(values.size * HEIGHT) {
        values[it % values.size]
    },
)

class ScanTest {
    @Test
    fun brightColumnsReadAsFirstLetterDarkAsSecond() {
        val reading = recognizer.read(columns(255f, 0f, 0f, 255f))
        assertEquals("aba", reading.text)
        assertEquals(listOf(0.125f, 0.375f, 0.875f), reading.positions)
        val expected = exp(1.0) / (exp(0.1) + exp(1.0) + exp(0.0))
        assertEquals(expected, reading.confidence.toDouble(), 1e-4)
    }

    @Test
    fun blankColumnSeparatesRepeatedLetters() {
        assertEquals("aab", recognizer.read(columns(255f, 255f, 127.5f, 255f, 0f)).text)
    }

    @Test
    fun residualLayerAddsItsInput() {
        val withResidual = Recognizer(model("ab", layers + identity))
        val image = columns(255f, 0f, 0f, 255f)
        val plain = recognizer.read(image)
        val reading = withResidual.read(image)
        assertEquals(plain.text, reading.text)
        assertEquals(plain.confidence, reading.confidence, 1e-6f)
    }

    @Test
    fun poolAveragesNeighbouringColumns() {
        val pooled = Recognizer(model("ab", listOf(split, PoolSpec(1, 2), classes())))
        val reading = pooled.read(columns(255f, 127.5f))
        assertEquals("a", reading.text)
        val expected = exp(0.5) / (exp(0.1) + exp(0.5) + exp(0.0))
        assertEquals(expected, reading.confidence.toDouble(), 1e-4)
    }

    @Test
    fun closedGateSilencesEveryLetter() {
        val closed = LayerSpec(
            1, 2, 1, 1, activation = 4, weights = listOf(0, 0),
            bias = listOf(-3f, -3f),
        )
        val reduce = LayerSpec(2, 1, 1, 1, activation = 1, weights = listOf(0, 0))
        val gated =
            Recognizer(model("ab", listOf(split, SqueezeSpec(reduce, closed), classes())))
        assertEquals("", gated.read(columns(255f, 0f, 255f)).text)
    }

    @Test
    fun geluKeepsPositiveAndDampsNegativeLogits() {
        val gelu = Recognizer(model("ab", listOf(split, classes(activation = 2))))
        val reading = gelu.read(columns(255f, 0f))
        assertEquals("ab", reading.text)
        val letter = 0.5 * (1 + 0.6826895)
        val expected = exp(letter) / (exp(0.1 * 0.5 * (1 + 0.0797)) + exp(letter) + 1)
        assertEquals(expected, reading.confidence.toDouble(), 1e-3)
    }

    @Test
    fun flatImageReadsNothing() {
        val reading = recognizer.read(columns(127.5f, 127.5f, 127.5f))
        assertEquals("", reading.text)
        assertEquals(0f, reading.confidence)
    }

    @Test
    fun stopsOnlyAfterClearLeadHoldsForEnoughFrames() {
        val rule =
            StopRule(ScanSettings(threshold = 1.0, margin = 0.5, frames = 2, decay = 0.5))
        assertNull(rule.update(mapOf(1 to 0.8, 2 to 0.6)))
        assertNull(rule.update(mapOf(1 to 0.9)))
        assertEquals(1, rule.update(mapOf(1 to 0.9)))
    }

    @Test
    fun tapStopsOnFirstFrameWithLowerThreshold() {
        val settings = ScanSettings(threshold = 1.0, tapThreshold = 0.5, tapMargin = 0.2)
        val scores = mapOf(1 to 0.6, 2 to 0.3)
        assertNull(StopRule(settings).update(scores))
        assertEquals(1, StopRule(settings).update(scores, tapped = true))
        assertNull(StopRule(settings).update(mapOf(1 to 0.4), tapped = true))
    }

    @Test
    fun dotsFollowCharacterPositionsInCrop() {
        val frame = Gray(100, 50, FloatArray(5000))
        val view = View(frame, 0.0, 100, 50)
        val line = LineBox(10f, 20f, 50f, 28f)
        val dots = view.dots(line, listOf(0f, 0.5f, 1f), flipped = false)
        assertEquals(listOf(8, 30, 52), dots.map { it.x.roundToInt() })
        assertEquals(listOf(24f), dots.map { it.y }.distinct())
        val flipped = view.dots(line, listOf(0f, 1f), flipped = true)
        assertEquals(listOf(52, 8), flipped.map { it.x.roundToInt() })
    }

    @Test
    fun coverStopPhrasesDoNotCountAsTitleWords() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "The Novel Garden", "Ann Author"),
                Fixture(2, "Garden", "Bo Bee"),
            ),
        )
        val scores = Matcher(catalogue, Searcher(catalogue))
            .scores(listOf(Reading("GARDEN", 1f, 40f), Reading("A NOVEL", 1f, 20f)))
        assertEquals(2, scores.maxBy { it.value }.key)
    }

    @Test
    fun letterSpacedTitleReadsAsWholeWords() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "Das Heulen der Wolfe", "Ann Author"),
                Fixture(2, "Heu", "Bo Bee"),
            ),
        )
        val readings =
            listOf(Reading("D.A.S HEU·L·E.N", 1f, 20f), Reading("D-E.R W-OLFE", 1f, 20f))
        val scores = Matcher(catalogue, Searcher(catalogue)).scores(readings)
        assertEquals(1, scores.maxBy { it.value }.key)
    }

    @Test
    fun exactTitleWordStaysCandidateBesideManyCompletions() {
        val completions = (2..80).map { Fixture(it, "Letrangers $it", "Bo Bee") }
        val catalogue = catalogue(completions + Fixture(1, "L’étranger", "Albert Camus"))
        val scores = Matcher(catalogue, Searcher(catalogue))
            .scores(listOf(Reading("LÉTRANGER", 1f, 40f)))
        assertEquals(1, scores.maxBy { it.value }.key)
    }

    @Test
    fun titleFragmentDoesNotCountAgainstBook() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "Le meilleur des mondes", "Aldous Huxley"),
                Fixture(2, "Mon meilleur copain", "Bo Bee"),
            ),
        )
        val readings = listOf(
            Reading("LE MEILLEUR", 1f, 25f),
            Reading("ALDOUS", 1f, 22f),
            Reading("DES MONDES", 1f, 25f),
            Reading("ES MON", 1f, 50f),
            Reading("UR", 1f, 35f),
        )
        val scores = Matcher(catalogue, Searcher(catalogue)).scores(readings)
        assertEquals(1, scores.maxBy { it.value }.key)
    }

    @Test
    fun readSubtitleBeatsShorterTitle() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "Harry Potter and the Philosopher's Stone", "J. K. Rowling"),
                Fixture(2, "Harry Potter", "J. K. Rowling"),
                Fixture(3, "The Stone Garden", "Bo Bee"),
            ),
        )
        val readings = listOf(
            Reading("HARRY POTTER", 1f, 40f),
            Reading("opher's Stone", 1f, 20f),
            Reading("J.K. ROWLING", 1f, 20f),
        )
        val scores = Matcher(catalogue, Searcher(catalogue)).scores(readings)
        assertEquals(1, scores.maxBy { it.value }.key)
    }

    @Test
    fun volumeNumberDoesNotSplitEditions() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "Harry Potter und der Stein der Weisen", "J. K. Rowling"),
                Fixture(2, "Harry Potter 1 und der Stein der Weisen", "J. K. Rowling"),
            ),
        )
        assertEquals(identity(catalogue, 1), identity(catalogue, 2))
    }

    @Test
    fun gluedWordWithTypoSplitsIntoKnownWords() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "Harry Potter", "J. K. Rowling"),
                Fixture(2, "Harry Smith", "Bo Bee"),
            ),
        )
        val scores = Matcher(catalogue, Searcher(catalogue))
            .scores(listOf(Reading("HARRYPOTTE", 1f, 40f)))
        assertEquals(1, scores.maxBy { it.value }.key)
    }
}
