package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTextTest {

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("It is sunny today.", SpeechText.clean("It is sunny today."))
    }

    @Test
    fun markdownMarksAreRemoved() {
        assertEquals("This is bold and italic and code.", SpeechText.clean("This is **bold** and _italic_ and `code`."))
        assertEquals("A heading\nSome text", SpeechText.clean("## A heading\nSome text"))
    }

    @Test
    fun listsAreReadWithoutTheirMarkers() {
        assertEquals("Milk\nEggs\nBread", SpeechText.clean("- Milk\n* Eggs\n1. Bread"))
    }

    @Test
    fun linksKeepTheirLabelAndBareAddressesAreDropped() {
        assertEquals("See the docs for more", SpeechText.clean("See [the docs](https://example.com/x) for more"))
        assertEquals("Go to now", SpeechText.clean("Go to https://example.com/page now"))
    }

    @Test
    fun codeBlocksAreSkipped() {
        assertEquals("Run this\nThen done", SpeechText.clean("Run this\n```\nrm -rf x\n```\nThen done"))
    }

    @Test
    fun emojiAreDropped() {
        assertEquals("Great job!", SpeechText.clean("Great job! 🎉"))
    }

    @Test
    fun sentencesComeOutAsTheyComplete() {
        val s = SpeechText.SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Hello th"))
        assertEquals(listOf("Hello there."), s.feed("ere. How are"))
        assertEquals(listOf("How are you?"), s.feed(" you? Fine"))
        assertEquals("Fine", s.flush())
        assertNull(s.flush())
    }

    @Test
    fun aMarkAtTheVeryEndWaitsForTheNextCharacter() {
        val s = SpeechText.SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("It costs 3.5"))   // a decimal point is not an end
        assertEquals(emptyList<String>(), s.feed(" dollars."))      // the last mark waits
        assertEquals(listOf("It costs 3.5 dollars."), s.feed(" Next"))
    }

    @Test
    fun lineBreaksEndASentence() {
        val s = SpeechText.SentenceSplitter()
        assertEquals(listOf("First item", "Second item"), s.feed("First item\nSecond item\nThird"))
        assertEquals("Third", s.flush())
    }

    @Test
    fun aSingleLetterOrAListNumberIsNotASentence() {
        val s = SpeechText.SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("1. Milk"))
        assertEquals(listOf("1. Milk and J. Smith came."), s.feed(" and J. Smith came. Ok"))
    }

    @Test
    fun aSpokenWordIsFoundInTheReplyFromWhereTheLastOneEnded() {
        val reply = "It is **sunny** today, and sunny tomorrow."
        val first = SpeechText.locate(reply, "sunny", 0)!!
        assertEquals("sunny", reply.substring(first.first, first.last + 1))
        val second = SpeechText.locate(reply, "sunny", first.last + 1)!!
        assertEquals(reply.indexOf("sunny tomorrow"), second.first)
    }

    @Test
    fun wordsAreMatchedWithoutCaseOrPunctuation() {
        assertEquals(0..4, SpeechText.locate("Hello, world", "hello,", 0))
        assertNull(SpeechText.locate("Hello world", "missing", 0))
        assertNull(SpeechText.locate("Hello world", "", 0))
        assertNull(SpeechText.locate("Hello", "Hello", 99))
    }
}
