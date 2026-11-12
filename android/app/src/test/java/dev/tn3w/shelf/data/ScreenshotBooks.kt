package dev.tn3w.shelf.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private const val POPULAR = 200

private fun mapped(file: File) = RandomAccessFile(file, "r").use {
    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
}

private fun pack(folder: File, language: String, kind: String) = folder.listFiles()
    .orEmpty()
    .first { it.name.startsWith("$language-$kind-") && it.extension == "bin" }

private fun catalogue(folder: File, language: String) = Catalogue(
    language,
    listOf(Segment(mapped(pack(folder, language, "core")))),
    Ranks(mapped(pack(folder, language, "ranks"))),
)

private fun Book.row() = buildJsonArray {
    add(work)
    add(title)
    add(author)
    add(cover)
}

private fun Chapter.row() = buildJsonArray {
    add(title)
    add(section)
}

private fun chapters(epub: File) = buildJsonArray {
    (openDocument(epub) as TextDocument).use { document ->
        document.chapters.forEach { add(it.row()) }
    }
}

private fun books(catalogue: Catalogue, request: JsonObject) = buildJsonObject {
    val works = request.getValue("works").jsonArray.map { it.jsonPrimitive.int }
    put("books", buildJsonArray { catalogue.books(works).forEach { add(it.row()) } })
    val fiction = catalogue.tagBySlug["fiction"]?.id
    val popular = catalogue.popularWorks(POPULAR)
        .filter { catalogue.series(it) == null }
        .mapNotNull(catalogue::book)
        .filter { it.cover > 0 && fiction in it.tags }
    put("popular", buildJsonArray { popular.forEach { add(it.row()) } })
    put("chapters", chapters(File(request.getValue("epub").jsonPrimitive.content)))
}

fun main(arguments: Array<String>) {
    val (folder, requests, output) = arguments.map(::File)
    val catalogues = mutableMapOf<String, Catalogue>()
    val locales = Json.parseToJsonElement(requests.readText()).jsonObject
    val answer = buildJsonObject {
        locales.forEach { (locale, value) ->
            val request = value.jsonObject
            val language = request.getValue("catalogue").jsonPrimitive.content
            val catalogue = catalogues.getOrPut(language) { catalogue(folder, language) }
            put(locale, books(catalogue, request))
        }
    }
    output.writeText(answer.toString())
}
