package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private fun millis(date: String) =
    LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

class TextTest {
    @Test
    fun tokenizeFoldsAccentsCaseAndApostrophes() {
        assertEquals(listOf("dont", "panic", "ca", "va"), tokenize("Don't Panic! Ça va"))
        assertEquals(listOf("ete", "strase"), tokenize("éte Straße"))
        assertEquals(listOf("muller", "o", "neill"), tokenize("Müller O-Neill"))
    }

    @Test
    fun tokenizeSplitsLongTokens() {
        assertEquals(listOf("a".repeat(24), "a".repeat(6)), tokenize("a".repeat(30)))
    }

    @Test
    fun articlesCoverAllCatalogueLanguages() {
        val expected = listOf("the", "a", "an", "der", "die", "das", "le", "la", "les")
        assertTrue(ARTICLES.containsAll(expected + listOf("el", "los", "las")))
    }

    @Test
    fun mainTitleStopsAtBreak() {
        assertEquals("Dune", mainTitle("Dune: Messiah"))
        assertEquals("Circe ", mainTitle("Circe (novel)"))
        assertEquals("Emma", mainTitle("Emma"))
    }

    @Test
    fun readsGoodreadsExport() {
        val bom = Char(0xFEFF)
        val header = "Title,Author,My Rating,Exclusive Shelf,Date Read,Date Added"
        val csv = "$bom$header\r\n" +
            "\"Dune, Book \"\"One\"\"\",Frank Herbert,4,read,2020/01/02,2019/05/06\r\n" +
            "Circe,Madeline Miller,0,to-read,2020/02/02,2021/03/04\r\n" +
            ",Nobody,3,read,,\r\n"
        assertEquals(
            listOf(
                CsvBook(
                    "Dune, Book \"One\"",
                    "Frank Herbert",
                    Shelf.Read,
                    4,
                    millis("2020-01-02"),
                ),
                CsvBook("Circe", "Madeline Miller", Shelf.Want, 0, millis("2021-03-04")),
            ),
            csvBooks(csv),
        )
    }

    @Test
    fun readsStoryGraphExport() {
        val before = System.currentTimeMillis()
        val csv = "Title,Authors,Read Status,Star Rating,Last Date Read\n" +
            "Project Hail Mary,\"Andy Weir, Someone\",currently-reading,4.5,\n" +
            "Paused Book,Someone,paused,,\n" +
            "Done,Someone,read,,2022/12/31"
        val books = csvBooks(csv)
        assertEquals(listOf("Andy Weir", "Someone", "Someone"), books.map { it.author })
        val shelves = books.map { it.shelf }
        assertEquals(listOf(Shelf.Reading, Shelf.Reading, Shelf.Read), shelves)
        assertEquals(listOf(5, 0, 0), books.map { it.rating })
        assertTrue(books[0].date >= before)
        assertEquals(millis("2022-12-31"), books[2].date)
    }

    @Test
    fun ignoresFilesWithoutRows() {
        assertEquals(emptyList<CsvBook>(), csvBooks(""))
        assertEquals(emptyList<CsvBook>(), csvBooks("Title,Author\n"))
    }

    @Test
    fun exportReadsBack() {
        val updated = millis("2023-04-05")
        val entries = listOf(
            Saved(1, Shelf.Read, updated, "Say \"hi\", world", "Ann Author", rating = 5),
            Saved(2, Shelf.Want, updated, "Later", "Bob"),
        )
        val csv = csvOf(entries)
        assertTrue(csv.startsWith("\"Title\",\"Author\",\"ISBN\",\"My Rating\""))
        assertEquals(
            listOf(
                CsvBook("Say \"hi\", world", "Ann Author", Shelf.Read, 5, updated),
                CsvBook("Later", "Bob", Shelf.Want, 0, updated),
            ),
            csvBooks(csv),
        )
    }
}
