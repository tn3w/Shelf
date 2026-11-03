package dev.tn3w.shelf.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

private const val TOKEN = "solved"
private val CHALLENGE = Challenge(c = "ab".repeat(24), d = 3, k = 4, n = 64, r = 2)

class PowTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val issued = AtomicInteger()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun HttpExchange.reply(
        code: Int,
        body: String = "",
        challenge: String? = null,
    ) {
        challenge?.let { responseHeaders.add("WWW-Authenticate", it) }
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    private fun valid(counters: List<Long>) = counters.size == CHALLENGE.k &&
        counters.distinct().size == counters.size &&
        counters.all { counter ->
            val salt = ByteBuffer.allocate(4).putInt(counter.toInt()).array()
            val hash =
                scrypt(CHALLENGE.c.hexToByteArray(), salt, CHALLENGE.n, CHALLENGE.r)
            ByteBuffer.wrap(hash).int.countLeadingZeroBits() >= CHALLENGE.d
        }

    private fun start() = server.apply {
        createContext("/api/pow") { exchange ->
            if (exchange.requestMethod == "GET") {
                return@createContext exchange.reply(200, json.encodeToString(CHALLENGE))
            }
            val body = exchange.requestBody.readBytes().decodeToString()
            val counters = Regex("""\[(.*)]""").find(body)!!.groupValues[1]
                .split(",").map(String::toLong)
            if (!valid(counters)) return@createContext exchange.reply(403)
            issued.incrementAndGet()
            exchange.reply(200, """{"t":"$TOKEN"}""")
        }
        createContext("/cover") { exchange ->
            val authorized =
                exchange.requestHeaders.getFirst("Authorization") == "Bearer $TOKEN"
            if (authorized) {
                exchange.reply(200, "image")
            } else {
                exchange.reply(401, challenge = """Bearer realm="shelf"""")
            }
        }
        createContext("/other") { it.reply(401, challenge = """Bearer realm="api"""") }
        start()
    }

    private fun client(): OkHttpClient {
        lateinit var client: OkHttpClient
        val proofOfWork = ProofOfWork { client }
        client = OkHttpClient.Builder()
            .addInterceptor(proofOfWork)
            .authenticator(proofOfWork)
            .build()
        return client
    }

    private fun OkHttpClient.get(path: String) =
        newCall(Request.Builder().url(base + path).build()).execute().use { it.code }

    @After
    fun stop() = server.stop(0)

    @Test
    fun scryptMatchesTheReferenceVector() {
        val password = "pleaseletmein".toByteArray()
        val hash = scrypt(password, "SodiumChloride".toByteArray(), 16384, 8)
        val expected = "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2"
        assertEquals(expected, hash.toHexString())
    }

    @Test
    fun solvedCountersAreDistinctAndMeetTheDifficulty() {
        assertTrue(valid(solve(CHALLENGE)))
    }

    @Test
    fun shelfChallengeIsSolvedOnceAndTheTokenReused() {
        start()
        val client = client()
        assertEquals(200, client.get("/cover"))
        assertEquals(200, client.get("/cover"))
        assertEquals(1, issued.get())
    }

    @Test
    fun otherBearerChallengesStayUnanswered() {
        start()
        assertEquals(401, client().get("/other"))
        assertEquals(0, issued.get())
    }
}
