package dev.tn3w.shelf.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

val TAGS =
    listOf("fiction", "nonfiction", "fantasy", "magic", "dragons", "space", "crime")
private const val RECORDS_PER_BLOCK = 2
private const val TERMS_PER_BLOCK = 3
private const val COMPLETION_LIMIT = 12
private val DICTIONARY = "the of and".toByteArray()

data class Fixture(
    val work: Int,
    val title: String,
    val author: String = "Ann Author",
    val tags: List<String> = emptyList(),
    val series: String? = null,
    val subtitle: String = "",
    val year: Int = 0,
    val description: String = "",
    val score: Int = 0,
    val isbns: List<String> = emptyList(),
)

class Bytes {
    private val output = ByteArrayOutputStream()

    fun varint(value: Int): Bytes = apply {
        var rest = value
        while (rest >= 0x80) {
            output.write(rest and 0x7F or 0x80)
            rest = rest ushr 7
        }
        output.write(rest)
    }

    fun byte(value: Int) = apply { output.write(value) }

    fun raw(bytes: ByteArray) = apply { output.write(bytes) }

    fun text(value: String) = value.toByteArray().let { varint(it.size).raw(it) }

    fun postings(values: List<Int>) = apply {
        values.fold(0) { previous, value ->
            varint(value - previous)
            value
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

fun bytes(build: Bytes.() -> Unit) = Bytes().apply(build).toByteArray()

private fun ints(values: List<Int>) = ByteBuffer
    .allocate(4 * values.size)
    .order(ByteOrder.LITTLE_ENDIAN)
    .apply { values.forEach { putInt(it) } }
    .array()

private fun concat(parts: List<ByteArray>) = parts.fold(ByteArray(0), ByteArray::plus)

private fun table(rows: List<ByteArray>) =
    ints(listOf(rows.size) + rows.runningFold(0) { offset, row -> offset + row.size }) +
        concat(rows)

private fun blockIndex(firsts: List<Int>, blocks: List<ByteArray>) =
    ints(listOf(firsts.size) + firsts) + table(blocks)

private fun deflate(raw: ByteArray, dictionary: ByteArray = ByteArray(0)): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    if (dictionary.isNotEmpty()) deflater.setDictionary(dictionary)
    val output = ByteArrayOutputStream()
    DeflaterOutputStream(output, deflater).use { it.write(raw) }
    return output.toByteArray()
}

private fun records(rows: List<ByteArray>, dictionary: ByteArray = ByteArray(0)) = table(
    rows.chunked(RECORDS_PER_BLOCK).map { chunk ->
        deflate(bytes { chunk.forEach { varint(it.size).raw(it) } }, dictionary)
    },
)

fun container(
    sections: Map<String, ByteArray>,
    magic: String = "SHLF",
    version: Int = CATALOGUE_FORMAT,
): ByteBuffer {
    val entries = sections.entries.toList()
    val header = 12 + 24 * entries.size
    val offsets = entries.runningFold(header) { offset, entry ->
        offset + entry.value.size
    }
    val directory = entries.mapIndexed { index, (name, data) ->
        name.toByteArray().copyOf(16) + ints(listOf(offsets[index], data.size))
    }
    val head = magic.toByteArray() + ints(listOf(version, entries.size))
    return ByteBuffer.wrap(head + concat(directory) + concat(entries.map { it.value }))
}

private class Postings(val titles: List<Int>, val authors: List<Int>)

private fun terms(books: List<Fixture>, authors: List<String>): Map<String, Postings> {
    val titles = books.withIndex().flatMap { (local, book) ->
        tokenize("${book.title} ${book.subtitle}").map { it to local }
    }
    val names = authors.withIndex().flatMap { (slot, name) ->
        tokenize(name).map { it to slot }
    }
    fun postings(pairs: List<Pair<String, Int>>, text: String) =
        pairs.filter { it.first == text }.map { it.second }.distinct().sorted()
    return (titles + names).map { it.first }.toSortedSet().associateWith {
        Postings(postings(titles, it), postings(names, it))
    }
}

private fun termBlocks(texts: List<String>, entry: (String) -> ByteArray) = table(
    texts.chunked(TERMS_PER_BLOCK).map { chunk ->
        bytes {
            chunk.fold("") { previous, text ->
                val shared = text.commonPrefixWith(previous).length
                varint(shared).text(text.drop(shared)).raw(entry(text))
                text
            }
        }
    },
)

private fun completions(texts: List<String>, terms: Map<String, Postings>): ByteArray {
    val frequency = { id: Int ->
        terms.getValue(texts[id]).let { it.titles + it.authors }.size
    }
    val prefixes = texts.flatMap { text -> (2..4).map(text::take) }.toSortedSet()
    return table(
        prefixes.map { prefix ->
            val ids = texts.indices
                .filter { texts[it].startsWith(prefix) && texts[it] != prefix }
                .sortedByDescending(frequency)
                .take(COMPLETION_LIMIT)
            bytes { text(prefix).varint(ids.size).apply { ids.forEach(::varint) } }
        },
    )
}

private fun grams(texts: List<String>) = table(
    texts
        .withIndex()
        .flatMap { (id, text) -> "$$text$".windowed(3).distinct().map { it to id } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (gram, ids) -> bytes { text(gram).postings(ids) } },
)

private fun meta(vararg lines: Pair<String, Any?>) = lines
    .filter { it.second != null }
    .joinToString("") { (key, value) -> "$key=$value\n" }
    .toByteArray()

private fun isbnSection(books: List<Fixture>): ByteArray? {
    val rows = books.withIndex()
        .flatMap { (local, book) -> book.isbns.map { isbnKey(it)!! to local } }
        .sortedBy { it.first }
        .ifEmpty { return null }
        .chunked(RECORDS_PER_BLOCK)
    val blocks = rows.map { chunk ->
        deflate(
            bytes {
                chunk.fold(chunk.first().first) { previous, (key, local) ->
                    varint(key - previous).varint(local)
                    key
                }
            },
        )
    }
    return blockIndex(rows.map { it.first().first }, blocks)
}

fun segmentBuffer(
    unsorted: List<Fixture>,
    pack: String = "core",
    month: String = "2000-01",
    base: String? = null,
    tombstones: List<Int> = emptyList(),
): ByteBuffer {
    val books = unsorted.sortedBy { it.work }
    val authors = books.map { it.author }.distinct()
    val series = unsorted.mapNotNull { it.series }.distinct()
    val terms = terms(books, authors)
    val texts = terms.keys.toList()
    val facts = books.map { book ->
        bytes {
            varint(1).varint(authors.indexOf(book.author))
            varint(if (book.year == 0) 0 else book.year - 1400).varint(0)
            varint(book.tags.size).apply { book.tags.forEach { byte(TAGS.indexOf(it)) } }
            varint(series.indexOf(book.series) + 1)
        }
    }
    val heads = books.map { "${it.title}\u001f${it.subtitle}\u001f".toByteArray() }
    val authorRecords = authors.map { name ->
        val locals = books.indices.filter { books[it].author == name }
        bytes { varint(numberOf(name)).varint(0).text(name).postings(locals) }
    }
    val seriesRows = series.map { name ->
        val members = unsorted.filter { it.series == name }.map { it.work }
        bytes { text(name).varint(members.size).apply { members.forEach(::varint) } }
    }
    val tagRows = TAGS.map { slug ->
        val locals = books.indices.filter { slug in books[it].tags }
        bytes { text(slug).text(slug).text("genre").varint(locals.size).postings(locals) }
    }
    val descriptions = bytes {
        books.forEachIndexed { local, book ->
            if (book.description.isNotEmpty()) varint(local shl 1).text(book.description)
        }
    }
    val sections = mapOf(
        "meta" to meta(
            "pack" to pack,
            "month" to month,
            "base" to base,
            "records_per_block" to RECORDS_PER_BLOCK,
            "terms_per_block" to TERMS_PER_BLOCK,
        ),
        "works" to ints(books.map { it.work }),
        "tombstones" to ints(tombstones.sorted()),
        "head_dictionary" to DICTIONARY,
        "text_dictionary" to DICTIONARY,
        "facts" to records(facts),
        "heads" to records(heads, DICTIONARY),
        "authors" to records(authorRecords),
        "series" to table(seriesRows),
        "terms" to termBlocks(texts) { text ->
            val postings = terms.getValue(text)
            val titles = bytes { postings(postings.titles) }
            val names = bytes { postings(postings.authors) }
            bytes {
                varint(postings.titles.size).varint(postings.authors.size)
                varint(titles.size).varint(names.size).raw(titles).raw(names)
            }
        },
        "completions" to completions(texts, terms),
        "grams" to grams(texts),
        "descriptions" to blockIndex(
            listOf(0),
            listOf(deflate(descriptions, DICTIONARY)),
        ),
        "tags" to table(tagRows),
    )
    return container(sections + listOfNotNull(isbnSection(books)?.let { "isbns" to it }))
}

fun numberOf(author: String) = author.hashCode() and Int.MAX_VALUE

fun segment(
    books: List<Fixture>,
    pack: String = "core",
    month: String = "2000-01",
    base: String? = null,
) = Segment(segmentBuffer(books, pack, month, base))

fun ranksBuffer(unsorted: List<Fixture>): ByteBuffer {
    val books = unsorted.sortedBy { it.work }
    val terms = terms(books, books.map { it.author }.distinct())
    val blocks = books.chunked(RECORDS_PER_BLOCK)
    val popularity = blocks.map { chunk ->
        deflate(
            bytes {
                chunk.fold(chunk.first().work) { previous, book ->
                    varint(book.work - previous).varint(book.score * 10)
                    varint(book.score).varint(book.score).byte(80).byte(1)
                    book.work
                }
            },
        )
    }
    return container(
        mapOf(
            "meta" to meta("works" to books.size),
            "terms" to termBlocks(terms.keys.toList()) { text ->
                val postings = terms.getValue(text)
                bytes { varint(postings.titles.size).varint(postings.authors.size) }
            },
            "popularity" to blockIndex(blocks.map { it.first().work }, popularity),
        ),
    )
}

fun catalogue(books: List<Fixture>) =
    Catalogue("en", listOf(segment(books)), Ranks(ranksBuffer(books)))
