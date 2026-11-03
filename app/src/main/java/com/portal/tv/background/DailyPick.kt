package com.portal.tv.background

import java.time.LocalDate

/**
 * The "Random" background: one video per day, chosen without storing anything. The same day always
 * gives the same video. Days are grouped into cycles as long as the list of videos; each cycle plays
 * every video once in a random order, so nothing repeats until all have been seen, and a cycle never
 * starts with the video the last one ended on.
 */
object DailyPick {

    /** The video for [today] from [names], or null when there are none. */
    fun pick(names: List<String>, today: LocalDate): String? {
        val sorted = names.sorted()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val day = today.toEpochDay()
        // Two videos simply take turns.
        if (sorted.size == 2) return sorted[Math.floorMod(day, 2L).toInt()]
        val n = sorted.size
        val cycle = Math.floorDiv(day, n.toLong())
        return order(sorted, cycle)[Math.floorMod(day, n.toLong()).toInt()]
    }

    /** The order videos play in during [cycle]. Never begins with the previous cycle's last video. */
    private fun order(sorted: List<String>, cycle: Long): List<String> {
        val current = shuffled(sorted, cycle)
        val last = shuffled(sorted, cycle - 1).last()
        // Swapping the first two can't change the cycle's own last video when there are three or more.
        return if (current.first() == last) current.toMutableList().also { java.util.Collections.swap(it, 0, 1) } else current
    }

    /** A repeatable shuffle for [seed]. */
    private fun shuffled(items: List<String>, seed: Long): List<String> {
        val out = items.toMutableList()
        var state = seed * -7046029254386353131L + 1442695040888963407L
        for (i in out.size - 1 downTo 1) {
            state = state xor (state ushr 33)
            state *= -49064778989728563L
            state = state xor (state ushr 29)
            java.util.Collections.swap(out, i, Math.floorMod(state, (i + 1).toLong()).toInt())
        }
        return out
    }
}
