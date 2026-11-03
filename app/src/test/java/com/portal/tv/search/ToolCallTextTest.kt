package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallTextTest {

    @Test
    fun aJsonCallInTagsIsFound() {
        val e = ToolCallText.extract("""<tool_call>
{"name": "web_search", "arguments": {"query": "mayor of portland"}}
</tool_call>""")
        val call = e.calls.single()
        assertEquals("web_search", call.name)
        assertEquals("mayor of portland", Tools.argument(call.arguments, "query"))
        assertEquals("", e.text)
    }

    @Test
    fun wordsAroundTheCallAreKept() {
        val e = ToolCallText.extract("Let me look that up.\n<tool_call>{\"name\":\"open_app\",\"arguments\":{\"name\":\"YouTube\"}}</tool_call>")
        assertEquals("Let me look that up.", e.text)
        assertEquals("open_app", e.calls.single().name)
    }

    @Test
    fun argumentsGivenAsAStringAreKeptAsWritten() {
        val e = ToolCallText.extract("""<tool_call>{"name":"web_search","arguments":"{\"query\":\"x\"}"}</tool_call>""")
        assertEquals("""{"query":"x"}""", e.calls.single().arguments)
    }

    @Test
    fun theOpenAiShapeWithAFunctionObjectIsRead() {
        val e = ToolCallText.extract("""<tool_call>{"type":"function","function":{"name":"web_search","arguments":{"query":"a"}}}</tool_call>""")
        assertEquals("web_search", e.calls.single().name)
        assertEquals("a", Tools.argument(e.calls.single().arguments, "query"))
    }

    @Test
    fun theXmlStyleIsRead() {
        val e = ToolCallText.extract("<tool_call>\n<function=web_search>\n<parameter=query>\nweather today\n</parameter>\n</function>\n</tool_call>")
        val call = e.calls.single()
        assertEquals("web_search", call.name)
        assertEquals("weather today", Tools.argument(call.arguments, "query"))
    }

    @Test
    fun aCallThatWasNeverClosedIsStillRun() {
        val e = ToolCallText.extract("""<tool_call>{"name":"web_search","arguments":{"query":"x"}}""")
        assertEquals("web_search", e.calls.single().name)
    }

    @Test
    fun severalCallsGetDifferentIds() {
        val e = ToolCallText.extract("""<tool_call>{"name":"a","arguments":{}}</tool_call><tool_call>{"name":"b","arguments":{}}</tool_call>""")
        assertEquals(listOf("a", "b"), e.calls.map { it.name })
        assertEquals(2, e.calls.map { it.id }.toSet().size)
    }

    @Test
    fun brokenOrNamelessCallsAreDroppedButStillHidden() {
        val e = ToolCallText.extract("Hi <tool_call>{not json}</tool_call> there <tool_call>{\"arguments\":{}}</tool_call>")
        assertTrue(e.calls.isEmpty())
        assertEquals("Hi  there", e.text)
    }

    @Test
    fun plainTextHasNoCalls() {
        val e = ToolCallText.extract("Nothing special here.")
        assertEquals("Nothing special here.", e.text)
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun aStreamingReplyShowsNothingOfATagOnceItStarts() {
        assertEquals("Let me search ", ToolCallText.visiblePrefix("Let me search <tool_call>{\"na"))
        assertEquals("", ToolCallText.visiblePrefix("<tool_call>"))
    }

    @Test
    fun aTagThatIsStillBeingWrittenIsHeldBack() {
        assertEquals("Sure ", ToolCallText.visiblePrefix("Sure <"))
        assertEquals("Sure ", ToolCallText.visiblePrefix("Sure <tool_"))
        assertEquals("Sure ", ToolCallText.visiblePrefix("Sure <tool_call"))
    }

    @Test
    fun anAngleBracketThatIsNotATagShowsAsSoonAsItIsClear() {
        assertEquals("a < b", ToolCallText.visiblePrefix("a < b"))
        assertEquals("1 <b>bold", ToolCallText.visiblePrefix("1 <b>bold"))
    }
}
