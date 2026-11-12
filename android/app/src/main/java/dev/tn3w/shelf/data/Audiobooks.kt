package dev.tn3w.shelf.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.SeekableByteChannel

@Serializable
data class Mark(val title: String, val millis: Long)

@Serializable
data class Track(
    val uri: String,
    val title: String,
    val millis: Long,
    val chapters: List<Mark> = emptyList(),
)

@Serializable
data class Audiobook(val tracks: List<Track>, val track: Int = 0, val millis: Long = 0) {
    val seconds get() = secondAt(tracks.size, 0)

    val second get() = secondAt(track, millis)

    val chapters
        get() = tracks.flatMapIndexed { index, track ->
            track.chapters.ifEmpty { listOf(Mark(track.title, 0)) }
                .map { secondAt(index, it.millis) to it.title }
        }

    fun chapterAt(second: Int) = chapters.indexOfLast { it.first <= second }

    fun secondAt(track: Int, millis: Long) =
        ((tracks.take(track).sumOf { it.millis } + millis) / 1000).toInt()

    fun at(second: Int): Audiobook {
        var left = second.coerceAtLeast(0) * 1000L
        for ((index, track) in tracks.withIndex()) {
            if (left < track.millis || index == tracks.lastIndex) {
                return copy(track = index, millis = left.coerceAtMost(track.millis))
            }
            left -= track.millis
        }
        return this
    }
}

data class AudioFile(
    val uri: String,
    val name: String,
    val title: String = "",
    val album: String = "",
    val author: String = "",
    val track: Int = 0,
    val disc: Int = 0,
    val millis: Long = 0,
    val chapters: List<Mark> = emptyList(),
)

val AUDIO_EXTENSIONS =
    setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav")

private val MP4_EXTENSIONS = setOf("m4b", "m4a", "mp4")
private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "tref")
private const val NERO_UNITS_PER_MILLI = 10_000

private val DIGITS = Regex("\\d+")
private val BRACKETED = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
private val NOT_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
private const val MIN_TITLE_LENGTH = 3

private fun naturalKey(name: String) =
    DIGITS.replace(name.lowercase()) { it.value.padStart(10, '0') }

fun audiobookOf(files: List<AudioFile>): Audiobook {
    val ordered = files.sortedWith(
        compareBy({ it.disc }, { it.track }, { naturalKey(it.name) }),
    )
    return Audiobook(
        ordered.map { file ->
            val title = file.title.ifBlank { file.name.substringBeforeLast('.') }
            Track(file.uri, title, file.millis, file.chapters)
        },
    )
}

private fun mostCommon(values: List<String>) = values
    .filter(String::isNotBlank)
    .groupingBy { it }
    .eachCount()
    .maxByOrNull { it.value }
    ?.key

fun titleOf(files: List<AudioFile>): String {
    val title = mostCommon(files.map { it.album })
        ?: files.singleOrNull()?.title?.takeIf(String::isNotBlank)
        ?: files.first().name.substringBeforeLast('.')
    return title.replace(BRACKETED, "").trim().ifEmpty { title }
}

fun authorOf(files: List<AudioFile>) = mostCommon(files.map { it.author }).orEmpty()

private fun normalized(text: String) =
    " ${text.lowercase().replace(NOT_ALPHANUMERIC, " ").trim()} "

fun matchingBook(books: List<Saved>, title: String, author: String): Saved? {
    val wanted = normalized(title)
    val writers = normalized(author).split(' ').filter(String::isNotEmpty).toSet()
    fun sharesAuthor(book: Saved) =
        normalized(book.author).split(' ').any { it in writers }
    return books
        .filter { book ->
            val own = normalized(book.title)
            own.trim().length >= MIN_TITLE_LENGTH && own in wanted
        }
        .maxWithOrNull(compareBy({ sharesAuthor(it) }, { it.title.length }))
}

private val WINDOWS_1252 = charset("windows-1252")

fun repairedText(text: String): String {
    val encoder = WINDOWS_1252.newEncoder()
    if (text.all { it.code < 0x80 } || !encoder.canEncode(text)) return text
    val bytes = encoder.encode(CharBuffer.wrap(text))
    return runCatching { Charsets.UTF_8.newDecoder().decode(bytes).toString() }
        .getOrDefault(text)
}

private fun number(tag: String) = DIGITS.find(tag)?.value?.toIntOrNull() ?: 0

fun Context.audioFile(uri: Uri): AudioFile? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(this, uri)
        fun tag(key: Int) = repairedText(retriever.extractMetadata(key).orEmpty().trim())
        val millis = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0
        if (millis <= 0) return null
        val name = displayName(uri)
        val author = listOf(
            MediaMetadataRetriever.METADATA_KEY_AUTHOR,
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
            MediaMetadataRetriever.METADATA_KEY_ARTIST,
        ).map(::tag).firstOrNull(String::isNotBlank).orEmpty()
        AudioFile(
            uri.toString(),
            name,
            tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
            tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            author,
            number(tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
            number(tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
            millis,
            if (isMp4(name)) chaptersOf(uri) else emptyList(),
        )
    } catch (error: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}

private fun isMp4(name: String) = File(name).extension.lowercase() in MP4_EXTENSIONS

private fun Context.chaptersOf(uri: Uri) = runCatching {
    contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).channel.use(::mp4Chapters)
    }
}.getOrDefault(emptyList())

private class Atom(
    val type: String,
    val body: Long,
    val end: Long,
    val children: List<Atom>,
) {
    fun find(vararg path: String): Atom? = path.fold(this as Atom?) { atom, type ->
        atom?.children?.firstOrNull { it.type == type }
    }
}

private fun SeekableByteChannel.bytes(at: Long, count: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(count)
    position(at)
    while (buffer.hasRemaining() && read(buffer) >= 0) continue
    return buffer.apply { flip() }
}

private fun SeekableByteChannel.body(atom: Atom) =
    bytes(atom.body, (atom.end - atom.body).toInt())

private fun SeekableByteChannel.atoms(start: Long, end: Long): List<Atom> {
    val atoms = mutableListOf<Atom>()
    var at = start
    while (at + 8 <= end) {
        val header = bytes(at, 16)
        val small = header.int.toLong() and 0xffffffffL
        val type = ByteArray(4).also(header::get).decodeToString()
        val (body, size) = when (small) {
            0L -> at + 8 to end - at
            1L -> at + 16 to header.long
            else -> at + 8 to small
        }
        if (size < 8 || at + size > end) break
        val children = if (type in CONTAINERS) atoms(body, at + size) else emptyList()
        atoms += Atom(type, body, at + size, children)
        at += size
    }
    return atoms
}

fun mp4Chapters(channel: SeekableByteChannel): List<Mark> = runCatching {
    val root = Atom("", 0, channel.size(), channel.atoms(0, channel.size()))
    val movie = root.find("moov") ?: return emptyList()
    neroChapters(channel, movie).ifEmpty { trackChapters(channel, movie) }
}.getOrDefault(emptyList())

private fun neroChapters(channel: SeekableByteChannel, movie: Atom): List<Mark> {
    val data = channel.body(movie.find("udta", "chpl") ?: return emptyList())
    data.position(if (data.get() > 0) 8 else 4)
    return List(data.get().toInt() and 0xff) {
        val start = data.long
        val title = ByteArray(data.get().toInt() and 0xff).also(data::get)
        Mark(title.decodeToString(), start / NERO_UNITS_PER_MILLI)
    }
}

private fun ByteBuffer.versioned(short: Int, long: Int) =
    getInt(if (get(0) > 0) long else short)

private fun ByteBuffer.table(width: Int): List<Long> {
    position(4)
    return List(int) { if (width == 8) long else int.toLong() and 0xffffffffL }
}

private fun trackChapters(channel: SeekableByteChannel, movie: Atom): List<Mark> {
    val tracks = movie.children.filter { it.type == "trak" }
    val wanted = tracks.mapNotNull { it.find("tref", "chap") }
        .map(channel::body)
        .flatMap { ids -> List(ids.limit() / 4) { ids.int } }
    val track = tracks.firstOrNull { track ->
        val header = track.find("tkhd") ?: return@firstOrNull false
        channel.body(header).versioned(12, 20) in wanted
    } ?: return emptyList()
    val media = track.find("mdia", "mdhd") ?: return emptyList()
    val scale = channel.body(media).versioned(12, 20)
    val samples = track.find("mdia", "minf", "stbl") ?: return emptyList()
    fun table(type: String) = samples.find(type)?.let(channel::body)
    val starts = startsOf(table("stts") ?: return emptyList())
    val offsets = offsetsOf(
        sizesOf(table("stsz") ?: return emptyList()),
        table("stco")?.table(4) ?: table("co64")?.table(8) ?: return emptyList(),
        table("stsc") ?: return emptyList(),
    )
    return offsets.zip(starts) { offset, start ->
        Mark(sampleText(channel, offset), start * 1000 / scale)
    }
}

private fun startsOf(data: ByteBuffer): List<Long> {
    data.position(4)
    var time = 0L
    return List(data.int) { data.int to data.int }.flatMap { (count, delta) ->
        List(count) { time.also { time += delta } }
    }
}

private fun sizesOf(data: ByteBuffer): List<Int> {
    data.position(4)
    val uniform = data.int
    return List(data.int) { if (uniform > 0) uniform else data.int }
}

private fun offsetsOf(
    sizes: List<Int>,
    chunks: List<Long>,
    runs: ByteBuffer,
): List<Long> {
    runs.position(4)
    val perChunk = List(runs.int) { (runs.int to runs.int).also { runs.int } }
    val offsets = mutableListOf<Long>()
    for ((index, chunk) in chunks.withIndex()) {
        val count = perChunk.lastOrNull { it.first <= index + 1 }?.second ?: 1
        var at = chunk
        repeat(minOf(count, sizes.size - offsets.size)) {
            offsets += at
            at += sizes[offsets.size - 1]
        }
    }
    return offsets
}

private fun sampleText(channel: SeekableByteChannel, offset: Long): String {
    val length = channel.bytes(offset, 2).short.toInt() and 0xffff
    val bytes = channel.bytes(offset + 2, length).array()
    val utf16 = length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
    return String(bytes, if (utf16) Charsets.UTF_16 else Charsets.UTF_8).trim()
}
