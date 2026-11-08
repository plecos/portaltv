package net.getportaltv.scenic

/** Colours as packed ints (0xAARRGGBB), with no Android types so the maths can be tested on a computer. */
object Colors {
    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return rgb(
            (r(a) + (r(b) - r(a)) * u).toInt(),
            (g(a) + (g(b) - g(a)) * u).toInt(),
            (b(a) + (b(b) - b(a)) * u).toInt()
        )
    }

    /** [c] with each channel multiplied. */
    fun mul(c: Int, mr: Float, mg: Float, mb: Float): Int = rgb((r(c) * mr).toInt(), (g(c) * mg).toInt(), (b(c) * mb).toInt())

    fun scale(c: Int, k: Float) = mul(c, k, k, k)

    /** Each channel rounded to one of [levels] steps: the limited palette of old computers. */
    fun quantize(c: Int, levels: Int): Int {
        val n = (levels - 1).coerceAtLeast(1)
        fun q(v: Int) = ((v * n + 127) / 255) * 255 / n
        return rgb(q(r(c)), q(g(c)), q(b(c)))
    }

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
