package dev.tn3w.shelf

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.serializer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

private val RELEASE_DEX =
    File("build/intermediates/dex/githubRelease/minifyGithubReleaseWithR8")

private fun entriesOf(location: File): List<String> = if (location.isDirectory) {
    location.walk().map { it.toRelativeString(location) }.toList()
} else {
    JarFile(location).use { jar -> jar.entries().toList().map { it.name } }
}

private fun routeClasses(): List<Class<*>> {
    val source = HomeRoute::class.java.protectionDomain.codeSource.location
    return entriesOf(File(source.toURI()))
        .filter { it.matches(Regex("dev/tn3w/shelf/\\w+Route\\.class")) }
        .map { Class.forName(it.removeSuffix(".class").replace('/', '.')) }
}

private fun enumNames(descriptor: SerialDescriptor): Set<String> {
    if (descriptor.kind == SerialKind.ENUM) return setOf(descriptor.serialName)
    return descriptor.elementDescriptors.flatMap(::enumNames).toSet()
}

private fun releaseDex(): String? {
    val files = RELEASE_DEX.listFiles { file -> file.extension == "dex" }.orEmpty()
    if (files.isEmpty()) return null
    return files.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
}

class ReleaseRouteTest {
    @Test
    fun routeEnumsSurviveMinification() {
        val names = routeClasses().flatMap { enumNames(serializer(it).descriptor) }
        assertTrue(names.isNotEmpty())
        val dex = releaseDex()
        assumeTrue("run minifyGithubReleaseWithR8 first", dex != null)
        names.forEach { name ->
            val descriptor = "L${name.replace('.', '/')};"
            assertTrue("$name stripped by R8, add @Keep", descriptor in dex!!)
        }
    }
}
