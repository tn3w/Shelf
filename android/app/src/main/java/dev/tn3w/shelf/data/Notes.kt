package dev.tn3w.shelf.data

import kotlinx.serialization.Serializable

enum class HighlightColor { Yellow, Green, Blue, Pink }

@Serializable
data class Highlight(
    val section: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String = "",
    val color: HighlightColor = HighlightColor.Yellow,
    val chapter: String = "",
    val created: Long = 0,
)

data class BookNotes(
    val title: String,
    val author: String,
    val highlights: List<Highlight>,
)

enum class NotesFormat(val mimeType: String, val extension: String) {
    Markdown("text/markdown", "md"),
    Readwise("text/csv", "csv"),
    ;

    fun render(books: List<BookNotes>) = when (this) {
        Markdown -> notesMarkdown(books)
        Readwise -> readwiseCsv(books)
    }
}

fun List<Highlight>.inReadingOrder() =
    sortedWith(compareBy(Highlight::section, Highlight::start, Highlight::end))

fun Highlight.rangeOn(section: Int, start: Int, length: Int): IntRange? {
    if (section != this.section) return null
    val from = maxOf(this.start, start) - start
    val to = minOf(end, start + length) - start
    return if (from < to) from until to else null
}

fun List<Highlight>.at(section: Int, offset: Int) =
    lastOrNull { it.section == section && offset in it.start until it.end }

fun paragraphsIn(text: String, paragraphStarts: List<Int>, start: Int, end: Int) =
    (listOf(start) + paragraphStarts.filter { it in start + 1 until end } + end)
        .zipWithNext { from, to -> text.substring(from, to).trim() }
        .filter(String::isNotEmpty)
        .joinToString("\n")

internal fun bookNotes(saved: List<Saved>, highlights: Map<Int, List<Highlight>>) = saved
    .mapNotNull { entry ->
        highlights[entry.work]?.let { BookNotes(entry.title, entry.author, it) }
    }
    .sortedBy { it.title.lowercase() }

fun notesMarkdown(books: List<BookNotes>) = books.joinToString("\n") { it.markdown() }

private fun BookNotes.markdown() = buildString {
    appendLine("# $title")
    if (author.isNotBlank()) appendLine().appendLine("*$author*")
    var chapter = ""
    highlights.inReadingOrder().forEach { highlight ->
        if (highlight.chapter.isNotBlank() && highlight.chapter != chapter) {
            appendLine().appendLine("## ${highlight.chapter}")
        }
        chapter = highlight.chapter
        appendLine().appendLine(blockquote(highlight.text))
        if (highlight.note.isNotBlank()) appendLine().appendLine(highlight.note.trim())
    }
}

private fun blockquote(text: String) =
    text.trim().lines().joinToString("\n") { "> $it".trimEnd() }

fun quoteText(highlight: Highlight, title: String, author: String) = buildString {
    append("“${highlight.text.trim()}”")
    val source = listOf(title, author).filter(String::isNotBlank)
    if (source.isNotEmpty()) append("\n— ").append(source.joinToString(", "))
    if (highlight.note.isNotBlank()) append("\n\n").append(highlight.note.trim())
}
