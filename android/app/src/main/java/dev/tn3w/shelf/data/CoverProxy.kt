package dev.tn3w.shelf.data

import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** AVIF cover proxy, see `tooling/server/` in the repository. */
const val COVER_PROXY = "https://shelf-books.tn3w.dev"
private val BOOK_WIDTHS = intArrayOf(160, 320, 640)
private val AUTHOR_WIDTHS = intArrayOf(96, 192)
private const val VARIANT_ISBNS = 24
private const val MAX_VARIANTS = 48

/** Android decodes AVIF from 12 on. */
val avifSupported
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun isValidProxy(url: String): Boolean {
    if (url.isEmpty()) return true
    val parsed = url.toHttpUrlOrNull() ?: return false
    return url.startsWith("https://") && parsed.query == null
}

/** Base address of the cover proxy, or null when covers load from their sources. */
fun Settings.coverProxyBase(): String? {
    if (!coverProxy || !avifSupported) return null
    return coverProxyUrl.ifEmpty { COVER_PROXY }.takeIf(::isValidProxy)?.trimEnd('/')
}

private fun width(widths: IntArray, pixels: Int) =
    widths.firstOrNull { it >= pixels } ?: widths.last()

/** Cover of [work] in the [language] catalogue, at least [pixels] wide, any release. */
fun proxyCoverUrl(base: String, language: String, work: Int, pixels: Int) =
    "$base/b/$work/$language/${width(BOOK_WIDTHS, pixels)}.avif"

fun proxyPhotoUrl(base: String, author: Author, pixels: Int) =
    "$base/a/${author.number}/${width(AUTHOR_WIDTHS, pixels)}.avif".takeIf {
        author.number in 1 until SYNTHETIC
    }

@Serializable
private class Editions(val entries: List<Edition> = emptyList())

@Serializable
private class Edition(val covers: List<Int> = emptyList())

private val editionJson = Json { ignoreUnknownKeys = true }

fun parseEditionCovers(text: String) =
    editionJson.decodeFromString<Editions>(text).entries
        .flatMap { it.covers }
        .filter { it > 0 }
        .distinct()

suspend fun editionCovers(work: Int): List<Int> {
    if (work !in 1 until SYNTHETIC) return emptyList()
    val url = "https://openlibrary.org/works/OL${work}W/editions.json?limit=200"
    return runSuspendCatching { parseEditionCovers(fetchText(url, "application/json")) }
        .getOrDefault(emptyList())
}

fun coverVariants(
    book: Book,
    isbns: List<String>,
    editionCovers: List<Int>,
): List<String> {
    val known = listOfNotNull(book.coverUrl("L"), book.libraryCover?.url)
    val editions = editionCovers.map { "$COVERS/b/id/$it-L.jpg" }
    val byIsbn = isbns.take(VARIANT_ISBNS).map {
        "$COVERS/b/isbn/$it-L.jpg?default=false"
    }
    return (known + editions + byIsbn).distinct().take(MAX_VARIANTS)
}
