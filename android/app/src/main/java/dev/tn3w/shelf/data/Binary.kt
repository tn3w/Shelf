package dev.tn3w.shelf.data

import androidx.collection.IntList
import androidx.collection.LruCache
import androidx.collection.MutableIntList

class ByteReader(private val bytes: ByteArray, var offset: Int = 0) {
    val hasMore
        get() = offset < bytes.size

    fun byte() = bytes[offset++].toInt() and 0xFF

    fun varint(): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = byte()
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    fun text(): String {
        val length = varint()
        offset += length
        return String(bytes, offset - length, length, Charsets.UTF_8)
    }

    fun skip(length: Int) {
        offset += length
    }

    fun take(length: Int) =
        bytes.copyOfRange(offset, offset + length).also { offset += length }

    fun rest() = take(bytes.size - offset)
}

fun decodePostings(bytes: ByteArray): IntArray {
    val reader = ByteReader(bytes)
    val values = MutableIntList()
    var current = 0
    while (reader.hasMore) {
        current += reader.varint()
        values.add(current)
    }
    return values.toIntArray()
}

fun IntList.toIntArray() = IntArray(size, ::get)

fun <K : Any, V : Any> LruCache<K, V>.getOrPut(key: K, load: (K) -> V): V =
    get(key) ?: load(key).also { put(key, it) }
