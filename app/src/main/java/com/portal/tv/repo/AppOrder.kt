package com.portal.tv.repo

/** Custom app ordering, kept free of Android types so it can be unit tested. */
object AppOrder {

    /**
     * [items] in the saved [order]. Items not in the order keep their relative place after those
     * that are, so a newly installed app lands at the end instead of shuffling the rest.
     */
    fun <T> sort(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
        if (order.isEmpty()) return items
        val position = order.withIndex().associate { it.value to it.index }
        return items.sortedBy { position[key(it)] ?: Int.MAX_VALUE }
    }

    /**
     * The saved order after moving [pkg] to [newIndex] within [shown] (package names in on-screen
     * order). Entries in [saved] that aren't on screen, such as apps currently in the frequent
     * row, keep their place at the end.
     */
    fun moveTo(shown: List<String>, saved: List<String>, pkg: String, newIndex: Int): List<String> {
        val moved = shown.toMutableList().apply { remove(pkg) }
        moved.add(newIndex.coerceIn(0, moved.size), pkg)
        return moved + saved.filter { it !in moved }
    }
}
