package dev.tn3w.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest

const val USER_AGENT = "Shelf (https://github.com/tn3w/Shelf)"
val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
) {
    val version
        get() = tag.removePrefix("v")

    fun assetUrl(name: String) = assets.first { it.name == name }.url

    fun changelogUrl(language: String) = listOf(language, "en")
        .firstNotNullOfOrNull { code -> assets.find { it.name == "changelog-$code.txt" } }
        ?.url
}

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
        .inputStream.use { it.readBytes().decodeToString() }

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
