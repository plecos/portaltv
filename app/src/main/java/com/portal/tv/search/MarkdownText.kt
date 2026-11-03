package com.portal.tv.search

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/** Turns a markdown reply into styled text for a chat bubble (the reading is done by [Markdown]). */
object MarkdownText {

    fun toSpanned(markdown: String): SpannableStringBuilder {
        val rendered = Markdown.render(markdown)
        val text = SpannableStringBuilder(rendered.text)
        fun add(span: Any, from: Int, to: Int) = text.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (s in rendered.spans) {
            when (s.style) {
                Markdown.Style.BOLD -> add(StyleSpan(Typeface.BOLD), s.start, s.end)
                Markdown.Style.ITALIC -> add(StyleSpan(Typeface.ITALIC), s.start, s.end)
                Markdown.Style.STRIKE -> add(StrikethroughSpan(), s.start, s.end)
                Markdown.Style.LINK -> {
                    add(UnderlineSpan(), s.start, s.end)
                    add(ForegroundColorSpan(0xFFB7B2FF.toInt()), s.start, s.end)
                }
                Markdown.Style.CODE -> {
                    add(TypefaceSpan("monospace"), s.start, s.end)
                    add(BackgroundColorSpan(0x33FFFFFF), s.start, s.end)
                }
                Markdown.Style.CODE_BLOCK -> {
                    add(TypefaceSpan("monospace"), s.start, s.end)
                    add(BackgroundColorSpan(0x22FFFFFF), s.start, s.end)
                    add(RelativeSizeSpan(0.9f), s.start, s.end)
                }
                Markdown.Style.HEADING -> {
                    add(StyleSpan(Typeface.BOLD), s.start, s.end)
                    add(RelativeSizeSpan((1.3f - 0.05f * (s.level - 1)).coerceAtLeast(1.05f)), s.start, s.end)
                }
                Markdown.Style.QUOTE -> {
                    add(StyleSpan(Typeface.ITALIC), s.start, s.end)
                    add(ForegroundColorSpan(Color.parseColor("#C9C9DD")), s.start, s.end)
                }
            }
        }
        return text
    }
}
