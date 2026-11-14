package dev.tn3w.shelf.data

import kotlinx.serialization.Serializable
import kotlin.math.abs

enum class Gender { Male, Female, Unknown }

enum class Age { Child, Teen, YoungAdult, Adult, Elderly }

@Serializable
data class Character(
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: Gender = Gender.Unknown,
    val age: Age = Age.Adult,
)

data class Utterance(val kind: Kind, val text: String, val start: Int = 0) {
    enum class Kind { Narration, Dialogue, Scare }
}

sealed interface QuoteStyle {
    data object Dash : QuoteStyle

    class Marks(val pattern: Regex) : QuoteStyle
}

data class Voice(val id: String, val gender: Gender, val age: Age)

data class Casting(val voice: Voice, val pitch: Float = 1f)

class Cast(val narrator: Casting, val speakers: Map<Character, Casting>)

private val QUOTE_PAIRS =
    listOf("»«", "«»", "„“", "“”", "””", "‘’", "「」", "『』", "\"\"", "‹›")
private val DASH = Regex("^[—–―]\\s?")
private val DASH_SPLIT = Regex("\\s*[—–―]\\s*")
private val SPOKEN_END = Regex("[.!?…,:。！？、]$")
private val SENTENCE_END = Regex("(?<=[.!?…。！？]) ?")
private val SPACES = Regex("\\s+")
private const val SENTENCE_MARKS = ".!?…。！？"
private val AFTER_SPOKEN = ",.:!?、。".toSet()
private val TAG_OPENERS = ",，、".toSet()
private const val TAG_TRIM = ",;:—–― "
private const val SHORT_QUOTE_LENGTH = 12
private const val SHORT_QUOTE_WORDS = 3
private const val TAG_WORDS = 3
private const val UNCASED_TAG_LENGTH = 12
private const val PITCH_STEP = 0.08f
private const val AGE_PITCH_STEP = 0.07f
private val AGE_LIMITS = listOf(
    11 to Age.Child,
    19 to Age.Teen,
    35 to Age.YoungAdult,
    64 to Age.Adult,
)

private fun words(text: String) = text.trim().split(SPACES).filter(String::isNotEmpty)

private fun narration(text: String, start: Int) =
    Utterance(Utterance.Kind.Narration, text, start)

private fun isCased(text: String) =
    text.firstOrNull()?.let { it.uppercaseChar() != it.lowercaseChar() } == true

private fun quotePattern(pair: String) = Regex("${pair[0]}([^${pair[1]}\\n]+)${pair[1]}")

fun quoteStyleOf(paragraphs: List<String>): QuoteStyle {
    val text = paragraphs.joinToString("\n")
    val patterns = QUOTE_PAIRS.map(::quotePattern)
    val best = patterns.associateWith { it.findAll(text).count() }.maxBy { it.value }
    val dashed = paragraphs.count(DASH::containsMatchIn)
    return if (dashed > best.value) QuoteStyle.Dash else QuoteStyle.Marks(best.key)
}

private fun isSpoken(inside: String, after: String) =
    SPOKEN_END.containsMatchIn(inside) || after.firstOrNull() in AFTER_SPOKEN ||
        inside.length > SHORT_QUOTE_LENGTH ||
        words(inside).size > SHORT_QUOTE_WORDS

private fun dashedUtterances(paragraph: String): List<Utterance> {
    if (!DASH.containsMatchIn(paragraph)) return listOf(narration(paragraph, 0))
    var cursor = 0
    return paragraph.split(DASH_SPLIT).drop(1).mapIndexedNotNull { index, part ->
        val spoken = index % 2 == 0
        val kind = if (spoken) Utterance.Kind.Dialogue else Utterance.Kind.Narration
        val start = paragraph.indexOf(part, cursor).coerceAtLeast(cursor)
        cursor = start + part.length
        part.takeIf(String::isNotEmpty)?.let { Utterance(kind, it, start) }
    }
}

fun utterancesOf(paragraph: String, style: QuoteStyle): List<Utterance> {
    if (style !is QuoteStyle.Marks) return dashedUtterances(paragraph)
    val utterances = mutableListOf<Utterance>()
    var position = 0
    for (match in style.pattern.findAll(paragraph)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (start > position) {
            utterances += narration(paragraph.substring(position, start), position)
        }
        val inside = match.groupValues[1]
        val spoken = isSpoken(inside, paragraph.substring(end))
        val kind = if (spoken) Utterance.Kind.Dialogue else Utterance.Kind.Scare
        utterances += Utterance(kind, inside, start + 1)
        position = end
    }
    if (position < paragraph.length) {
        utterances += narration(paragraph.substring(position), position)
    }
    return utterances
}

class Roster(val characters: List<Character>) {
    private val tokenCounts = characters
        .flatMap { words(it.name).toSet() }
        .groupingBy { it }
        .eachCount()

    fun mentions(character: Character): Set<String> {
        val own = words(character.name).filter { tokenCounts[it] == 1 && it !in TITLES }
        return setOf(character.name) + character.aliases + own
    }

    fun named(text: String) = characters
        .filter { character -> mentions(character).any { mentioned(text, it) } }
        .singleOrNull()

    fun mentioned(text: String, character: Character) =
        mentions(character).any { mentioned(text, it) }

    private fun mentioned(text: String, name: String): Boolean {
        var at = text.indexOf(name)
        while (at >= 0) {
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + name.length)
            if (before?.isLetter() != true && after?.isLetter() != true) return true
            at = text.indexOf(name, at + 1)
        }
        return false
    }
}

private fun tagStart(text: String): String {
    val stripped = text.trimStart { it in TAG_TRIM }
    if (!isCased(stripped)) return stripped.take(UNCASED_TAG_LENGTH)
    return words(stripped).take(TAG_WORDS).joinToString(" ")
}

fun taggedSpeaker(roster: Roster, utterances: List<Utterance>, index: Int): Character? {
    val quote = utterances[index].text.trimEnd()
    val following = utterances.getOrNull(index + 1)?.text.orEmpty()
    val next = following.trimStart().firstOrNull()
    val startsTag = next in TAG_OPENERS || quote.endsWith(",")
    if (following.isNotEmpty() && (startsTag || next?.isUpperCase() != true)) {
        return roster.named(tagStart(following))
    }
    val leading = utterances.getOrNull(index - 1)?.text?.trimEnd().orEmpty()
    if (!leading.endsWith(":")) return null
    return roster.named(leading.split(SENTENCE_END).last())
}

fun ageOf(years: Int) =
    AGE_LIMITS.firstOrNull { years <= it.first }?.second ?: Age.Elderly

private fun ageGap(character: Character, voice: Voice) =
    voice.age.ordinal - character.age.ordinal

private fun pitchFor(uses: Int, character: Character, voice: Voice): Float {
    val step = (uses + 1) / 2
    val direction = if (uses % 2 == 1) 1 else -1
    return 1f + direction * step * PITCH_STEP + ageGap(character, voice) * AGE_PITCH_STEP
}

private fun isWrongGender(character: Character, voice: Voice) =
    character.gender != Gender.Unknown && voice.gender != character.gender

fun castOf(
    characters: List<Character>,
    pool: List<Voice>,
    narrator: Voice = pool.first(),
): Cast {
    val uses = pool.associateWith { 0 }.toMutableMap()
    uses[narrator] = 1
    val speakers = characters.associateWith { character ->
        val voice = pool.minWith(
            compareBy(
                { isWrongGender(character, it) },
                { abs(ageGap(character, it)) + uses.getValue(it) },
                { uses.getValue(it) },
            ),
        )
        val used = uses.getValue(voice)
        uses[voice] = used + 1
        Casting(voice, pitchFor(used, character, voice))
    }
    return Cast(Casting(narrator), speakers)
}

val TITLES = setOf(
    "Mr", "Mrs", "Ms", "Miss", "Dr", "Herr", "Frau", "Sir", "Lady", "Lord", "Madame",
    "Monsieur", "Mademoiselle", "Señor", "Señora", "Signor", "Signora", "Professor",
)
private const val CONTEXT_PARAGRAPHS = 3
private const val PREVIOUS_LENGTH = 400
private const val ANSWER_PREFIX = "{\"answer\": \""
private const val RECENT_QUOTES = 3
private const val QUOTE_PREVIEW = 50
private const val TAG_NAME_WORDS = 4
private const val UNKNOWN = "Unknown"
private val ANSWER = Regex("\"speaker\"\\s*:\\s*\"([^\"]*)\"")
private val GENDER_WORDS = mapOf(
    "male" to Gender.Male, "female" to Gender.Female, "unknown" to Gender.Unknown,
)
private val AGE_WORDS = mapOf(
    "child" to Age.Child, "teen" to Age.Teen, "young adult" to Age.YoungAdult,
    "adult" to Age.Adult, "elderly" to Age.Elderly,
)
private val INSTRUCTION = listOf(
    "Use speech tags, pronouns, who is addressed, and the flow of the conversation",
    "(speakers usually alternate in an untagged exchange; a part after a tag continues",
    "the same speaker). A person addressed by name is not the speaker. Unknown = an",
    "unnamed speaker.",
).joinToString(" ")

interface Judge {
    fun answer(prompt: String): String

    fun choose(prompt: String, options: List<String>, prefix: String = ""): String
}

private fun tagWords(text: String, quote: String): String? {
    val after = text.trimStart()
    val opensTag = after.firstOrNull() in TAG_OPENERS || quote.trimEnd().endsWith(",")
    if (!opensTag) return null
    val clause = words(after.trim { it in TAG_TRIM })
    val end = clause.indexOfFirst(::endsSentence)
    val name = (if (end < 0) clause else clause.take(end + 1)).take(TAG_NAME_WORDS)
        .map { it.trim { char -> !char.isLetter() } }
        .dropWhile { !isCapitalized(it) }
        .takeWhile(::isCapitalized)
    return name.joinToString(" ").takeIf(String::isNotEmpty)
}

private fun endsSentence(word: String) =
    word.last() in SENTENCE_MARKS && word.trimEnd('.') !in TITLES

private fun isCapitalized(word: String) =
    word.firstOrNull()?.isUpperCase() == true && word.all(Char::isLetter)

fun tagNames(paragraphs: List<String>, style: QuoteStyle): Map<String, Int> {
    val found = linkedMapOf<String, Int>()
    paragraphs.forEachIndexed { index, paragraph ->
        val utterances = utterancesOf(paragraph, style)
        utterances.forEachIndexed { at, utterance ->
            val next = utterances.getOrNull(at + 1)?.text
            if (utterance.kind == Utterance.Kind.Dialogue && next != null) {
                tagWords(next, utterance.text)?.let { found.putIfAbsent(it, index) }
            }
        }
    }
    return found.filterKeys { name -> !(name.endsWith("s") && name.dropLast(1) in found) }
}

private fun coreOf(name: String) = words(name).toSet() - TITLES

fun mergedNames(names: Collection<String>): List<List<String>> {
    val groups = mutableListOf<MutableList<String>>()
    for (name in names.sortedByDescending { coreOf(it).size }) {
        val core = coreOf(name)
        val group = groups.firstOrNull {
            core.isNotEmpty() && coreOf(it.first()).containsAll(core)
        }
        if (group != null) group += name else groups += mutableListOf(name)
    }
    return groups
}

private fun describe(character: Character): String {
    val gender = GENDER_WORDS.entries.first { it.value == character.gender }.key
    val age = AGE_WORDS.entries.first { it.value == character.age }.key
    return "- ${character.name}: $gender, $age"
}

internal fun recentText(paragraphs: List<String>): String {
    val text = paragraphs.joinToString("\n")
    if (text.length <= PREVIOUS_LENGTH) return text
    val tail = text.takeLast(PREVIOUS_LENGTH)
    return tail.substring(tail.indexOfFirst(Char::isWhitespace) + 1)
}

fun speakerPrompt(
    candidates: List<Character>,
    previous: List<String>,
    recent: List<Pair<String, String>>,
    target: String,
    part: Int,
): String {
    val lines = candidates.joinToString("\n", transform = ::describe)
    val last = recent.joinToString("\n") { (name, quote) ->
        "$name: ⟦${quote.take(QUOTE_PREVIEW)}⟧"
    }
    return "Task: find who speaks a quote in a novel.\nCharacters:\n$lines\n\n" +
        "Previous paragraphs:\n${recentText(previous)}\n\n" +
        "Last quotes, oldest first:\n$last\n\n" +
        "Target paragraph, spoken words wrapped in ⟦ ⟧:\n$target\n\n" +
        "Who speaks the ⟦ ⟧ part number $part? $INSTRUCTION"
}

fun answeredSpeaker(reply: String) = ANSWER.find(reply)?.groupValues?.get(1)

fun markedParagraph(paragraph: String, utterances: List<Utterance>): String {
    val marked = StringBuilder()
    var position = 0
    for (utterance in utterances.filter { it.kind == Utterance.Kind.Dialogue }) {
        val end = utterance.start + utterance.text.length
        marked.append(paragraph, position, (utterance.start - 1).coerceAtLeast(position))
        marked.append('⟦').append(utterance.text).append('⟧')
        position = (end + 1).coerceAtMost(paragraph.length)
    }
    return marked.append(paragraph.substring(position)).toString()
}

class Attributor(
    private val judge: Judge,
    private val style: QuoteStyle,
    val characters: MutableList<Character> = mutableListOf(),
    val checked: MutableSet<String> = mutableSetOf(),
) {
    private val recent = ArrayDeque<Pair<String, String>>()

    fun learn(paragraphs: List<String>) {
        val found = tagNames(paragraphs, style).filterKeys { it !in checked }
        checked += found.keys
        val people = found.mapNotNull { (name, index) ->
            val context = paragraphs.subList((index - 2).coerceAtLeast(0), index + 1)
                .joinToString("\n")
            if (isPerson(name, context)) profiled(name, context) else null
        }.associateBy { it.name }
        for (group in mergedNames(people.keys)) {
            val core = coreOf(group[0])
            val known = characters.indexOfFirst { coreOf(it.name).containsAll(core) }
            if (known < 0) {
                characters += people.getValue(group[0]).copy(aliases = group.drop(1))
                continue
            }
            val character = characters[known]
            characters[known] = character.copy(aliases = character.aliases + group)
        }
    }

    fun speakers(paragraphs: List<String>, index: Int): List<String?> {
        val paragraph = paragraphs[index]
        val utterances = utterancesOf(paragraph, style)
        val roster = Roster(characters)
        val target = markedParagraph(paragraph, utterances)
        var part = 0
        return utterances.mapIndexed { at, utterance ->
            if (utterance.kind != Utterance.Kind.Dialogue) return@mapIndexed null
            part++
            val name = taggedSpeaker(roster, utterances, at)?.name
                ?: ask(roster, paragraphs, index, utterance.text, target, part)
            recent.addLast(name to utterance.text)
            if (recent.size > RECENT_QUOTES) recent.removeFirst()
            name
        }
    }

    private fun ask(
        roster: Roster,
        paragraphs: List<String>,
        index: Int,
        quote: String,
        target: String,
        part: Int,
    ): String {
        val first = (index - CONTEXT_PARAGRAPHS).coerceAtLeast(0)
        val previous = paragraphs.subList(first, index)
        val nearby = (previous + paragraphs[index]).joinToString("\n")
        val speakers = recent.map { it.first }.toSet()
        val candidates = characters.filter { character ->
            (roster.mentioned(nearby, character) || character.name in speakers) &&
                !roster.mentioned(quote, character)
        }
        val prompt = speakerPrompt(candidates, previous, recent.toList(), target, part)
        val name = answeredSpeaker(judge.answer(prompt)) ?: return UNKNOWN
        val character = characters.firstOrNull { name == it.name || name in it.aliases }
        return character?.name ?: UNKNOWN
    }

    private fun isPerson(name: String, context: String) = judge.choose(
        "Text from a novel:\n$context\n\nIs \"$name\" the name of a person or " +
            "character in this text? Answer yes or no.",
        listOf("yes", "no"),
    ) == "yes"

    private fun profiled(name: String, context: String): Character {
        val gender = judge.choose(
            "Text from a novel:\n$context\n\nIs $name male or female? Answer male, " +
                "female or unknown.",
            GENDER_WORDS.keys.toList(),
            ANSWER_PREFIX,
        )
        val age = judge.choose(
            "Text from a novel:\n$context\n\nHow old is $name? Answer child, teen, " +
                "young adult, adult or elderly.",
            AGE_WORDS.keys.toList(),
            ANSWER_PREFIX,
        )
        val known = GENDER_WORDS.getValue(gender)
        return Character(name, gender = known, age = AGE_WORDS.getValue(age))
    }
}

data class Spoken(val text: String, val speaker: String?)

fun segmentsOf(
    paragraph: String,
    utterances: List<Utterance>,
    speakers: List<String?>,
    start: Int,
    end: Int,
): List<Spoken> {
    val marks = utterances.mapIndexed { index, utterance ->
        val spoken = utterance.kind == Utterance.Kind.Dialogue
        val speaker = if (spoken) speakers.getOrNull(index) ?: UNKNOWN else null
        (if (index == 0) 0 else utterance.start) to speaker
    }.ifEmpty { listOf(0 to null) }
    val segments = mutableListOf<Spoken>()
    marks.forEachIndexed { index, (from, speaker) ->
        val until = marks.getOrNull(index + 1)?.first ?: paragraph.length
        val first = maxOf(from, start)
        val last = minOf(until, end, paragraph.length)
        if (first >= last) return@forEachIndexed
        val text = paragraph.substring(first, last)
        val previous = segments.lastOrNull()
        val spoken = text.any(Char::isLetterOrDigit)
        val silent = previous?.text?.none(Char::isLetterOrDigit) == true
        if (previous == null || (previous.speaker != speaker && spoken && !silent)) {
            segments += Spoken(text, speaker)
            return@forEachIndexed
        }
        val owner = if (spoken) speaker else previous.speaker
        segments[segments.lastIndex] = Spoken(previous.text + text, owner)
    }
    return segments
}

const val UNKNOWN_SPEAKER = UNKNOWN
