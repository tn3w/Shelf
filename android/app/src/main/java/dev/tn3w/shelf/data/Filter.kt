package dev.tn3w.shelf.data

private const val FACET_LIMIT = 5

enum class LibrarySort {
    Added,
    Title,
    Author,
    Rating,
    Finished,
}

enum class ResultSort {
    Relevance,
    Popular,
    Newest,
    Oldest,
}

val ERAS = listOf(1..1899, 1900..1999, 2000..2019, 2020..9999)

data class SearchFilter(
    val tag: Int? = null,
    val era: IntRange? = null,
    val sort: ResultSort = ResultSort.Relevance,
) {
    val narrows
        get() = tag != null || era != null
}

fun sortableTitle(title: String) = withoutArticle(tokenize(title).joinToString(" "))

fun surname(author: String) = tokenize(author).lastOrNull().orEmpty()

fun matchesWords(text: String, query: String): Boolean {
    val words = tokenize(text)
    return tokenize(query).all { token -> words.any { it.startsWith(token) } }
}

fun List<Saved>.filtered(
    query: String,
    shelf: Shelf? = null,
    tag: Int? = null,
    tagsOf: (Int) -> List<Int> = { emptyList() },
) = filter { saved ->
    (shelf == null || saved.shelf == shelf) &&
        (tag == null || tag in tagsOf(saved.work)) &&
        matchesWords("${saved.title} ${saved.author}", query)
}

private val Saved.since
    get() = added.takeIf { it > 0 } ?: updated

fun List<Saved>.sorted(sort: LibrarySort): List<Saved> {
    val byTitle = compareBy<Saved> { sortableTitle(it.title) }
    val comparator: Comparator<Saved> = when (sort) {
        LibrarySort.Added -> compareByDescending { it.since }

        LibrarySort.Title -> byTitle

        LibrarySort.Author -> compareBy<Saved> { surname(it.author) }.then(byTitle)

        LibrarySort.Rating -> compareByDescending<Saved> { it.rating }.then(byTitle)

        LibrarySort.Finished -> compareByDescending<Saved> { it.shelf == Shelf.Read }
            .thenByDescending { it.updated }
    }
    return sortedWith(comparator)
}

fun List<Book>.sorted(sort: ResultSort) = when (sort) {
    ResultSort.Relevance -> this
    ResultSort.Popular -> sortedByDescending { it.popularity?.score ?: 0.0 }
    ResultSort.Newest -> sortedByDescending { it.year }
    ResultSort.Oldest -> sortedBy { it.year.takeIf { year -> year > 0 } ?: Int.MAX_VALUE }
}

fun facets(tagLists: List<List<Int>>) = tagLists
    .flatten()
    .groupingBy { it }
    .eachCount()
    .filterValues { it >= 2 }
    .entries
    .sortedByDescending { it.value }
    .take(FACET_LIMIT)
    .map { it.key }
