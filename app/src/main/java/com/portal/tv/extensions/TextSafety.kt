package com.portal.tv.extensions

import java.text.Normalizer
import java.util.Locale

/**
 * What text from outside Portal (an extension's name, a card, a catalog entry) may contain. Everything shown comes from
 * another app or another person, so it is plain, visible text: nothing that can reorder it, hide words in it, or stack
 * marks into a smear.
 */
object TextSafety {

    /** Most combining marks in a row. Real writing needs a few; more is a smear over the line above. */
    const val MAX_COMBINING_RUN = 3

    /**
     * True if [text] has only ordinary visible characters: no control characters, no invisible "format" characters
     * (zero-width spaces, the controls that reverse the direction of text), no private-use, unassigned or lone surrogate
     * code points, no line or paragraph separators, and no long runs of combining marks.
     */
    fun isPlain(text: String): Boolean {
        var run = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return false
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> {
                    run++
                    if (run > MAX_COMBINING_RUN) return false
                }
                else -> run = 0
            }
        }
        return true
    }
}

/**
 * Names that would let an extension pass for part of Portal or Android. An extension's name, its tiles' names and its
 * buttons' words appear in Portal's own style, so none of them may be (or hide) "Settings", "Portal", "Parent PIN" and the
 * like: a fake Settings button could open a look-alike PIN screen.
 */
object Impersonation {

    /** Words that are refused only when they stand alone as a word (a name like "Update" but not "Updates for my pets"). */
    private val WORDS = setOf(
        "portal", "settings", "setting", "android", "google", "system", "parent", "parents", "pin", "profile", "profiles",
        "update", "search", "launcher"
    )

    /** Longer names, refused wherever they appear once spaces and punctuation are ignored ("S e t t i n g s", "Portal-TV"). */
    private val INSIDE = listOf("portal", "settings", "android", "google", "launcher", "parentpin")

    // Letters from other alphabets that look like Latin ones, folded so "Pоrtal" (Cyrillic о) is caught.
    private val LOOKALIKES = mapOf(
        'а' to 'a', 'в' to 'b', 'е' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'т' to 't',
        'у' to 'y', 'х' to 'x', 'і' to 'i', 'ѕ' to 's', 'ј' to 'j', 'ԁ' to 'd', 'ɡ' to 'g', 'ο' to 'o', 'ν' to 'v', 'α' to 'a',
        'ι' to 'i', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'κ' to 'k', 'ѵ' to 'v', '0' to 'o', '1' to 'l', '5' to 's'
    )

    /** What [text] looks like to a person: compatibility forms folded (fullwidth letters), case ignored, lookalikes made Latin. */
    internal fun looksLike(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).map { LOOKALIKES[it] ?: it }.joinToString("")

    /** Why [text] can't be used as a name, or null if it can. */
    fun reason(text: String): String? {
        val folded = looksLike(text)
        val words = folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val collapsed = folded.filter { it.isLetterOrDigit() }
        val hit = words.firstOrNull { it in WORDS } ?: INSIDE.firstOrNull { it in collapsed }
        return if (hit != null) "can pass for part of Portal or Android (\"$hit\")." else null
    }
}
