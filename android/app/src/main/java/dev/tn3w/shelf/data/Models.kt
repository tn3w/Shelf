package dev.tn3w.shelf.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

const val VOICES_RELEASE = "voices-2026-11"
const val MODELS_URL =
    "https://github.com/$REPOSITORY/releases/download/$VOICES_RELEASE/voices.json"
private const val MODELS_FORMAT = 1
private const val FREE_SPACE_MARGIN = 100L * 1024 * 1024
private const val PART = ".part"
val OPEN_LICENCES = setOf("Apache-2.0", "MIT", "BSD-3-Clause", "CC-BY-4.0", "CC0-1.0")
private val SUPPORTED_KINDS = setOf("speaker", "tts")
private val QUALITIES = listOf("standard", "high")
private val SHA256 = Regex("[0-9a-f]{64}")
private val NAME = Regex("[\\w-][\\w.-]*")

@Serializable
data class ModelFile(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class ModelVoice(
    val id: String,
    val file: String,
    val gender: Gender,
    val years: Int? = null,
    val licence: String,
    val attribution: String = "",
)

@Serializable
data class Model(
    val id: String,
    val kind: String,
    val version: String,
    val licence: String,
    val language: String = "",
    val quality: String = "",
    val attribution: String = "",
    val source: String = "",
    val ram: Long = 0,
    val files: List<ModelFile>,
    val voices: List<ModelVoice> = emptyList(),
) {
    val size get() = files.sumOf { it.size }

    val folder get() = "$id@$version"
}

@Serializable
data class ModelManifest(val format: Int, val models: List<Model>)

class NoSpaceException : IOException("not enough storage space")

class Ranged(val stream: InputStream, val offset: Long)

private fun isValid(file: ModelFile) = NAME.matches(file.name) &&
    file.url.startsWith("https://") && file.size > 0 && SHA256.matches(file.sha256)

private fun isValid(voice: ModelVoice, files: List<ModelFile>) =
    voice.licence in OPEN_LICENCES && files.any { it.name == voice.file }

internal fun isValid(model: Model) = model.licence in OPEN_LICENCES &&
    NAME.matches(model.id) && NAME.matches(model.version) &&
    model.files.isNotEmpty() && model.files.all(::isValid) &&
    model.voices.all { isValid(it, model.files) }

fun voicePool(model: Model) = model.voices.map { voice ->
    Voice(voice.id, voice.gender, voice.years?.let(::ageOf) ?: Age.Adult)
}

fun fits(model: Model, memory: Long) = model.ram <= memory / 2

fun isSupported(model: Model) =
    model.kind in SUPPORTED_KINDS && (model.kind != "tts" || model.quality in QUALITIES)

fun voicesByLanguage(models: List<Model>) = models
    .filter { it.kind == "tts" && it.language.isNotEmpty() }
    .sortedBy { QUALITIES.indexOf(it.quality) }
    .groupBy { it.language }

fun preferredModel(choices: List<Model>, installed: Set<String>) =
    choices.lastOrNull { stateOf(it, installed) != PackState.Available }
        ?: choices.first()

fun parseModels(text: String) = runCatching { json.decodeFromString<ModelManifest>(text) }
    .getOrNull()
    ?.takeIf { it.format == MODELS_FORMAT }
    ?.models
    ?.filter(::isValid)
    .orEmpty()

private fun MessageDigest.add(file: File) =
    DigestInputStream(file.inputStream(), this).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (input.read(buffer) >= 0) continue
    }

internal suspend fun resume(
    file: ModelFile,
    partial: File,
    open: suspend (Long) -> Ranged,
    onBytes: (Long) -> Unit,
) {
    if (partial.length() > file.size) partial.delete()
    val digest = MessageDigest.getInstance("SHA-256")
    if (partial.length() < file.size) {
        append(file, partial, open(partial.length()), digest, onBytes)
    } else {
        digest.add(partial)
    }
    check(partial.length() == file.size) { "${file.name} is incomplete" }
    if (digest.digest().toHexString() == file.sha256) return
    partial.delete()
    error("checksum mismatch for ${file.name}")
}

private fun append(
    file: ModelFile,
    partial: File,
    ranged: Ranged,
    digest: MessageDigest,
    onBytes: (Long) -> Unit,
) = ranged.stream.use { input ->
    val resumed = ranged.offset > 0
    if (resumed) digest.add(partial)
    DigestOutputStream(FileOutputStream(partial, resumed), digest).use { output ->
        copyWithProgress(input, output) { bytes ->
            val total = ranged.offset + bytes
            check(total <= file.size) { "${file.name} is larger than listed" }
            onBytes(total)
        }
    }
}

private suspend fun openRanged(url: String, offset: Long): Ranged {
    val response = downloadClient.fetch(url) {
        if (offset > 0) header("Range", "bytes=$offset-")
    }
    return Ranged(response.stream, if (response.code == 206) offset else 0)
}

class Models(context: Context) {
    private val directory = context.filesDir.resolve("models")
    private val manifestFile = directory.resolve("voices.json")
    private val _available = MutableStateFlow(storedModels())
    val available = _available.asStateFlow()
    private val _installed = MutableStateFlow(installedFolders())
    val installed = _installed.asStateFlow()

    private fun storedModels() =
        runCatching { parseModels(manifestFile.readText()) }.getOrDefault(emptyList())

    private fun installedFolders() = directory.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.endsWith(PART) }
        .map { it.name }
        .toSet()

    private fun changed() {
        _installed.value = installedFolders()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val text = fetchText(MODELS_URL, accept = "application/json")
        val models = parseModels(text)
        if (models.isEmpty()) return@withContext
        directory.mkdirs()
        replaceFile(manifestFile) { it.writeText(text) }
        _available.value = models
    }

    fun folderOf(model: Model) = directory.resolve(model.folder)

    private fun partOf(model: Model) = directory.resolve(model.folder + PART)

    suspend fun download(model: Model, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = partOf(model).apply { mkdirs() }
            val pending = part.listFiles().orEmpty().sumOf { it.length() }
            if (part.usableSpace < model.size - pending + FREE_SPACE_MARGIN) {
                throw NoSpaceException()
            }
            var done = 0L
            for (file in model.files) {
                val open: suspend (Long) -> Ranged = { openRanged(file.url, it) }
                resume(file, part.resolve(file.name), open) { bytes ->
                    ensureActive()
                    onProgress((done + bytes).toFloat() / model.size)
                }
                done += file.size
            }
            delete(model.id, keep = part)
            check(part.renameTo(folderOf(model))) { "cannot install ${model.folder}" }
            changed()
        }

    fun paused(model: Model): Download.Paused? {
        val bytes = partOf(model).listFiles().orEmpty().sumOf { it.length() }
        return if (bytes > 0) Download.Paused(bytes.toFloat() / model.size) else null
    }

    fun installed(kind: String, language: String = ""): Model? = available.value
        .filter { it.kind == kind && isSupported(it) && it.folder in installed.value }
        .filter { language.isEmpty() || it.language == language }
        .maxByOrNull { QUALITIES.indexOf(it.quality) }

    fun fileOf(model: Model) = folderOf(model).resolve(model.files.first().name)

    fun remove(model: Model) {
        delete(model.id)
        changed()
    }

    private fun delete(id: String, keep: File? = null) = directory
        .listFiles { file -> file.name.startsWith("$id@") && file != keep }
        .orEmpty()
        .forEach { it.deleteRecursively() }
}

fun modelKey(model: Model) = "model:${model.id}"

fun stateOf(model: Model, installed: Set<String>) = when {
    model.folder in installed -> PackState.Installed
    installed.any { it.startsWith("${model.id}@") } -> PackState.Update
    else -> PackState.Available
}
