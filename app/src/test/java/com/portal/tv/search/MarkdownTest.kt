package com.portal.tv.search

import com.portal.tv.search.Markdown.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun styled(r: Markdown.Rendered, style: Style) =
        r.spans.filter { it.style == style }.map { r.text.substring(it.start, it.end) }

    @Test
    fun plainTextComesBackUnchanged() {
        val r = Markdown.render("Just a sentence.")
        assertEquals("Just a sentence.", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun boldAndItalicLoseTheirMarks() {
        val r = Markdown.render("This is **bold**, *italic* and __also bold__ and _also italic_.")
        assertEquals("This is bold, italic and also bold and also italic.", r.text)
        assertEquals(listOf("bold", "also bold"), styled(r, Style.BOLD))
        assertEquals(listOf("italic", "also italic"), styled(r, Style.ITALIC))
    }

    @Test
    fun boldAndItalicCanNest() {
        val r = Markdown.render("A **bold and *italic* part** here")
        assertEquals("A bold and italic part here", r.text)
        assertEquals(listOf("bold and italic part"), styled(r, Style.BOLD))
        assertEquals(listOf("italic"), styled(r, Style.ITALIC))
    }

    @Test
    fun inlineCodeIsKeptLiterally() {
        val r = Markdown.render("Run `ls **-la**` now")
        assertEquals("Run ls **-la** now", r.text)
        assertEquals(listOf("ls **-la**"), styled(r, Style.CODE))
        assertTrue(styled(r, Style.BOLD).isEmpty())
    }

    @Test
    fun strikethroughAndLinks() {
        val r = Markdown.render("~~old~~ and [the docs](https://example.com/x)")
        assertEquals("old and the docs", r.text)
        assertEquals(listOf("old"), styled(r, Style.STRIKE))
        assertEquals(listOf("the docs"), styled(r, Style.LINK))
    }

    @Test
    fun aMarkThatIsNotClosedYetStaysAsTyped() {
        // while a reply streams in, "**bold" has no end yet
        assertEquals("This is **bold", Markdown.render("This is **bold").text)
        assertEquals("Half *done", Markdown.render("Half *done").text)
        assertEquals("A `code", Markdown.render("A `code").text)
    }

    @Test
    fun mathAndSnakeCaseAreNotEmphasis() {
        val r = Markdown.render("2 * 3 * 4 = 24 and my_variable_name is fine")
        assertEquals("2 * 3 * 4 = 24 and my_variable_name is fine", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun headingsLoseTheirHashesAndAreMarked() {
        val r = Markdown.render("## A heading\nSome text")
        assertEquals("A heading\nSome text", r.text)
        val h = r.spans.single { it.style == Style.HEADING }
        assertEquals("A heading", r.text.substring(h.start, h.end))
        assertEquals(2, h.level)
    }

    @Test
    fun listsGetBulletsAndKeepTheirNumbers() {
        val r = Markdown.render("- Milk\n* Eggs\n1. First\n2) Second\n  - Nested")
        assertEquals("• Milk\n• Eggs\n1. First\n2) Second\n  • Nested", r.text)
    }

    @Test
    fun marksInsideListItemsAreStyled() {
        val r = Markdown.render("- **Bold** item")
        assertEquals("• Bold item", r.text)
        assertEquals(listOf("Bold"), styled(r, Style.BOLD))
    }

    @Test
    fun quotesAreMarked() {
        val r = Markdown.render("> wise words")
        assertEquals("wise words", r.text)
        assertEquals(listOf("wise words"), styled(r, Style.QUOTE))
    }

    @Test
    fun fencedCodeIsKeptAsIsAndMarkedAsABlock() {
        val r = Markdown.render("Run:\n```kotlin\nval a = **1**\nprintln(a)\n```\nDone")
        assertEquals("Run:\nval a = **1**\nprintln(a)\nDone", r.text)
        assertEquals(listOf("val a = **1**\nprintln(a)"), styled(r, Style.CODE_BLOCK))
    }

    @Test
    fun anUnclosedFenceIsStillACodeBlock() {
        val r = Markdown.render("```\nstill typing")
        assertEquals("still typing", r.text)
        assertEquals(listOf("still typing"), styled(r, Style.CODE_BLOCK))
    }

    @Test
    fun blankLinesAreKeptButNotPiledUp() {
        assertEquals("One\n\nTwo", Markdown.render("\n\nOne\n\n\n\nTwo\n\n").text)
    }

    @Test
    fun aRuleBecomesABlankLine() {
        assertEquals("Above\n\nBelow", Markdown.render("Above\n---\nBelow").text)
    }

    @Test
    fun anEscapedMarkIsLiteral() {
        assertEquals("a *star* here", Markdown.render("a \\*star\\* here").text)
    }
}
