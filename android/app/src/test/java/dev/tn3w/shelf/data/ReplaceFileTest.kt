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

    @Test
    fun failedReplaceKeepsExistingFile() {
        val directory = Files.createTempDirectory("replace").toFile()
        val target = directory.resolve("1.epub").apply { writeText("book") }
        runCatching { replaceFile(target) { error("copy failed") } }
        assertEquals("book", target.readText())
        assertEquals(listOf("1.epub"), directory.list()!!.toList())
        directory.deleteRecursively()
    }
}
