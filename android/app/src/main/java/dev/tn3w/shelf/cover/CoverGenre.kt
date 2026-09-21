package dev.tn3w.shelf.cover

enum class CoverGenre {
    Fantasy,
    Dystopian,
    ScienceFiction,
    Mystery,
    Horror,
    Romance,
    Adventure,
    Children,
    Poetry,
    Nature,
    Travel,
    Spiritual,
    Business,
    Humor,
    Vintage,
    Biography,
    History,
    Technical,
    Literary,
}

private val SLUG_GENRES =
    mapOf(
        "epic-fantasy" to (CoverGenre.Fantasy to 5),
        "urban-fantasy" to (CoverGenre.Fantasy to 5),
        "fantasy" to (CoverGenre.Fantasy to 6),
        "folklore" to (CoverGenre.Fantasy to 5),
        "dystopian" to (CoverGenre.Dystopian to 7),
        "science-fiction" to (CoverGenre.ScienceFiction to 6),
        "space-opera" to (CoverGenre.ScienceFiction to 7),
        "cozy-mystery" to (CoverGenre.Mystery to 7),
        "mystery" to (CoverGenre.Mystery to 6),
        "crime" to (CoverGenre.Mystery to 6),
        "thriller" to (CoverGenre.Mystery to 6),
        "true-crime" to (CoverGenre.Mystery to 5),
        "horror" to (CoverGenre.Horror to 7),
        "paranormal" to (CoverGenre.Horror to 6),
        "romance" to (CoverGenre.Romance to 6),
        "contemporary-romance" to (CoverGenre.Romance to 7),
        "historical-romance" to (CoverGenre.Romance to 7),
        "regency-romance" to (CoverGenre.Romance to 7),
        "adventure" to (CoverGenre.Adventure to 6),
        "western" to (CoverGenre.Adventure to 6),
        "sports" to (CoverGenre.Adventure to 5),
        "picture-book" to (CoverGenre.Children to 9),
        "childrens" to (CoverGenre.Children to 5),
        "middle-grade" to (CoverGenre.Children to 3),
        "parenting" to (CoverGenre.Children to 5),
        "poetry" to (CoverGenre.Poetry to 7),
        "nature" to (CoverGenre.Nature to 6),
        "health" to (CoverGenre.Nature to 5),
        "cooking" to (CoverGenre.Nature to 5),
        "travel" to (CoverGenre.Travel to 6),
        "spirituality" to (CoverGenre.Spiritual to 6),
        "religion" to (CoverGenre.Spiritual to 6),
        "philosophy" to (CoverGenre.Spiritual to 5),
        "business" to (CoverGenre.Business to 6),
        "economics" to (CoverGenre.Business to 5),
        "self-help" to (CoverGenre.Business to 5),
        "psychology" to (CoverGenre.Business to 5),
        "society" to (CoverGenre.Business to 4),
        "politics" to (CoverGenre.Business to 4),
        "humor" to (CoverGenre.Humor to 5),
        "graphic-novel" to (CoverGenre.Humor to 3),
        "classics" to (CoverGenre.Vintage to 6),
        "biography" to (CoverGenre.Biography to 8),
        "history" to (CoverGenre.History to 6),
        "war-history" to (CoverGenre.History to 6),
        "historical-fiction" to (CoverGenre.History to 6),
        "war-fiction" to (CoverGenre.History to 6),
        "law" to (CoverGenre.History to 4),
        "technology" to (CoverGenre.Technical to 6),
        "science" to (CoverGenre.Technical to 6),
        "mathematics" to (CoverGenre.Technical to 6),
        "education" to (CoverGenre.Technical to 5),
        "reference" to (CoverGenre.Technical to 5),
        "language" to (CoverGenre.Technical to 5),
        "music" to (CoverGenre.Technical to 4),
        "art" to (CoverGenre.Technical to 4),
        "literary-fiction" to (CoverGenre.Literary to 6),
        "literary-criticism" to (CoverGenre.Literary to 5),
        "drama" to (CoverGenre.Literary to 4),
        "short-stories" to (CoverGenre.Literary to 3),
        "fiction" to (CoverGenre.Literary to 1),
    )

private val TOPICS =
    setOf(
        "nature", "health", "cooking", "travel", "business", "economics", "self-help",
        "psychology", "society", "politics", "history", "war-history", "law",
        "technology", "science", "mathematics", "education", "reference", "language",
        "music", "art", "spirituality", "religion", "philosophy", "parenting", "sports",
        "biography",
    )

internal fun classify(slugs: List<String>): CoverGenre {
    val scores = IntArray(CoverGenre.entries.size)
    val fiction = "fiction" in slugs
    for (slug in slugs) {
        val match = SLUG_GENRES[slug] ?: continue
        val weight = if (fiction && slug in TOPICS) match.second / 2 else match.second
        scores[match.first.ordinal] += weight
    }
    val best = scores.indices.maxByOrNull { scores[it] } ?: return CoverGenre.Literary
    return if (scores[best] == 0) CoverGenre.Literary else CoverGenre.entries[best]
}
