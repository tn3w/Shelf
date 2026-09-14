package dev.tn3w.shelf.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

private const val MAGIC = "SHLF"
private const val VERSION = 1
private const val NAME_BYTES = 16
private const val SEPARATOR = '\u001f'
private const val YEAR_EPOCH = 1400
private const val MAX_TOKEN_BYTES = 24
private val COMPLETION_LENGTHS = 2..4

private val FOLD = buildMap {
    listOf(
            "áàâäãåÁÀÂÄÃÅ" to 'a',
            "éèêëÉÈÊË" to 'e',
            "íìîïÍÌÎÏ" to 'i',
            "óòôöõøÓÒÔÖÕØ" to 'o',
            "úùûüÚÙÛÜ" to 'u',
            "ñÑ" to 'n',
            "çÇ" to 'c',
            "ýÿÝ" to 'y',
            "ß" to 's',
        )
        .forEach { (characters, replacement) ->
            characters.forEach { put(it, replacement) }
        }
}

private fun foldCharacter(character: Char): Char? {
    val lowered = if (character.code < 128) character.lowercaseChar() else character
    if (lowered.code < 128 && lowered.isLetterOrDigit()) return lowered
    return FOLD[character]
}

fun tokenize(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    for (character in text) {
        if (character == '\'' || character == '’' || character in '\u0300'..'\u036f')
            continue
        val folded = foldCharacter(character)
        if (folded != null) current.append(folded)
        if (
            (folded == null || current.length >= MAX_TOKEN_BYTES) && current.isNotEmpty()
        ) {
            tokens += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

class Reader(private val bytes: ByteArray, var offset: Int = 0) {
    val hasMore
        get() = offset < bytes.size

    fun byte() = bytes[offset++].toInt() and 0xFF

    fun varint(): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = byte()
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    fun text(): String {
        val length = varint()
        offset += length
        return String(bytes, offset - length, length, Charsets.UTF_8)
    }

    fun skip(length: Int) {
        offset += length
    }

    fun take(length: Int) =
        bytes.copyOfRange(offset, offset + length).also { offset += length }

    fun rest() = take(bytes.size - offset)
}

fun decodePostings(bytes: ByteArray): IntArray {
    val reader = Reader(bytes)
    val values = IntArrayList()
    var current = 0
    while (reader.hasMore) {
        current += reader.varint()
        values.add(current)
    }
    return values.toArray()
}

class IntArrayList {
    private var values = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun toArray(): IntArray = values.copyOf(size)
}

class Cache<K : Any, V : Any>(private val capacity: Int) {
    private val map =
        object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) =
                size > capacity
        }

    fun get(key: K, load: (K) -> V): V {
        synchronized(map) {
            map[key]?.let {
                return it
            }
        }
        val value = load(key)
        synchronized(map) { map[key] = value }
        return value
    }
}

fun mapFile(file: File): ByteBuffer =
    RandomAccessFile(file, "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }

private fun ByteBuffer.region(offset: Int, length: Int): ByteBuffer =
    duplicate()
        .apply {
            position(offset)
            limit(offset + length)
        }
        .slice()
        .order(ByteOrder.LITTLE_ENDIAN)

private fun ByteBuffer.bytes(offset: Int, length: Int) =
    ByteArray(length).also { region(offset, length).get(it) }

private fun ByteBuffer.all() = bytes(0, limit())

private class Table(private val buffer: ByteBuffer) {
    val count = buffer.getInt(0)
    private val data = 4 + 4 * (count + 1)

    operator fun get(index: Int): ByteArray {
        val start = buffer.getInt(4 + 4 * index)
        val end = buffer.getInt(8 + 4 * index)
        return buffer.bytes(data + start, end - start)
    }
}

private fun inflate(
    compressed: ByteArray,
    dictionary: ByteArray = ByteArray(0),
): ByteArray {
    val inflater = Inflater(true)
    if (dictionary.isNotEmpty()) inflater.setDictionary(dictionary)
    inflater.setInput(compressed)
    val output = ByteArrayOutputStream(compressed.size * 4)
    val chunk = ByteArray(16384)
    while (!inflater.finished()) {
        val read = inflater.inflate(chunk)
        if (read == 0 && inflater.needsInput()) break
        output.write(chunk, 0, read)
    }
    inflater.end()
    return output.toByteArray()
}

private fun lengthPrefixed(raw: ByteArray): List<ByteArray> {
    val reader = Reader(raw)
    return buildList { while (reader.hasMore) add(reader.take(reader.varint())) }
}

private fun <T : Comparable<T>> List<T>.lastAtMost(key: T) =
    binarySearch(key).let { if (it >= 0) it else -it - 2 }

private fun <T : Comparable<T>> List<T>.firstAtLeast(key: T) =
    binarySearch(key).let { if (it >= 0) it else -it - 1 }

private fun readSections(buffer: ByteBuffer): Map<String, ByteBuffer> {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    check(String(buffer.bytes(0, 4)) == MAGIC) { "not a shelf segment" }
    val version = buffer.getInt(4)
    check(version == VERSION) { "unsupported segment version $version" }
    return (0 until buffer.getInt(8)).associate { index ->
        val entry = 12 + index * (NAME_BYTES + 8)
        val name = String(buffer.bytes(entry, NAME_BYTES)).trimEnd('\u0000')
        name to
            buffer.region(buffer.getInt(entry + NAME_BYTES), buffer.getInt(entry + 20))
    }
}

private fun readMeta(buffer: ByteBuffer) =
    String(buffer.all())
        .lines()
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

data class Facts(
    val authors: IntArray,
    val year: Int,
    val cover: Int,
    val tags: IntArray,
    val series: Int,
    val order: Int,
)

data class Heads(val title: String, val subtitle: String, val alternate: String)

data class AuthorRecord(
    val number: Int,
    val born: Int,
    val name: String,
    val works: IntArray,
)

data class TagRecord(
    val id: Int,
    val slug: String,
    val label: String,
    val category: String,
)

class SeriesRecord(val name: String, val members: IntArray)

class Term(
    val text: String,
    val titleCount: Int,
    val authorCount: Int,
    private val titleBytes: ByteArray,
    private val authorBytes: ByteArray,
) {
    val titleWorks
        get() = decodePostings(titleBytes)

    val authorSlots
        get() = decodePostings(authorBytes)

    val frequency
        get() = titleCount + authorCount
}

private class TermBlocks(buffer: ByteBuffer, private val withPostings: Boolean) {
    private val table = Table(buffer)
    val count = table.count
    private val firstTerms = List(count) { Reader(table[it], 1).text() }
    private val cache = Cache<Int, List<Term>>(512)

    fun block(index: Int) =
        cache.get(index) {
            val reader = Reader(table[index])
            var previous = ""
            buildList {
                while (reader.hasMore) {
                    val text = previous.take(reader.varint()) + reader.text()
                    add(readTerm(reader, text))
                    previous = text
                }
            }
        }

    private fun readTerm(reader: Reader, text: String): Term {
        val titleCount = reader.varint()
        val authorCount = reader.varint()
        if (!withPostings) return Term(text, titleCount, authorCount, EMPTY, EMPTY)
        val titleLength = reader.varint()
        val authorLength = reader.varint()
        val titles = reader.take(titleLength)
        return Term(text, titleCount, authorCount, titles, reader.take(authorLength))
    }

    fun find(text: String): Term? {
        val index = firstTerms.lastAtMost(text)
        if (index < 0) return null
        return block(index).firstOrNull { it.text == text }
    }

    fun scan(prefix: String): Sequence<Term> {
        val start = maxOf(0, firstTerms.firstAtLeast(prefix) - 1)
        return (start until count)
            .asSequence()
            .takeWhile { firstTerms[it] <= prefix || firstTerms[it].startsWith(prefix) }
            .flatMap { block(it) }
            .filter { it.text.startsWith(prefix) && it.text != prefix }
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

class Segment(val name: String, buffer: ByteBuffer) {
    private val sections = readSections(buffer)
    private val meta = readMeta(section("meta"))
    val language = meta.getValue("language")
    val pack = meta.getValue("pack")
    val month = meta.getValue("month")
    val base = meta["base"] ?: month
    val isBase
        get() = base == month

    private val recordsPerBlock = meta.getValue("records_per_block").toInt()
    private val termsPerBlock = meta.getValue("terms_per_block").toInt()
    private val worksBuffer = section("works").asIntBuffer()
    val workCount = worksBuffer.limit()
    val tombstones =
        section("tombstones").asIntBuffer().let { IntArray(it.limit(), it::get) }
    private val headDictionary = section("head_dictionary").all()
    private val textDictionary = section("text_dictionary").all()
    private val facts = Table(section("facts"))
    private val heads = Table(section("heads"))
    private val authors = Table(section("authors"))
    private val series = Table(section("series"))
    private val terms = TermBlocks(section("terms"), withPostings = true)
    private val completions = Table(section("completions"))
    private val completionKeys =
        List(completions.count) { Reader(completions[it]).text() }
    private val grams = Table(section("grams"))
    private val gramKeys = List(grams.count) { Reader(grams[it]).text() }
    private val descriptionFirsts: List<Int>
    private val descriptions: Table
    private val tagTable = Table(section("tags"))
    val tags =
        List(tagTable.count) {
            val reader = Reader(tagTable[it])
            TagRecord(it, reader.text(), reader.text(), reader.text())
        }
    private val blocks = Cache<Pair<Table, Int>, List<ByteArray>>(256)
    private val descriptionBlocks = Cache<Int, Map<Int, String>>(64)

    init {
        val section = section("descriptions")
        val count = section.getInt(0)
        descriptionFirsts = List(count) { section.getInt(4 + 4 * it) }
        val start = 4 + 4 * count
        descriptions = Table(section.region(start, section.limit() - start))
    }

    private fun section(name: String) = sections.getValue(name)

    fun work(local: Int) = worksBuffer.get(local)

    fun localOf(work: Int): Int {
        var low = 0
        var high = workCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val value = worksBuffer.get(middle)
            when {
                value < work -> low = middle + 1
                value > work -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun record(table: Table, index: Int, dictionary: ByteArray = ByteArray(0)) =
        blocks
            .get(table to index / recordsPerBlock) { (source, block) ->
                lengthPrefixed(inflate(source[block], dictionary))
            }[index % recordsPerBlock]

    fun facts(local: Int): Facts {
        val reader = Reader(record(facts, local))
        val authorSlots = IntArray(reader.varint()) { reader.varint() }
        val year = reader.varint().let { if (it == 0) 0 else it + YEAR_EPOCH }
        val cover = reader.varint()
        val tagIds = IntArray(reader.varint()) { reader.byte() }
        return Facts(
            authorSlots,
            year,
            cover,
            tagIds,
            reader.varint() - 1,
            reader.varint(),
        )
    }

    fun heads(local: Int): Heads {
        val fields =
            String(record(heads, local, headDictionary), Charsets.UTF_8).split(SEPARATOR)
        return Heads(fields[0], fields.getOrElse(1) { "" }, fields.getOrElse(2) { "" })
    }

    fun author(slot: Int): AuthorRecord {
        val reader = Reader(record(authors, slot))
        val number = reader.varint()
        val born = reader.varint()
        return AuthorRecord(number, born, reader.text(), decodePostings(reader.rest()))
    }

    fun series(slot: Int): SeriesRecord {
        val reader = Reader(series[slot])
        val name = reader.text()
        return SeriesRecord(name, IntArray(reader.varint()) { reader.varint() })
    }

    fun tagWorks(tag: Int): IntArray {
        if (tag >= tagTable.count) return IntArray(0)
        val reader = Reader(tagTable[tag])
        repeat(3) { reader.skip(reader.varint()) }
        reader.varint()
        return decodePostings(reader.rest())
    }

    fun tagCount(tag: Int): Int {
        if (tag >= tagTable.count) return 0
        val reader = Reader(tagTable[tag])
        repeat(3) { reader.skip(reader.varint()) }
        return reader.varint()
    }

    fun description(local: Int): String {
        val block = descriptionFirsts.lastAtMost(local)
        if (block < 0) return ""
        return descriptionBlocks
            .get(block) {
                val reader = Reader(inflate(descriptions[it], textDictionary))
                val first = descriptionFirsts[it]
                buildMap {
                    while (reader.hasMore) put(first + reader.varint(), reader.text())
                }
            }[local]
            .orEmpty()
    }

    fun term(text: String) = terms.find(text)

    fun termById(id: Int) = terms.block(id / termsPerBlock)[id % termsPerBlock]

    fun completions(prefix: String, limit: Int): List<Term> {
        if (prefix.length !in COMPLETION_LENGTHS) return scan(prefix, limit)
        val position = completionKeys.binarySearch(prefix)
        if (position < 0) return scan(prefix, limit)
        val reader = Reader(completions[position])
        reader.text()
        return List(minOf(reader.varint(), limit)) { termById(reader.varint()) }
    }

    private fun scan(prefix: String, limit: Int) =
        terms.scan(prefix).sortedByDescending { it.frequency }.take(limit).toList()

    fun gramTerms(gram: String): IntArray {
        val position = gramKeys.binarySearch(gram)
        if (position < 0) return IntArray(0)
        val reader = Reader(grams[position])
        reader.text()
        return decodePostings(reader.rest())
    }
}

data class Popularity(
    val score: Double,
    val readers: Int,
    val ratings: Int,
    val rating: Double,
    val editions: Int,
)

private class PopularityBlock(
    val works: IntArray,
    val rows: Array<Popularity>,
)

class Ranks(val name: String, buffer: ByteBuffer) {
    private val sections = readSections(buffer)
    private val meta = readMeta(sections.getValue("meta"))
    val language = meta.getValue("language")
    val month = meta.getValue("month")
    val works = meta.getValue("works").toInt()
    private val terms = TermBlocks(sections.getValue("terms"), withPostings = false)
    private val firstWorks: List<Int>
    private val blocks: Table
    private val cache = Cache<Int, PopularityBlock>(64)

    init {
        val section = sections.getValue("popularity")
        val count = section.getInt(0)
        firstWorks = List(count) { section.getInt(4 + 4 * it) }
        val start = 4 + 4 * count
        blocks = Table(section.region(start, section.limit() - start))
    }

    fun frequency(text: String) = terms.find(text)?.frequency ?: 0

    fun popularity(work: Int): Popularity? {
        val index = firstWorks.lastAtMost(work)
        if (index < 0) return null
        val block = cache.get(index, ::decode)
        val position = block.works.binarySearch(work)
        return if (position < 0) null else block.rows[position]
    }

    private fun decode(index: Int): PopularityBlock {
        val reader = Reader(inflate(blocks[index]))
        val works = IntArrayList()
        val rows = mutableListOf<Popularity>()
        var work = firstWorks[index]
        while (reader.hasMore) {
            work += reader.varint()
            works.add(work)
            val score = reader.varint() / 10.0
            val readers = reader.varint()
            val ratings = reader.varint()
            rows +=
                Popularity(score, readers, ratings, reader.byte() / 20.0, reader.byte())
        }
        return PopularityBlock(works.toArray(), rows.toTypedArray())
    }
}
