package dev.tn3w.shelf.data

const val MIN_QUERY = 2
const val MAX_MATCHES = 500
private const val CONTEXT_BEFORE = 36
private const val CONTEXT_AFTER = 90
private val SPACES = Regex("""\s+""")

data class Match(
    val section: Int,
    val offset: Int,
    val before: String,
    val text: String,
    val after: String,
)

internal fun blockStarts(blocks: List<Block>): List<Int> {
    var run = 0
    var chars = 0
    var count = 0
    return blocks.map { block ->
        if (block.image == null) {
            (run + chars).also {
                chars += block.text.length
                count++
            }
        } else {
            run += chars + count
            chars = 0
            count = 0
            run.also { run++ }
        }
    }
}

internal fun snippetBefore(text: String, end: Int): String {
    val start = (end - CONTEXT_BEFORE).coerceAtLeast(0)
    val clipped = text.substring(start, end)
    if (start == 0) return clipped.replace(SPACES, " ").trimStart()
    return "..." + clipped.substringAfter(' ', clipped).replace(SPACES, " ").trimStart()
}

internal fun snippetAfter(text: String, start: Int): String {
    val end = (start + CONTEXT_AFTER).coerceAtMost(text.length)
    val clipped = text.substring(start, end)
    if (end == text.length) return clipped.replace(SPACES, " ").trimEnd()
    return clipped.substringBeforeLast(' ', clipped).replace(SPACES, " ").trimEnd() +
        "..."
}

fun TextDocument.find(query: String, limit: Int = MAX_MATCHES): List<Match> {
    val needle = query.trim()
    if (needle.length < MIN_QUERY) return emptyList()
    val matches = mutableListOf<Match>()
    sections.forEachIndexed { section, blocks ->
        blockStarts(blocks).forEachIndexed { index, blockStart ->
            val text = blocks[index].text
            var at = text.indexOf(needle, ignoreCase = true)
            while (at >= 0) {
                val end = at + needle.length
                matches += Match(
                    section,
                    blockStart + at,
                    snippetBefore(text, at),
                    text.substring(at, end),
                    snippetAfter(text, end),
                )
                if (matches.size >= limit) return matches
                at = text.indexOf(needle, end, ignoreCase = true)
            }
        }
    }
    return matches
}

fun matchRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.length < MIN_QUERY) return emptyList()
    return generateSequence(text.indexOf(needle, ignoreCase = true)) {
        text.indexOf(needle, it + needle.length, ignoreCase = true)
    }.takeWhile { it >= 0 }.map { it until it + needle.length }.toList()
}

fun excerptOf(text: CharSequence, length: Int = 120): String {
    val flat = text.toString().replace(SPACES, " ").trim()
    if (flat.length <= length) return flat
    return flat.take(length).substringBeforeLast(' ').trimEnd() + "..."
}
