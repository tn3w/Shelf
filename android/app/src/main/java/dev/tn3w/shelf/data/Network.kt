package dev.tn3w.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val MAX_TEXT_BYTES = 16L * 1024 * 1024
const val USER_AGENT = "Shelf (https://github.com/tn3w/Shelf)"
val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

const val UPDATE_DELAY_MILLIS = 24L * 60 * 60 * 1000

@Serializable
data class Asset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    @SerialName("updated_at") val updated: String = "",
    val id: Long = 0,
    val size: Long = 0,
)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
    @SerialName("published_at") val published: String = "",
) {
    val version
        get() = tag.removePrefix("v")

    val changedAt
        get() = (assets.map { it.updated } + published).maxOf(::epochMillis)

    val readyAt
        get() = changedAt + UPDATE_DELAY_MILLIS

    fun assetUrl(name: String) = assets.first { it.name == name }.url

    fun isUnchanged(current: Release?, files: Set<String>): Boolean {
        if (current == null || current.draft || current.prerelease) return false
        if (current.tag != tag || current.published != published) return false
        fun picked(release: Release) = release.assets.filter { it.name in files }.toSet()
        return picked(this).size == files.size && picked(current) == picked(this)
    }

    fun changelogUrl(language: String) = listOf(language, "en")
        .firstNotNullOfOrNull { code -> assets.find { it.name == "changelog-$code.txt" } }
        ?.url
}

private fun epochMillis(time: String) =
    runCatching { Instant.parse(time).toEpochMilli() }.getOrDefault(0)

class HttpException(val code: Int, url: String) : IOException("HTTP $code from $url")

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val request = chain.request().newBuilder().header("User-Agent", USER_AGENT)
        chain.proceed(request.build())
    }
    .build()

fun OkHttpClient.fetch(url: String, build: Request.Builder.() -> Unit = {}): Response {
    val response = newCall(Request.Builder().url(url).apply(build).build()).execute()
    if (response.isSuccessful) return response
    response.close()
    throw HttpException(response.code, url)
}

val Response.stream: InputStream
    get() = body.byteStream()

fun fetchText(url: String, accept: String = "application/vnd.github+json") =
    http.fetch(url) { header("Accept", accept) }
        .use { it.stream.readAtMost(MAX_TEXT_BYTES).decodeToString() }

fun InputStream.readAtMost(limit: Long) = source().buffer().run {
    if (request(limit + 1)) throw IOException("more than $limit bytes")
    readByteArray()
}

fun copyWithProgress(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        output.write(buffer, 0, read)
        copied += read
        onBytes(copied)
    }
}
