package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseTest {

    @Test
    fun readsATextPiece() {
        val line = """data: {"choices":[{"delta":{"content":"Hello"}}]}"""
        assertEquals(Sse.Event.Text("Hello"), Sse.parse(line))
    }

    @Test
    fun keepsSpacesAndNewlinesInsideTheText() {
        val line = """data: {"choices":[{"delta":{"content":" world\n"}}]}"""
        assertEquals(Sse.Event.Text(" world\n"), Sse.parse(line))
    }

    @Test
    fun doneEndsTheStream() {
        assertEquals(Sse.Event.Done, Sse.parse("data: [DONE]"))
        assertEquals(Sse.Event.Done, Sse.parse("data:[DONE]"))
    }

    @Test
    fun linesWithNothingToShowAreIgnored() {
        assertNull(Sse.parse(""))
        assertNull(Sse.parse(": keep-alive"))
        assertNull(Sse.parse("event: message"))
        assertNull(Sse.parse("data:"))
        // the opening chunk carries only the role
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        // the closing chunk carries only a finish reason
        assertNull(Sse.parse("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertNull(Sse.parse("""data: {"choices":[]}"""))
    }

    @Test
    fun brokenJsonIsIgnoredRatherThanCrashing() {
        assertNull(Sse.parse("data: {not json"))
    }

    @Test
    fun anErrorInsideTheStreamIsReported() {
        val line = """data: {"error":{"message":"Rate limit reached"}}"""
        assertEquals(Sse.Event.Error("Rate limit reached"), Sse.parse(line))
    }

    @Test
    fun aNullContentIsNotTheWordNull() {
        // llama.cpp and others send "content":null in the opening chunk
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"content":null},"finish_reason":null}]}"""))
    }

    @Test
    fun nullToolCallFieldsAreAbsentNotTheWordNull() {
        val line = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"{\"a\":1}"}}]}}]}"""
        val delta = (Sse.parse(line) as Sse.Event.ToolDeltas).deltas.single()
        assertNull(delta.id)
        assertNull(delta.name)
        assertEquals("{\"a\":1}", delta.arguments)
    }

    @Test
    fun aFinishReasonMarksTheEndOfTheAnswer() {
        assertEquals(true, Sse.isFinal("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals(true, Sse.isFinal("""data: {"choices":[{"delta":{"content":"."},"finish_reason":"length"}]}"""))
        assertEquals(true, Sse.isFinal("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""))
    }

    @Test
    fun noFinishReasonMeansTheAnswerGoesOn() {
        assertEquals(false, Sse.isFinal("""data: {"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals(false, Sse.isFinal("""data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}"""))
        assertEquals(false, Sse.isFinal("""data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":""}]}"""))
        assertEquals(false, Sse.isFinal("""data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":"null"}]}"""))
        assertEquals(false, Sse.isFinal("""data: {"choices":[]}"""))
    }

    @Test
    fun linesThatAreNotFinishChunksAreNotFinal() {
        assertEquals(false, Sse.isFinal("data: [DONE]"))
        assertEquals(false, Sse.isFinal(""))
        assertEquals(false, Sse.isFinal(": keep-alive"))
        assertEquals(false, Sse.isFinal("data: not json"))
    }
}
