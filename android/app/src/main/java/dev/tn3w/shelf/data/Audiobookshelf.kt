package dev.tn3w.shelf.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

private const val PAGE_SIZE = 50
private val LIBRARY_ITEMS = Regex("""/api/libraries/([^/]+)/items\?.*page=(\d+)""")

fun audiobookshelfCredentials(username: String, password: String) = buildJsonObject {
    put("username", username)
    put("password", password)
}.toString().toRequestBody("application/json".toMediaType())

fun audiobookshelfToken(text: String): String {
    val user = json.parseToJsonElement(text).jsonObject.child("user")
    return user.text("token").ifEmpty { user.text("accessToken") }
}

private fun JsonObject.child(key: String) =
    this[key] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.number(key: String) =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0

class Audiobookshelf(url: String) {
    private val base = url.trim().trimEnd('/')
    val libraries = "$base/api/libraries"
    val login = "$base/login"
    val me = "$base/api/me"

    private fun items(library: String, page: Int) =
        "$libraries/$library/items?limit=$PAGE_SIZE&page=$page&minified=1" +
            "&sort=media.metadata.title"

    fun feed(text: String, url: String): Feed {
        val root = json.parseToJsonElement(text).jsonObject
        return when {
            "libraries" in root -> Feed("", libraries(root))
            "results" in root -> page(root, url)
            "book" in root -> Feed("", found(root))
            else -> Feed("", listOf(item(root)))
        }
    }

    private fun libraries(root: JsonObject) = root["libraries"].objects()
        .filter { it.text("mediaType") == "book" }
        .map { Entry(it.text("name"), link = items(it.text("id"), 0)) }

    private fun found(root: JsonObject) = root["book"].objects()
        .map { item(it.child("libraryItem")) }

    private fun page(root: JsonObject, url: String): Feed {
        val (library, page) = LIBRARY_ITEMS.find(url)?.destructured
            ?: return Feed("", root["results"].objects().map(::item))
        val shown = (page.toInt() + 1) * root.number("limit")
        return Feed(
            title = "",
            entries = root["results"].objects().map(::item),
            next = items(library, page.toInt() + 1).takeIf {
                shown < root.number("total")
            },
            search = "$libraries/$library/search?q={searchTerms}&limit=$PAGE_SIZE",
        )
    }

    private fun item(item: JsonObject): Entry {
        val id = item.text("id")
        val media = item.child("media")
        val metadata = media.child("metadata")
        val title = metadata.text("title")
        val author = metadata.text("authorName")
            .ifEmpty { names(metadata["authors"]).joinToString(", ") }
        return Entry(
            title = title,
            author = author,
            summary = Jsoup.parse(metadata.text("description")).text(),
            cover = "$base/api/items/$id/cover".takeIf {
                media.text("coverPath").isNotEmpty()
            },
            details = "$base/api/items/$id?expanded=1",
            files = ebook(media, id),
            tracks = tracks(media, id, title, author),
        )
    }

    private fun ebook(media: JsonObject, id: String): List<Acquisition> {
        val ebook = media["ebookFile"] as? JsonObject ?: return emptyList()
        val file = ebook.child("metadata")
        val format = opdsFormat("", file.text("filename")) ?: return emptyList()
        val bytes = file.number("size").toLong()
        return listOf(Acquisition("$base/api/items/$id/ebook", format, bytes = bytes))
    }

    private fun tracks(
        media: JsonObject,
        id: String,
        title: String,
        author: String,
    ): List<AudioFile> {
        val files = media["audioFiles"].objects()
            .filter { it.text("exclude") != "true" }
            .sortedBy { it.number("index") }
        val starts = files.runningFold(0.0) { start, file ->
            start +
                file.number("duration")
        }
        val chapters = media["chapters"].objects()
        return files.mapIndexed { index, file ->
            val seconds = file.number("duration")
            val name = file.child("metadata").text("filename")
            val marks = chapters
                .map { it.text("title") to it.number("start") - starts[index] }
                .filter { (_, start) -> start >= 0 && start < seconds }
                .map { (chapter, start) -> Mark(chapter, (start * 1000).toLong()) }
            AudioFile(
                "$base/api/items/$id/file/${file.text("ino")}",
                name,
                name.substringBeforeLast('.'),
                title,
                author,
                track = index + 1,
                millis = (seconds * 1000).toLong(),
                chapters = marks,
            )
        }
    }
}
