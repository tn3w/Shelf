package dev.tn3w.shelf.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val EXPORT_COLUMNS =
    "Title,Author,ISBN,My Rating,Date Read,Date Added,Exclusive Shelf".split(",")
private val SHELF_NAMES = mapOf(
    Shelf.Read to "read",
    Shelf.Reading to "currently-reading",
    Shelf.Want to "to-read",
)

data class CsvBook(
    val title: String,
    val author: String,
    val shelf: Shelf,
    val rating: Int,
    val date: Long,
)

data class CsvReport(val total: Int, val missing: List<String>)

private fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    fun endField() {
        row += field.toString()
        field.clear()
    }
    while (index < text.length) {
        val character = text[index++]
        when {
            quoted && character == '"' && text.getOrNull(index) == '"' -> {
                field.append('"')
                index++
            }

            character == '"' -> quoted = !quoted

            quoted -> field.append(character)

            character == ',' -> endField()

            character == '\n' -> {
                endField()
                rows += row.toList()
                row.clear()
            }

            character != '\r' -> field.append(character)
        }
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        endField()
        rows += row.toList()
    }
    return rows
}

fun csvBooks(text: String): List<CsvBook> {
    val rows = parseCsv(text.trimStart('\uFEFF'))
    val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
    fun List<String>.column(vararg names: String) = names
        .map(header::indexOf)
        .firstOrNull { it >= 0 }
        ?.let { getOrNull(it)?.trim() }
        .orEmpty()
    return rows.drop(1).mapNotNull { row ->
        val title = row.column("title").ifEmpty { return@mapNotNull null }
        val shelf = shelfOf(row.column("exclusive shelf", "read status"))
        val read = dateOf(row.column("date read", "last date read"))
            .takeIf { shelf == Shelf.Read }
        val added = dateOf(row.column("date added"))
        CsvBook(
            title,
            row.column("author", "authors").substringBefore(',').trim(),
            shelf,
            row.column("my rating", "star rating").toFloatOrNull()?.roundToInt() ?: 0,
            read ?: added ?: System.currentTimeMillis(),
        )
    }
}

private fun shelfOf(status: String) = when (status.lowercase()) {
    "read" -> Shelf.Read
    "currently-reading", "paused" -> Shelf.Reading
    else -> Shelf.Want
}

private fun dateOf(value: String) = runCatching {
    LocalDate.parse(value, DATE).atStartOfDay(ZoneId.systemDefault()).toInstant()
        .toEpochMilli()
}
    .getOrNull()

private fun formatDate(millis: Long) =
    DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun Searcher.find(title: String, author: String): Book? {
    val wanted = tokenize(mainTitle(title))
    val surname = tokenize(author).lastOrNull()
    return search("${wanted.joinToString(" ")} $author", 5).firstOrNull { book ->
        val titles = listOf(book.title, book.alternate).map { tokenize(mainTitle(it)) }
        wanted in titles &&
            (surname == null || book.authors.any { surname in tokenize(it.name) })
    }
}

fun csvOf(entries: List<Saved>): String {
    fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
    val rows = entries.map { saved ->
        val date = formatDate(saved.updated)
        listOf(
            saved.title,
            saved.author,
            "",
            saved.rating.toString(),
            if (saved.shelf == Shelf.Read) date else "",
            date,
            SHELF_NAMES.getValue(saved.shelf),
        )
    }
    return (listOf(EXPORT_COLUMNS) + rows).joinToString("") { row ->
        row.joinToString(",", postfix = "\n", transform = ::quoted)
    }
}
