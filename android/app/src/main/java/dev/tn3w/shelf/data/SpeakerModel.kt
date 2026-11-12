package dev.tn3w.shelf.data

import android.os.Process
import java.io.File

private const val ANSWER_TOKENS = 24
private const val END = "<|im_end|>"

fun <T> withPriority(priority: Int, block: () -> T): T {
    val previous = Process.getThreadPriority(Process.myTid())
    runCatching { Process.setThreadPriority(priority) }
    return try {
        block()
    } finally {
        Process.setThreadPriority(previous)
    }
}

internal fun sharedPrefix(first: IntArray, second: IntArray): Int {
    val limit = minOf(first.size, second.size)
    for (index in 0 until limit) if (first[index] != second[index]) return index
    return limit
}

class SpeakerModel private constructor(
    private var handle: Long,
    private val context: Int,
) : AutoCloseable {
    private var fed = IntArray(0)

    fun feed(tokens: IntArray, position: Int) =
        check(feed(handle, tokens, position)) { "context full at $position" }

    fun scores(allowed: IntArray): FloatArray = score(handle, allowed)

    fun fits(tokens: IntArray) = tokens.size + ANSWER_TOKENS < context

    fun prompt(tokens: IntArray) {
        val shared = sharedPrefix(fed, tokens).coerceAtMost(tokens.size - 1)
        feed(tokens.copyOfRange(shared, tokens.size), shared)
        fed = tokens
    }

    fun generate(tokens: IntArray, stop: Int): IntArray {
        prompt(tokens)
        val answer = mutableListOf<Int>()
        while (answer.size < ANSWER_TOKENS) {
            val token = next(handle)
            if (token == stop) break
            feed(intArrayOf(token), tokens.size + answer.size)
            answer += token
        }
        fed = tokens + answer
        return answer.toIntArray()
    }

    override fun close() {
        if (handle != 0L) release(handle)
        handle = 0
    }

    companion object {
        val available by lazy { runCatching { System.loadLibrary("shelf") }.isSuccess }

        fun open(file: File, context: Int, threads: Int): SpeakerModel? {
            if (!available) return null
            val handle = withPriority(Process.THREAD_PRIORITY_BACKGROUND) {
                load(file.path, context, threads)
            }
            return if (handle == 0L) null else SpeakerModel(handle, context)
        }

        @JvmStatic
        private external fun load(path: String, context: Int, threads: Int): Long

        @JvmStatic private external fun release(handle: Long)

        @JvmStatic
        private external fun feed(handle: Long, tokens: IntArray, position: Int): Boolean

        @JvmStatic private external fun score(handle: Long, allowed: IntArray): FloatArray

        @JvmStatic private external fun next(handle: Long): Int
    }
}

fun chatPrompt(prompt: String) =
    "<|im_start|>user\n$prompt$END\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

class ModelJudge(private val model: SpeakerModel, private val tokenizer: Tokenizer) :
    Judge {
    private val end = tokenizer.id(END) ?: 0

    override fun answer(prompt: String): String {
        val tokens = tokenizer.encode(chatPrompt(prompt))
        if (!model.fits(tokens)) return ""
        return tokenizer.decode(model.generate(tokens, end))
    }

    override fun choose(prompt: String, options: List<String>, prefix: String): String {
        val tokens = tokenizer.encode(chatPrompt(prompt) + prefix)
        if (!model.fits(tokens)) return options.last()
        model.prompt(tokens)
        val firsts = options.map { tokenizer.encode(it).first() }.toIntArray()
        val scores = model.scores(firsts)
        return options[scores.indices.maxBy { scores[it] }]
    }
}
