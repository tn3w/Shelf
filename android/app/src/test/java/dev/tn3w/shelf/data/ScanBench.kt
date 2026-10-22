package dev.tn3w.shelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import javax.imageio.ImageIO

private val CATALOGUE = File("android/app/build/generated/downloadCatalogue")
private val CACHE = File("scanbench/cache")
private val MODEL = File("android/app/src/main/assets/scan.bin")
private const val FRAME_MILLIS = 100.0
private const val KEPT_SCORES = 12
private const val HOLD_MILLIS = 6000.0
private const val HELD_FRAMES = 6

@Serializable
class Processed(
    val frame: Int,
    val end: Double,
    val milliseconds: Double,
    val texts: List<String>,
    val confidences: List<Float> = emptyList(),
    val heights: List<Float> = emptyList(),
    val works: List<Int>,
    val scores: List<Double>,
)

@Serializable
class Record(val id: String, val expected: Int?, val frames: List<Processed>)

private class Clip(
    val id: String,
    val language: String,
    val expected: Int?,
    val folder: File,
)

private fun mapped(file: File) = RandomAccessFile(file, "r").use {
    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
}

private fun loadCatalogue(language: String): Catalogue {
    val files = CATALOGUE.listFiles().orEmpty()
        .filter { it.name.startsWith("$language-") && it.extension == "bin" }
    val (ranks, segments) = files.partition { "-ranks-" in it.name }
    return Catalogue(
        language, currentSegments(segments.map { Segment(mapped(it)) }),
        ranks.maxByOrNull { it.name }?.let { Ranks(mapped(it)) },
    )
}

private fun export(output: File) {
    output.mkdirs()
    for (language in LANGUAGES) {
        val catalogue = loadCatalogue(language)
        output.resolve("$language.jsonl").bufferedWriter().use { writer ->
            val works = catalogue.popularWorks(catalogue.workCount)
            works.mapNotNull(catalogue::book).forEach { book ->
                val json = buildJsonObject {
                    put("work", book.work)
                    put("title", book.title)
                    put("subtitle", book.subtitle)
                    put("authors", JsonArray(book.authors.map { JsonPrimitive(it.name) }))
                    put("cover", book.cover)
                    put("popularity", catalogue.popularity(book.work))
                    put("series", catalogue.series(book.work)?.name)
                    val tags = book.tags.mapNotNull { catalogue.tags.getOrNull(it)?.slug }
                    put("tags", JsonArray(tags.map(::JsonPrimitive)))
                    put("companion", book.isCompanion)
                }
                writer.appendLine(json.toString())
            }
        }
    }
}

private fun gray(file: File): Gray {
    val image = ImageIO.read(file)
    val pixels = FloatArray(image.width * image.height) {
        image.raster.getSample(it % image.width, it / image.width, 0).toFloat()
    }
    return Gray(image.width, image.height, pixels)
}

private fun parity(folder: File) {
    val recognizer = Recognizer(MODEL.readBytes())
    val files = folder.listFiles { file -> file.extension == "png" }.orEmpty().sorted()
    val texts = files.associate {
        it.name to JsonPrimitive(recognizer.read(gray(it)).text)
    }
    folder.resolve("kotlin.json").writeText(JsonObject(texts).toString())
}

private fun clips(split: String): List<Clip> {
    val manifest = Json.parseToJsonElement(CACHE.resolve("manifest.json").readText())
    return manifest.jsonObject.getValue("clips").jsonArray.map { it.jsonObject }
        .filter { it.getValue("split").jsonPrimitive.content == split }
        .map { clip ->
            val id = clip.getValue("id").jsonPrimitive.content
            val book = clip["book"] as? JsonObject
            val seed = clip.getValue("seed").jsonPrimitive.long
            val language = book?.getValue("language")?.jsonPrimitive?.content
                ?: LANGUAGES[(seed % LANGUAGES.size).toInt()]
            val work = book?.getValue("work")?.jsonPrimitive?.int
            Clip(id, language, work, CACHE.resolve("clips/$id"))
        }
}

private fun cpuMillis() = ManagementFactory.getThreadMXBean().currentThreadCpuTime / 1e6

private fun held(index: Int, count: Int): Int {
    if (index < count) return index
    val span = minOf(HELD_FRAMES, count) - 1
    if (span == 0) return count - 1
    val phase = (index - count + 1) % (2 * span)
    return count - 1 - if (phase <= span) phase else 2 * span - phase
}

private fun ranked(
    catalogue: Catalogue,
    scores: Map<Int, Double>,
    expected: Int?,
): Pair<List<Int>, List<Double>> {
    val identity = expected?.let { identity(catalogue, it) }
    val top = scores.entries.sortedByDescending { it.value }.take(KEPT_SCORES)
    val works = top.map {
        if (identity(catalogue, it.key) ==
            identity
        ) {
            expected
        } else {
            it.key
        }
    }
    return works to top.map { it.value }
}

private fun rescored(
    frame: Processed,
    catalogue: Catalogue,
    matcher: Matcher,
    record: Record,
): Processed = with(frame) {
    val lines = texts.indices.map { TextLine(texts[it], confidences[it], heights[it]) }
    val (works, scores) = ranked(catalogue, matcher.scores(lines), record.expected)
    Processed(this.frame, end, milliseconds, texts, confidences, heights, works, scores)
}

private fun rematch(split: String, from: String, name: String): List<Record> {
    val source = Json.decodeFromString<List<Record>>(
        CACHE.resolve("bench/$split-$from.json").readText(),
    )
    val languages = clips(split).associate { it.id to it.language }
    val catalogues = languages.values.distinct().associateWith(::loadCatalogue)
    val searchers = catalogues.mapValues { Searcher(it.value) }
    val records = source.parallelStream().map { record ->
        val language = languages.getValue(record.id)
        val catalogue = catalogues.getValue(language)
        val matcher = Matcher(catalogue, searchers.getValue(language))
        val frames = record.frames.map { frame ->
            rescored(frame, catalogue, matcher, record)
        }
        Record(record.id, record.expected, frames)
    }.toList()
    CACHE.resolve("bench/$split-$name.json").writeText(Json.encodeToString(records))
    return records
}

private fun simulate(
    clip: Clip,
    catalogue: Catalogue,
    look: (Gray) -> Look,
    slowdown: Double,
): Record {
    val expected = clip.expected?.let { identity(catalogue, it) }
    val files = clip.folder.listFiles().orEmpty().filter {
        it.extension == "jpg"
    }.sorted()
    val processed = mutableListOf<Processed>()
    var clock = 0.0
    var index = 0
    while (index * FRAME_MILLIS < HOLD_MILLIS) {
        val frame = gray(files[held(index, files.size)])
        clock = maxOf(clock, index * FRAME_MILLIS)
        val started = cpuMillis()
        val result = look(frame)
        val milliseconds = (cpuMillis() - started) * slowdown
        clock += milliseconds
        val (works, scores) = ranked(catalogue, result.scores, clip.expected)
        processed += Processed(
            index, clock, milliseconds, result.lines.map { it.text },
            result.lines.map { it.confidence }, result.lines.map { it.height },
            works, scores,
        )
        index = maxOf(index + 1, (clock / FRAME_MILLIS).toInt())
    }
    return Record(clip.id, clip.expected, processed)
}

private fun records(
    split: String,
    name: String,
    reuse: Boolean,
    slowdown: Double,
    pipeline: (Catalogue, Searcher) -> (Gray) -> Look,
): List<Record> {
    val file = CACHE.resolve("bench/$split-$name.json")
    if (reuse && file.exists()) return Json.decodeFromString(file.readText())
    val clips = clips(split)
    val catalogues = clips.map { it.language }.distinct().associateWith(::loadCatalogue)
    val searchers = catalogues.mapValues { Searcher(it.value) }
    val pools = List(Runtime.getRuntime().availableProcessors()) { ForkJoinPool(1) }
    val records = clips.mapIndexed { index, clip ->
        pools[index % pools.size].submit(
            Callable {
                val catalogue = catalogues.getValue(clip.language)
                val look = pipeline(catalogue, searchers.getValue(clip.language))
                simulate(clip, catalogue, look, slowdown)
            },
        )
    }.map { it.get() }
    pools.forEach { it.shutdown() }
    file.parentFile?.mkdirs()
    file.writeText(Json.encodeToString(records))
    return records
}

private class Stop(val work: Int, val end: Double)

private fun stop(record: Record, settings: ScanSettings, hidden: Int? = null): Stop? {
    val rule = StopRule(settings)
    for (frame in record.frames) {
        val scores = frame.works.zip(frame.scores).filter { it.first != hidden }.toMap()
        rule.update(scores)?.let { return Stop(it, frame.end) }
    }
    return null
}

private class Summary(
    val right: Double,
    val wrong: Double,
    val hiddenStops: Double,
    val seconds: List<Double>,
)

private fun summarize(records: List<Record>, settings: ScanSettings): Summary {
    val books = records.filter { it.expected != null }
    val stops = books.map { stop(it, settings) }
    val right = stops.zip(books).filter { (stop, book) -> stop?.work == book.expected }
    val wrong = stops.zip(books).count { (stop, book) ->
        stop != null && stop.work != book.expected
    }
    val hidden = books.count { stop(it, settings, it.expected) != null }
    return Summary(
        right.size.toDouble() / books.size,
        wrong.toDouble() / books.size,
        hidden.toDouble() / books.size,
        right.map { it.first!!.end / 1000 }.sorted(),
    )
}

private fun grid() = listOf(0.3, 0.5, 0.7).flatMap { decay ->
    (1..3).flatMap { frames ->
        (4..30).flatMap { threshold ->
            (0..12).map { margin ->
                ScanSettings(
                    decay = decay, frames = frames,
                    threshold = threshold / 10.0, margin = margin / 10.0,
                )
            }
        }
    }
}

private fun tune(records: List<Record>, wrong: Double, hidden: Double) = grid()
    .map { it to summarize(records, it) }
    .filter { (_, summary) -> summary.wrong <= wrong && summary.hiddenStops <= hidden }
    .maxByOrNull { (_, summary) -> summary.right - summary.seconds.sum() / 100_000 }
    ?.first ?: ScanSettings()

private fun List<Double>.percentile(share: Double) =
    if (isEmpty()) Double.NaN else this[((size - 1) * share).toInt()]

private fun report(split: String, records: List<Record>, settings: ScanSettings) {
    val summary = summarize(records, settings)
    val books = records.filter { it.expected != null }
    val top = books.count { book ->
        book.frames.any {
            it.works.firstOrNull() ==
                book.expected
        }
    }
    val frames = records.flatMap { it.frames }
    println("$split: ${books.size} books, ${frames.size} processed frames")
    println(
        "ever top-1 %.1f%%, %.0f ms/frame, %.1f processed frames/s".format(
            100.0 * top / books.size, frames.map { it.milliseconds }.average(),
            1000 * frames.size / records.sumOf { it.frames.last().end },
        ),
    )
    println(
        "right %.1f%%, wrong %.1f%%, hidden-book stops %.1f%%".format(
            100 * summary.right, 100 * summary.wrong, 100 * summary.hiddenStops,
        ),
    )
    println(
        "seconds to right stop: median %.2f, p90 %.2f, mean %.2f".format(
            summary.seconds.percentile(0.5), summary.seconds.percentile(0.9),
            summary.seconds.average(),
        ),
    )
    println(
        "decay ${settings.decay} frames ${settings.frames} " +
            "threshold ${settings.threshold} margin ${settings.margin}",
    )
}

private fun option(arguments: List<String>, name: String) =
    arguments.indexOf(name).takeIf { it >= 0 }?.let { arguments[it + 1] }

private fun bench(arguments: List<String>) {
    val split = arguments.getOrNull(1) ?: "tune"
    val name = option(arguments, "--name") ?: "current"
    val slowdown = option(arguments, "--slowdown")?.toDouble() ?: 1.0
    val reuse = "--reuse" in arguments
    val recognizer = Recognizer(MODEL.readBytes())
    val from = option(arguments, "--rematch")
    val records = if (from == null) {
        records(split, name, reuse, slowdown) { catalogue, searcher ->
            Scanner(catalogue, searcher, recognizer)::look
        }
    } else {
        rematch(split, from, name)
    }
    val wrong = option(arguments, "--wrong")?.toDouble() ?: 0.01
    val hidden = option(arguments, "--hidden")?.toDouble() ?: 0.05
    val settings = if ("--tune" in
        arguments
    ) {
        tune(records, wrong, hidden)
    } else {
        ScanSettings()
    }
    report(split, records, settings)
}

fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "export" -> export(File(arguments[1]))
        "parity" -> parity(File(arguments[1]))
        "bench" -> bench(arguments.toList())
        else -> error("usage: export <dir> | parity <dir> | bench [split] [options]")
    }
}
