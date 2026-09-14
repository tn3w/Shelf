package dev.tn3w.shelf.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val LANGUAGES = listOf("en", "de", "fr", "es")
val PACKS =
    listOf(
        "core",
        "fantasy",
        "scifi",
        "mystery",
        "romance",
        "kids",
        "young-adult",
        "nonfiction",
        "general",
    )
private const val RELEASES =
    "https://api.github.com/repos/tn3w/Shelf/releases?per_page=30"
private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class ManifestEntry(
    val id: String,
    val language: String,
    val pack: String,
    val month: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

@Serializable
data class Manifest(val format: Int, val month: String, val segments: List<ManifestEntry>)

@Serializable private data class Asset(val name: String, val browser_download_url: String)

@Serializable private data class Release(val tag_name: String, val assets: List<Asset>)

enum class PackState {
    Installed,
    Update,
    Available,
}

data class PackInfo(val pack: String, val state: PackState, val bytes: Long)

private data class LocalFile(val id: String, val pack: String, val month: String) {
    val language
        get() = id.take(2)
}

private fun parseName(name: String): LocalFile? {
    if (!name.endsWith(".bin") || name.length < 15) return null
    val id = name.removeSuffix(".bin")
    return LocalFile(id, id.drop(3).dropLast(8), id.takeLast(7))
}

fun copy(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
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

fun connect(url: String): HttpURLConnection =
    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }

class Packs(private val context: Context) {
    private val directory = context.filesDir.resolve("catalogue").apply { mkdirs() }
    private val manifestFile = directory.resolve("manifest.json")
    private val bundled = context.assets.list("").orEmpty().mapNotNull(::parseName)

    val manifest: Manifest?
        get() =
            manifestFile
                .takeIf { it.exists() }
                ?.let {
                    runCatching { json.decodeFromString<Manifest>(it.readText()) }
                        .getOrNull()
                }

    private fun downloaded() =
        directory.listFiles().orEmpty().mapNotNull { parseName(it.name) }

    private fun localIds() = (bundled + downloaded()).map { it.id }.toSet()

    fun storageBytes() = directory.listFiles().orEmpty().sumOf { it.length() }

    fun load(language: String): Catalogue {
        val files = (downloaded() + bundled).filter { it.language == language }
        val segments =
            files
                .filter { it.pack != "ranks" }
                .distinctBy { it.id }
                .map(::open)
                .groupBy { it.pack }
                .flatMap { (_, chain) -> currentChain(chain) }
                .sortedWith(
                    compareBy({ it.month }, { !it.isBase }, { PACKS.indexOf(it.pack) })
                )
        val ranks =
            files
                .filter { it.pack == "ranks" }
                .maxByOrNull { it.month }
                ?.let {
                    Ranks(it.id, map(it))
                }
        return Catalogue(language, segments, ranks)
    }

    private fun currentChain(chain: List<Segment>): List<Segment> {
        val base =
            chain.filter { it.isBase }.maxOfOrNull { it.month } ?: return emptyList()
        return chain.filter { it.month >= base && it.base == base }
    }

    private fun open(file: LocalFile) = Segment(file.id, map(file))

    private fun map(file: LocalFile): ByteBuffer {
        val local = directory.resolve("${file.id}.bin")
        if (local.exists()) return mapFile(local)
        val descriptor = context.assets.openFd("${file.id}.bin")
        return FileInputStream(descriptor.fileDescriptor).channel.use {
            it.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.length,
            )
        }
    }

    fun packs(language: String): List<PackInfo> {
        val ids = localIds()
        val installed = (bundled + downloaded()).filter { it.language == language }
        val entries = manifest?.segments.orEmpty().filter { it.language == language }
        return PACKS.map { pack ->
            val needed = entries.filter { it.pack == pack }
            val present = installed.filter { it.pack == pack }
            val missing = needed.filter { it.id !in ids }.sumOf { it.size }
            when {
                present.isEmpty() ->
                    PackInfo(pack, PackState.Available, needed.sumOf { it.size })
                missing > 0 -> PackInfo(pack, PackState.Update, missing)
                else -> PackInfo(pack, PackState.Installed, present.sumOf(::sizeOf))
            }
        }
    }

    private fun sizeOf(file: LocalFile): Long {
        val local = directory.resolve("${file.id}.bin")
        if (local.exists()) return local.length()
        return context.assets.openFd("${file.id}.bin").use { it.length }
    }

    fun months() = LANGUAGES.associateWith { language ->
        (bundled + downloaded())
            .filter { it.language == language }
            .maxOfOrNull { it.month }
    }

    suspend fun refreshManifest(): Manifest =
        withContext(Dispatchers.IO) {
            val releases =
                connect(RELEASES).inputStream.use { it.readBytes().decodeToString() }
            val release =
                json.decodeFromString<List<Release>>(releases).first {
                    it.tag_name.startsWith("db-")
                }
            val url =
                release.assets.first { it.name == "manifest.json" }.browser_download_url
            val text = connect(url).inputStream.use { it.readBytes().decodeToString() }
            val manifest = json.decodeFromString<Manifest>(text)
            writeAtomically(manifestFile, text.toByteArray())
            manifest
        }

    fun pendingUpdates(language: String) =
        packs(language).filter { it.state == PackState.Update }

    suspend fun download(
        language: String,
        pack: String,
        onProgress: (Float) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val manifest = manifest ?: refreshManifest()
            val ids = localIds()
            val entries =
                manifest.segments.filter {
                    it.language == language && (it.pack == pack || it.pack == "ranks")
                }
            val missing = entries.filter { it.id !in ids }
            val total = missing.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            for (entry in missing) {
                fetch(entry) { onProgress((done + it).toFloat() / total) }
                done += entry.size
            }
            prune(language, entries)
            onProgress(1f)
        }

    private fun prune(language: String, entries: List<ManifestEntry>) {
        val keep = entries.map { it.id }.toSet()
        val packs = entries.map { it.pack }.toSet()
        downloaded()
            .filter { it.language == language && it.pack in packs && it.id !in keep }
            .forEach { directory.resolve("${it.id}.bin").delete() }
    }

    fun remove(language: String, pack: String) {
        downloaded()
            .filter { it.language == language && it.pack == pack }
            .forEach { directory.resolve("${it.id}.bin").delete() }
    }

    private fun fetch(entry: ManifestEntry, onBytes: (Long) -> Unit) {
        val temporary = directory.resolve("${entry.id}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(connect(entry.url).inputStream, digest).use { input ->
            temporary.outputStream().use { copy(input, it, onBytes) }
        }
        if (sha256(digest) != entry.sha256) {
            temporary.delete()
            error("checksum mismatch for ${entry.id}")
        }
        check(temporary.renameTo(directory.resolve("${entry.id}.bin")))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.path + ".part")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }
}
