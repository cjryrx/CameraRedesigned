package com.cjryrx.cameraredesigned.imaging

import java.io.File
import java.io.Reader
import kotlin.math.floor

/** A bounded .cube 3D LUT. The file's red coordinate changes fastest. */
class CubeLut private constructor(
    val size: Int,
    val title: String?,
    private val minimum: FloatArray,
    private val maximum: FloatArray,
    private val values: FloatArray,
) {
    data class LutTextureData(val size: Int, val domainMin: FloatArray, val domainMax: FloatArray, val values: FloatArray)

    /** Defensive copies for GPU upload; callers cannot mutate the CPU LUT. Red is fastest. */
    fun textureData(): LutTextureData = LutTextureData(size, minimum.copyOf(), maximum.copyOf(), values.copyOf())

    /** Interpolation writes into caller-owned storage, so the pixel loop never allocates. */
    fun sample(red: Float, green: Float, blue: Float, result: FloatArray) {
        require(result.size >= 3)
        val r = coordinate(red, 0)
        val g = coordinate(green, 1)
        val b = coordinate(blue, 2)
        val ri = floor(r).toInt().coerceAtMost(size - 2)
        val gi = floor(g).toInt().coerceAtMost(size - 2)
        val bi = floor(b).toInt().coerceAtMost(size - 2)
        val rf = r - ri
        val gf = g - gi
        val bf = b - bi
        for (channel in 0..2) {
            var sum = 0f
            for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
                val weight = (if (dx == 0) 1f - rf else rf) *
                    (if (dy == 0) 1f - gf else gf) * (if (dz == 0) 1f - bf else bf)
                val index = (((bi + dz) * size + gi + dy) * size + ri + dx) * 3 + channel
                sum += values[index] * weight
            }
            result[channel] = sum
        }
    }

    private fun coordinate(value: Float, channel: Int): Float =
        ((value - minimum[channel]) / (maximum[channel] - minimum[channel])).coerceIn(0f, 1f) * (size - 1)

    companion object {
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
        const val MAX_SIZE = 65

        fun read(file: File): CubeLut {
            require(file.length() in 1..MAX_FILE_BYTES) { "LUT must be between 1 byte and 32 MiB" }
            return file.bufferedReader().use(::parse)
        }

        fun parse(reader: Reader): CubeLut {
            var size = 0
            var title: String? = null
            var data: FloatArray? = null
            var cursor = 0
            var characters = 0L
            val whitespace = Regex("\\s+")
            val minimum = floatArrayOf(0f, 0f, 0f)
            val maximum = floatArrayOf(1f, 1f, 1f)
            val seen = mutableSetOf<String>()
            reader.buffered().forEachLine { raw ->
                characters += raw.length + 1
                require(characters <= MAX_FILE_BYTES && raw.length <= 4096) { "LUT text exceeds safety limits" }
                val line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
                if (line.isNotBlank()) {
                    val parts = line.split(whitespace)
                    when (val key = parts[0]) {
                        "TITLE" -> {
                            require(seen.add(key) && cursor == 0) { "Repeated or misplaced TITLE" }
                            title = line.substringAfter("TITLE").trim().removeSurrounding("\"").take(200)
                        }
                        "LUT_3D_SIZE" -> {
                            require(parts.size == 2 && seen.add(key)) { "Invalid LUT_3D_SIZE" }
                            size = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid LUT size")
                            require(size in 2..MAX_SIZE) { "Supported 3D LUT sizes: 2..65" }
                            data = FloatArray(size * size * size * 3)
                        }
                        "DOMAIN_MIN", "DOMAIN_MAX" -> {
                            require(parts.size == 4 && seen.add(key) && cursor == 0) { "Invalid LUT domain" }
                            val target = if (key == "DOMAIN_MIN") minimum else maximum
                            for (i in 0..2) target[i] = finite(parts[i + 1])
                        }
                        "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE" ->
                            throw IllegalArgumentException("Only 3D .cube LUTs using DOMAIN_MIN/MAX are supported")
                        else -> {
                            val destination = requireNotNull(data) { "LUT_3D_SIZE must precede samples" }
                            require(parts.size == 3 && cursor + 3 <= destination.size) { "Invalid or excess LUT sample" }
                            for (i in 0..2) destination[cursor++] = finite(parts[i])
                        }
                    }
                }
            }
            val samples = requireNotNull(data) { "Missing LUT_3D_SIZE" }
            require(cursor == samples.size) { "Incomplete LUT: expected ${samples.size / 3} samples, got ${cursor / 3}" }
            for (i in 0..2) require(maximum[i] > minimum[i]) { "LUT domain must have positive extent" }
            return CubeLut(size, title, minimum, maximum, samples)
        }

        private fun finite(text: String): Float {
            val value = text.toFloatOrNull()
            require(value != null && value.isFinite() && kotlin.math.abs(value) <= 65536f) { "Invalid LUT number" }
            return value
        }
    }
}
