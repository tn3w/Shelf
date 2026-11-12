package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset

private val ZONE = ZoneOffset.UTC
private const val YEAR = 2030

private fun day(month: Int, day: Int) = LocalDate.of(YEAR, month, day)

private fun millis(date: LocalDate) = date.atStartOfDay(ZONE).toInstant().toEpochMilli()

private fun finished(work: Int, date: LocalDate, author: String = "Author $work") =
    Saved(work, Shelf.Read, millis(date), "Title $work", author)

private val fantasy = Tag(0, "fantasy", "Fantasy", "genre")
private val mystery = Tag(1, "mystery", "Mystery", "genre")

class StatsTest {
    @Test
    fun recapSumsPagesAndBooksPerMonth() {
        val activity = mapOf(
            day(1, 1).toString() to 10,
            day(1, 2).toString() to 20,
            day(3, 5).toString() to 7,
            LocalDate.of(YEAR - 1, 1, 1).toString() to 99,
        )
        val entries = listOf(finished(1, day(1, 3)), finished(2, day(3, 9)))
        val recap = recapOf(YEAR, entries, activity, zone = ZONE)
        assertEquals(12, recap.months.size)
        assertEquals(30, recap.months[0].pages)
        assertEquals(7, recap.months[2].pages)
        assertEquals(37, recap.pages)
        assertEquals(listOf(1, 2), recap.books.map { it.work })
        assertEquals(YearMonth.of(YEAR, Month.JANUARY), recap.bestMonth?.month)
        assertEquals(day(1, 2) to 20, recap.bestDay)
        assertEquals(3, recap.readingDays)
    }

    @Test
    fun recapIgnoresUnfinishedAndOtherYears() {
        val entries = listOf(
            Saved(1, Shelf.Reading, millis(day(2, 1))),
            Saved(2, Shelf.Want, millis(day(2, 1))),
            finished(3, LocalDate.of(YEAR + 1, 1, 1)),
            Saved(4, Shelf.Read, 0),
        )
        assertTrue(recapOf(YEAR, entries, emptyMap(), zone = ZONE).isEmpty)
    }

    @Test
    fun audioCountsOnlyPlayedSeconds() {
        assertEquals(1, heardSeconds(before = 100, after = 101))
        assertEquals(2, heardSeconds(before = 100, after = 102))
        assertEquals(0, heardSeconds(before = 100, after = 400))
        assertEquals(0, heardSeconds(before = 100, after = 90))
    }

    @Test
    fun hourOfAudioCountsAsHourOfPages() {
        val pages = pagesHeard(3600)
        val activity = mapOf(day(1, 1).toString() to pages)
        assertEquals(1, recapOf(YEAR, emptyList(), activity).hours)
        assertEquals(0, pagesHeard(secondsOfPages(1) - 1))
    }

    @Test
    fun recapCountsBooksStillBeingRead() {
        val reading =
            Saved(1, Shelf.Reading, millis(day(5, 2)), started = millis(day(5, 1)))
        val lastYear = millis(LocalDate.of(YEAR - 1, 9, 1))
        val nextYear = millis(LocalDate.of(YEAR + 1, 1, 1))
        val earlier = reading.copy(work = 2, started = lastYear)
        val later = reading.copy(work = 3, started = nextYear)
        val entries = listOf(reading, earlier, later, finished(4, day(6, 1)))
        val recap = recapOf(YEAR, entries, emptyMap(), zone = ZONE)
        assertEquals(listOf(4), recap.books.map { it.work })
        assertEquals(setOf(1, 2, 4), recap.allBooks.map { it.work }.toSet())
        assertEquals(listOf(1), recap.months[4].allBooks.map { it.work })
        assertTrue(!recap.isEmpty)
    }

    @Test
    fun genresRankByFinishedBooks() {
        val entries = listOf(
            finished(1, day(1, 1)),
            finished(2, day(1, 2)),
            finished(3, day(2, 1)),
        )
        val genres = mapOf(1 to listOf(fantasy), 2 to listOf(fantasy, mystery))
        val recap = recapOf(YEAR, entries, emptyMap(), { genres[it].orEmpty() }, ZONE)
        val ranked = recap.genres.map { it.tag to it.books.map(Saved::work) }
        assertEquals(listOf(fantasy to listOf(1, 2), mystery to listOf(2)), ranked)
        assertEquals(listOf(fantasy, mystery), recap.months[0].genres)
        assertEquals(emptyList<Tag>(), recap.months[1].genres)
    }

    @Test
    fun topAuthorNeedsMoreThanOneBook() {
        val single = listOf(finished(1, day(1, 1)), finished(2, day(1, 2)))
        assertNull(recapOf(YEAR, single, emptyMap(), zone = ZONE).topAuthor)
        val repeated = single + finished(3, day(4, 1), author = "Author 1")
        val recap = recapOf(YEAR, repeated, emptyMap(), zone = ZONE)
        assertEquals("Author 1", recap.topAuthor)
    }

    @Test
    fun longestStreakCountsConsecutiveDays() {
        val days =
            listOf(day(1, 1), day(1, 2), day(1, 4), day(1, 5), day(1, 6), day(2, 1))
        assertEquals(3, longestStreak(days.shuffled()))
        assertEquals(0, longestStreak(emptyList()))
    }

    @Test
    fun streakCrossesMonthAndSkipsIdleDays() {
        val activity = mapOf(
            day(1, 31).toString() to 5,
            day(2, 1).toString() to 5,
            day(2, 2).toString() to 0,
        )
        val recap = recapOf(YEAR, emptyList(), activity, zone = ZONE)
        assertEquals(2, recap.longestStreak)
        assertEquals(2, recap.readingDays)
    }

    @Test
    fun yearsIncludeCurrentAndPastActivityNewestFirst() {
        val today = day(6, 1)
        val entries = listOf(finished(1, LocalDate.of(YEAR - 2, 5, 1)))
        val activity = mapOf(
            LocalDate.of(YEAR - 1, 1, 1).toString() to 3,
            LocalDate.of(YEAR - 3, 1, 1).toString() to 0,
            "unreadable" to 4,
        )
        assertEquals(
            listOf(YEAR, YEAR - 1, YEAR - 2),
            statsYears(entries, activity, today, ZONE),
        )
    }

    @Test
    fun hoursRoundToNearest() {
        val activity = mapOf(day(1, 1).toString() to 59, day(1, 2).toString() to 2)
        assertEquals(2, recapOf(YEAR, emptyList(), activity, zone = ZONE).hours)
    }

    @Test
    fun readerTypeFollowsReadingPattern() {
        val month = (1..21).associate { day(3, it).toString() to 5 }
        assertEquals(ReaderType.Marathoner, recapOf(YEAR, emptyList(), month).readerType)
        val tags = (0 until 5).map { Tag(it, "tag$it", "Tag $it", "genre") }
        val varied = (0 until 5).map { finished(it, day(1, 1)) }
        val explorer = recapOf(YEAR, varied, emptyMap(), { listOf(tags[it]) }, ZONE)
        assertEquals(ReaderType.Explorer, explorer.readerType)
        val fan = (1..3).map { finished(it, day(2, it), author = "Same") }
        val devotee = recapOf(YEAR, fan, emptyMap(), zone = ZONE)
        assertEquals(ReaderType.Devotee, devotee.readerType)
        assertEquals(3, devotee.authorBooks.size)
        val saturday = LocalDate.of(YEAR, 1, 5)
        val weekend = mapOf(saturday.toString() to 30, day(1, 7).toString() to 10)
        assertEquals(
            ReaderType.Weekender,
            recapOf(YEAR, emptyList(), weekend, zone = ZONE).readerType,
        )
        assertEquals(
            ReaderType.Bookworm,
            recapOf(YEAR, emptyList(), mapOf(day(1, 7).toString() to 10)).readerType,
        )
    }

    @Test
    fun wrappedShowsInDecemberAndJanuaryForTheEndingYear() {
        assertEquals(YEAR, wrappedYear(day(12, 1)))
        assertEquals(YEAR - 1, wrappedYear(day(1, 31)))
        assertNull(wrappedYear(day(2, 1)))
    }

    @Test
    fun finishDateOverridesLastUpdate() {
        val entry = finished(1, day(5, 1)).copy(
            started = millis(day(1, 10)),
            finished = millis(day(1, 19)),
            rating = 4,
        )
        assertEquals(day(1, 19), entry.finishedOn(ZONE))
        assertEquals(day(1, 10), entry.startedOn(ZONE))
        assertEquals(10, entry.daysToFinish(ZONE))
        val recap =
            recapOf(YEAR, listOf(entry, finished(2, day(2, 1))), emptyMap(), zone = ZONE)
        assertEquals(listOf(1), recap.months[0].books.map { it.work })
        assertEquals(4.0, recap.averageRating)
    }

    @Test
    fun unstartedOrUnfinishedBooksHaveNoDuration() {
        assertNull(finished(1, day(1, 1)).daysToFinish(ZONE))
        val reading = Saved(2, Shelf.Reading, 1, started = millis(day(1, 1)))
        assertNull(reading.daysToFinish(ZONE))
        assertNull(recapOf(YEAR, emptyList(), emptyMap(), zone = ZONE).averageRating)
    }
}
