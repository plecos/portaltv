package com.portal.tv.search

/**
 * A small markdown reader for the chat bubbles. It turns a reply into plain text and a list of
 * style ranges; the screen then turns the ranges into Android spans. Free of Android types so it can
 * be unit tested.
 *
 * It handles what chat models actually write: **bold**, *italic*, `code`, ~~strikethrough~~,
 * [links](address), # headings, - and 1. lists, > quotes, fenced code blocks and rules. A mark that
 * isn't closed yet stays as typed, which matters while a reply is still streaming in.
 */
object Markdown {

    enum class Style { BOLD, ITALIC, CODE, STRIKE, LINK, HEADING, QUOTE, CODE_BLOCK }

    /** [style] applies to the text from [start] up to but not including [end]. [level] is a heading's depth. */
    data class Span(val start: Int, val end: Int, val style: Style, val level: Int = 0)

    data class Rendered(val text: String, val spans: List<Span>)

    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")

    fun render(markdown: String): Rendered {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var emitted = false
        var lastBlank = false
        fun newLine() { if (emitted) out.append('\n'); emitted = true }

        var inFence = false
        var fenceStart = -1
        for (rawLine in markdown.replace("\r\n", "\n").split("\n")) {
            if (rawLine.trimStart().startsWith("```")) {
                if (inFence && fenceStart >= 0) spans += Span(fenceStart, out.length, Style.CODE_BLOCK)
                inFence = !inFence
                fenceStart = -1
                lastBlank = false
                continue
            }
            if (inFence) {
                newLine()
                if (fenceStart < 0) fenceStart = out.length
                out.append(rawLine)
                continue
            }
            if (rawLine.isBlank()) {
                // One blank line at most, and none at the very start.
                if (emitted && !lastBlank) { newLine(); lastBlank = true }
                continue
            }
            lastBlank = false
            newLine()

            var line = rawLine
            var heading = 0
            var quote = false
            var prefix = ""
            val h = HEADING.find(line)
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            val quoted = QUOTE.find(line)
            when {
                RULE.matches(line) -> line = ""
                h != null -> { heading = h.groupValues[1].length; line = h.groupValues[2].trimEnd().trimEnd('#').trimEnd() }
                bullet != null -> { prefix = "  ".repeat(bullet.groupValues[1].length / 2) + "\u2022 "; line = bullet.groupValues[2] }
                numbered != null -> { prefix = "  ".repeat(numbered.groupValues[1].length / 2) + numbered.groupValues[2] + " "; line = numbered.groupValues[3] }
                quoted != null -> { quote = true; line = quoted.groupValues[1] }
            }
            out.append(prefix)
            val inlineStart = out.length
            inline(line, out, spans)
            if (heading > 0) spans += Span(inlineStart, out.length, Style.HEADING, heading)
            if (quote) spans += Span(inlineStart, out.length, Style.QUOTE)
        }
        // A fence that was never closed (the reply is still streaming) is still a code block.
        if (inFence && fenceStart >= 0) spans += Span(fenceStart, out.length, Style.CODE_BLOCK)
        val text = out.toString().trimEnd('\n')
        return Rendered(text, spans.filter { it.start < it.end && it.start < text.length }.map { it.copy(end = minOf(it.end, text.length)) })
    }

    // ---------- Inline marks ----------

    /** Reads inline marks in [s], appending the plain text to [out] and the styled ranges to [spans]. */
    private fun inline(s: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!~>|" -> { out.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i + 1) {
                        val start = out.length
                        out.append(s, i + 1, end)
                        spans += Span(start, out.length, Style.CODE)
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                c == '[' -> {
                    val close = s.indexOf(']', i + 1)
                    if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                        val paren = s.indexOf(')', close + 2)
                        if (paren > close) {
                            val start = out.length
                            inline(s.substring(i + 1, close), out, spans)
                            spans += Span(start, out.length, Style.LINK)
                            i = paren + 1
                            continue
                        }
                    }
                    out.append(c); i++
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    val end = closer(s, i + 2, mark)
                    if (end > 0 && (mark == "**" || boundaryBefore(s, i)) && (mark == "**" || boundaryAfter(s, end + 1))) {
                        val start = out.length
                        inline(s.substring(i + 2, end), out, spans)
                        spans += Span(start, out.length, Style.BOLD)
                        i = end + 2
                    } else { out.append(mark); i += 2 }
                }
                s.startsWith("~~", i) -> {
                    val end = closer(s, i + 2, "~~")
                    if (end > 0) {
                        val start = out.length
                        inline(s.substring(i + 2, end), out, spans)
                        spans += Span(start, out.length, Style.STRIKE)
                        i = end + 2
                    } else { out.append("~~"); i += 2 }
                }
                c == '*' || c == '_' -> {
                    val end = if (i + 1 < s.length && !s[i + 1].isWhitespace()) closer(s, i + 1, c.toString()) else -1
                    val ok = end > 0 && (c == '*' || (boundaryBefore(s, i) && boundaryAfter(s, end)))
                    if (ok) {
                        val start = out.length
                        inline(s.substring(i + 1, end), out, spans)
                        spans += Span(start, out.length, Style.ITALIC)
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    /**
     * The position of the mark that closes one opened before [from], or -1. A closing mark can't
     * follow a space (so "2 * 3 * 4" isn't italic), and a single * or _ doesn't close on a doubled one.
     */
    private fun closer(s: String, from: Int, mark: String): Int {
        var i = from
        while (i <= s.length - mark.length) {
            if (s[i] == '`') { val e = s.indexOf('`', i + 1); if (e > 0) { i = e + 1; continue } }
            if (s.startsWith(mark, i) && i > from && !s[i - 1].isWhitespace()) {
                val doubled = mark.length == 1 && i + 1 < s.length && s[i + 1] == mark[0]
                if (!doubled) return i
                i += 2
                continue
            }
            i++
        }
        return -1
    }

    /** True when nothing word-like sits just before [i], so an underscore there opens emphasis rather than sitting inside a word. */
    private fun boundaryBefore(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()

    /** True when nothing word-like follows the mark that ends at [markIndex]. */
    private fun boundaryAfter(s: String, markIndex: Int): Boolean {
        val next = markIndex + 1
        return next >= s.length || !s[next].isLetterOrDigit()
    }
}
