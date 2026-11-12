package dev.tn3w.shelf

import dev.tn3w.shelf.data.PocketModel
import dev.tn3w.shelf.data.SpeakerModel
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.serializer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

private fun releaseDexFiles() =
    RELEASE_DEX.listFiles { file -> file.extension == "dex" }.orEmpty().toList()

private fun releaseDex(): String? = releaseDexFiles().takeIf { it.isNotEmpty() }
    ?.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }

private fun dexMethods(file: File): List<String> {
    val bytes = file.readBytes()
    val dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun string(index: Int): String {
        var at = dex.getInt(dex.getInt(0x3C) + index * 4)
        while (dex.get(at++) < 0) continue
        val end = generateSequence(at) { it + 1 }.first { bytes[it] == 0.toByte() }
        return String(bytes, at, end - at)
    }
    fun type(index: Int) = string(dex.getInt(dex.getInt(0x44) + index * 4))
    val methods = dex.getInt(0x5C)
    return List(dex.getInt(0x58)) {
        val at = methods + it * 8
        type(dex.getShort(at).toInt() and 0xffff) + "->" + string(dex.getInt(at + 4))
    }
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

    @Test
    fun nativeModelsKeepTheirJniNames() {
        val files = releaseDexFiles()
        assumeTrue("run minifyGithubReleaseWithR8 first", files.isNotEmpty())
        val methods = files.flatMap(::dexMethods)
        for (type in listOf(SpeakerModel::class.java, PocketModel::class.java)) {
            val natives = type.declaredMethods.filter { Modifier.isNative(it.modifiers) }
            assertTrue(natives.isNotEmpty())
            val owner = "L${type.name.replace('.', '/')};"
            natives.forEach { method ->
                val name = "$owner->${method.name}"
                assertTrue("$name renamed or stripped by R8", name in methods)
            }
        }
    }
}
