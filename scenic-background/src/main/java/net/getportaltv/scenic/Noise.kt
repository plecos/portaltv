package net.getportaltv.scenic

import kotlin.math.abs
import kotlin.math.floor

/** Repeatable "randomness": the same inputs always give the same numbers, so the world needs no stored state. */
object Noise {

    /** A well-mixed integer from up to three integers. */
    fun hash(a: Int, b: Int = 0, c: Int = 0): Int {
        var h = a * 374761393 + b * 668265263 + c * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        h *= 0x2c1b3c6d
        return h xor (h ushr 12)
    }

    /** A number from 0 up to (not including) 1. */
    fun unit(a: Int, b: Int = 0, c: Int = 0): Float = (hash(a, b, c) and 0xFFFFFF) / 16777216f

    /** Smooth one-dimensional noise from 0 to 1: values at whole numbers are random and it eases between them. */
    fun smooth(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3 - 2 * f)
        return unit(i, seed) * (1 - u) + unit(i + 1, seed) * u
    }

    /** Layers of [smooth] noise (each twice as fine and half as strong), from 0 to 1: hills and mountain ridges. */
    fun ridge(x: Float, seed: Int, octaves: Int = 3): Float {
        var total = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        for (o in 0 until octaves) {
            total += smooth(x * freq, seed + o * 31) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return total / norm
    }

    /** Peaks and valleys from 0 to 1: layers of folded noise with rounded tops, so the heights are uneven but the summits are not needles. */
    fun mountain(x: Float, seed: Int): Float {
        var total = 0f
        var amp = 0.55f
        var freq = 1f
        var norm = 0f
        for (o in 0 until 3) {
            val d = 2f * smooth(x * freq, seed + o * 17) - 1f
            val r = 1f - d * d
            total += r * r * amp
            norm += amp
            amp *= 0.4f
            freq *= 2f
        }
        return total / norm
    }
}
