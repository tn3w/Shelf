package dev.tn3w.shelf.data

import androidx.annotation.StringRes
import dev.tn3w.shelf.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

private const val PAGES_PER_HOUR = 40
private const val MARATHON_DAYS = 21
private const val EXPLORER_GENRES = 5
private const val DEVOTED_BOOKS = 3
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

enum class ReaderType(@StringRes val title: Int, @StringRes val description: Int) {
    Marathoner(R.string.type_marathoner, R.string.type_marathoner_text),
    Explorer(R.string.type_explorer, R.string.type_explorer_text),
    Devotee(R.string.type_devotee, R.string.type_devotee_text),
    Weekender(R.string.type_weekender, R.string.type_weekender_text),
    Bookworm(R.string.type_bookworm, R.string.type_bookworm_text),
}

data class MonthStats(
    val month: YearMonth,
    val pages: Int,
    val books: List<Saved>,
    val genres: List<Tag>,
)

data class GenreStats(val tag: Tag, val books: List<Saved>)

data class Recap(
    val year: Int,
    val months: List<MonthStats>,
    val genres: List<GenreStats>,
    val days: Map<LocalDate, Int>,
) {
    val pages
        get() = months.sumOf { it.pages }
    val books
        get() = months.flatMap { it.books }
    val isEmpty
        get() = pages == 0 && books.isEmpty()
    val readingDays
        get() = days.size
    val longestStreak
        get() = longestStreak(days.keys)
    val hours
        get() = (pages + PAGES_PER_HOUR / 2) / PAGES_PER_HOUR
    val bestMonth
        get() = months.filter { it.pages > 0 }.maxByOrNull { it.pages }
    val bestDay
        get() = days.entries.maxByOrNull { it.value }?.toPair()
    val authorBooks
        get() = books
            .filter { it.author.isNotBlank() }
            .groupBy { it.author }
            .values
            .filter { it.size > 1 }
            .maxByOrNull { it.size }
            .orEmpty()
    val topAuthor
        get() = authorBooks.firstOrNull()?.author
    val readerType
        get() = when {
            longestStreak >= MARATHON_DAYS -> ReaderType.Marathoner
            genres.size >= EXPLORER_GENRES -> ReaderType.Explorer
            authorBooks.size >= DEVOTED_BOOKS -> ReaderType.Devotee
            weekendShare() > 0.5 -> ReaderType.Weekender
            else -> ReaderType.Bookworm
        }

    private fun weekendShare(): Double {
        if (pages == 0) return 0.0
        val weekend = days.filterKeys { it.dayOfWeek in WEEKEND }.values.sum()
        return weekend.toDouble() / pages
    }
}

fun wrappedYear(today: LocalDate) = when (today.month) {
    Month.DECEMBER -> today.year
    Month.JANUARY -> today.year - 1
    else -> null
}

fun Saved.finishedOn(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    if (shelf == Shelf.Read && updated > 0) {
        Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
    } else {
        null
    }

private fun readingDays(activity: Map<String, Int>) = activity
    .filterValues { it > 0 }
    .mapNotNull { (day, pages) ->
        runCatching { LocalDate.parse(day) to pages }.getOrNull()
    }
    .toMap()

fun statsYears(
    entries: List<Saved>,
    activity: Map<String, Int>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<Int> {
    val finished = entries.mapNotNull { it.finishedOn(zone)?.year }
    val read = readingDays(activity).keys.map { it.year }
    return (finished + read + today.year)
        .filter { it <= today.year }
        .distinct()
        .sortedDescending()
}

internal fun longestStreak(days: Collection<LocalDate>): Int {
    var longest = 0
    var current = 0
    var previous: LocalDate? = null
    days.sorted().forEach { day ->
        current = if (previous?.plusDays(1) == day) current + 1 else 1
        longest = maxOf(longest, current)
        previous = day
    }
    return longest
}

private fun genresOf(books: List<Saved>, genresOf: (Int) -> List<Tag>) = books
    .flatMap { book -> genresOf(book.work).map { it to book } }
    .groupBy({ it.first }, { it.second })
    .map { (tag, books) -> GenreStats(tag, books) }
    .sortedByDescending { it.books.size }

fun recapOf(
    year: Int,
    entries: List<Saved>,
    activity: Map<String, Int>,
    genresOf: (Int) -> List<Tag> = { emptyList() },
    zone: ZoneId = ZoneId.systemDefault(),
): Recap {
    val finished = entries
        .filter { it.finishedOn(zone)?.year == year }
        .sortedBy { it.updated }
    val days = readingDays(activity).filterKeys { it.year == year }
    val months = Month.entries.map { month ->
        val current = YearMonth.of(year, month)
        val books = finished.filter { YearMonth.from(it.finishedOn(zone)) == current }
        MonthStats(
            current,
            days.filterKeys { YearMonth.from(it) == current }.values.sum(),
            books,
            genresOf(books, genresOf).map { it.tag },
        )
    }
    return Recap(year, months, genresOf(finished, genresOf), days)
}
