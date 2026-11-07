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

    /** Package-name parts only Portal's own apps may use: a name like "tv.portal.settings" looks official on a card. */
    private val RESERVED_PARTS = setOf("portal", "portaltv", "getportal", "getportaltv")

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
        "ı=i ɩ=i ł=l ø=o đ=d ħ=h ɡ=g ᴏ=o ᴜ=u ᴠ=v ᴡ=w ᴢ=z ʟ=l ɴ=n ʀ=r ɪ=i ꜱ=s ᴅ=d ᴇ=e ᴛ=t ᴍ=m ᴘ=p ᴋ=k ᴀ=a ᴃ=b ᴊ=j ɢ=g ʜ=h ꜰ=f ᴉ=i ǀ=i ƿ=p ꝑ=p ɿ=r ꞁ=l ꝺ=d ꞓ=c ᵽ=p ɍ=r ꞅ=r ʙ=b ᴙ=r ɾ=r ꞇ=t ꞔ=c ᴄ=c ʏ=y ɑ=a ց=g ο=o".split(' ')
            .forEach { put(it[0], it[2]) }
        // Digits that pass for letters
        "0=o 1=i 5=s 3=e 4=a 7=t 8=b".split(' ').forEach { put(it[0], it[2]) }
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
        val greek = if (alternate) ALTERNATE else PLAIN_GREEK
        val out = StringBuilder(noAccents.length)
        var i = 0
        while (i < noAccents.length) {
            val cp = noAccents.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp).toByte() == Character.NON_SPACING_MARK) continue
            // Unicode's list first (every character it says is drawn like Latin), then the few extra readings kept here.
            val unicode = Confusables.lookalikeOf(cp)
            if (unicode != null) { out.append(unicode); continue }
            val c = if (cp < 0x10000) cp.toChar() else null
            val lower = if (c != null) c.lowercaseChar() else null
            if (lower != null) out.append(greek[lower] ?: LOOKALIKES[lower] ?: lower) else out.appendCodePoint(cp)
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    /** Letters that pass for each other in many typefaces (i, l and 1; q and g) are made one before comparing. */
    private fun same(text: String): String = text.replace('l', 'i').replace('q', 'g')

    private val WORDS_SAME = WORDS.associateBy { same(looksLike(it)) }
    private val INSIDE_SAME = INSIDE.associateBy { same(looksLike(it)) }

    private fun hit(folded: String): String? {
        val words = folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map { same(it) }
        val collapsed = same(folded.filter { it.isLetterOrDigit() })
        return words.firstNotNullOfOrNull { WORDS_SAME[it] } ?: INSIDE_SAME.entries.firstOrNull { it.key in collapsed }?.value
    }

    /** True if a word mixes Latin letters with another alphabet ("Pօrtal", "ᑭortal"): that is how lookalikes are made. */
    private fun mixesAlphabets(text: String): Boolean = text.split(Regex("[^\\p{L}\\p{M}]+")).any { word ->
        val scripts = word.codePoints().toArray().map { Character.UnicodeScript.of(it) }
            .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }.toSet()
        Character.UnicodeScript.LATIN in scripts && scripts.any { it !in WRITTEN_WITH_LATIN }
    }

    /**
     * True if [text] has letters from an alphabet outside [COMMON_ALPHABETS]. Some rare alphabets (Lisu, Cherokee and others)
     * have capitals drawn like Latin ones, so a whole word in them can read as "SETTINGS" or "PORTAL"; no table of look-alikes
     * can be complete, so names in those alphabets are refused instead. The ones in everyday use stay allowed.
     */
    private fun usesRareAlphabet(text: String): Boolean = text.codePoints().toArray().any { cp ->
        Character.isLetter(cp) && Character.UnicodeScript.of(cp) !in COMMON_ALPHABETS
    }

    private val COMMON_ALPHABETS: Set<Character.UnicodeScript> = listOf(
        "LATIN", "COMMON", "INHERITED", "CYRILLIC", "GREEK", "ARABIC", "HEBREW", "ARMENIAN", "GEORGIAN", "DEVANAGARI", "BENGALI",
        "GUJARATI", "GURMUKHI", "TAMIL", "TELUGU", "KANNADA", "MALAYALAM", "SINHALA", "THAI", "LAO", "KHMER", "MYANMAR", "TIBETAN",
        "ETHIOPIC", "HAN", "HIRAGANA", "KATAKANA", "HANGUL", "BOPOMOFO"
    ).map { Character.UnicodeScript.valueOf(it) }.toSet()

    /** Scripts that are ordinarily written next to Latin letters (as in "TVアプリ") and have no letters made to look Latin. Every other script next to Latin is refused. */
    private val WRITTEN_WITH_LATIN = setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO
    )

    /** Why [text] can't be used as a name, or null if it can. */
    fun reason(text: String): String? {
        if (usesRareAlphabet(text)) return "uses letters from an alphabet that is rarely used for names and easy to make look like Latin ones."
        if (mixesAlphabets(text)) return "mixes Latin letters with another alphabet's look-alikes in one word."
        val found = hit(looksLike(text)) ?: hit(looksLike(text, alternate = true))
        return if (found != null) "can pass for part of Portal or Android (\"$found\")." else null
    }

    /**
     * Whether [packageName] has a part that is Portal's own name however it is joined or numbered: split at dots, at
     * underscores and at dashes, and also read with those and any digits taken out ("portal_tv", "getportaltv2").
     */
    fun isReservedPackage(packageName: String): Boolean = joinsPortalWithAnother(packageName) || packageName.split('.').any { segment ->
        val pieces = segment.split('_', '-') + segment
        pieces.any { piece ->
            // As written (so "p0rtal" is read as letters), and with any digits taken out (so "getportaltv2" is too).
            listOf(piece.filter { it.isLetterOrDigit() }, piece.filter { it.isLetter() }).any { candidate ->
                candidate.isNotEmpty() && (same(looksLike(candidate)) in RESERVED_SAME || same(looksLike(candidate, alternate = true)) in RESERVED_SAME)
            }
        }
    }

    /**
     * "portal" alone in a part is a fair name for something that works with Portal ("com.acme.portalclock"), but "portal" with
     * another official word anywhere in the package ("com.myportal.settings") reads as Portal's own app. Names that contain
     * getportal or portaltv are reserved outright.
     */
    private fun joinsPortalWithAnother(packageName: String): Boolean {
        val joined = same(looksLike(packageName.filter { it.isLetterOrDigit() }))
        if (same(looksLike("getportal")) in joined || same(looksLike("portaltv")) in joined) return true
        return same(looksLike("portal")) in joined && listOf("settings", "parental", "parentpin", "passcode", "launcher", "android", "google").any { same(looksLike(it)) in joined }
    }

    private val RESERVED_SAME = RESERVED_PARTS.map { same(looksLike(it)) }.toSet()
}
