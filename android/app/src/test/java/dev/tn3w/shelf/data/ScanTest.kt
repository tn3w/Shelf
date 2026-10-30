package dev.tn3w.shelf.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

private val model by lazy { Recognizer(File("src/main/assets/scan.bin").readBytes()) }

private class ConvSpec(
    val inputs: Int,
    val outputs: Int,
    val weights: List<Int>,
    val kernel: Pair<Int, Int> = 1 to 1,
    val stride: Pair<Int, Int> = 1 to 1,
    val groups: Int = 1,
    val activation: Int = 0,
    val bias: List<Float> = List(outputs) { 0f },
    val residual: Int = 0,
)

private sealed interface Spec

private class Plain(val conv: ConvSpec) : Spec

private class Gate(val reduce: ConvSpec, val expand: ConvSpec) : Spec

private class Average(val height: Int, val width: Int) : Spec

private fun ByteBuffer.putConv(conv: ConvSpec) {
    val (kernelHeight, kernelWidth) = conv.kernel
    val (strideHeight, strideWidth) = conv.stride
    listOf(
        conv.inputs, conv.outputs, kernelHeight, kernelWidth, strideHeight, strideWidth,
        conv.groups, conv.activation, conv.residual, 8,
    ).forEach(::putInt)
    repeat(conv.outputs) { putFloat(1f / 127) }
    conv.bias.forEach(::putFloat)
    conv.weights.forEach { put(it.toByte()) }
}

private fun model(alphabet: String, layers: List<Spec>): ByteArray {
    val buffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("SCAN".toByteArray()).putInt(3).putInt(LINE_HEIGHT).putInt(alphabet.length)
    buffer.put(alphabet.toByteArray()).putInt(layers.size)
    for (layer in layers) {
        when (layer) {
            is Plain -> buffer.putInt(0).putConv(layer.conv)

            is Gate -> buffer.putInt(1).apply {
                putConv(layer.reduce)
                putConv(layer.expand)
            }

            is Average -> buffer.putInt(2).putInt(layer.height).putInt(layer.width)
        }
    }
    return buffer.array().copyOf(buffer.position())
}

private val split = Plain(
    ConvSpec(1, 2, listOf(127, -127), stride = LINE_HEIGHT to 1, activation = 1),
)

private fun classes(activation: Int = 0) = Plain(
    ConvSpec(
        2, 3, listOf(0, 0, 127, 0, 0, 127), activation = activation,
        bias = listOf(0.1f, 0f, 0f),
    ),
)

private val recognizer = Recognizer(model("ab", listOf(split, classes())))

private fun columns(vararg values: Float) = Gray(
    values.size, LINE_HEIGHT,
    FloatArray(values.size * LINE_HEIGHT) { values[it % values.size] },
)

private class Planes(
    val channels: Int,
    val height: Int,
    val width: Int,
    val data: DoubleArray,
) {
    operator fun get(channel: Int, y: Int, x: Int) = if (y in 0 until height &&
        x in 0 until width
    ) {
        data[(channel * height + y) * width + x]
    } else {
        0.0
    }
}

private fun activated(value: Double, activation: Int) = when (activation) {
    1 -> max(value, 0.0)
    2 -> 0.5 * value * (1 + erf(value / Math.sqrt(2.0)))
    3 -> value * (value / 6 + 0.5).coerceIn(0.0, 1.0)
    else -> value
}

private fun erf(value: Double): Double {
    if (abs(value) > 4) return Math.signum(value)
    var total = 0.0
    var term = value
    for (n in 0 until 60) {
        total += term / (2 * n + 1)
        term *= -value * value / (n + 1)
    }
    return total * 2 / Math.sqrt(Math.PI)
}

private fun naive(input: Planes, conv: ConvSpec): Planes {
    val (kernelHeight, kernelWidth) = conv.kernel
    val (strideHeight, strideWidth) = conv.stride
    val height = (input.height - 1) / strideHeight + 1
    val width = (input.width - 1) / strideWidth + 1
    val perGroup = conv.inputs / conv.groups
    val data = DoubleArray(conv.outputs * height * width) { index ->
        val output = index / (height * width)
        val y = index / width % height
        val x = index % width
        var total = conv.bias[output].toDouble()
        for (channel in 0 until perGroup) {
            for (kernelY in 0 until kernelHeight) {
                for (kernelX in 0 until kernelWidth) {
                    val weight = conv.weights[
                        ((output * perGroup + channel) * kernelHeight + kernelY) *
                            kernelWidth +
                            kernelX,
                    ] / 127.0
                    total += weight * input[
                        output / (conv.outputs / conv.groups) * perGroup + channel,
                        y * strideHeight + kernelY - (kernelHeight - 1) / 2,
                        x * strideWidth + kernelX - (kernelWidth - 1) / 2,
                    ]
                }
            }
        }
        activated(total, conv.activation)
    }
    return Planes(conv.outputs, height, width, data)
}

private fun naiveRead(
    image: Gray,
    convs: List<ConvSpec>,
    alphabet: String,
): Pair<String, Double> {
    var planes = Planes(
        1, image.height, image.width,
        DoubleArray(image.pixels.size) { image.pixels[it] / 127.5 - 1 },
    )
    for (conv in convs) planes = naive(planes, conv)
    val steps = planes.width
    val text = StringBuilder()
    val confidences = mutableListOf<Double>()
    var previous = 0
    for (step in 0 until steps) {
        val logits = (0 until planes.channels).map { planes[it, 0, step] }
        val best = logits.indices.maxBy { logits[it] }
        if (best != 0 && best != previous) {
            text.append(alphabet[best - 1])
            confidences += 1 / logits.sumOf { exp(it - logits[best]) }
        }
        previous = best
    }
    return text.toString() to (confidences.average().takeIf { it.isFinite() } ?: 0.0)
}

private fun randomConv(
    random: Random,
    inputs: Int,
    outputs: Int,
    kernel: Pair<Int, Int> = 1 to 1,
    stride: Pair<Int, Int> = 1 to 1,
    groups: Int = 1,
    activation: Int = 0,
) = ConvSpec(
    inputs, outputs,
    List(outputs * inputs / groups * kernel.first * kernel.second) {
        random.nextInt(-127, 128)
    },
    kernel, stride, groups, activation,
    List(outputs) { random.nextFloat() - 0.5f },
)

private fun cover(
    lines: List<Pair<String, Int>>,
    width: Int = 640,
    height: Int = 480,
    quarterTurns: Int = 0,
): Gray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
    )
    graphics.color = Color(225, 225, 225)
    graphics.fillRect(0, 0, width, height)
    graphics.color = Color(30, 30, 30)
    var baseline = height / 4
    for ((text, size) in lines) {
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, size)
        val left = (width - graphics.fontMetrics.stringWidth(text)) / 2
        baseline += size
        graphics.drawString(text, left, baseline)
        baseline += size / 2
    }
    graphics.dispose()
    val pixels = FloatArray(width * height) {
        image.raster.getSample(it % width, it / width, 0).toFloat()
    }
    return turned(Gray(width, height, pixels), quarterTurns)
}

private fun printed(text: String): Gray {
    val font = Font(Font.SERIF, Font.PLAIN, 30)
    val probe = BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY).createGraphics()
    val metrics = probe.getFontMetrics(font)
    val width = metrics.stringWidth(text) + 16
    val image = BufferedImage(width, LINE_HEIGHT, BufferedImage.TYPE_BYTE_GRAY)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
    )
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, width, LINE_HEIGHT)
    graphics.color = Color.BLACK
    graphics.font = font
    graphics.drawString(text, 8, (LINE_HEIGHT + metrics.ascent - metrics.descent) / 2)
    graphics.dispose()
    return Gray(
        width, LINE_HEIGHT,
        FloatArray(width * LINE_HEIGHT) {
            image.raster.getSample(it % width, it / width, 0).toFloat()
        },
    )
}

private fun turned(frame: Gray, quarterTurns: Int): Gray {
    if (quarterTurns % 4 == 0) return frame
    val width = frame.height
    val height = frame.width
    val pixels = FloatArray(width * height) {
        val x = it % width
        val y = it / width
        frame.pixels[(frame.height - 1 - x) * frame.width + y]
    }
    return turned(Gray(width, height, pixels), quarterTurns - 1)
}

private fun words(lines: List<TextLine>) = lines.flatMap { tokenize(it.text) }.toSet()

private fun barcode(text: String, format: BarcodeFormat, turned: Boolean = false): Gray {
    val matrix = MultiFormatWriter().encode(text, format, 400, 240)
    val width = if (turned) matrix.height else matrix.width
    val height = if (turned) matrix.width else matrix.height
    return Gray(
        width, height,
        FloatArray(width * height) {
            val (x, y) = it % width to it / width
            if (if (turned) matrix[y, x] else matrix[x, y]) 0f else 255f
        },
    )
}

private val dune = Fixture(1, "Dune", "Frank Herbert")
private val shelf = listOf(
    dune,
    Fixture(2, "Dune Messiah", "Frank Herbert"),
    Fixture(3, "Emma", "Jane Austen"),
    Fixture(4, "The Dune Garden", "Bo Bee"),
)
private val duneCover = listOf("DUNE" to 72, "FRANK HERBERT" to 32)

class ScanTest {
    @Test
    fun barcodesReadAsIsbnInAnyOrientation() {
        val reader = BarcodeReader()
        val frames = listOf(
            barcode("9780439023481", BarcodeFormat.EAN_13),
            barcode("9780439023481", BarcodeFormat.EAN_13, turned = true),
            barcode("https://example.org/isbn/978-0-439-02348-1", BarcodeFormat.QR_CODE),
        )
        assertEquals(List(3) { "9780439023481" }, frames.map(reader::isbn))
        assertNull(reader.isbn(barcode("4006381333931", BarcodeFormat.EAN_13)))
    }

    @Test
    fun scannerFindsBarcodeInOtherInstalledCatalogues() {
        val dune = Fixture(7, "Dune", isbns = listOf("0441013597"))
        val catalogue = Catalogue("de", listOf(segment(listOf(Fixture(1, "Emma")))), null)
        val other = Catalogue("en", listOf(segment(listOf(dune))), null)
        val scanner =
            Scanner(catalogue, Searcher(catalogue), recognizer) { listOf(other) }
        assertEquals(7, scanner.next(barcode("9780441013593", BarcodeFormat.EAN_13)).work)
    }

    @Test
    fun scannerSkipsOtherCataloguesWhenBarcodeIsInCurrent() {
        val dune = Fixture(7, "Dune", isbns = listOf("0441013597"))
        val catalogue = Catalogue("en", listOf(segment(listOf(dune))), null)
        val scanner = Scanner(catalogue, Searcher(catalogue), recognizer) {
            error("other catalogues loaded")
        }
        assertEquals(7, scanner.next(barcode("9780441013593", BarcodeFormat.EAN_13)).work)
    }

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
    fun residualLayerAddsInputOfEarlierLayer() {
        val zero = Plain(ConvSpec(3, 3, List(9) { 0 }))
        val residual = Plain(ConvSpec(3, 3, List(9) { 0 }, residual = 2))
        val image = columns(255f, 0f, 0f, 255f)
        val plain = recognizer.read(image)
        val withResidual =
            Recognizer(model("ab", listOf(split, classes(), zero, residual)))
        val without = Recognizer(model("ab", listOf(split, classes(), zero)))
        val reading = withResidual.read(image)
        assertEquals("", without.read(image).text)
        assertEquals(plain.text, reading.text)
        assertEquals(plain.confidence, reading.confidence, 1e-6f)
    }

    @Test
    fun poolAveragesNeighbouringColumns() {
        val pooled = Recognizer(model("ab", listOf(split, Average(1, 2), classes())))
        val reading = pooled.read(columns(255f, 127.5f))
        assertEquals("a", reading.text)
        val expected = exp(0.5) / (exp(0.1) + exp(0.5) + exp(0.0))
        assertEquals(expected, reading.confidence.toDouble(), 1e-4)
    }

    @Test
    fun closedGateSilencesEveryLetter() {
        val closed = ConvSpec(1, 2, listOf(0, 0), activation = 4, bias = listOf(-3f, -3f))
        val reduce = ConvSpec(2, 1, listOf(0, 0), activation = 1)
        val gated =
            Recognizer(model("ab", listOf(split, Gate(reduce, closed), classes())))
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
    fun convolutionsMatchNaiveReference() {
        val random = Random(5)
        val convs = listOf(
            randomConv(random, 1, 4, 3 to 3, 2 to 2, activation = 2),
            randomConv(random, 4, 4, 3 to 3, 2 to 1, groups = 4, activation = 3),
            randomConv(random, 4, 6, 3 to 3, 2 to 2, activation = 1),
            randomConv(random, 6, 6, 1 to 5, groups = 6, activation = 2),
            randomConv(random, 6, 7, stride = 6 to 1, activation = 0),
            randomConv(random, 7, 5),
        )
        val recognizer = Recognizer(model("abcd", convs.map(::Plain)))
        repeat(5) {
            val width = random.nextInt(8, 120)
            val image = Gray(
                width, LINE_HEIGHT,
                FloatArray(width * LINE_HEIGHT) {
                    random.nextFloat() * 255
                },
            )
            val (text, confidence) = naiveRead(image, convs, "abcd")
            val reading = recognizer.read(image)
            assertEquals(text, reading.text)
            assertEquals(confidence, reading.confidence.toDouble(), 1e-3)
        }
    }

    @Test
    fun modelReadsPrintedWords() {
        for (word in listOf("HOBBIT", "Middlemarch", "Les Misérables")) {
            assertEquals(tokenize(word), tokenize(model.read(printed(word)).text))
        }
    }

    @Test
    fun readerReadsCoverInEveryOrientation() {
        for (quarterTurns in 0..3) {
            val frame = cover(duneCover, quarterTurns = quarterTurns)
            val lines = Reader(model, ScanSettings()).read(frame)
            assertTrue(
                "turn $quarterTurns: ${lines.map { it.text }}",
                "dune" in words(lines),
            )
            assertTrue("herbert" in words(lines))
        }
    }

    @Test
    fun readerKeepsOrientationAcrossFrames() {
        val reader = Reader(model, ScanSettings())
        val frame = cover(duneCover, quarterTurns = 2)
        repeat(3) { assertTrue("dune" in words(reader.read(frame))) }
    }

    @Test
    fun fullResolutionFrameReadsAndDotsInFramePixels() {
        val frame = cover(duneCover.map { (text, size) -> text to size * 2 }, 1280, 960)
        val lines = Reader(model, ScanSettings()).read(frame)
        val title = lines.first { "dune" in tokenize(it.text) }
        assertTrue(title.dots.size >= 4)
        assertTrue(title.dots.all { it.x in 400f..880f && it.y in 300f..440f })
    }

    @Test
    fun finerPassAddsOnlyLinesTheCoarsePassMissed() {
        val frame = cover(duneCover.map { (text, size) -> text to size * 2 }, 1280, 960)
        val fine = finerLines(frame, 0.0, 2, emptyList())
        assertTrue(fine.isNotEmpty() && fine.size <= 3)
        assertTrue(fine.all { it.right <= 640f && it.bottom <= 480f })
        val known = findLines(View(frame, 0.0, 1).render(frame)).map { it.scaled(0.5f) }
        assertTrue(finerLines(frame, 0.0, 2, known).isEmpty())
        assertTrue(finerLines(frame, 0.0, 1, emptyList()).isEmpty())
    }

    @Test
    fun blurryFrameReadsNothing() {
        val flat = Gray(640, 480, FloatArray(640 * 480) { 128f })
        assertEquals(emptyList<TextLine>(), Reader(model, ScanSettings()).read(flat))
    }

    @Test
    fun scannerStopsOnCoverWithDotsOnTitle() {
        val catalogue = catalogue(shelf)
        val scanner = Scanner(catalogue, Searcher(catalogue), model)
        val frame = cover(duneCover, quarterTurns = 1)
        val steps = List(3) { scanner.next(frame) }
        val step = steps.first { it.work != null }
        assertEquals(1, step.work)
        assertTrue(step.dots.isNotEmpty())
        assertTrue(step.dots.all { it.x in 0f..480f && it.y in 0f..640f })
    }

    @Test
    fun scannerIgnoresCoverMissingFromCatalogue() {
        val catalogue = catalogue(shelf)
        val scanner = Scanner(catalogue, Searcher(catalogue), model)
        val frame = cover(listOf("MOBY DICK" to 64, "HERMAN MELVILLE" to 32))
        repeat(5) { assertNull(scanner.next(frame).work) }
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
    fun stopsOnlyAfterClearLeadHoldsForEnoughFrames() {
        val rule =
            StopRule(ScanSettings(threshold = 1.0, margin = 0.5, frames = 2, decay = 0.5))
        assertNull(rule.update(mapOf(1 to 0.8, 2 to 0.6)))
        assertNull(rule.update(mapOf(1 to 0.9)))
        assertEquals(1, rule.update(mapOf(1 to 0.9)))
    }

    @Test
    fun dotsFollowCharacterPositionsInCrop() {
        val view = View(Gray(100, 50, FloatArray(5000)), 0.0, 1)
        val line = LineBox(10f, 20f, 50f, 28f)
        val dots = view.dots(line, listOf(0f, 0.5f, 1f), flipped = false)
        assertEquals(listOf(8, 30, 52), dots.map { it.x.roundToInt() })
        assertEquals(listOf(24f), dots.map { it.y }.distinct())
        val flipped = view.dots(line, listOf(0f, 1f), flipped = true)
        assertEquals(listOf(52, 8), flipped.map { it.x.roundToInt() })
    }

    @Test
    fun scaledViewMapsToFullResolution() {
        val view = View(Gray(200, 100, FloatArray(20000)), 0.0, 2)
        assertEquals(100 to 50, view.width to view.height)
        val dots = view.dots(LineBox(10f, 20f, 50f, 28f), listOf(0.5f), flipped = false)
        assertEquals(60, dots.single().x.roundToInt())
        assertEquals(48, dots.single().y.roundToInt())
    }

    @Test
    fun shrinkAveragesBlocks() {
        val image = Gray(4, 2, floatArrayOf(0f, 4f, 8f, 8f, 4f, 0f, 0f, 0f))
        assertEquals(listOf(2f, 4f), image.shrunk(2).pixels.toList())
        assertEquals(1, detectionScale(Gray(640, 480, FloatArray(0))))
        assertEquals(2, detectionScale(Gray(1280, 960, FloatArray(0))))
    }

    @Test
    fun coverStopPhrasesDoNotCountAsTitleWords() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "The Novel Garden", "Ann Author"),
                Fixture(2, "Garden", "Bo Bee"),
            ) + (3..60).map { Fixture(it, "Other $it", "Cy Dee") },
        )
        val scores = Matcher(catalogue, Searcher(catalogue))
            .scores(listOf(TextLine("GARDEN", 1f, 40f), TextLine("A NOVEL", 1f, 20f)))
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
        val lines = listOf(
            TextLine("D.A.S HEU·L·E.N", 1f, 20f),
            TextLine("D-E.R W-OLFE", 1f, 20f),
        )
        val scores = Matcher(catalogue, Searcher(catalogue)).scores(lines)
        assertEquals(1, scores.maxBy { it.value }.key)
    }

    @Test
    fun exactTitleWordStaysCandidateBesideManyCompletions() {
        val completions = (2..80).map { Fixture(it, "Letrangers $it", "Bo Bee") }
        val catalogue = catalogue(completions + Fixture(1, "L’étranger", "Albert Camus"))
        val scores = Matcher(catalogue, Searcher(catalogue))
            .scores(listOf(TextLine("LÉTRANGER", 1f, 40f)))
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
        val lines = listOf(
            TextLine("LE MEILLEUR", 1f, 25f),
            TextLine("ALDOUS", 1f, 22f),
            TextLine("DES MONDES", 1f, 25f),
            TextLine("ES MON", 1f, 50f),
            TextLine("UR", 1f, 35f),
        )
        val scores = Matcher(catalogue, Searcher(catalogue)).scores(lines)
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
        val lines = listOf(
            TextLine("HARRY POTTER", 1f, 40f),
            TextLine("opher's Stone", 1f, 20f),
            TextLine("J.K. ROWLING", 1f, 20f),
        )
        val scores = Matcher(catalogue, Searcher(catalogue)).scores(lines)
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
            .scores(listOf(TextLine("HARRYPOTTE", 1f, 40f)))
        assertEquals(1, scores.maxBy { it.value }.key)
    }

    @Test
    fun authorLineAloneDoesNotConfirmGenericTitle() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "James", "Percival Everett"),
                Fixture(2, "Fifty Shades of Grey", "E. L. James"),
            ) + (3..200).map { Fixture(it, "Other Story $it", "Cy Dee") },
        )
        val matcher = Matcher(catalogue, Searcher(catalogue))
        val alone = matcher.scores(listOf(TextLine("E L James", 1f, 30f)))[1] ?: 0.0
        assertTrue(alone < ScanSettings().threshold)
        val confirmed = matcher.scores(
            listOf(TextLine("JAMES", 1f, 40f), TextLine("PERCIVAL EVERETT", 1f, 20f)),
        )
        assertTrue(confirmed.getValue(1) > 2 * alone)
    }

    @Test
    fun authorNameInsideTitleIsNoTitleEvidence() {
        val catalogue = catalogue(
            listOf(
                Fixture(1, "Die große Roald Dahl Schatzkiste", "Roald Dahl"),
                Fixture(2, "Matilda", "Roald Dahl"),
            ) + (3..60).map { Fixture(it, "Other $it", "Cy Dee") },
        )
        val scores = Matcher(catalogue, Searcher(catalogue))
            .scores(listOf(TextLine("ROALD DAHL", 1f, 30f)))
        assertNull(scores[1])
    }

    @Test
    fun seriesNameAloneDoesNotPickShorterLessPopularTitle() {
        val short = Fixture(1, "Harry Potter", "Warner Bros", score = 1)
        val long = Fixture(2, "Harry Potter and the Secrets", "J. K. Rowling", score = 9)
        val filler = (3..60).map { Fixture(it, "Other $it", "Cy Dee") }
        val lines = listOf(TextLine("HARRY", 1f, 40f), TextLine("POTTER", 1f, 40f))
        fun scoreOfShort(books: List<Fixture>): Double {
            val catalogue = catalogue(books + filler)
            return Matcher(catalogue, Searcher(catalogue)).scores(lines).getValue(1)
        }
        val alone = scoreOfShort(listOf(short, long.copy(title = "Secrets")))
        assertEquals(alone / 2, scoreOfShort(listOf(short, long)), 0.05)
    }

    @Test
    fun sampleHandlesSinglePixelAxes() {
        assertEquals(7f, Gray(1, 1, floatArrayOf(7f)).sample(3f, 2f), 0f)
        assertEquals(5f, Gray(1, 2, floatArrayOf(4f, 6f)).sample(0f, 0.5f), 0.01f)
    }

    @Test
    fun naiveErfMatchesKnownValues() {
        assertEquals(0.8427007929, erf(1.0), 1e-9)
        assertTrue(abs(erf(-0.5) + 0.5204998778) < 1e-9)
    }
}
