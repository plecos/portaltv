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
        "update", "search", "launcher", "parental", "password", "passcode"
    )

    /** Longer names, refused wherever they appear once spaces and punctuation are ignored ("S e t t i n g s", "Portal-TV"). */
    private val INSIDE = listOf("portal", "settings", "android", "google", "launcher", "parentpin", "parental", "passcode")

    /** Package names only Portal's own apps may use: a name like "net.getportaltv.settings" looks official on a card. */
    private val RESERVED_PACKAGES = listOf("net.getportaltv", "com.portal.tv")

    /**
     * Letters from other alphabets (and Latin ones with strokes) that look like Latin ones, folded so "Pоrtal" (Cyrillic о)
     * and "Sεttings" (Greek ε) are caught. Letters are lower case here, because the text is lower-cased first.
     */
    private val LOOKALIKES: Map<Char, Char> = buildMap {
        // Cyrillic
        "а=a в=b е=e к=k м=m н=h о=o р=p с=c т=t у=y х=x і=i ѕ=s ј=j ԁ=d ѵ=v ь=b һ=h ӏ=l ԛ=q ԝ=w ѡ=w".split(' ')
            .forEach { put(it[0], it[2]) }
        // Greek
        "α=a β=b γ=y ε=e ζ=z ι=i κ=k ν=v ο=o ρ=p τ=t υ=u χ=x ω=w ϲ=c ϳ=j ϸ=p".split(' ').forEach { put(it[0], it[2]) }
        // Latin with a stroke or no dot, and a few more
        "ı=i ɩ=i ł=l ø=o đ=d ħ=h ɡ=g ᴏ=o ᴜ=u ᴠ=v ᴡ=w ᴢ=z ʟ=l ɴ=n ʀ=r ɪ=i ꜱ=s ᴅ=d ᴇ=e ᴛ=t ᴍ=m ᴘ=p ᴋ=k ᴄ=c ʏ=y ɑ=a ց=g ο=o".split(' ')
            .forEach { put(it[0], it[2]) }
        // Digits that pass for letters
        "0=o 1=l 5=s 3=e 4=a 7=t 8=b".split(' ').forEach { put(it[0], it[2]) }
    }

    /** Greek letters that look different in capitals and in small letters: read the other way too. */
    private val ALTERNATE: Map<Char, Char> = mapOf('η' to 'h', 'μ' to 'm', 'π' to 'n', 'θ' to 'o', 'φ' to 'o', 'δ' to 'd')
    private val PLAIN_GREEK: Map<Char, Char> = mapOf('η' to 'n', 'μ' to 'u', 'π' to 'n', 'θ' to 'o', 'φ' to 'o', 'δ' to 'd')

    /**
     * What [text] looks like to a person: compatibility forms folded (fullwidth letters), accents removed, case ignored,
     * lookalikes made Latin. [alternate] reads the Greek letters that differ between capitals and small letters the other way.
     */
    internal fun looksLike(text: String, alternate: Boolean = false): String {
        val noAccents = Normalizer.normalize(Normalizer.normalize(text, Normalizer.Form.NFKC), Normalizer.Form.NFD)
            .filter { Character.getType(it).toByte() != Character.NON_SPACING_MARK }
        val greek = if (alternate) ALTERNATE else PLAIN_GREEK
        return noAccents.lowercase(Locale.ROOT).map { greek[it] ?: LOOKALIKES[it] ?: it }.joinToString("")
    }

    private fun hit(folded: String): String? {
        val words = folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val collapsed = folded.filter { it.isLetterOrDigit() }
        return words.firstOrNull { it in WORDS } ?: INSIDE.firstOrNull { it in collapsed }
    }

    /** Why [text] can't be used as a name, or null if it can. */
    fun reason(text: String): String? {
        val found = hit(looksLike(text)) ?: hit(looksLike(text, alternate = true))
        return if (found != null) "can pass for part of Portal or Android (\"$found\")." else null
    }

    /** Whether [packageName] is one only Portal's own apps may use. */
    fun isReservedPackage(packageName: String): Boolean = RESERVED_PACKAGES.any { packageName == it || packageName.startsWith("$it.") }
}
