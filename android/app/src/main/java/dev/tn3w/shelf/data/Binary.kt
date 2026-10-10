package dev.tn3w.shelf.data

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
    val values = IntArrayList()
    var current = 0
    while (reader.hasMore) {
        current += reader.varint()
        values.add(current)
    }
    return values.toArray()
}

class IntArrayList {
    private var values = IntArray(16)
    private var count = 0

    fun add(value: Int) {
        if (count == values.size) values = values.copyOf(count * 2)
        values[count++] = value
    }

    fun toArray(): IntArray = values.copyOf(count)
}

class Cache<K : Any, V : Any>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) =
            size > capacity
    }

    fun get(key: K, load: (K) -> V): V = synchronized(entries) { entries[key] }
        ?: load(key).also { synchronized(entries) { entries[key] = it } }
}
