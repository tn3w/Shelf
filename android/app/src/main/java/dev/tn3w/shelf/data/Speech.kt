package dev.tn3w.shelf.data

class Passage(val text: String, val section: Int, val offset: Int)

private class Piece(
    val text: String,
    val heading: Int = 0,
    val section: Int = 0,
    val offset: Int = 0,
)

private enum class Kind { Front, Back, Body, Unknown, Preamble, Repeat }

private class Part(val title: Piece?, val pieces: MutableList<Piece>) {
    val characters get() = pieces.sumOf { it.text.length }
    val content get() = pieces.drop(if (title == null) 0 else 1).sumOf { it.text.length }
}

private const val TITLE_LENGTH = 70
private const val MARKER_LENGTH = 200
private const val MIN_BODY_CHARACTERS = 600
private const val MIN_TITLED_CHARACTERS = 150
private const val MAX_SPEECH_CHARACTERS = 3500
private const val GARBAGE_LENGTH = 30
private const val GARBAGE_RATIO = 0.4
private const val REPEATED_HEADER = 5
private const val BOILERPLATE_RATIO = 0.3
private const val SAMPLE_PASSAGES = 300
private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

private fun pattern(source: String) = Regex(source, OPTIONS)

private const val SOURCE =
    "(project gutenberg\\w*|e-?book|text|book|document|file|edition|work|etext)"
private const val BANNER = "^\\W{0,3}(?:[*=#~_-]{3,}\\s*)"
private val START_MARKER = pattern(
    "$BANNER[^\\n]{0,80}\\b(start|begin\\w*)\\b[^\\n]{0,120}" +
        "|^\\W{0,3}(start|begin\\w*) of (the |this |these )?(full )?$SOURCE\\b.{0,120}$",
)
private val END_MARKER = pattern(
    "$BANNER[^\\n]{0,80}\\bend\\b[^\\n]{0,120}" +
        "|^\\W{0,3}end of (the |this |these )?(full )?$SOURCE\\b.{0,120}$",
)

private const val FRONT_TITLES =
    "cover|title\\s*page|half\\s*title|haupttitel|titel(blatt|seite)?|titre|titolo|" +
        "table of contents|contents|inhalt\\w*|sommaire|table des mati[eè]res|" +
        "[ií]ndice|sommario|indice|copyright\\w*|impressum|imprint|colophon|legal|" +
        "licen[sc]e\\w*|dedication|dedicat\\w+|widmung|d[eé]dicace|dedicatoria|dedica|" +
        "epigraph\\w*|preface|foreword|vorwort|avant-propos|pr[eé]face|" +
        "list of illustrations|illustrations|characters|dramatis personae|maps?|" +
        "praise for.*|by the same author|also by.*|other books.*|books by.*|" +
        "frontispiece|publisher'?s? note|note on the text|" +
        "about (the|this) (book|ebook)|[uü]ber dieses buch|project gutenberg.*"
private const val BACK_TITLES =
    "acknowledg\\w+|danksagung\\w*|remerciements|agradecimientos|ringraziamenti|" +
        "author'?s? bio\\w*|biograph\\w+|more from.*|notes?|notes? (on|to) .*|" +
        "footnotes|endnotes|anmerkungen|bibliograph\\w+|glossary|glossar|index|" +
        "transcriber'?s? notes?|leseprobe\\w*|excerpt|sneak peek|newsletter|" +
        "reading group.*|discussion questions|credits|permissions|sources|" +
        "appendix|anhang|([uü]ber|about|sobre|[àa] propos|acerca) .*"
private const val NUMBER =
    "(\\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|" +
        "twelve|first|second|third|eins|zwei|drei|un|une|deux|trois|uno|dos|tres)"
private const val BODY_TITLES =
    "prologue|prolog|pr[eé]lude|epilogue|epilog|[eé]pilogue|afterword|nachwort|" +
        "introduction|einleitung|vorher|danach|interlude|intermezzo|" +
        "(chapter|kapitel|chapitre|cap[ií]tulo|capitolo|part|teil|partie|parte|book|" +
        "buch|livre|libro|volume|band|act|akt|scene|section|letter|canto|stave)" +
        "\\W*$NUMBER\\b.*"
private val FRONT = pattern("^(?:$FRONT_TITLES)$")
private val BACK = pattern("^(?:$BACK_TITLES)$")
private val BODY = pattern("^(?:$BODY_TITLES)$")
private val NUMBERED = pattern("^(?:(\\d+|[ivxlcdm]+)[.:)]\\s.*|$NUMBER\\b.*)$")
private val BARE_NUMBER = Regex(
    "^(\\d+|M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3}))[.:)]?$",
)
private val LEADING_CHAPTER = Regex(
    "^(?:\\S+ ){0,2}(?:CHAPTER|KAPITEL|CHAPITRE|CAP[IÍ]TULO|CAPITOLO) " +
        "([IVXLCDM]+|\\d+)\\b",
)
private val SENTENCE_END = Regex("[.!?…:;\"”»’')]$")
private val NOISE = pattern(
    "^(\\[(illustration|image|footnote|cover)[^\\]]*]?|\\d+|[ivxlc]+|page\\s+\\d+|" +
        "\\*+|[-–—_=~. ]{3,}|(the )?end\\.?|finis\\.?|ende\\.?|fin\\.?)$",
)
private val INLINE_NOTE =
    pattern("\\[(\\d{1,3}|[a-z])]|\\[Illustration[^\\]]*]|\\{[\\divxlc]+}")
private val BOILERPLATE = pattern(
    "all rights reserved|alle rechte vorbehalten|\\bisbn\\b|©|\\(c\\)\\s*\\d{4}|" +
        "printed in|erschienen bei|published by|project gutenberg|first published|" +
        "tous droits r[eé]serv[eé]s|todos los derechos|diritti riservati",
)
private val WHITESPACE = Regex("\\s+")
private val EDGE_PUNCTUATION = Regex("^[\\s*_#]+|[\\s.:!*_#]+$")
private val BRACKETS = Regex("^\\[|]$")
private val CLOSING_ONLY = Regex("^([^\\[]*)\\s*]$")
private val DIGITS = Regex("\\d+")
private val NON_LETTERS = Regex("[^\\p{L}]")

private fun squash(text: String) = text.replace(' ', ' ').replace(WHITESPACE, " ").trim()

private fun kindOf(piece: Piece): Kind? {
    if (LEADING_CHAPTER.containsMatchIn(piece.text)) return Kind.Body
    val text = piece.text.replace(EDGE_PUNCTUATION, "").replace(BRACKETS, "").trim()
    return when {
        text.isEmpty() || text.length > TITLE_LENGTH -> null
        BODY.matches(text) || BARE_NUMBER.matches(text) -> Kind.Body
        piece.heading > 0 && NUMBERED.matches(text) -> Kind.Body
        FRONT.matches(text) -> Kind.Front
        BACK.matches(text) -> Kind.Back
        piece.heading > 0 -> Kind.Unknown
        else -> null
    }
}

private fun cut(pieces: List<Piece>, marker: Regex, from: Boolean): List<Piece> {
    val hits = pieces.indices.filter {
        pieces[it].text.length <= MARKER_LENGTH && marker.containsMatchIn(pieces[it].text)
    }
    val index = (if (from) hits.firstOrNull() else hits.lastOrNull()) ?: return pieces
    return when {
        from && index < pieces.size * 0.5 -> pieces.drop(index + 1)
        !from && index >= pieces.size * 0.5 -> pieces.take(index)
        else -> pieces
    }
}

private fun isGarbage(text: String): Boolean {
    val words = text.split(' ')
    val long = words.count { it.replace(NON_LETTERS, "").length >= 3 }
    return text.length >= GARBAGE_LENGTH && long.toDouble() / words.size < GARBAGE_RATIO
}

private fun tidy(pieces: List<Piece>) = pieces.mapNotNull { piece ->
    val text = squash(INLINE_NOTE.replace(piece.text.replace("­", ""), ""))
    val numbered = piece.heading > 0 && BARE_NUMBER.matches(text)
    val speakable = text.count(Char::isLetter) > 1 && !NOISE.matches(text)
    val keep = numbered || (speakable && !isGarbage(text))
    if (keep) Piece(text, piece.heading, piece.section, piece.offset) else null
}

private fun withoutRunningHeaders(pieces: List<Piece>): List<Piece> {
    fun key(piece: Piece) = piece.text.lowercase().replace(DIGITS, "#")
    fun short(piece: Piece) = piece.text.length <= TITLE_LENGTH &&
        kindOf(piece) != Kind.Body && !SENTENCE_END.containsMatchIn(piece.text)
    val counts = pieces.filter(::short).groupingBy(::key).eachCount()
    val repeated = counts.filter { it.value >= REPEATED_HEADER }.keys
        .filterNot { key -> key.all { it in "#. " } }.toSet()
    return pieces.filter {
        key(it) !in repeated || SENTENCE_END.containsMatchIn(it.text)
    }
}

private fun partsOf(pieces: List<Piece>): List<Part> {
    val parts = mutableListOf(Part(null, mutableListOf()))
    for (piece in pieces) {
        if (kindOf(piece) != null) {
            parts += Part(piece, mutableListOf(piece))
        } else {
            parts.last().pieces += piece
        }
    }
    return parts.filter { it.pieces.isNotEmpty() }
}

private fun isContents(body: List<Piece>, titles: Set<String>): Boolean {
    if (body.size < 3) return false
    val listed = body.count { it.text.lowercase() in titles || it.text.length < 40 }
    return listed.toDouble() / body.size > 0.8 && body.sumOf { it.text.length } < 3000
}

private fun kindsOf(parts: List<Part>): MutableList<Kind> {
    val titles = parts.mapNotNull { it.title?.text?.lowercase() }.toSet()
    val seen = mutableSetOf<String>()
    return parts.map { part ->
        val title = part.title ?: return@map Kind.Preamble
        val kind = when {
            isContents(part.pieces.drop(1), titles) -> Kind.Front
            else -> kindOf(title) ?: Kind.Unknown
        }
        val key = title.text.lowercase()
        val repeated = key in seen && kind == Kind.Body
        if (part.content >= MIN_TITLED_CHARACTERS) seen += key
        if (repeated) Kind.Repeat else kind
    }.toMutableList()
}

private fun isBody(kind: Kind, part: Part, titled: Boolean): Boolean {
    if (kind == Kind.Front || kind == Kind.Back || kind == Kind.Repeat) return false
    if (titled) return kind == Kind.Body && part.content >= MIN_TITLED_CHARACTERS
    val boilerplate = part.pieces.count { BOILERPLATE.containsMatchIn(it.text) }
    return part.content >= MIN_BODY_CHARACTERS &&
        boilerplate.toDouble() / part.pieces.size < BOILERPLATE_RATIO
}

private fun isMain(kind: Kind, part: Part) = when (kind) {
    Kind.Body -> part.content >= MIN_TITLED_CHARACTERS
    Kind.Unknown, Kind.Preamble -> part.content >= MIN_BODY_CHARACTERS
    else -> false
}

private fun backMatterStart(parts: List<Part>, kinds: List<Kind>, first: Int): Int {
    val total = parts.sumOf { it.characters }
    var passed = 0
    for ((index, part) in parts.withIndex()) {
        val matter = kinds[index] == Kind.Front || kinds[index] == Kind.Back
        if (index > first && matter && passed > total / 2) return index
        passed += part.characters
    }
    return parts.size
}

private fun trimmed(parts: List<Part>): List<Part> {
    val kinds = kindsOf(parts)
    val titled = parts.indices.any { isBody(kinds[it], parts[it], true) }
    val first = parts.indices.firstOrNull { isBody(kinds[it], parts[it], titled) }
        ?: return parts
    val main = parts.indices.lastOrNull { isMain(kinds[it], parts[it]) } ?: first
    val last = minOf(main, backMatterStart(parts, kinds, first) - 1)
    return parts.subList(first, maxOf(first, last) + 1)
}

private fun isColophon(text: String) =
    NOISE.matches(text) || BOILERPLATE.containsMatchIn(text) || text == text.uppercase()

private fun spokenPieces(pieces: List<Piece>): List<Piece> {
    val bounded = cut(cut(tidy(pieces), START_MARKER, true), END_MARKER, false)
    val tidied = tidy(withoutRunningHeaders(bounded))
    val body = trimmed(partsOf(tidied)).flatMap { it.pieces }
    return body.dropLastWhile { isColophon(it.text) }
}

private fun piecesOf(document: TextDocument) =
    document.sections.flatMapIndexed { section, blocks ->
        val starts = blockStarts(blocks)
        blocks.mapIndexedNotNull { index, block ->
            if (block.image != null) {
                null
            } else {
                Piece(block.text, block.heading, section, starts[index])
            }
        }
    }

fun speechOf(document: TextDocument) = passagesOf(spokenPieces(piecesOf(document)))

fun speechOf(pages: List<String>) = passagesOf(spokenPieces(pagePieces(pages)))

private val ABBREVIATION = Regex(
    "(\\b(Mr|Mrs|Ms|Dr|St|Prof|Jr|Sr|vs|Mme|Mlle|Hr|Fr|Nr|No|etc|pp?)|\\b\\p{Lu})\\.$",
)
private val SENTENCE_BREAK =
    Regex("(?<=[.!?…][”\"’»)]{0,2})\\s+(?=[\\p{Lu}“\"‘»(¿¡])")

internal fun sentences(text: String): List<String> {
    val merged = mutableListOf<String>()
    for (sentence in text.split(SENTENCE_BREAK)) {
        val previous = merged.lastOrNull()
        if (previous != null && ABBREVIATION.containsMatchIn(previous)) {
            merged[merged.lastIndex] = "$previous $sentence"
        } else {
            merged += sentence
        }
    }
    return merged.flatMap { it.chunked(MAX_SPEECH_CHARACTERS) }
}

private fun passagesOf(pieces: List<Piece>) = pieces.flatMap { piece ->
    var searchFrom = 0
    sentences(piece.text).map { sentence ->
        val at = piece.text.indexOf(sentence, searchFrom).coerceAtLeast(searchFrom)
        searchFrom = at + sentence.length
        Passage(sentence, piece.section, piece.offset + at)
    }
}

private fun pagePieces(pages: List<String>): List<Piece> {
    val linesPerPage = pages.map { page ->
        page.lines().map(String::trim).filter(String::isNotEmpty)
    }
    val running = runningLines(linesPerPage)
    return linesPerPage.flatMapIndexed { index, lines ->
        val kept = lines.filter { it.lowercase().replace(DIGITS, "#") !in running }
        paragraphs(kept).map { Piece(it, section = index) }
    }
}

private fun runningLines(linesPerPage: List<List<String>>): Set<String> {
    val edges = linesPerPage.flatMap { lines ->
        (lines.take(2) + lines.takeLast(2)).toSet().map {
            it.lowercase().replace(DIGITS, "#")
        }
    }
    val threshold = maxOf(REPEATED_HEADER, linesPerPage.size / 20)
    return edges.groupingBy { it }.eachCount().filter { it.value >= threshold }.keys
}

private fun isChapterLine(line: String) =
    LEADING_CHAPTER.containsMatchIn(line) || BODY.matches(line.trim('.', ':', ' '))

private fun paragraphs(lines: List<String>): List<String> {
    if (lines.isEmpty()) return emptyList()
    val full = lines.map { it.length }.sorted()[(lines.size * 0.8).toInt()]
    val blocks = mutableListOf(mutableListOf<String>())
    for (line in lines) {
        val previous = blocks.last().lastOrNull()
        val ends = previous != null && SENTENCE_END.containsMatchIn(previous) &&
            previous.length < full * 0.75
        if (previous != null &&
            (ends || isChapterLine(line) || isChapterLine(previous))
        ) {
            blocks += mutableListOf<String>()
        }
        blocks.last() += line
    }
    return blocks.map { it.joinToString(" ").replace(Regex("-\\s+(?=\\p{Ll})"), "") }
}

private val STOPWORDS = mapOf(
    "en" to "the and of to was that with for his her you not had they".split(' '),
    "de" to "der die und das ist nicht mit sich ein er sie ich zu den auf".split(' '),
    "fr" to "le la les des est une que pas vous qui dans il elle pour et".split(' '),
    "es" to "el los las que una por con para como pero sus del se es".split(' '),
    "it" to "il che di non una per sono con gli le del della era si ma".split(' '),
    "pt" to "que não uma para com os das dos como mais mas ele ela foi seu".split(' '),
    "sv" to "och att det som en jag på är med för inte han hon den av".split(' '),
    "pl" to "nie się na że jest to jak ale do czy tak już po tylko".split(' '),
    "cs" to "je se na že to byl jsem jako ale do tak už pro který".split(' '),
    "fi" to "ja oli että ei hän se on mutta kun niin jo vain kuin tai".split(' '),
    "et" to "ja on ei see oli et kui aga mis ta nii ka veel siis".split(' '),
    "lv" to "un ir ka tas es viņš viņa bet kā ar uz par nav arī bija".split(' '),
    "ru" to "и в не на что он она с как но это все был его".split(' '),
)
private val SCRIPTS = mapOf(
    "ja" to Regex("[\\u3040-\\u30ff]"),
    "ko" to Regex("[\\uac00-\\ud7af]"),
    "ru" to Regex("[\\u0400-\\u04ff]"),
)
private val WORD = Regex("\\p{L}+")

fun languageOf(passages: List<Passage>): String? {
    val sample = passages.take(SAMPLE_PASSAGES).joinToString(" ") { it.text }
    SCRIPTS.forEach { (language, script) ->
        if (script.findAll(sample).count() > sample.length / 10) return language
    }
    val words = WORD.findAll(sample.lowercase()).map { it.value }.toList()
    val scores = STOPWORDS.mapValues { (_, common) -> words.count { it in common } }
    return scores.maxByOrNull { it.value }?.takeIf { it.value > words.size / 50 }?.key
}

sealed interface Sleep {
    data object Off : Sleep

    data class After(val minutes: Int) : Sleep

    data object ChapterEnd : Sleep
}

val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)
val SPEECH_RATES = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val FADE_MILLIS = 20_000f
private const val MIN_VOLUME = 0.1f
private const val CHARACTERS_PER_SECOND = 15f

fun fadeVolume(leftMillis: Long) = (leftMillis / FADE_MILLIS).coerceIn(MIN_VOLUME, 1f)

fun secondsLeft(passages: List<Passage>, from: Int, rate: Float) = (
    passages.drop(from).sumOf {
        it.text.length + 1
    } / (CHARACTERS_PER_SECOND * rate)
    ).toLong()

fun chapterStarts(passages: List<Passage>, chapters: List<Chapter>) = chapters
    .map { chapter -> chapter to passages.indexOfFirst { it.section >= chapter.section } }
    .filter { it.second >= 0 }
    .distinctBy { it.second }
