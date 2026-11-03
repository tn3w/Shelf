package dev.tn3w.shelf.data

import kotlinx.serialization.Serializable
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

private const val MAX_THREADS = 8
private const val REALM = "shelf"
private val JSON_TYPE = "application/json".toMediaType()

@Serializable
class Challenge(val c: String, val d: Int, val k: Int, val n: Int, val r: Int)

@Serializable
private class Solution(val c: String, val n: List<Long>)

@Serializable
private class Issued(val t: String)

private fun pbkdf2(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(password, "HmacSHA256"))
    }
    val blocks = (1..(length + 31) / 32).map { index ->
        mac.update(salt)
        mac.doFinal(ByteBuffer.allocate(4).putInt(index).array())
    }
    return blocks.reduce(ByteArray::plus).copyOf(length)
}

private fun IntArray.quarter(a: Int, b: Int, c: Int, d: Int) {
    this[b] = this[b] xor (this[a] + this[d]).rotateLeft(7)
    this[c] = this[c] xor (this[b] + this[a]).rotateLeft(9)
    this[d] = this[d] xor (this[c] + this[b]).rotateLeft(13)
    this[a] = this[a] xor (this[d] + this[c]).rotateLeft(18)
}

private fun salsa(block: IntArray, state: IntArray) {
    block.copyInto(state)
    repeat(4) {
        state.quarter(0, 4, 8, 12)
        state.quarter(5, 9, 13, 1)
        state.quarter(10, 14, 2, 6)
        state.quarter(15, 3, 7, 11)
        state.quarter(0, 1, 2, 3)
        state.quarter(5, 6, 7, 4)
        state.quarter(10, 11, 8, 9)
        state.quarter(15, 12, 13, 14)
    }
    for (index in 0 until 16) block[index] += state[index]
}

private class Mixer(private val blockSize: Int) {
    private val carry = IntArray(16)
    private val state = IntArray(16)

    fun mix(input: IntArray, output: IntArray) {
        input.copyInto(carry, 0, input.size - 16)
        for (part in 0 until 2 * blockSize) {
            for (index in 0 until 16) {
                carry[index] =
                    carry[index] xor input[part * 16 + index]
            }
            salsa(carry, state)
            carry.copyInto(output, ((part and 1) * blockSize + (part shr 1)) * 16)
        }
    }
}

/** scrypt with p = 1 and a 32-byte result, as the server checks it. */
fun scrypt(password: ByteArray, salt: ByteArray, cost: Int, blockSize: Int): ByteArray {
    val words = 32 * blockSize
    val order = ByteOrder.LITTLE_ENDIAN
    var block = IntArray(words)
    val start = pbkdf2(password, salt, words * 4)
    ByteBuffer.wrap(start).order(order).asIntBuffer().get(block)
    var spare = IntArray(words)
    val memory = IntArray(cost * words)
    val mixer = Mixer(blockSize)
    fun step() {
        mixer.mix(block, spare)
        block = spare.also { spare = block }
    }
    for (round in 0 until cost) {
        block.copyInto(memory, round * words)
        step()
    }
    repeat(cost) {
        val pick = (block[words - 16] and (cost - 1)) * words
        for (index in 0 until words) block[index] = block[index] xor memory[pick + index]
        step()
    }
    val bytes = ByteBuffer.allocate(words * 4).order(order)
    bytes.asIntBuffer().put(block)
    return pbkdf2(password, bytes.array(), 32)
}

private fun leadingZeroBits(hash: ByteArray) =
    ByteBuffer.wrap(hash).int.countLeadingZeroBits()

/** [Challenge.k] distinct counters whose hash starts with [Challenge.d] zero bits. */
fun solve(challenge: Challenge): List<Long> {
    val secret = challenge.c.hexToByteArray()
    val found = ConcurrentHashMap.newKeySet<Long>()
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
    val workers = (0 until threads).map { start ->
        thread {
            var counter = start.toLong()
            while (found.size < challenge.k) {
                val salt = ByteBuffer.allocate(4).putInt(counter.toInt()).array()
                val hash = scrypt(secret, salt, challenge.n, challenge.r)
                if (leadingZeroBits(hash) >= challenge.d) found += counter
                counter += threads
            }
        }
    }
    workers.forEach(Thread::join)
    return found.sorted().take(challenge.k)
}

/**
 * Answers the Shelf server's `WWW-Authenticate: Bearer` with a solved proof of work, see
 * `tooling/server/`, and sends the token to that host from then on. Tokens are bound to
 * the User-Agent, so [client] must send the same one.
 */
class ProofOfWork(private val client: () -> OkHttpClient) :
    Interceptor,
    Authenticator {
    private val tokens = ConcurrentHashMap<String, String>()

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokens[request.url.host] ?: return chain.proceed(request)
        return chain.proceed(request.withToken(token))
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.challenges().none { it.scheme == "Bearer" && it.realm == REALM }) {
            return null
        }
        if (response.priorResponse?.code == 401) return null
        val request = response.request
        val sent = request.header("Authorization")
        val token = synchronized(this) {
            tokens[request.url.host]?.takeIf { sent != "Bearer $it" }
                ?: runCatching { issue(request.url) }.getOrNull()
                    ?.also { tokens[request.url.host] = it }
        } ?: return null
        return request.withToken(token)
    }

    private fun issue(url: HttpUrl): String {
        val endpoint = url.resolve("/api/pow") ?: throw IOException("no proof of work")
        val text = call(Request.Builder().url(endpoint))
        val challenge = json.decodeFromString<Challenge>(text)
        val solution = json.encodeToString(Solution(challenge.c, solve(challenge)))
        val post = Request.Builder().url(endpoint).post(solution.toRequestBody(JSON_TYPE))
        return json.decodeFromString<Issued>(call(post)).t
    }

    private fun call(request: Request.Builder) =
        client().newCall(request.build()).execute().use {
            if (!it.isSuccessful) throw HttpException(it.code, it.request.url.toString())
            it.body.string()
        }
}
