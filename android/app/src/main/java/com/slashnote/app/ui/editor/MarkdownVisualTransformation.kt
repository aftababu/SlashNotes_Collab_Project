package com.slashnote.app.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.DarkCodeBg
import com.slashnote.app.ui.theme.DarkCodeHighlight
import com.slashnote.app.ui.theme.LightCodeBg
import com.slashnote.app.ui.theme.LightCodeHighlight
import uniffi.slash_notes_core.MarkdownSpan

/**
 * Small LRU cache of parsed markdown spans keyed by a content hash.
 */
class MarkdownSpanCache(private val maxEntries: Int = 10) {
    private val map = object : LinkedHashMap<Long, List<MarkdownSpan>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MarkdownSpan>>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: Long): List<MarkdownSpan>? = map[key]

    @Synchronized
    fun put(key: Long, spans: List<MarkdownSpan>) {
        map[key] = spans
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

/** FNV-1a 64-bit hash for fast caching. */
fun markdownHash(text: String): Long {
    var h = -3750763034362895579L
    for (i in text.indices) {
        h = h xor text[i].code.toLong()
        h *= 1099511628211L
    }
    h = h xor text.length.toLong()
    h *= 1099511628211L
    return h
}

/**
 * Single-pass high-performance Lexer & AST-based VisualTransformation.
 *
 * Provides O(N) linear syntax highlighting for headers, code spans, lists,
 * blockquotes, bold/italic, strikethrough, links, wikilinks, and math blocks
 * with zero typing latency on BasicTextField.
 */
class MarkdownVisualTransformation(
    private val isDarkTheme: Boolean,
    private val cache: MarkdownSpanCache = MarkdownSpanCache(),
    private val searchQuery: String = "",
    private val searchMatches: List<Int> = emptyList(),
    private val currentMatchIndex: Int = 0
) : VisualTransformation {

    private var lastHash: Long = Long.MIN_VALUE
    private var lastDarkTheme: Boolean = !isDarkTheme
    private var lastSearchQuery: String = ""
    private var lastCurrentMatchIndex: Int = -1
    private var lastResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val rawText = text.text
        if (rawText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val hash = markdownHash(rawText)

        if (hash == lastHash && isDarkTheme == lastDarkTheme && searchQuery == lastSearchQuery && currentMatchIndex == lastCurrentMatchIndex && lastResult != null) {
            return lastResult!!
        }

        val spans = cache.get(hash) ?: emptyList()

        val builder = AnnotatedString.Builder(rawText)
        val textLength = rawText.length

        val codeHighlight = if (isDarkTheme) DarkCodeHighlight else LightCodeHighlight
        val codeBg = if (isDarkTheme) DarkCodeBg else LightCodeBg
        val linkColor = Color(0xFF3B82F6)
        val wikilinkColor = if (isDarkTheme) Color(0xFF2DD4BF) else Color(0xFF0D9488)

        for (span in spans) {
            val start = span.start.toInt()
            val end = span.end.toInt()

            if (start >= 0 && end <= textLength && start < end) {
                val style = matchSpanStyle(span.kind, codeHighlight, codeBg, linkColor, wikilinkColor)
                if (style != null) {
                    builder.addStyle(style, start, end)
                }
            }
        }

        // Apply In-Note Search Highlighting
        if (searchQuery.isNotEmpty() && searchMatches.isNotEmpty()) {
            val queryLen = searchQuery.length
            searchMatches.forEachIndexed { idx, matchStart ->
                val matchEnd = (matchStart + queryLen).coerceAtMost(textLength)
                if (matchStart >= 0 && matchEnd <= textLength && matchStart < matchEnd) {
                    val isActive = idx == currentMatchIndex
                    val highlightBg = if (isActive) Color(0xFFF59E0B) else Color(0xFFFDE047)
                    builder.addStyle(
                        SpanStyle(
                            background = highlightBg,
                            color = Color(0xFF111827),
                            fontWeight = FontWeight.Bold
                        ),
                        matchStart,
                        matchEnd
                    )
                }
            }
        }

        val result = TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        lastHash = hash
        lastDarkTheme = isDarkTheme
        lastSearchQuery = searchQuery
        lastCurrentMatchIndex = currentMatchIndex
        lastResult = result
        return result
    }

    private fun matchSpanStyle(
        kind: kotlin.UByte,
        codeHighlight: Color,
        codeBg: Color,
        linkColor: Color,
        wikilinkColor: Color
    ): SpanStyle? {
        return when (kind.toInt()) {
            1, 2 -> SpanStyle(fontSize = if (kind.toInt() == 1) 24.sp else 20.sp, fontWeight = FontWeight.Bold)
            3 -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
            4, 5, 6 -> SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            10 -> SpanStyle(fontStyle = FontStyle.Italic)
            11 -> SpanStyle(fontWeight = FontWeight.Bold)
            12 -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            20, 21 -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = codeHighlight,
                background = codeBg
            )
            30 -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            31 -> SpanStyle(color = wikilinkColor, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
            40 -> SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFC084FC), background = Color(0x22C084FC))
            41 -> SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFC084FC), background = Color(0x33A855F7), fontWeight = FontWeight.SemiBold)
            50 -> SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFF34D399), background = Color(0x3310B981), fontWeight = FontWeight.Bold)
            60 -> SpanStyle(fontStyle = FontStyle.Italic)
            else -> null
        }
    }
}
