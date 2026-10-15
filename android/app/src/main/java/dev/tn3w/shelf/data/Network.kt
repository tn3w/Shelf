package dev.tn3w.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

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

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa")
private val IP_LITERAL = Regex("""[\d.]+|[\da-f]*:[\da-f:.]*""")

fun isLocalHost(host: String): Boolean {
    val name = host.lowercase().trim('[', ']')
    if (name == "localhost" || LOCAL_SUFFIXES.any(name::endsWith)) return true
    if (!IP_LITERAL.matches(name)) return false
    val address = runCatching { InetAddress.getByName(name) }.getOrNull() ?: return false
    val uniqueLocal =
        address is Inet6Address && address.address[0].toInt() and 0xFE == 0xFC
    return address.isLoopbackAddress || address.isSiteLocalAddress ||
        address.isLinkLocalAddress || uniqueLocal
}

fun isPermitted(url: URL, local: Boolean = false) = url.protocol == "https" ||
    (local && url.protocol == "http" && isLocalHost(url.host))

fun isPermittedFrom(origin: String, target: URL) = if (isLocalHost(origin)) {
    isPermitted(target, local = true)
} else {
    target.protocol == "https" && !isLocalHost(target.host)
}

fun connect(url: String, local: Boolean = false): HttpURLConnection {
    val target = URI(url).toURL()
    require(isPermitted(target, local)) { "refusing plain http request to $url" }
    return (target.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        setRequestProperty("User-Agent", USER_AGENT)
    }
}

fun fetchText(url: String, accept: String = "application/vnd.github+json") =
    connect(url).apply { setRequestProperty("Accept", accept) }
        .inputStream.use { it.readAtMost(MAX_TEXT_BYTES).decodeToString() }

fun InputStream.readAtMost(limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    copyWithProgress(this, output) { size ->
        if (size > limit) throw IOException("more than $limit bytes")
    }
    return output.toByteArray()
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

fun sha256(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }
