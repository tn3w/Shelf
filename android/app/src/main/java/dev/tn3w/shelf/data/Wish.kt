package dev.tn3w.shelf.data

private const val STEM_MIN = 4
private const val STEM_SLACK = 2
private const val SUFFIX_MAX = 4
private const val PICTURE_BOOK_AGE = 6
private const val CHILDRENS_AGE = 9
private const val MIDDLE_GRADE_AGE = 13
private const val YOUNG_ADULT_AGE = 18

private val NEGATIONS = setOf(
    "no", "not", "without", "ohne", "kein", "keine", "keinen", "sans", "pas", "sin",
    "sem", "senza", "bez", "utan", "ei", "geen", "без", "не",
)

private val YEARS = setOf(
    "year", "years", "yo", "jahr", "jahre", "jahrig", "jahrige", "jahrigen", "ans",
    "ano", "anos", "anni", "ar", "lat", "jaar", "let", "лет", "года", "歳",
)

private val READERS = mapOf(
    "picture-book" to "toddler toddlers baby babies preschool preschooler kleinkind " +
        "kleinkinder kita peuter bebe bebes neonati smabarn maluchy 幼児 малышей",
    "childrens" to "kid kids child children son daughter boy girl boys girls kinder " +
        "kind sohn tochter kinderen enfant enfants fils fille nino ninos nina ninas " +
        "hijo hija bambini bambino bambina figlio figlia criancas crianca filho filha " +
        "barn dzieci dziecka syn corka lapsille lapsi lapset deti детей ребенка " +
        "子供 아이",
    "middle-grade" to "tween tweens preteen preteens schoolers",
    "young-adult" to "teen teens teenager teenagers teenage jugendliche jugendlichen " +
        "jongeren ados ado adolescent adolescents adolescentes adolescenti juvenil " +
        "ragazzi jovens ungdom ungdomar mlodziezy mlodziez nuorille nuoret mladez " +
        "подростков подростки 청소년",
).flatMap { (slug, words) -> words.split(" ").flatMap(::tokenize).map { it to slug } }
    .toMap()

private val SYNONYMS = mapOf(
    "humor" to "funny, fun, light, lighthearted, feel good, hilarious, comedy, lustig, " +
        "witzig, heiter, drole, amusant, divertido, gracioso, divertente, engracado, " +
        "rolig, zabawny, смешной",
    "vacations" to "beach, holiday, holidays, vacation, summer, summer read, strand, " +
        "urlaub, sommer, plage, vacances, playa, vacaciones, spiaggia, vacanze, praia, " +
        "ferias, semester, plaza, wakacje, пляж, отпуск",
    "horror" to "scary, spooky, creepy, terrifying, gruselig, unheimlich, effrayant, " +
        "miedo, paura, skrammande, straszny, страшный",
    "crime" to "krimi, krimis, polar, giallo, gialli, deckare, kryminal, детектив",
    "romance" to "love story, love stories, liebesgeschichte, histoire damour, " +
        "historia de amor, storia damore",
    "science-fiction" to "scifi, sci fi",
    "thriller" to "suspense, page turner",
    "world-war-2" to "ww2, wwii, second world war, zweiter weltkrieg",
    "mental-illness" to "anxiety, depression, angst",
    "programming" to "coding, python, javascript",
)

private class TagTerm(val tag: Tag, val words: List<String>, val loose: Boolean)

private class Found(val tag: Tag, val start: Int, val end: Int, val exact: Boolean) {
    val size get() = end - start

    fun beats(other: Found): Boolean {
        val inside = start <= other.start && other.end <= end
        return inside && (size > other.size || (exact && !other.exact))
    }
}

private fun singular(word: String) = when {
    word.endsWith("ies") -> word.dropLast(3) + "y"

    word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") ->
        word.dropLast(2)

    word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)

    else -> word
}

private fun inflects(term: String, word: String): Boolean {
    if (term == word) return true
    if (term.length < STEM_MIN || word.length < STEM_MIN) return false
    val shared = term.commonPrefixWith(word).length
    return shared >= maxOf(STEM_MIN, term.length - STEM_SLACK) &&
        word.length - shared <= SUFFIX_MAX
}

private fun TagTerm.matches(word: String, term: String) =
    if (loose) inflects(term, word) else singular(term) == singular(word)

private fun termsOf(tags: List<Tag>) = tags.flatMap { tag ->
    val synonyms = SYNONYMS[tag.slug]?.split(", ").orEmpty()
    val strict = synonyms + tag.slug.replace('-', ' ')
    (
        listOf(TagTerm(tag, tokenize(tag.label), loose = true)) +
            strict.map { TagTerm(tag, tokenize(it), loose = false) }
        )
        .filter { it.words.isNotEmpty() }
}

private fun found(words: List<String>, terms: List<TagTerm>) = terms.flatMap { term ->
    (0..words.size - term.words.size).filter { start ->
        term.words.indices.all { term.matches(words[start + it], term.words[it]) }
    }.map { start ->
        val exact =
            !term.loose || term.words.indices.all { words[start + it] == term.words[it] }
        Found(term.tag, start, start + term.words.size, exact)
    }
}

private fun readerOfAge(age: Int) = when {
    age < PICTURE_BOOK_AGE -> "picture-book"
    age < CHILDRENS_AGE -> "childrens"
    age < MIDDLE_GRADE_AGE -> "middle-grade"
    age < YOUNG_ADULT_AGE -> "young-adult"
    else -> null
}

private fun readerOf(words: List<String>): String? {
    val age = words.zipWithNext().firstNotNullOfOrNull { (number, unit) ->
        number.toIntOrNull()?.takeIf { unit in YEARS }
    }
    if (age != null) return readerOfAge(age)
    val readers = words.mapNotNull(READERS::get).toSet()
    return readers.firstOrNull { it != "childrens" } ?: readers.firstOrNull()
}

data class Wish(val tags: List<Tag>, val excluded: List<Tag>)

/** The catalogue tags a wish like "cozy witches, no romance" asks for or rules out. */
fun wishOf(query: String, tags: List<Tag>): Wish {
    val words = tokenize(query)
    val all = found(words, termsOf(tags))
    val (negated, named) = all.filter { match -> all.none { it.beats(match) } }
        .sortedBy { it.start }
        .partition { words.getOrNull(it.start - 1) in NEGATIONS }
    val excluded = negated.map { it.tag }.distinctBy { it.id }
    val wanted = named.map { it.tag }.distinctBy { it.id }.filter { it !in excluded }
    val reader = readerOf(words)?.let { slug -> tags.firstOrNull { it.slug == slug } }
        ?: return Wish(wanted, excluded)
    return Wish(listOf(reader) + wanted.filter { it.slug !in AUDIENCE_TAGS }, excluded)
}
