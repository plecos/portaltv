package net.getportaltv.scenic

/**
 * A little timer for finding what makes a frame slow: how long each part of the picture takes, and how many rectangles and
 * sprites it draws. On only in debuggable builds, and logged every few seconds by the view.
 */
object Profile {
    @Volatile var on = false
    var draws = 0
    var sprites = 0
    private val totals = HashMap<String, Long>()
    private var frames = 0

    fun add(name: String, nanos: Long) { totals[name] = (totals[name] ?: 0L) + nanos }

    /** One line for the log: the slowest parts, each in milliseconds a frame, and the drawing calls a frame. */
    fun report(): String {
        val n = frames.coerceAtLeast(1)
        val top = totals.entries.sortedByDescending { it.value }.take(9).joinToString(", ") { "%s %.1f".format(it.key.removePrefix("draw"), it.value / 1e6 / n) }
        val line = "phases (ms/frame): $top; rects ${draws / n}, sprites ${sprites / n}"
        totals.clear(); draws = 0; sprites = 0; frames = 0
        return line
    }

    fun frame() { frames++ }
}
