package dev.tn3w.shelf.data

import androidx.collection.LruCache
import androidx.collection.MutableIntList
import dev.tn3w.shelf.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

private const val MAGIC = "SHLF"
const val CATALOGUE_FORMAT = BuildConfig.CATALOGUE_FORMAT
private const val NAME_BYTES = 16
private const val SEPARATOR = '\u001f'
private const val YEAR_EPOCH = 1400
private val COMPLETION_LENGTHS = 2..4

private fun ByteBuffer.region(offset: Int, length: Int): ByteBuffer = duplicate()
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

    fun leadingText(index: Int, skip: Int = 0): String {
        var position = data + buffer.getInt(4 + 4 * index) + skip
        var length = 0
        var shift = 0
        do {
            val byte = buffer.get(position++).toInt() and 0xFF
            length = length or ((byte and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        return String(buffer.bytes(position, length), Charsets.UTF_8)
    }
}

private class BlockIndex(buffer: ByteBuffer) {
    private val count = buffer.getInt(0)
    private val start = 4 + 4 * count
    val firsts = List(count) { buffer.getInt(4 + 4 * it) }
    val blocks = Table(buffer.region(start, buffer.limit() - start))
}

private fun inflate(
    compressed: ByteArray,
    dictionary: ByteArray = ByteArray(0),
): ByteArray {
    val inflater = Inflater(true)
    if (dictionary.isNotEmpty()) inflater.setDictionary(dictionary)
    return try {
        InflaterInputStream(compressed.inputStream(), inflater).readBytes()
    } finally {
        inflater.end()
    }
}

private fun lengthPrefixed(raw: ByteArray): List<ByteArray> {
    val reader = ByteReader(raw)
    return buildList { while (reader.hasMore) add(reader.take(reader.varint())) }
}

private fun <T : Comparable<T>> List<T>.lastAtMost(key: T) =
    binarySearch(key).let { if (it >= 0) it else -it - 2 }

private fun <T : Comparable<T>> List<T>.firstAtLeast(key: T) =
    binarySearch(key).let { if (it >= 0) it else -it - 1 }

private fun readVersion(buffer: ByteBuffer) {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    check(String(buffer.bytes(0, 4)) == MAGIC) { "not a shelf segment" }
    val version = buffer.getInt(4)
    check(version == CATALOGUE_FORMAT) { "unsupported segment version $version" }
}

private fun readSections(buffer: ByteBuffer): Map<String, ByteBuffer> {
    readVersion(buffer)
    return (0 until buffer.getInt(8)).associate { index ->
        val entry = 12 + index * (NAME_BYTES + 8)
        val name = String(buffer.bytes(entry, NAME_BYTES)).trimEnd('\u0000')
        name to
            buffer.region(buffer.getInt(entry + NAME_BYTES), buffer.getInt(entry + 20))
    }
}

private fun readMeta(buffer: ByteBuffer) = String(buffer.all())
    .lines()
    .filter { '=' in it }
    .associate { it.substringBefore('=') to it.substringAfter('=') }

class Facts(
    val authors: IntArray,
    val year: Int,
    val cover: Int,
    val tags: IntArray,
    val series: Int,
)

data class Heads(val title: String, val subtitle: String, val alternate: String)

data class Description(val text: String, val translated: Boolean)

class AuthorRecord(val number: Int, val born: Int, val name: String, val works: IntArray)

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
    private val firstTerms = List(count) { table.leadingText(it, skip = 1) }
    private val cache = LruCache<Int, List<Term>>(512)

    fun block(index: Int) = cache.getOrPut(index) {
        val reader = ByteReader(table[index])
        var previous = ""
        buildList {
            while (reader.hasMore) {
                val text = previous.take(reader.varint()) + reader.text()
                add(readTerm(reader, text))
                previous = text
            }
        }
    }

    private fun readTerm(reader: ByteReader, text: String): Term {
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

class Segment(buffer: ByteBuffer) {
    private val sections = readSections(buffer)
    private val meta = readMeta(section("meta"))
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
        List(completions.count) { completions.leadingText(it) }
    private val grams = Table(section("grams"))
    private val gramKeys = List(grams.count) { grams.leadingText(it) }
    private val descriptions = BlockIndex(section("descriptions"))
    private val tagTable = Table(section("tags"))
    val tags = List(tagTable.count) {
        val reader = ByteReader(tagTable[it])
        Tag(it, reader.text(), reader.text(), reader.text())
    }
    private val isbns = sections["isbns"]?.let(::BlockIndex)
    private val libraryCovers = sections["library_covers"]?.let(::BlockIndex)
    private val blocks = LruCache<Pair<Table, Int>, List<ByteArray>>(256)
    private val descriptionBlocks = LruCache<Int, Map<Int, Description>>(64)
    private val isbnBlocks = LruCache<Int, Map<Int, Int>>(16)
    private val editionIsbns = LruCache<Int, List<Int>>(16)
    private val coverBlocks = LruCache<Int, Map<Int, LibraryCover>>(16)

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
            .getOrPut(table to index / recordsPerBlock) { (source, block) ->
                lengthPrefixed(inflate(source[block], dictionary))
            }[index % recordsPerBlock]

    fun facts(local: Int): Facts {
        val reader = ByteReader(record(facts, local))
        val authorSlots = IntArray(reader.varint()) { reader.varint() }
        val year = reader.varint().let { if (it == 0) 0 else it + YEAR_EPOCH }
        val cover = reader.varint()
        val tagIds = IntArray(reader.varint()) { reader.byte() }
        return Facts(authorSlots, year, cover, tagIds, reader.varint() - 1)
    }

    fun heads(local: Int): Heads {
        val fields =
            String(record(heads, local, headDictionary), Charsets.UTF_8).split(SEPARATOR)
        return Heads(fields[0], fields.getOrElse(1) { "" }, fields.getOrElse(2) { "" })
    }

    fun author(slot: Int): AuthorRecord {
        val reader = ByteReader(record(authors, slot))
        val number = reader.varint()
        val born = reader.varint()
        return AuthorRecord(number, born, reader.text(), decodePostings(reader.rest()))
    }

    fun series(slot: Int): Series {
        val reader = ByteReader(series[slot])
        val name = reader.text()
        return Series(name, List(reader.varint()) { reader.varint() })
    }

    private fun afterTagLabels(tag: Int): ByteReader? {
        if (tag >= tagTable.count) return null
        return ByteReader(tagTable[tag]).also { reader ->
            repeat(3) { reader.skip(reader.varint()) }
        }
    }

    fun tagWorks(tag: Int): IntArray {
        val reader = afterTagLabels(tag) ?: return IntArray(0)
        reader.varint()
        return decodePostings(reader.rest())
    }

    fun tagCount(tag: Int) = afterTagLabels(tag)?.varint() ?: 0

    fun description(local: Int): Description {
        val block = descriptions.firsts.lastAtMost(local)
        if (block < 0) return Description("", false)
        return descriptionBlocks
            .getOrPut(block) {
                val reader = ByteReader(inflate(descriptions.blocks[it], textDictionary))
                val first = descriptions.firsts[it]
                buildMap {
                    while (reader.hasMore) {
                        val marked = reader.varint()
                        val text = reader.text()
                        val offset = marked shr 1
                        val translated = marked and 1 == 1
                        put(first + offset, Description(text, translated))
                    }
                }
            }[local] ?: Description("", false)
    }

    fun localOfIsbn(key: Int): Int {
        val index = isbns ?: return -1
        val block = index.firsts.lastAtMost(key)
        if (block < 0) return -1
        return isbnBlocks.getOrPut(block) { isbnBlock(index, it) }[key] ?: -1
    }

    fun isbnsOf(local: Int): List<Int> {
        val index = isbns ?: return emptyList()
        return editionIsbns.getOrPut(local) {
            (0 until index.blocks.count).flatMap { block ->
                isbnBlock(index, block).filterValues { it == local }.keys
            }
        }
    }

    private fun isbnBlock(index: BlockIndex, block: Int): Map<Int, Int> {
        val reader = ByteReader(inflate(index.blocks[block]))
        var isbn = index.firsts[block]
        return buildMap {
            while (reader.hasMore) {
                isbn += reader.varint()
                put(isbn, reader.varint())
            }
        }
    }

    fun libraryCover(local: Int): LibraryCover? {
        val index = libraryCovers ?: return null
        val block = index.firsts.lastAtMost(local)
        if (block < 0) return null
        return coverBlocks.getOrPut(block) {
            val reader = ByteReader(inflate(index.blocks[it]))
            var covered = index.firsts[it]
            buildMap {
                while (reader.hasMore) {
                    covered += reader.varint()
                    val source = reader.byte()
                    put(covered, LibraryCover(source, isbnOf(reader.varint())))
                }
            }
        }[local]
    }

    fun term(text: String) = terms.find(text)

    fun termById(id: Int) = terms.block(id / termsPerBlock)[id % termsPerBlock]

    fun completions(prefix: String, limit: Int): List<Term> {
        if (prefix.length !in COMPLETION_LENGTHS) return scan(prefix, limit)
        val position = completionKeys.binarySearch(prefix)
        if (position < 0) return scan(prefix, limit)
        val reader = ByteReader(completions[position])
        reader.text()
        return List(minOf(reader.varint(), limit)) { termById(reader.varint()) }
    }

    private fun scan(prefix: String, limit: Int) =
        terms.scan(prefix).sortedByDescending { it.frequency }.take(limit).toList()

    fun gramTerms(gram: String): IntArray {
        val position = gramKeys.binarySearch(gram)
        if (position < 0) return IntArray(0)
        val reader = ByteReader(grams[position])
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

private class PopularityBlock(val works: IntArray, val rows: Array<Popularity>)

class Ranks(buffer: ByteBuffer) {
    private val sections = readSections(buffer)
    val works = readMeta(sections.getValue("meta")).getValue("works").toInt()
    private val terms = TermBlocks(sections.getValue("terms"), withPostings = false)
    private val popularityIndex = BlockIndex(sections.getValue("popularity"))
    private val aliases = sections["aliases"]?.asIntBuffer() ?: IntBuffer.allocate(0)
    private val aliasFrom = IntArray(aliases.limit() / 2) { aliases[2 * it] }
    private val aliasTo = IntArray(aliasFrom.size) { aliases[2 * it + 1] }
    private val cache = LruCache<Int, PopularityBlock>(64)

    fun frequency(text: String) = terms.find(text)?.frequency ?: 0

    fun alias(work: Int) =
        aliasFrom.binarySearch(work).takeIf { it >= 0 }?.let(aliasTo::get)

    fun popularity(work: Int): Popularity? {
        val index = popularityIndex.firsts.lastAtMost(work)
        if (index < 0) return null
        val block = cache.getOrPut(index, ::decode)
        val position = block.works.binarySearch(work)
        return if (position < 0) null else block.rows[position]
    }

    private fun decode(index: Int): PopularityBlock {
        val reader = ByteReader(inflate(popularityIndex.blocks[index]))
        val works = MutableIntList()
        val rows = mutableListOf<Popularity>()
        var work = popularityIndex.firsts[index]
        while (reader.hasMore) {
            work += reader.varint()
            works.add(work)
            val score = reader.varint() / 10.0
            val readers = reader.varint()
            val ratings = reader.varint()
            rows +=
                Popularity(score, readers, ratings, reader.byte() / 20.0, reader.byte())
        }
        return PopularityBlock(works.toIntArray(), rows.toTypedArray())
    }
}
