package com.portal.tv.search

/**
 * Turns a reply into something worth saying aloud, and cuts a streaming reply into sentences so
 * speech can start before the answer has finished. Free of Android types so it can be unit tested.
 */
object SpeechText {

    /** The reply without markdown marks, links, code fences or emoji, so a voice doesn't read the symbols out. */
    fun clean(text: String): String {
        var t = text
        t = t.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")        // code blocks
        t = t.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")                    // [label](url) -> label
        t = t.replace(Regex("https?://\\S+"), " ")                                  // bare links
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")                         // headings
        t = t.replace(Regex("(?m)^\\s*[-*•]\\s+"), "")                              // bullets
        t = t.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")                           // numbered lists
        t = t.replace(Regex("[*_`~|]+"), "")                                        // emphasis, code, tables
        t = t.replace(Regex("[\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]"), "") // emoji
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex("\\s*\\n\\s*"), "\n")
        return t.trim()
    }

    /**
     * Where [word] (as the voice said it) is in [text], searching from [from] and ignoring case, or
     * null if it isn't there. Used to light up the spoken word in the reply as it is read, even though
     * the voice was given the reply with its markdown taken out.
     */
    fun locate(text: String, word: String, from: Int): IntRange? {
        val w = word.trim().trim { !it.isLetterOrDigit() }
        if (w.isEmpty()) return null
        val i = text.indexOf(w, startIndex = from.coerceIn(0, text.length), ignoreCase = true)
        return if (i < 0) null else i until i + w.length
    }

    /**
     * Collects streamed pieces and hands back whole sentences as they complete. A sentence ends at
     * . ! or ? followed by a space or the end of a line, or at a line break. The rest waits for more
     * text, or for [flush] when the reply is over.
     */
    class SentenceSplitter {
        private val pending = StringBuilder()

        fun feed(piece: String): List<String> {
            pending.append(piece)
            val out = mutableListOf<String>()
            while (true) {
                val end = sentenceEnd(pending) ?: break
                val sentence = pending.substring(0, end).trim()
                pending.delete(0, end)
                if (sentence.isNotEmpty()) out += sentence
            }
            return out
        }

        /** Whatever is left once the reply is complete, or null. */
        fun flush(): String? {
            val rest = pending.toString().trim()
            pending.clear()
            return rest.takeIf { it.isNotEmpty() }
        }

        private fun sentenceEnd(s: CharSequence): Int? {
            for (i in s.indices) {
                val c = s[i]
                if (c == '\n') return i + 1
                if (c == '.' || c == '!' || c == '?') {
                    // The mark must be followed by whitespace; the last character waits for what comes next.
                    if (i + 1 < s.length && s[i + 1].isWhitespace()) {
                        // Not after a number such as "3. " at the start, nor an abbreviation of one letter.
                        val start = s.lastIndexOf(' ', i - 1).let { if (it < 0) 0 else it + 1 }
                        val word = s.substring(start, i)
                        if (c == '.' && (word.length == 1 && word[0].isLetter() || word.all { it.isDigit() } && word.isNotEmpty() && start == 0)) continue
                        return i + 1
                    }
                }
            }
            return null
        }
    }
}
