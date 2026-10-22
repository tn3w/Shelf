package dev.tn3w.shelf.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NetworkTest {
    @Test
    fun suspendCatchingKeepsExceptions() {
        val result = runSuspendCatching { throw IOException("offline") }
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test(expected = CancellationException::class)
    fun suspendCatchingRethrowsCancellation() {
        runSuspendCatching { throw CancellationException("left") }
    }

    @Test(expected = StackOverflowError::class)
    fun suspendCatchingRethrowsErrors() {
        runSuspendCatching { throw StackOverflowError() }
    }

    @Test
    fun downloadsHaveNoCallTimeout() {
        assertTrue(http.callTimeoutMillis > 0)
        assertEquals(0, downloadClient.callTimeoutMillis)
    }
}
