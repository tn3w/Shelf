package dev.tn3w.shelf.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import java.util.regex.Pattern

private const val MAX_HEADER_BYTES = 256L * 1024 * 1024
private const val TYPE_STRING = 8
private const val TYPE_ARRAY = 9
private const val CONTROL = 3
private const val USER_DEFINED = 4
private val VALUE_SIZES = intArrayOf(1, 1, 2, 2, 4, 4, 4, 1, 0, 0, 8, 8, 8)
private const val SPACE_CLASS = "\\s\\p{Z}"
private val PIECES = Pattern.compile(
    "(?:'[sS]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD])|" +
        "[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}|" +
        " ?[^${SPACE_CLASS}\\p{L}\\p{N}]+[\\r\\n]*|" +
        "[$SPACE_CLASS]*[\\r\\n]+|[$SPACE_CLASS]+(?![^$SPACE_CLASS])|[$SPACE_CLASS]+",
)

private class GgufReader(private val buffer: ByteBuffer) {
    fun string(): String {
        val bytes = ByteArray(buffer.long.toInt())
        buffer.get(bytes)
        return bytes.decodeToString()
    }

    fun value(type: Int): Any? = when (type) {
        TYPE_STRING -> string()
        TYPE_ARRAY -> array()
        4 -> buffer.int
        5 -> buffer.int
        6 -> buffer.float
        else -> null.also { buffer.position(buffer.position() + VALUE_SIZES[type]) }
    }

    private fun array(): List<Any?> {
        val type = buffer.int
        return List(buffer.long.toInt()) { value(type) }
    }
}

fun ggufMetadata(file: File): Map<String, Any?> =
    RandomAccessFile(file, "r").use { input ->
        val size = minOf(input.length(), MAX_HEADER_BYTES)
        val buffer = input.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            .order(ByteOrder.LITTLE_ENDIAN)
        check(buffer.int == 0x46554747) { "${file.name} is not GGUF" }
        buffer.int
        buffer.long
        val reader = GgufReader(buffer)
        List(buffer.long.toInt()) { reader.string() to reader.value(buffer.int) }.toMap()
    }

private val BYTE_CHARACTERS: CharArray = run {
    val printable = (33..126) + (161..172) + (174..255)
    var next = 256
    CharArray(256) { byte -> if (byte in printable) byte.toChar() else (next++).toChar() }
}
private val CHARACTER_BYTES = BYTE_CHARACTERS.withIndex()
    .associate { (byte, char) -> char to byte.toByte() }

private class Merge(val rank: Int, val left: Int, val first: String, val second: String)

class Tokenizer(tokens: List<String>, types: List<Int>, merges: List<String>) {
    private val ids = tokens.withIndex().associate { (id, token) -> token to id }
    private val tokens = tokens
    private val ranks = merges.withIndex().associate { (rank, merge) -> merge to rank }
    private val specials = tokens.indices
        .filter { types.getOrNull(it) == CONTROL || types.getOrNull(it) == USER_DEFINED }
        .map { tokens[it] }
        .filter(String::isNotEmpty)
        .sortedByDescending(String::length)

    fun encode(text: String): IntArray {
        val result = mutableListOf<Int>()
        var start = 0
        while (start < text.length) {
            val (at, special) = nextSpecial(text, start)
            encodePlain(text.substring(start, at), result)
            if (special == null) break
            result += ids.getValue(special)
            start = at + special.length
        }
        return result.toIntArray()
    }

    fun id(token: String) = ids[token]

    fun decode(ids: IntArray): String {
        val bytes = ids.flatMap { id ->
            val token = tokens[id]
            if (token in specials) {
                token.encodeToByteArray().toList()
            } else {
                token.map(CHARACTER_BYTES::getValue)
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun nextSpecial(text: String, from: Int): Pair<Int, String?> {
        var best = text.length to null as String?
        for (special in specials) {
            val at = text.indexOf(special, from)
            if (at >= 0 && at < best.first) best = at to special
        }
        return best
    }

    private fun encodePlain(text: String, result: MutableList<Int>) {
        val matcher = PIECES.matcher(text)
        while (matcher.find()) {
            val piece = matcher.group().encodeToByteArray()
                .map { BYTE_CHARACTERS[it.toInt() and 0xff].toString() }
            merged(piece).forEach { symbol -> result += ids.getValue(symbol) }
        }
    }

    private fun merged(start: List<String>): List<String> {
        val symbols = start.toMutableList<String?>()
        val next = IntArray(symbols.size) { it + 1 }
        val previous = IntArray(symbols.size) { it - 1 }
        val queue = PriorityQueue(compareBy<Merge>({ it.rank }, { it.left }))
        fun offer(left: Int) {
            val right = next[left]
            if (right >= symbols.size) return
            val first = symbols[left] ?: return
            val second = symbols[right] ?: return
            val rank = ranks["$first $second"] ?: return
            queue += Merge(rank, left, first, second)
        }
        symbols.indices.forEach(::offer)
        while (queue.isNotEmpty()) {
            val merge = queue.poll()
            val right = next[merge.left]
            val current = right < symbols.size && symbols[merge.left] === merge.first &&
                symbols[right] === merge.second
            if (!current) continue
            symbols[merge.left] = merge.first + merge.second
            symbols[right] = null
            next[merge.left] = next[right]
            if (next[right] < symbols.size) previous[next[right]] = merge.left
            if (previous[merge.left] >= 0) offer(previous[merge.left])
            offer(merge.left)
        }
        return symbols.filterNotNull()
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(metadata: Map<String, Any?>) = Tokenizer(
            metadata["tokenizer.ggml.tokens"] as List<String>,
            metadata["tokenizer.ggml.token_type"] as List<Int>,
            metadata["tokenizer.ggml.merges"] as List<String>,
        )
    }
}

private const val SPACE = "▁"
private const val UNKNOWN_PENALTY = 10f

class Unigram(pieces: List<Pair<String, Float>>) {
    private val ids = pieces.withIndex().associate { (id, piece) -> piece.first to id }
    private val scores = pieces.map { it.second }.toFloatArray()
    private val longest = pieces.maxOf { it.first.codePointCount(0, it.first.length) }
    private val unknownScore = pieces.minOf { it.second } - UNKNOWN_PENALTY
    private val bytes = IntArray(256) { ids["<0x%02X>".format(it)] ?: 0 }

    fun encode(text: String): IntArray {
        val result = mutableListOf<Int>()
        val marked = SPACE + text.replace(" ", SPACE)
        var start = 0
        for (index in 1..marked.length) {
            if (index < marked.length && !marked.startsWith(SPACE, index)) continue
            encodeWord(marked.substring(start, index), result)
            start = index
        }
        return result.toIntArray()
    }

    private fun encodeWord(word: String, result: MutableList<Int>) {
        val points = word.codePoints().toArray()
        val best = FloatArray(points.size + 1) { Float.NEGATIVE_INFINITY }.also {
            it[0] =
                0f
        }
        val from = IntArray(points.size + 1)
        val piece = IntArray(points.size + 1) { -1 }
        for (end in 1..points.size) {
            for (length in 1..minOf(longest, end)) {
                val start = end - length
                val id = ids[String(points, start, length)]
                val score = if (id !=
                    null
                ) {
                    scores[id]
                } else if (length == 1) {
                    unknownScore
                } else {
                    continue
                }
                if (best[start] + score <= best[end]) continue
                best[end] = best[start] + score
                from[end] = start
                piece[end] = id ?: -1
            }
        }
        val parts = mutableListOf<IntArray>()
        var end = points.size
        while (end > 0) {
            val start = from[end]
            parts += if (piece[end] >= 0) {
                intArrayOf(piece[end])
            } else {
                String(points, start, end - start).encodeToByteArray()
                    .map { bytes[it.toInt() and 0xff] }.toIntArray()
            }
            end = start
        }
        parts.asReversed().forEach { result += it.toList() }
    }
}
