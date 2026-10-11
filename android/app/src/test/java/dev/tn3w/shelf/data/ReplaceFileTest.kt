package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

class ReplaceFileTest {
    @Test
    fun parallelReplacesNeverLeaveHalfWrittenFile() {
        val directory = Files.createTempDirectory("replace").toFile()
        val target = directory.resolve("cover.webp")
        val contents = List(8) { index -> index.toString().repeat(200_000) }

        val pool = Executors.newFixedThreadPool(contents.size)
        contents.map { content ->
            pool.submit { replaceFile(target) { it.writeText(content) } }
        }.forEach { it.get() }
        pool.shutdown()

        assertEquals(true, target.readText() in contents)
        assertEquals(listOf("cover.webp"), directory.list()!!.toList())
        directory.deleteRecursively()
    }
}
