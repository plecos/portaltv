package com.portal.tv.search

/** Picks the apps whose names fit what was typed. Free of Android types so it can be unit tested. */
object AppSearch {

    /**
     * The [items] whose label matches [query], best matches first: names that start with the text,
     * then names with a word that starts with it, then names that contain it. Case and spacing are
     * ignored. An empty query matches nothing.
     */
    fun <T> match(items: List<T>, query: String, label: (T) -> String): List<T> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return items
            .mapNotNull { item ->
                val name = normalize(label(item))
                val rank = when {
                    name.startsWith(q) -> 0
                    label(item).lowercase().split(Regex("[^a-z0-9]+")).any { it.startsWith(q) } -> 1
                    name.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                rank to item
            }
            .sortedBy { it.first } // stable: equal ranks keep the incoming (alphabetical) order
            .map { it.second }
    }

    /**
     * The single best item for a name the user or the model gave: an exact name (ignoring case and
     * spacing) wins, otherwise the top partial match, otherwise null.
     */
    fun <T> find(items: List<T>, name: String, label: (T) -> String): T? {
        val q = normalize(name)
        if (q.isEmpty()) return null
        return items.firstOrNull { normalize(label(it)) == q } ?: match(items, name, label).firstOrNull()
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
