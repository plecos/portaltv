package com.portal.tv.extensions

import android.content.res.Resources
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/** Turns an extension's compiled XML resource into an [XmlNode] tree, looking up text it points to in its resources. */
internal object XmlAdapter {

    private const val MAX_DEPTH = 6
    private const val MAX_NODES = 300

    /** The first element of [parser] and everything in it, or null if there is none. */
    fun read(parser: XmlResourceParser, res: Resources): XmlNode? {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
        if (event != XmlPullParser.START_TAG) return null
        return element(parser, res, intArrayOf(0))
    }

    private fun element(p: XmlResourceParser, res: Resources, count: IntArray): XmlNode {
        // Somebody else's file: don't follow it forever.
        if (++count[0] > MAX_NODES || p.depth > MAX_DEPTH) throw IllegalStateException("The extension file is too big")
        val attrs = (0 until p.attributeCount).associate { i -> p.getAttributeName(i) to attr(p, i, res) }
        val name = p.name
        val depth = p.depth
        val kids = mutableListOf<XmlNode>()
        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth == depth) break
            if (event == XmlPullParser.START_TAG) kids += element(p, res, count)
        }
        return XmlNode(name, attrs, kids)
    }

    private fun attr(p: XmlResourceParser, index: Int, res: Resources): XmlAttr {
        val id = p.getAttributeResourceValue(index, 0)
        if (id == 0) return XmlAttr(p.getAttributeValue(index) ?: "")
        // A reference: read the text if it is a string; for anything else (an icon) only the id is wanted.
        val text = runCatching { if (res.getResourceTypeName(id) == "string") res.getString(id) else "" }.getOrDefault("")
        return XmlAttr(text, id)
    }
}
