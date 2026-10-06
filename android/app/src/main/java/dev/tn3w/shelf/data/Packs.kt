package dev.tn3w.shelf.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

val LANGUAGES = listOf("en", "de", "fr", "es")
val PACKS = listOf(
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
private fun labelParts(label: String) = label.split("-").map { it.toIntOrNull() ?: 0 }

val releaseOrder = Comparator<String> { first, second ->
    val (left, right) = labelParts(first) to labelParts(second)
    left.zip(right).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 }
        ?: left.size.compareTo(right.size)
}

const val REPOSITORY = "tn3w/Shelf"
private val GITHUB_REPOSITORY = Regex("""[\w.-]+/[\w.-]+""")

fun releasesUrl(source: String): String {
    val repository = source.ifEmpty { REPOSITORY }
    if (!GITHUB_REPOSITORY.matches(repository)) return repository
    return "https://api.github.com/repos/$repository/releases?per_page=30"
}

val RELEASES = releasesUrl(REPOSITORY)

fun isValidSource(source: String) =
    source.isEmpty() || GITHUB_REPOSITORY.matches(source) || source.startsWith("https://")

private const val MANIFEST_NAME = "manifest.json"
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

@Serializable data class Asset(val name: String, val browser_download_url: String)

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

private val segmentName =
    Regex("""([a-z]{2})-([a-z-]+)-(\d{4}-\d{2}(?:-\d{2}(?:-\d+)?)?)\.bin""")

private fun parseName(name: String): LocalFile? {
    val (_, pack, release) = segmentName.matchEntire(name)?.destructured ?: return null
    return LocalFile(name.removeSuffix(".bin"), pack, release)
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

const val USER_AGENT = "Shelf"

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

class Packs(private val context: Context) {
    private val directory = context.filesDir.resolve("catalogue").apply { mkdirs() }
    private val manifestFile = directory.resolve(MANIFEST_NAME)
    private val fetching = Mutex()
    private val bundled = context.assets.list("").orEmpty().mapNotNull(::parseName)

    var source = ""

    val manifest: Manifest?
        get() = stored() ?: bundledManifest().takeIf { source.isEmpty() }

    fun forgetManifest() = manifestFile.delete()

    private fun stored(): Manifest? {
        if (!manifestFile.exists()) return null
        val manifest = parseManifest(manifestFile.readText())
        if (manifest != null) return manifest
        manifestFile.delete()
        return null
    }

    private fun bundledManifest() = runCatching {
        context.assets.open(MANIFEST_NAME).use { it.readBytes().decodeToString() }
    }
        .getOrNull()
        ?.let(::parseManifest)

    private fun parseManifest(text: String) =
        runCatching { json.decodeFromString<Manifest>(text) }
            .getOrNull()
            ?.takeIf { it.format == CATALOGUE_FORMAT }

    private fun downloaded() =
        directory.listFiles().orEmpty().mapNotNull { parseName(it.name) }

    private fun binOf(id: String) = directory.resolve("$id.bin")

    private fun deleteAll(files: List<LocalFile>) =
        files.forEach { binOf(it.id).delete() }

    private fun localIds() = (bundled + downloaded()).map { it.id }.toSet()

    private fun installed(language: String) =
        (bundled + downloaded()).filter { it.language == language }

    fun storageBytes() = downloaded().sumOf { binOf(it.id).length() }

    fun load(language: String): Catalogue {
        val files = (downloaded() + bundled).filter { it.language == language }
        val segments = files
            .filter { it.pack != "ranks" }
            .distinctBy { it.id }
            .mapNotNull(::open)
            .groupBy { it.pack }
            .flatMap { (_, chain) -> currentChain(chain) }
            .sortedWith(
                compareBy<Segment, String>(releaseOrder) { it.month }
                    .thenBy { !it.isBase }
                    .thenBy { PACKS.indexOf(it.pack) },
            )
        val ranks = files
            .filter { it.pack == "ranks" }
            .maxWithOrNull(compareBy(releaseOrder) { it.month })
            ?.let { runCatching { Ranks(map(it)) }.getOrNull() }
        return Catalogue(language, segments, ranks)
    }

    private fun currentChain(chain: List<Segment>): List<Segment> {
        val base = chain.filter { it.isBase }.map { it.month }.maxWithOrNull(releaseOrder)
            ?: return emptyList()
        return chain.filter {
            it.base == base && releaseOrder.compare(it.month, base) >= 0
        }
    }

    private fun open(file: LocalFile): Segment? {
        val segment = runCatching { Segment(map(file)) }.getOrNull()
        if (segment == null) binOf(file.id).delete()
        return segment
    }

    private fun map(file: LocalFile): ByteBuffer {
        val local = binOf(file.id)
        if (local.exists()) return mapFile(local)
        val descriptor = context.assets.openFd("${file.id}.bin")
        return FileInputStream(descriptor.fileDescriptor).channel.use {
            it.map(
                FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length,
            )
        }
    }

    fun packs(language: String): List<PackInfo> {
        val ids = localIds()
        val files = installed(language)
        val entries = manifest?.segments.orEmpty().filter { it.language == language }
        val offered = entries.map { it.pack }.toSet()
        val missingRanks =
            entries.filter { it.pack == "ranks" && it.id !in ids }.sumOf { it.size }
        val listed = if (manifest == null) PACKS else PACKS.filter { it in offered }
        return listed.map { pack ->
            val needed = entries.filter { it.pack == pack }
            val present = files.filter { it.pack == pack }
            val missing = needed.filter { it.id !in ids }.sumOf { it.size }
            val extra = if (pack == "core") missingRanks else 0
            when {
                present.isEmpty() ->
                    PackInfo(pack, PackState.Available, needed.sumOf { it.size } + extra)

                missing > 0 -> PackInfo(pack, PackState.Update, missing)

                else -> PackInfo(pack, PackState.Installed, present.sumOf(::sizeOf))
            }
        }
    }

    private fun sizeOf(file: LocalFile): Long {
        val local = binOf(file.id)
        if (local.exists()) return local.length()
        return context.assets.openFd("${file.id}.bin").use { it.length }
    }

    fun months() = LANGUAGES.associateWith { language ->
        installed(language).map { it.month }.maxWithOrNull(releaseOrder)
    }

    suspend fun refreshManifest(): Manifest = withContext(Dispatchers.IO) {
        val text = fetchText(manifestUrl())
        val manifest = json.decodeFromString<Manifest>(text)
        check(manifest.format == CATALOGUE_FORMAT) {
            "catalogue format ${manifest.format} is no longer supported"
        }
        writeAtomically(manifestFile, text.toByteArray())
        removeObsolete(manifest)
        manifest
    }

    private fun manifestUrl(): String {
        val url = releasesUrl(source)
        if (url.substringBefore('?').endsWith(MANIFEST_NAME)) return url
        val release = json.decodeFromString<List<Release>>(fetchText(url)).first {
            it.tag_name.startsWith("catalogue-")
        }
        return release.assets.first { it.name == MANIFEST_NAME }.browser_download_url
    }

    fun importFile(name: String, input: InputStream): Boolean {
        if (name == MANIFEST_NAME) return importManifest(input.readBytes())
        val file = parseName(name) ?: return false
        val temporary = directory.resolve("${file.id}.part")
        temporary.outputStream().use { input.copyTo(it) }
        val valid = runCatching {
            val buffer = mapFile(temporary)
            if (file.pack == "ranks") Ranks(buffer) else Segment(buffer)
        }
            .isSuccess
        if (valid && temporary.renameTo(binOf(file.id))) return true
        temporary.delete()
        return false
    }

    private fun importManifest(bytes: ByteArray): Boolean {
        parseManifest(bytes.decodeToString()) ?: return false
        writeAtomically(manifestFile, bytes)
        return true
    }

    private fun removeObsolete(manifest: Manifest) {
        val offered = manifest.segments.map { it.language to it.pack }.toSet()
        deleteAll(downloaded().filter { it.language to it.pack !in offered })
    }

    fun isComplete(language: String): Boolean {
        val packs = installed(language)
        return packs.any { it.pack == "core" } && packs.any { it.pack == "ranks" }
    }

    fun pendingUpdates(language: String) =
        packs(language).filter { it.state == PackState.Update }

    suspend fun download(language: String, pack: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val manifest = manifest ?: refreshManifest()
            val ids = localIds()
            val entries = manifest.segments.filter {
                it.language == language && (it.pack == pack || it.pack == "ranks")
            }
            val missing = entries.filter { it.id !in ids }
            val total = missing.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            for (entry in missing) {
                fetching.withLock {
                    if (entry.id !in localIds()) {
                        fetch(entry) { onProgress((done + it).toFloat() / total) }
                    }
                }
                done += entry.size
            }
            prune(language, entries)
            onProgress(1f)
        }

    private fun prune(language: String, entries: List<ManifestEntry>) {
        val keep = entries.map { it.id }.toSet()
        val packs = entries.map { it.pack }.toSet()
        deleteAll(
            downloaded().filter {
                it.language == language && it.pack in packs && it.id !in keep
            },
        )
    }

    fun remove(language: String, pack: String) =
        deleteAll(downloaded().filter { it.language == language && it.pack == pack })

    fun removeAll() = deleteAll(downloaded())

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
        check(temporary.renameTo(binOf(entry.id)))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.path + ".part")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }
}
