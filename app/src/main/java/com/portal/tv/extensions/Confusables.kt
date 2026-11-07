package com.portal.tv.extensions

/**
 * Characters that are drawn like Latin letters or digits, from Unicode's own confusables list (made into
 * `confusables.txt` by `scripts/gen-confusables.py`). It is read once, the first time a name is checked.
 */
object Confusables {

    private const val RESOURCE = "/confusables.txt"

    private val table: Map<Int, String> by lazy { load() }

    /** The lower-case Latin letters or digits [codePoint] is drawn like, or null if it isn't in the list. */
    fun lookalikeOf(codePoint: Int): String? = table[codePoint]

    private val perScript: Map<Character.UnicodeScript, Int> by lazy {
        table.keys.filter { Character.isLetterOrDigit(it) }.groupingBy { Character.UnicodeScript.of(it) }.eachCount()
    }

    /** How many letters of [script] the list says are drawn like Latin ones; none means the alphabet has no look-alikes. */
    fun lookalikesIn(script: Character.UnicodeScript): Int = perScript[script] ?: 0

    /** How many characters the list holds (a check that the file was found). */
    internal fun size(): Int = table.size

    private fun load(): Map<Int, String> {
        val stream = Confusables::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        val map = HashMap<Int, String>(4096)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val space = line.indexOf(' ')
                if (space <= 0) continue
                val cp = line.substring(0, space).toIntOrNull(16) ?: continue
                map[cp] = line.substring(space + 1).trim()
            }
        }
        return map
    }
}
