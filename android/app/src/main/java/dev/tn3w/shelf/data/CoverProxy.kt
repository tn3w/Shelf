package dev.tn3w.shelf.data

import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** AVIF cover proxy, see `tooling/server/` in the repository. */
const val COVER_PROXY = "https://shelf-books.tn3w.dev"
private val BOOK_WIDTHS = intArrayOf(160, 320, 640)
private val AUTHOR_WIDTHS = intArrayOf(96, 192)

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
private class CoverHits(val docs: List<CoverHit> = emptyList())

@Serializable
private class CoverHit(@SerialName("cover_i") val cover: Int? = null)

private val coverJson = Json { ignoreUnknownKeys = true }

fun coverSearchUrl(title: String, author: String) =
    "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
        .addQueryParameter("title", title.trim())
        .apply { if (author.isNotBlank()) addQueryParameter("author", author.trim()) }
        .addQueryParameter("fields", "cover_i")
        .addQueryParameter("limit", "40")
        .build()
        .toString()

fun parseCoverHits(text: String) = coverJson.decodeFromString<CoverHits>(text).docs
    .mapNotNull { it.cover?.takeIf { cover -> cover > 0 } }
    .distinct()
    .map { "$COVERS/b/id/$it-L.jpg" }

suspend fun searchCovers(title: String, author: String) = runSuspendCatching {
    parseCoverHits(fetchText(coverSearchUrl(title, author), "application/json"))
}.getOrDefault(emptyList())
