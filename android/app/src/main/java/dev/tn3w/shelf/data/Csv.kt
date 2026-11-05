package dev.tn3w.shelf.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val EXPORT_COLUMNS = listOf(
    "Title", "Author", "ISBN", "My Rating", "Date Read", "Date Added", "Exclusive Shelf",
)
private val READWISE_COLUMNS =
    listOf("Highlight", "Title", "Author", "Note", "Location", "Date")
private val READWISE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
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
    val isbn: String = "",
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

            else -> field.append(character)
        }
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        endField()
        rows += row.toList()
    }
    return rows
}

fun csvBooks(text: String): List<CsvBook> {
    val lines = text.trimStart('\uFEFF').replace("\r\n", "\n").replace('\r', '\n')
    val rows = parseCsv(lines)
    val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
    fun column(vararg names: String): (List<String>) -> String {
        val index = names.map(header::indexOf).firstOrNull { it >= 0 } ?: -1
        return { row -> row.getOrNull(index)?.trim().orEmpty() }
    }
    val titleOf = column("title")
    val statusOf = column("exclusive shelf", "read status")
    val readOf = column("date read", "last date read")
    val addedOf = column("date added")
    val authorOf = column("author", "authors")
    val ratingOf = column("my rating", "star rating")
    val isbnColumns = listOf(column("isbn13"), column("isbn"), column("isbn/uid"))
    return rows.drop(1).mapNotNull { row ->
        val title = titleOf(row).ifEmpty { return@mapNotNull null }
        val shelf = shelfOf(statusOf(row))
        val read = dateOf(readOf(row)).takeIf { shelf == Shelf.Read }
        val added = dateOf(addedOf(row))
        CsvBook(
            title,
            authorOf(row).substringBefore(',').trim(),
            shelf,
            ratingOf(row).toFloatOrNull()?.roundToInt()?.coerceIn(0, 5) ?: 0,
            read ?: added ?: System.currentTimeMillis(),
            isbnColumns.firstNotNullOfOrNull { normalizedIsbn(it(row)) }.orEmpty(),
        )
    }
}

private fun shelfOf(status: String) = when (status.lowercase()) {
    "read", "did-not-finish" -> Shelf.Read
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

private fun csvField(value: String): String {
    val safe = if (value.firstOrNull() in FORMULA_STARTS) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}

private fun csvText(rows: List<List<String>>) = rows.joinToString("") { row ->
    row.joinToString(",", postfix = "\n", transform = ::csvField)
}

private fun readwiseDate(millis: Long) = if (millis <= 0) {
    ""
} else {
    READWISE_DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))
}

fun readwiseCsv(books: List<BookNotes>): String {
    val rows = books.flatMap { book ->
        book.highlights.inReadingOrder().mapIndexed { index, highlight ->
            listOf(
                highlight.text,
                book.title,
                book.author,
                highlight.note,
                "${index + 1}",
                readwiseDate(highlight.created),
            )
        }
    }
    return csvText(listOf(READWISE_COLUMNS) + rows)
}

fun csvOf(entries: List<Saved>): String {
    val rows = entries.map { saved ->
        val date = formatDate(saved.updated)
        listOf(
            saved.title,
            saved.author,
            saved.isbn,
            saved.rating.toString(),
            if (saved.shelf == Shelf.Read) date else "",
            formatDate(saved.added.takeIf { it > 0 } ?: saved.updated),
            SHELF_NAMES.getValue(saved.shelf),
        )
    }
    return csvText(listOf(EXPORT_COLUMNS) + rows)
}
