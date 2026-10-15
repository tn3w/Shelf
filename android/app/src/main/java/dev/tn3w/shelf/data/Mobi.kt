package dev.tn3w.shelf.data

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset

private const val NO_INDEX = -1
private const val NO_COMPRESSION = 1
private const val PALM_DOC = 2
private const val HUFF_CDIC = 17480
private const val UTF8_CODE_PAGE = 65001
private const val EXTH_AUTHOR = 100
private const val EXTH_KF8_HEADER = 121
private const val EXTH_TITLE = 503
private val EMBED = Regex("kindle:embed:([0-9A-Va-v]{4})")
private val FILE_START = Regex("(?=<html[\\s>])", RegexOption.IGNORE_CASE)
private val PAGE_BREAK = Regex("<mbp:pagebreak", RegexOption.IGNORE_CASE)
private val TOC_REFERENCE =
    Regex("<reference[^>]*type=\"?toc[^>]*>", RegexOption.IGNORE_CASE)
private val FILE_POSITION = Regex("filepos=\"?0*(\\d+)", RegexOption.IGNORE_CASE)
private val TOC_LINK = Regex(
    "<a[^>]*filepos=\"?0*(\\d+)[^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private class PalmDatabase(file: File) : AutoCloseable {
    private val access = RandomAccessFile(file, "r")
    private val offsets: IntArray
    val name: String

    init {
        val header = ByteArray(78).also(access::readFully)
        name = String(header, 0, 32, Charsets.ISO_8859_1).substringBefore('\u0000')
        val count = ByteBuffer.wrap(header).getShort(76).toInt() and 0xFFFF
        val table = ByteBuffer.wrap(ByteArray(count * 8).also(access::readFully))
        offsets = IntArray(count + 1) {
            if (it < count) table.getInt(it * 8) else access.length().toInt()
        }
    }

    val size
        get() = offsets.size - 1

    fun record(index: Int): ByteArray = synchronized(access) {
        require(index in 0 until size) { "no record $index" }
        val length = offsets[index + 1] - offsets[index]
        require(length in 0..MAX_ENTRY_BYTES) { "bad record $index" }
        access.seek(offsets[index].toLong())
        ByteArray(length).also(access::readFully)
    }

    override fun close() = access.close()
}

private class MobiHeader(record: ByteArray) {
    private val buffer = ByteBuffer.wrap(record)
    val compression = short(0)
    val textRecords = short(8)
    val encrypted = short(12) != 0
    val isMobi = record.size >= 24 && String(record, 16, 4, Charsets.ISO_8859_1) == "MOBI"
    private val length = if (isMobi) buffer.getInt(20) else 0
    val charset: Charset =
        if (field(28) ==
            UTF8_CODE_PAGE
        ) {
            Charsets.UTF_8
        } else {
            Charset.forName("windows-1252")
        }
    val version = field(36)
    val firstImage = field(108)
    val huffman = field(112)
    val huffmanCount = field(116)
    val fdst = if (version >= 8) field(192) else NO_INDEX
    val extraFlags = if (length >= 0xE4) short(242) else 0
    val exth = if (isMobi && field(128) and 0x40 != 0) exth(16 + length) else emptyMap()
    val fullName = runCatching {
        String(record, field(84), field(88), charset)
    }.getOrDefault("")

    private fun short(offset: Int) = buffer.getShort(offset).toInt() and 0xFFFF

    private fun field(offset: Int) =
        if (isMobi && offset + 4 <= 16 + length) buffer.getInt(offset) else NO_INDEX

    private fun exth(start: Int): Map<Int, List<ByteArray>> = runCatching {
        check(String(buffer.array(), start, 4, Charsets.ISO_8859_1) == "EXTH")
        var offset = start + 12
        List(buffer.getInt(start + 8)) {
            val type = buffer.getInt(offset)
            val size = buffer.getInt(offset + 4)
            (type to buffer.array().copyOfRange(offset + 8, offset + size))
                .also { offset += size }
        }.groupBy({ it.first }, { it.second })
    }.getOrDefault(emptyMap())

    fun text(type: Int) = exth[type]?.joinToString(", ") { String(it, charset) }
}

private class MobiImages(private val database: PalmDatabase, private val first: Int) :
    Resources {
    override fun bytes(path: String) =
        if (first < 0) null else database.record(first + path.toInt())

    override fun close() = database.close()
}

internal fun resourceIndex(source: String): String? {
    val number = EMBED.find(source)?.groupValues?.get(1)?.toInt(32)
        ?: source.toIntOrNull()
        ?: return null
    return "${number - 1}"
}

fun mobiMetadata(file: File) = PalmDatabase(file).use { database ->
    val header = MobiHeader(database.record(0))
    val title = header.text(EXTH_TITLE) ?: header.fullName.ifBlank { database.name }
    Metadata(title, header.text(EXTH_AUTHOR).orEmpty())
}

fun readMobi(file: File): TextDocument {
    val database = PalmDatabase(file)
    return runCatching { readMobi(database) }.onFailure { database.close() }.getOrThrow()
}

private fun readMobi(database: PalmDatabase): TextDocument {
    val first = MobiHeader(database.record(0))
    check(!first.encrypted) { "DRM protected" }
    if (!first.isMobi) return database.use { palmText(decodeText(text(it, 0, first))) }
    val kf8Start = first.exth[EXTH_KF8_HEADER]?.firstOrNull()
        ?.let { ByteBuffer.wrap(it).getInt(0) }
        ?.takeIf { it in 1 until database.size }
    val kf8 = kf8Start?.let {
        MobiHeader(database.record(it))
    }?.takeIf { it.version >= 8 }
    val start = if (kf8 == null) 0 else kf8Start
    val header = kf8 ?: first
    val text = text(database, start, header)
    val images = MobiImages(database, first.firstImage)
    if (header.version < 8) return assemble(mobiParts(text, header.charset), images)
    val parts = flow(database, start, header, text).split(FILE_START)
        .filter { it.isNotBlank() }
        .flatMap { headingParts(mobiBlocks(it)) }
    return assemble(parts, images)
}

private fun palmText(text: String) =
    if (text.trimStart().startsWith("<html", ignoreCase = true)) {
        byHeadings(htmlBlocks(Jsoup.parse(text).body()) { null })
    } else {
        textDocument(text)
    }

private fun mobiBlocks(html: String): List<Block> {
    val body = Jsoup.parse(html).body()
    body.select("img[recindex]").forEach { it.attr("src", it.attr("recindex")) }
    return htmlBlocks(body, ::resourceIndex)
}

private fun mobiParts(
    text: ByteArray,
    charset: Charset,
): List<Pair<String?, List<Block>>> {
    val raw = String(text, Charsets.ISO_8859_1)
    val marks = tableOfContents(raw)
        .ifEmpty { PAGE_BREAK.scan(raw).map { it.start() to null }.toList() }
    val starts = (marks + (0 to null)).filter { it.first in raw.indices }
        .sortedBy { it.first }
        .distinctBy { it.first }
    return starts.mapIndexed { index, (start, title) ->
        val end = starts.getOrNull(index + 1)?.first ?: text.size
        val html = String(text, start, end - start, charset)
        val (fallback, blocks) = titled(mobiBlocks(html))
        (title?.let { decodeTitle(it, charset) } ?: fallback) to blocks
    }
}

private fun decodeTitle(raw: String, charset: Charset) =
    Jsoup.parse(String(raw.toByteArray(Charsets.ISO_8859_1), charset)).text().ifBlank {
        null
    }

private fun tableOfContents(raw: String): List<Pair<Int, String?>> {
    val reference = TOC_REFERENCE.find(raw) ?: return emptyList()
    val start = FILE_POSITION.find(reference.value)?.groupValues?.get(1)?.toIntOrNull()
    if (start == null || start !in raw.indices) return emptyList()
    val end = PAGE_BREAK.find(raw, start + 1)?.range?.first ?: raw.length
    val links = TOC_LINK.findAll(raw.substring(start, end)).mapNotNull { link ->
        link.groupValues[1].toIntOrNull()?.let { it to link.groupValues[2] }
    }
    return links.toList() + (start to null)
}

private fun flow(
    database: PalmDatabase,
    start: Int,
    header: MobiHeader,
    text: ByteArray,
) = runCatching {
    val table = ByteBuffer.wrap(database.record(start + header.fdst))
    check(String(table.array(), 0, 4, Charsets.ISO_8859_1) == "FDST")
    String(text, table.getInt(12), table.getInt(16) - table.getInt(12), header.charset)
}.getOrElse { String(text, header.charset) }

private fun text(database: PalmDatabase, start: Int, header: MobiHeader): ByteArray {
    val decode: (ByteArray) -> ByteArray = when (header.compression) {
        NO_COMPRESSION -> { bytes -> bytes }

        PALM_DOC -> ::decompressPalmDoc

        HUFF_CDIC -> {
            val first = start + header.huffman
            val records = (first until first + header.huffmanCount).map(database::record)
            HuffDecoder(records.first(), records.drop(1))::unpack
        }

        else -> error("unknown compression ${header.compression}")
    }
    val output = ByteArrayOutputStream()
    for (index in start + 1..start + header.textRecords) {
        val record = database.record(index)
        output.write(decode(record.copyOf(record.size - trailingSize(record, header))))
    }
    return output.toByteArray()
}

private fun trailingSize(record: ByteArray, header: MobiHeader): Int {
    var size = 0
    var flags = header.extraFlags ushr 1
    while (flags != 0) {
        if (flags and 1 != 0) size += trailingEntrySize(record, record.size - size)
        flags = flags ushr 1
    }
    if (header.extraFlags and 1 !=
        0
    ) {
        size += (record[record.size - size - 1].toInt() and 3) + 1
    }
    return size
}

private fun trailingEntrySize(record: ByteArray, end: Int) =
    record.copyOfRange(maxOf(0, end - 4), end).fold(0) { size, byte ->
        val value = byte.toInt() and 0xFF
        (if (value and 0x80 != 0) 0 else size) shl 7 or (value and 0x7F)
    }

internal fun decompressPalmDoc(input: ByteArray): ByteArray {
    val output = ByteArray(input.size * 5)
    var size = 0
    var index = 0
    while (index < input.size) {
        val byte = input[index++].toInt() and 0xFF
        when (byte) {
            in 1..8 -> repeat(byte) { output[size++] = input[index++] }

            in 0x80..0xBF -> {
                val pair = (byte shl 8 or (input[index++].toInt() and 0xFF)) and 0x3FFF
                val distance = pair shr 3
                repeat((pair and 7) + 3) {
                    output[size] = output[size - distance]
                    size++
                }
            }

            in 0xC0..0xFF -> {
                output[size++] = ' '.code.toByte()
                output[size++] = (byte xor 0x80).toByte()
            }

            else -> output[size++] = byte.toByte()
        }
    }
    return output.copyOf(size)
}

private class HuffDecoder(huff: ByteArray, dictionaries: List<ByteArray>) {
    private val lengths = IntArray(256)
    private val terminal = BooleanArray(256)
    private val tableMaximum = LongArray(256)
    private val minimum = LongArray(33)
    private val maximum = LongArray(33)
    private val phrases = mutableListOf<ByteArray>()
    private val resolved = mutableListOf<Boolean>()

    init {
        val buffer = ByteBuffer.wrap(huff)
        check(String(huff, 0, 4, Charsets.ISO_8859_1) == "HUFF") { "bad HUFF record" }
        val table = buffer.getInt(8)
        val ranges = buffer.getInt(12)
        for (code in 0 until 256) {
            val value = buffer.getInt(table + code * 4).toLong() and 0xFFFFFFFF
            lengths[code] = (value and 0x1F).toInt()
            terminal[code] = value and 0x80 != 0L
            tableMaximum[code] = ((value ushr 8) + 1 shl 32 - lengths[code]) - 1
        }
        maximum[0] = 0xFFFFFFFF
        for (length in 1..32) {
            val offset = ranges + (length - 1) * 8
            val low = buffer.getInt(offset).toLong() and 0xFFFFFFFF
            val high = buffer.getInt(offset + 4).toLong() and 0xFFFFFFFF
            minimum[length] = low shl 32 - length
            maximum[length] = (high + 1 shl 32 - length) - 1
        }
        dictionaries.forEach(::addPhrases)
    }

    private fun addPhrases(record: ByteArray) {
        val buffer = ByteBuffer.wrap(record)
        check(String(record, 0, 4, Charsets.ISO_8859_1) == "CDIC") { "bad CDIC record" }
        val count = minOf(1 shl buffer.getInt(12), buffer.getInt(8) - phrases.size)
        repeat(count) {
            val offset = 16 + (buffer.getShort(16 + it * 2).toInt() and 0xFFFF)
            val length = buffer.getShort(offset).toInt() and 0xFFFF
            phrases += record.copyOfRange(offset + 2, offset + 2 + (length and 0x7FFF))
            resolved += length and 0x8000 != 0
        }
    }

    fun unpack(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.wrap(data + ByteArray(8))
        var bitsLeft = data.size * 8L
        var position = 0
        var bits = buffer.getLong(0)
        var shift = 32
        while (true) {
            if (shift <= 0) {
                position += 4
                bits = buffer.getLong(position)
                shift += 32
            }
            val code = bits ushr shift and 0xFFFFFFFF
            val top = (code ushr 24).toInt()
            var length = lengths[top]
            var limit = tableMaximum[top]
            if (!terminal[top]) {
                while (code < minimum[length]) length++
                limit = maximum[length]
            }
            shift -= length
            bitsLeft -= length
            if (bitsLeft < 0) return output.toByteArray()
            output.write(phrase(((limit - code) ushr 32 - length).toInt()))
        }
    }

    private fun phrase(index: Int): ByteArray {
        if (!resolved[index]) {
            resolved[index] = true
            phrases[index] = unpack(phrases[index])
        }
        return phrases[index]
    }
}
