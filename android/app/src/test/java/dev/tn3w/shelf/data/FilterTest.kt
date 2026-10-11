package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

private val entries = listOf(
    Saved(1, Shelf.Read, 30, "The Hobbit", "John Tolkien"),
    Saved(2, Shelf.Want, 40, "Émile", "Jean Rousseau"),
    Saved(3, Shelf.Read, 10, "A Dance", "Ann Austen", rating = 5),
    Saved(4, Shelf.Reading, 20, "Dune", "Frank Herbert", added = 50),
)

private fun dragon(work: Int, title: String, year: Int, score: Int, vararg tags: String) =
    Fixture(work, "Dragon $title", tags = tags.toList(), year = year, score = score)

private val catalogue = catalogue(
    listOf(
        dragon(1, "Tales", 1950, 3, "fantasy", "dragons"),
        dragon(2, "Rider", 2004, 9, "fantasy"),
        dragon(3, "Facts", 2021, 5, "nonfiction"),
        dragon(4, "Hunt", 0, 1, "crime", "dragons"),
    ),
)

private val searcher = Searcher(catalogue)

private fun List<Saved>.works() = map { it.work }

private fun tagId(slug: String) = catalogue.tagBySlug.getValue(slug).id

private fun search(filter: SearchFilter) =
    searcher.search(searcher.lookup("dragon"), filter = filter).map { it.work }

class FilterTest {
    @Test
    fun titleSortIgnoresArticlesAndAccents() {
        assertEquals(listOf(3, 4, 2, 1), entries.sorted(LibrarySort.Title).works())
    }

    @Test
    fun authorSortUsesSurname() {
        assertEquals(listOf(3, 4, 2, 1), entries.sorted(LibrarySort.Author).works())
    }

    @Test
    fun ratingSortPutsUnratedLast() {
        assertEquals(3, entries.sorted(LibrarySort.Rating).first().work)
    }

    @Test
    fun finishedSortListsReadBooksNewestFirst() {
        assertEquals(listOf(1, 3), entries.sorted(LibrarySort.Finished).works().take(2))
    }

    @Test
    fun addedSortFallsBackToUpdated() {
        assertEquals(listOf(4, 2, 1, 3), entries.sorted(LibrarySort.Added).works())
    }

    @Test
    fun filterMatchesWordPrefixesWithoutAccents() {
        assertEquals(listOf(2), entries.filtered("emi").works())
        assertEquals(listOf(1), entries.filtered("tolk hob").works())
        assertEquals(emptyList<Int>(), entries.filtered("hobbit herbert").works())
    }

    @Test
    fun filterCombinesShelfAndTag() {
        val tags = mapOf(1 to listOf(7), 3 to listOf(7, 8))
        val read = entries.filtered("", Shelf.Read, 8) { tags[it].orEmpty() }
        assertEquals(listOf(3), read.works())
    }

    @Test
    fun facetsKeepRepeatedTagsByCount() {
        val tags = listOf(listOf(1, 2), listOf(2), listOf(1, 2, 3))
        assertEquals(listOf(2, 1), facets(tags))
    }

    @Test
    fun searchFiltersByTagAndEra() {
        assertEquals(setOf(1, 2), search(SearchFilter(tag = tagId("fantasy"))).toSet())
        assertEquals(listOf(3), search(SearchFilter(era = ERAS.last())))
        assertEquals(listOf(1), search(SearchFilter(tagId("dragons"), ERAS[1])))
    }

    @Test
    fun searchSortsResults() {
        assertEquals(listOf(3, 2, 1, 4), search(SearchFilter(sort = ResultSort.Newest)))
        assertEquals(listOf(1, 2, 3, 4), search(SearchFilter(sort = ResultSort.Oldest)))
        assertEquals(2, search(SearchFilter(sort = ResultSort.Popular)).first())
    }
}
