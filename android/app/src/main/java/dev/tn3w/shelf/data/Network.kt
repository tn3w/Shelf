package dev.tn3w.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

const val USER_AGENT = "Shelf"
val json = Json { ignoreUnknownKeys = true }

@Serializable
data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
) {
    val version
        get() = tag.removePrefix("v")

    fun assetUrl(name: String) = assets.first { it.name == name }.url
}

fun connect(url: String): HttpURLConnection {
    val target = URI(url).toURL()
    require(target.protocol == "https") { "refusing plain http request" }
    return (target.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/vnd.github+json")
    }
}

fun fetchText(url: String) =
    connect(url).inputStream.use { it.readBytes().decodeToString() }

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

fun sha256(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }
