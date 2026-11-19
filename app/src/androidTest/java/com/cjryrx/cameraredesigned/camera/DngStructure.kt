package com.cjryrx.cameraredesigned.camera

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent TIFF directory inspection: does not rely on ExifInterface's exported-tag allowlist. */
internal data class DngStructure(
    val version: List<Long>,
    val width: Long,
    val height: Long,
    val orientation: Long?,
    val cfaPattern: List<Long>,
    val stripCount: Int,
    val rasterBytes: Long,
    val directoryTags: List<List<Int>>,
) {
    companion object {
        fun read(file: File): DngStructure = RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(8).also(input::readFully)
            val order = when (String(header, 0, 2, Charsets.US_ASCII)) {
                "II" -> ByteOrder.LITTLE_ENDIAN
                "MM" -> ByteOrder.BIG_ENDIAN
                else -> error("Not a TIFF byte-order header")
            }
            val initial = ByteBuffer.wrap(header).order(order)
            require((initial.getShort(2).toInt() and 0xffff) == 42) { "Expected classic TIFF/DNG header" }
            fun readAt(offset: Long, size: Int): ByteBuffer {
                require(offset >= 0 && size >= 0 && offset + size <= input.length()) { "TIFF offset outside file: $offset + $size" }
                val bytes = ByteArray(size)
                input.seek(offset); input.readFully(bytes)
                return ByteBuffer.wrap(bytes).order(order)
            }
            fun unsignedInt(value: Int) = value.toLong() and 0xffffffffL
            fun values(entry: Entry): List<Long> {
                val size = when (entry.type) { 1, 7 -> 1; 3 -> 2; 4, 13 -> 4; else -> return emptyList() }
                require(entry.count <= 65536) { "Unreasonable metadata value count" }
                val bytes = (entry.count * size).toInt()
                val buffer = readAt(if (bytes <= 4) entry.inlineOffset else entry.valueOffset, bytes)
                return List(entry.count.toInt()) {
                    when (size) { 1 -> buffer.get().toLong() and 255; 2 -> buffer.short.toLong() and 65535; else -> unsignedInt(buffer.int) }
                }
            }
            val pending = ArrayDeque<Long>()
            pending += unsignedInt(initial.getInt(4))
            val visited = mutableSetOf<Long>()
            val directories = mutableListOf<Map<Int, Entry>>()
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                if (offset == 0L || !visited.add(offset)) continue
                require(visited.size <= 32) { "Too many TIFF directories" }
                val count = readAt(offset, 2).short.toInt() and 65535
                require(count <= 4096) { "Unreasonable TIFF entry count" }
                val directory = buildMap {
                    repeat(count) { index ->
                        val at = offset + 2 + index * 12
                        val b = readAt(at, 12)
                        val tag = b.short.toInt() and 65535
                        val type = b.short.toInt() and 65535
                        put(tag, Entry(type, unsignedInt(b.int), unsignedInt(b.int), at + 8))
                    }
                }
                directories += directory
                pending += unsignedInt(readAt(offset + 2 + count * 12, 4).int)
                directory[330]?.let { pending.addAll(values(it)) } // SubIFDs
            }
            val version = directories.firstNotNullOfOrNull { it[50706]?.let(::values) }.orEmpty()
            val imageDirectory = directories.filter { 256 in it && 257 in it && 273 in it && 279 in it }
                .maxByOrNull { values(it.getValue(256)).first() * values(it.getValue(257)).first() }
                ?: error("No TIFF image directory with complete strip metadata")
            val offsets = values(imageDirectory.getValue(273))
            val counts = values(imageDirectory.getValue(279))
            require(offsets.size == counts.size && offsets.isNotEmpty()) { "Mismatched TIFF raster strips" }
            offsets.zip(counts).forEach { (offset, count) ->
                require(offset >= 8 && count > 0 && offset + count <= file.length()) { "Truncated TIFF raster strip" }
            }
            DngStructure(version, values(imageDirectory.getValue(256)).first(), values(imageDirectory.getValue(257)).first(),
                imageDirectory[274]?.let(::values)?.firstOrNull(), imageDirectory[33422]?.let(::values).orEmpty(),
                offsets.size, counts.sum(), directories.map { it.keys.sorted() })
        }
    }

    private data class Entry(val type: Int, val count: Long, val valueOffset: Long, val inlineOffset: Long)
}
