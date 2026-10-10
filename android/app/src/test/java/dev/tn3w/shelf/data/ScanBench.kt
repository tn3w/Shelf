package dev.tn3w.shelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import javax.imageio.ImageIO

private val CATALOGUE = File("android/app/build/generated/downloadCatalogue")
private val CACHE = File("scanbench/cache")
private val MODEL = File("android/app/src/main/assets/scan.bin")

@Serializable
class Frame(
    val sharpness: Double,
    val texts: List<String>,
    val works: List<Int>,
    val scores: List<Double>,
    val milliseconds: Double,
)

@Serializable
class Record(val id: String, val expected: Int?, val frames: List<Frame>)

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
    val started = System.nanoTime()
    val texts = files.associate {
        it.name to JsonPrimitive(recognizer.read(gray(it)).text)
    }
    folder.resolve("kotlin.json").writeText(JsonObject(texts).toString())
    println("%.1f ms per line".format((System.nanoTime() - started) / 1e6 / files.size))
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

private fun analyze(
    clip: Clip,
    catalogue: Catalogue,
    searcher: Searcher,
    recognizer: Recognizer,
): Record {
    val known = { word: String -> searcher.frequency(word) > 0 }
    val reader = Reader(recognizer, ScanSettings(blur = 0.0), known)
    val matcher = Matcher(catalogue, searcher)
    val expected = clip.expected?.let { identity(catalogue, it) }
    fun same(work: Int) = identity(catalogue, work) == expected
    val files = clip.folder.listFiles().orEmpty().filter { it.extension == "jpg" }
    val frames = files.sorted().map { file ->
        val frame = gray(file)
        val started = System.nanoTime()
        val readings = reader.read(frame)
        val scores = matcher.scores(readings).entries.sortedByDescending { it.value }
            .take(12)
        val milliseconds = (System.nanoTime() - started) / 1e6
        val texts = readings.map { it.text }
        val works = scores.map { if (same(it.key)) clip.expected!! else it.key }
        Frame(sharpness(frame), texts, works, scores.map { it.value }, milliseconds)
    }
    return Record(clip.id, clip.expected, frames)
}

private fun records(split: String, threads: Int, reuse: Boolean): List<Record> {
    val file = CACHE.resolve("bench/$split.json")
    if (reuse && file.exists()) return Json.decodeFromString(file.readText())
    val clips = clips(split)
    val catalogues = clips.map { it.language }.distinct().associateWith(::loadCatalogue)
    val searchers = catalogues.mapValues { Searcher(it.value) }
    val recognizer = Recognizer(MODEL.readBytes())
    val pool = Executors.newFixedThreadPool(threads)
    val records = clips.map { clip ->
        pool.submit<Record> {
            val language = clip.language
            val catalogue = catalogues.getValue(language)
            analyze(clip, catalogue, searchers.getValue(language), recognizer)
        }
    }.map { it.get() }
    pool.shutdown()
    file.parentFile.mkdirs()
    file.writeText(Json.encodeToString(records))
    return records
}

private class Summary(
    val right: Double,
    val wrong: Double,
    val negativeStops: Int,
    val frames: Double,
)

private class Stop(val work: Int, val frame: Int)

private fun stop(record: Record, settings: ScanSettings): Stop? {
    val rule = StopRule(settings)
    record.frames.forEachIndexed { index, frame ->
        val sharp = frame.sharpness >= settings.blur
        val scores = if (sharp) frame.works.zip(frame.scores).toMap() else emptyMap()
        rule.update(scores)?.let { return Stop(it, index + 1) }
    }
    return null
}

private fun summarize(records: List<Record>, settings: ScanSettings): Summary {
    val (books, negatives) = records.partition { it.expected != null }
    val stops = books.map { stop(it, settings) to it.expected }
    val right = stops.filter { it.first?.work == it.second }
    return Summary(
        right.size.toDouble() / books.size,
        stops.count { it.first != null && it.first?.work != it.second }.toDouble() /
            books.size,
        negatives.count { stop(it, settings) != null },
        right.map { it.first!!.frame }.average(),
    )
}

private fun grid(): List<ScanSettings> {
    val rules = listOf(0.0, 20.0, 40.0, 80.0).flatMap { blur ->
        listOf(0.5, 0.7, 0.9).flatMap { decay -> (1..3).map { Triple(blur, decay, it) } }
    }
    return rules.flatMap { (blur, decay, frames) ->
        (5..60 step 2).flatMap { threshold ->
            (0..20 step 2).map { margin ->
                ScanSettings(
                    blur = blur, decay = decay, frames = frames,
                    threshold = threshold / 10.0, margin = margin / 10.0,
                )
            }
        }
    }
}

private fun tune(records: List<Record>) = grid().map { it to summarize(records, it) }
    .filter { (_, summary) -> summary.negativeStops == 0 && summary.wrong <= 0.002 }
    .maxByOrNull { it.second.right }?.first ?: ScanSettings()

private fun Record.found() = frames.any { it.works.firstOrNull() == expected }

private fun bench(arguments: List<String>) {
    val split = arguments.getOrNull(1) ?: "tune"
    val threads = Runtime.getRuntime().availableProcessors()
    val records = records(split, threads, "--reuse" in arguments)
    val books = records.filter { it.expected != null }
    val top = books.count { it.found() }
    val settings = if ("--tune" in arguments) tune(records) else ScanSettings()
    val summary = summarize(records, settings)
    val milliseconds = records.flatMap { it.frames }.map { it.milliseconds }.average()
    println("$split: ${books.size} books, ${records.size - books.size} negatives")
    println(
        "ever top-1 %.1f%%, right %.1f%%, wrong %.1f%%, negative stops %d, %.0f ms/frame"
            .format(
                100.0 * top / books.size, 100 * summary.right, 100 * summary.wrong,
                summary.negativeStops, milliseconds,
            ),
    )
    println(
        "%.2f frames until right stop, %.0f ms"
            .format(summary.frames, summary.frames * milliseconds),
    )
    with(settings) {
        println("blur $blur decay $decay frames $frames")
        println("threshold $threshold margin $margin")
    }
}

fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "export" -> export(File(arguments[1]))
        "parity" -> parity(File(arguments[1]))
        "bench" -> bench(arguments.toList())
        else -> error("usage: export | parity | bench [split] [--reuse] [--tune]")
    }
}
