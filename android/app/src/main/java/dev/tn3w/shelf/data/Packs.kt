package dev.tn3w.shelf.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
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
    val left = labelParts(first)
    val right = labelParts(second)
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

fun isValidCoverSource(source: String) = source.isEmpty() || source.startsWith("https://")

private const val MANIFEST_NAME = "manifest.json"

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

enum class PackState {
    Installed,
    Update,
    Available,
}

data class PackInfo(val pack: String, val state: PackState, val bytes: Long)

sealed interface Download {
    data class Running(val progress: Float) : Download

    data object Failed : Download
}

fun MutableStateFlow<Map<String, Download>>.claim(key: String): Boolean {
    val before = getAndUpdate {
        if (it[key] is Download.Running) it else it + (key to Download.Running(0f))
    }
    return before[key] !is Download.Running
}

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

private fun mapFile(file: File): ByteBuffer = RandomAccessFile(file, "r").use {
    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
}

private fun currentChain(chain: List<Segment>): List<Segment> {
    val base = chain.filter { it.isBase }.map { it.month }.maxWithOrNull(releaseOrder)
        ?: return emptyList()
    return chain.filter { it.base == base && releaseOrder.compare(it.month, base) >= 0 }
}

internal fun currentSegments(segments: List<Segment>) = segments
    .groupBy { it.pack }
    .flatMap { (_, chain) -> currentChain(chain) }
    .sortedWith(
        compareBy<Segment, String>(releaseOrder) { it.month }
            .thenBy { !it.isBase }
            .thenBy { PACKS.indexOf(it.pack) },
    )

internal fun newest(vararg manifests: Manifest?) = manifests
    .filterNotNull()
    .maxWithOrNull(compareBy(releaseOrder) { it.month })

class Packs(private val context: Context) {
    private val directory = context.filesDir.resolve("catalogue").apply {
        mkdirs()
        listFiles { file -> file.extension == "part" }?.forEach { it.delete() }
    }
    private val manifestFile = directory.resolve(MANIFEST_NAME)
    private val fetching = Mutex()
    private val bundled = context.assets.list("").orEmpty().mapNotNull(::parseName)

    init {
        removeBundledCopies()
    }

    var source = ""

    val manifest: Manifest?
        get() = newest(stored(), bundledManifest().takeIf { source.isEmpty() })

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

    fun copyBundled() = bundled
        .filterNot { binOf(it.id).exists() }
        .forEach { file ->
            replaceFile(binOf(file.id)) { temporary ->
                context.assets.open("${file.id}.bin").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
            }
        }

    fun removeBundledCopies() = deleteAll(bundled)

    fun storageBytes() = downloaded().sumOf { binOf(it.id).length() }

    fun load(language: String): Catalogue {
        val files = (downloaded() + bundled).filter { it.language == language }
        val segments = currentSegments(
            files.filter { it.pack != "ranks" }.distinctBy { it.id }.mapNotNull(::open),
        )
        val ranks = files
            .filter { it.pack == "ranks" }
            .maxWithOrNull(compareBy(releaseOrder) { it.month })
            ?.let { runCatching { Ranks(map(it)) }.getOrNull() }
        return Catalogue(language, segments, ranks)
    }

    private fun open(file: LocalFile): Segment? {
        val segment = runCatching { Segment(map(file)) }.getOrNull()
        if (segment == null) binOf(file.id).delete()
        return segment
    }

    private fun map(file: LocalFile): ByteBuffer {
        val local = binOf(file.id)
        if (local.exists()) return mapFile(local)
        return context.assets.openFd("${file.id}.bin").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use {
                it.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.length,
                )
            }
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
            it.tag.startsWith("catalogue-")
        }
        return release.assetUrl(MANIFEST_NAME)
    }

    fun importFile(name: String, input: InputStream): Boolean {
        if (name == MANIFEST_NAME) return importManifest(input.readBytes())
        val file = parseName(name) ?: return false
        return runCatching {
            replaceFile(binOf(file.id)) { temporary ->
                temporary.outputStream().use { input.copyTo(it) }
                val buffer = mapFile(temporary)
                if (file.pack == "ranks") Ranks(buffer) else Segment(buffer)
            }
        }
            .isSuccess
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

    private fun fetch(entry: ManifestEntry, onBytes: (Long) -> Unit) =
        replaceFile(binOf(entry.id)) { temporary ->
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(connect(entry.url).inputStream, digest).use { input ->
                temporary.outputStream().use { copyWithProgress(input, it, onBytes) }
            }
            check(sha256(digest) == entry.sha256) { "checksum mismatch for ${entry.id}" }
        }

    private fun writeAtomically(target: File, bytes: ByteArray) =
        replaceFile(target) { it.writeBytes(bytes) }
}

fun replaceFile(target: File, write: (File) -> Unit) {
    val temporary = File(target.path + ".part")
    try {
        write(temporary)
        check(temporary.renameTo(target)) { "cannot replace ${target.name}" }
    } finally {
        temporary.delete()
    }
}
