package net.getportaltv.scenic

/**
 * The value of something that depends only on where it is in the world (the shape of a range of mountains, say), for [size]
 * columns in a row. The view slides along the world a little at a time, so each frame only the columns that have just come into view
 * are worked out; the rest are kept from the frame before.
 */
class Strip(private val size: Int, private val compute: (Int) -> Float) {
    private val values = FloatArray(size)
    private var start = Int.MIN_VALUE

    /** Makes the strip cover world columns [from] up to [from] + size. */
    fun moveTo(from: Int) {
        if (from == start) return
        val shift = if (start == Int.MIN_VALUE) size else from - start
        if (kotlin.math.abs(shift) >= size) {
            for (i in 0 until size) values[i] = compute(from + i)
        } else if (shift > 0) {
            System.arraycopy(values, shift, values, 0, size - shift)
            for (i in size - shift until size) values[i] = compute(from + i)
        } else {
            System.arraycopy(values, 0, values, -shift, size + shift)
            for (i in 0 until -shift) values[i] = compute(from + i)
        }
        start = from
    }

    /** Forgets everything, so the next [moveTo] works every column out again (what it is computed from has changed). */
    fun reset() { start = Int.MIN_VALUE }

    /** The value at the [i]th column from where the strip starts. */
    operator fun get(i: Int): Float = values[i]
}
