package com.slashnote.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.StitchAccentCoral
import com.slashnote.app.ui.theme.StitchBackground
import com.slashnote.app.ui.theme.StitchBorder
import com.slashnote.app.ui.theme.StitchCardBg
import com.slashnote.app.ui.theme.StitchCodeBg
import com.slashnote.app.ui.theme.StitchCodeHighlight
import com.slashnote.app.ui.theme.StitchTextMuted

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState

/**
 * Compose-native Markdown renderer used in preview / read mode.
 *
 * Supports: headings, paragraphs, bold/italic/strikethrough, inline code,
 * links, wikilinks `[[note]]`, ordered/unordered/task lists, blockquotes,
 * tables, fenced code blocks (incl. mermaid), inline `$math$` and block `$$math$$`.
 */
@Composable
fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
    headingAnchor: String? = null,
    initialScrollRatio: Float = 0f,
    onScrollRatioChanged: ((Float) -> Unit)? = null,
    onWikilinkClick: ((String) -> Unit)? = null
) {
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }

    LaunchedEffect(content) {
        blocks = withContext(Dispatchers.Default) { parseMarkdownBlocks(content) }
    }
    val listState = rememberLazyListState()

    val headingIndexMap = remember(blocks) {
        val map = mutableMapOf<String, Int>()
        blocks.forEachIndexed { index, block ->
            if (block is MdBlock.Heading) {
                val rawText = block.content.trim()
                val normalized = rawText.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                map[normalized] = index
                map[rawText.lowercase()] = index
                map[rawText.lowercase().replace(" ", "")] = index
                map[rawText.lowercase().replace(" ", "-")] = index
            }
        }
        map
    }

    LaunchedEffect(initialScrollRatio, blocks) {
        if (headingAnchor.isNullOrBlank() && initialScrollRatio > 0f && blocks.isNotEmpty()) {
            val targetIndex = (blocks.size * initialScrollRatio).toInt().coerceIn(0, blocks.size - 1)
            listState.scrollToItem(targetIndex)
        }
    }

    LaunchedEffect(headingAnchor, blocks) {
        if (!headingAnchor.isNullOrBlank()) {
            val normAnchor = headingAnchor.trim().lowercase().removePrefix("#").replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val rawAnchor = headingAnchor.trim().lowercase().removePrefix("#")
            val targetIndex = headingIndexMap[normAnchor]
                ?: headingIndexMap[rawAnchor]
                ?: headingIndexMap[rawAnchor.replace("-", "")]
                ?: headingIndexMap[rawAnchor.replace("-", " ")]

            targetIndex?.let { idx ->
                listState.animateScrollToItem(idx)
            }
        }
    }

    val firstVisibleIndex = listState.firstVisibleItemIndex
    LaunchedEffect(firstVisibleIndex, blocks.size) {
        if (blocks.isNotEmpty()) {
            val ratio = if (blocks.size > 1) firstVisibleIndex.toFloat() / (blocks.size - 1).toFloat() else 0f
            onScrollRatioChanged?.invoke(ratio)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(blocks, key = { index, block -> index * 31 + block.hashCode() }) { index, block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, content)
                is MdBlock.Paragraph -> ParagraphBlock(block, content, onWikilinkClick)
                is MdBlock.ListBlock -> ListBlock(block, content, onWikilinkClick)
                is MdBlock.Blockquote -> BlockquoteBlock(block, content, onWikilinkClick)
                is MdBlock.CodeBlock -> CodeBlockView(block)
                is MdBlock.MathBlock -> MathBlockView(block)
                is MdBlock.TableBlock -> TableBlock(block)
                is MdBlock.HorizontalRule -> HorizontalDivider(color = StitchBorder, thickness = 1.dp)
                is MdBlock.ListItem -> Unit
            }
        }
        if (blocks.isEmpty()) {
            item {
                Text("Empty note", color = StitchTextMuted, fontSize = 13.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing model
// ---------------------------------------------------------------------------

internal sealed class MdBlock {
    class Heading(val level: Int, val content: String) : MdBlock()
    class Paragraph(val content: String) : MdBlock()
    class ListBlock(
        val ordered: Boolean,
        val items: List<ListItem>,
        val startNumber: Int
    ) : MdBlock()

    class ListItem(val checked: Boolean?, val content: String) : MdBlock()
    class Blockquote(val content: String) : MdBlock()
    class CodeBlock(val language: String, val code: String) : MdBlock()
    class MathBlock(val content: String) : MdBlock()
    class TableBlock(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<Align>
    ) : MdBlock()

    class HorizontalRule : MdBlock()
}

internal enum class Align { LEFT, CENTER, RIGHT }

internal fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    val n = lines.size

    fun isFenceStart(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    fun fenceChar(line: String): Char = line.trimStart().firstOrNull() ?: '`'

    fun fenceLen(line: String): Int {
        val t = line.trimStart()
        var c = 0
        while (c < t.length && t[c] == fenceChar(line)) c++
        return c
    }

    while (i < n) {
        val line = lines[i]
        val trimmed = line.trim()

        // Blank line -> skip
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Horizontal rule
        if (trimmed.matches(Regex("^(\\*{3,}|-{3,}|_{3,})$"))) {
            blocks.add(MdBlock.HorizontalRule())
            i++
            continue
        }

        // Fenced code block
        if (isFenceStart(line)) {
            val fc = fenceChar(line)
            val fl = fenceLen(line)
            val lang = line.trimStart().substring(fl).trim().substringBefore(" ")
            val codeLines = mutableListOf<String>()
            i++
            var closed = false
            while (i < n) {
                val l = lines[i]
                val t = l.trimStart()
                if (t.startsWith(fc.toString().repeat(fl))) {
                    closed = true
                    i++
                    break
                }
                codeLines.add(l)
                i++
            }
            if (!closed) {
                // Unterminated fence: treat remaining lines as code
                blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
                break
            }
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // Indented code block (4+ spaces)
        if (line.startsWith("    ") || line.startsWith("\t")) {
            val codeLines = mutableListOf<String>()
            while (i < n && (lines[i].startsWith("    ") || lines[i].startsWith("\t"))) {
                codeLines.add(lines[i].trimStart(' ', '\t'))
                i++
            }
            blocks.add(MdBlock.CodeBlock("", codeLines.joinToString("\n")))
            continue
        }

        // Block math: $$ ... $$ (multi-line or single-line)
        if (trimmed.startsWith("$$")) {
            val mathLines = mutableListOf<String>()
            // Case: "$$expr$$" on one line
            if (trimmed.length > 2 && trimmed.endsWith("$$") && trimmed.length >= 4) {
                val inner = trimmed.substring(2, trimmed.length - 2)
                if (inner.isNotEmpty()) {
                    blocks.add(MdBlock.MathBlock(inner))
                    i++
                    continue
                }
            }
            // Multi-line block
            i++
            var closed = false
            while (i < n) {
                if (lines[i].trim() == "$$" || (lines[i].trim().startsWith("$$") && lines[i].trim().endsWith("$$"))) {
                    closed = true
                    i++
                    break
                }
                mathLines.add(lines[i])
                i++
            }
            val content = mathLines.joinToString("\n")
            if (content.isNotEmpty()) {
                blocks.add(MdBlock.MathBlock(content))
            }
            if (!closed) break
            continue
        }

        // Heading
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null && !trimmed.startsWith("## ")) {
            val level = headingMatch.groupValues[1].length
            blocks.add(MdBlock.Heading(level, headingMatch.groupValues[2]))
            i++
            continue
        }
        // Allow ATX heading like "#### title" (regex already requires space; handle "###")
        if (Regex("^#{1,6}\\s+").containsMatchIn(trimmed) && headingMatch != null) {
            blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < n && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            blocks.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            continue
        }

        // Table
        if (trimmed.contains("|") && i + 1 < n) {
            val next = lines[i + 1].trim()
            if (next.matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$")) && next.contains("-")) {
                val headerRow = splitTableRow(trimmed)
                val alignRow = splitTableRow(next)
                val alignments = alignRow.map { col ->
                    val c = col.trim()
                    when {
                        c.startsWith(":") && c.endsWith(":") -> Align.CENTER
                        c.endsWith(":") -> Align.RIGHT
                        c.startsWith(":") -> Align.LEFT
                        else -> Align.LEFT
                    }
                }
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < n && lines[i].trim().isNotEmpty() && lines[i].contains("|")) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                blocks.add(MdBlock.TableBlock(headerRow, rows, alignments))
                continue
            }
        }

        // List (ordered / unordered / task)
        val listMatch = Regex("^\\s*([-*+]|\\d+\\.)\\s+(.*)$").find(line)
        if (listMatch != null) {
            val ordered = listMatch.groupValues[1].any { it.isDigit() }
            val startNumber = if (ordered) listMatch.groupValues[1].dropLast(1).toIntOrNull() ?: 1 else 1
            val items = mutableListOf<MdBlock.ListItem>()
            while (i < n) {
                val m = Regex("^\\s*([-*+]|\\d+\\.)\\s+(.*)$").find(lines[i])
                if (m == null) {
                    // Continuation line (indented)
                    if (items.isNotEmpty() && (lines[i].startsWith("  ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                        items[items.size - 1] = MdBlock.ListItem(
                            items[items.size - 1].checked,
                            items[items.size - 1].content + "\n" + lines[i].trim()
                        )
                        i++
                        continue
                    }
                    break
                }
                val marker = m.groupValues[1]
                val isOrderedMarker = marker.any { it.isDigit() }
                if (isOrderedMarker != ordered) break
                var content = m.groupValues[2]
                var checked: Boolean? = null
                val taskMatch = Regex("^\\[([ xX])\\]\\s+(.*)$").find(content)
                if (taskMatch != null) {
                    checked = taskMatch.groupValues[1] in listOf("x", "X")
                    content = taskMatch.groupValues[2]
                }
                items.add(MdBlock.ListItem(checked, content))
                i++
            }
            blocks.add(MdBlock.ListBlock(ordered, items, startNumber))
            continue
        }

        // Paragraph (accumulate until blank or block start)
        val paraLines = mutableListOf<String>()
        while (i < n) {
            val l = lines[i]
            if (l.trim().isEmpty()) break
            if (Regex("^#{1,6}\\s+").containsMatchIn(l.trim())) break
            if (isFenceStart(l)) break
            if (l.trim().matches(Regex("^(\\*{3,}|-{3,}|_{3,})$"))) break
            if (l.trimStart().startsWith(">")) break
            if (Regex("^\\s*([-*+]|\\d+\\.)\\s+").containsMatchIn(l)) break
            paraLines.add(l)
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
        } else {
            i++
        }
    }

    return blocks
}

private fun splitTableRow(row: String): List<String> {
    var s = row.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    val cols = mutableListOf<String>()
    var cur = StringBuilder()
    var inCode = false
    for (c in s) {
        when {
            c == '`' -> { inCode = !inCode; cur.append(c) }
            c == '|' && !inCode -> { cols.add(cur.toString().trim()); cur = StringBuilder() }
            else -> cur.append(c)
        }
    }
    cols.add(cur.toString().trim())
    return cols
}

// ---------------------------------------------------------------------------
// Rendering helpers
// ---------------------------------------------------------------------------

@Composable
private fun HeadingBlock(block: MdBlock.Heading, raw: String) {
    val size = when (block.level) {
        1 -> 26.sp
        2 -> 22.sp
        3 -> 19.sp
        4 -> 17.sp
        else -> 15.sp
    }
    Text(
        text = inlineMarkdown(block.content, raw),
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = size * 1.25f
    )
}

@Composable
private fun ClickableMarkdownText(
    annotatedString: AnnotatedString,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 21.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onWikilinkClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    @Suppress("DEPRECATION")
    androidx.compose.foundation.text.ClickableText(
        text = annotatedString,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color
        ),
        modifier = modifier,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "WIKILINK", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    onWikilinkClick?.invoke(annotation.item)
                }
        }
    )
}

@Composable
private fun ParagraphBlock(block: MdBlock.Paragraph, raw: String, onWikilinkClick: ((String) -> Unit)?) {
    ClickableMarkdownText(
        annotatedString = inlineMarkdown(block.content, raw),
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurface,
        onWikilinkClick = onWikilinkClick
    )
}

@Composable
private fun ListBlock(block: MdBlock.ListBlock, raw: String, onWikilinkClick: ((String) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Marker / bullet
                Box(modifier = Modifier.width(22.dp)) {
                    when {
                        item.checked != null -> {
                            Icon(
                                imageVector = if (item.checked == true) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (item.checked == true) StitchAccentCoral else StitchTextMuted,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp)
                            )
                        }
                        block.ordered -> {
                            Text(
                                text = "${block.startNumber + index}.",
                                color = StitchTextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.width(22.dp).padding(top = 1.dp)
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .padding(top = 4.dp)
                                    .background(StitchAccentCoral, RoundedCornerShape(50))
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                ClickableMarkdownText(
                    annotatedString = inlineMarkdown(item.content, raw),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    onWikilinkClick = onWikilinkClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BlockquoteBlock(block: MdBlock.Blockquote, raw: String, onWikilinkClick: ((String) -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(StitchAccentCoral, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = inlineMarkdown(block.content, raw),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontStyle = FontStyle.Italic,
            color = StitchTextMuted
        )
    }
}

private object SyntaxHighlighter {
    private val stringColor = Color(0xFF10B981) // Green
    private val keywordColor = Color(0xFF3B82F6) // Blue
    private val numberColor = Color(0xFFF59E0B) // Orange
    private val commentColor = Color(0xFF6B7280) // Gray
    private val typeColor = Color(0xFF8B5CF6) // Purple

    private val stringPattern = Regex("""(".*?"|'.*?'|`.*?`)""")
    private val keywordPattern = Regex("""\b(fun|const|let|var|val|if|else|return|class|interface|for|while|import|export|function|public|private|protected|static|extends|implements|fn|mut|impl|struct|enum|match)\b""")
    private val numberPattern = Regex("""\b(\d+)\b""")
    private val commentPattern = Regex("""(//.*|/\*[\s\S]*?\*/)""")
    private val typePattern = Regex("""\b([A-Z][a-zA-Z0-9_]*)\b""")

    fun highlight(code: String): AnnotatedString {
        val builder = AnnotatedString.Builder(code)
        
        fun applyPattern(pattern: Regex, color: Color) {
            pattern.findAll(code).forEach { match ->
                builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
            }
        }

        applyPattern(typePattern, typeColor)
        applyPattern(keywordPattern, keywordColor)
        applyPattern(numberPattern, numberColor)
        applyPattern(stringPattern, stringColor)
        applyPattern(commentPattern, commentColor)

        return builder.toAnnotatedString()
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboardManager = LocalClipboardManager.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StitchCodeBg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (block.language.isNotBlank()) block.language.uppercase() else "CODE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = StitchAccentCoral,
                letterSpacing = 1.sp
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(block.code))
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = StitchTextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        
        Text(
            text = SyntaxHighlighter.highlight(block.code),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = StitchCodeHighlight,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

/** Centered block math renderer: `$$ ... $$` (multi-line TeX). */
@Composable
private fun MathBlockView(block: MdBlock.MathBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StitchCodeBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        block.content.split("\n").forEach { line ->
            val converted = latexToUnicode(line)
            Text(
                text = converted,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = Color(0xFFC084FC),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.TableBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StitchCardBg, RoundedCornerShape(8.dp))
            .border(1.dp, StitchBorder, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        // Header row
        Row(modifier = Modifier.background(StitchBackground)) {
            block.headers.forEachIndexed { idx, h ->
                TableCell(
                    text = inlinePlain(h),
                    isHeader = true,
                    align = block.alignments.getOrElse(idx) { Align.LEFT }
                )
            }
        }
        HorizontalDivider(color = StitchBorder, thickness = 1.dp)
        // Body rows
        block.rows.forEach { row ->
            Row {
                block.headers.forEachIndexed { idx, _ ->
                    TableCell(
                        text = inlinePlain(row.getOrElse(idx) { "" }),
                        isHeader = false,
                        align = block.alignments.getOrElse(idx) { Align.LEFT }
                    )
                }
            }
            HorizontalDivider(color = StitchBorder.copy(alpha = 0.4f), thickness = 0.5.dp)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String,
    isHeader: Boolean,
    align: Align
) {
    Box(
        modifier = Modifier
            .widthIn(min = 110.dp)
            .weight(1f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = when (align) {
            Align.LEFT -> Alignment.CenterStart
            Align.CENTER -> Alignment.Center
            Align.RIGHT -> Alignment.CenterEnd
        }
    ) {
        Text(
            text = text,
            fontSize = if (isHeader) 13.sp else 12.sp,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            color = if (isHeader) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}

// ---------------------------------------------------------------------------
// Inline markdown (bold, italic, strike, code, links, wikilinks, math)
// ---------------------------------------------------------------------------

private val INLINE_PATTERN = Regex(
    "\\[\\[([^\\]]+)\\]\\]|" + // wikilink
        "\\$\\$([^$]+)\\$\\$|" + // inline math (double)
        "\\$([^$\\n]+)\\$|" + // inline math (single)
        "`([^`]+)`|" + // inline code
        "\\[([^\\]]+)\\]\\(([^)]+)\\)|" + // link
        "(\\*\\*|__)(.+?)\\1|" + // bold
        "(\\*|_)(.+?)\\1|" + // italic
        "(~~)(.+?)\\1" // strikethrough
)

// ---------------------------------------------------------------------------
// TeX -> Unicode math conversion (lightweight, native Compose)
// ---------------------------------------------------------------------------

private val GREEK_LETTERS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ϵ", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
    "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
    "phi" to "φ", "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
)

private val TEX_SYMBOLS = mapOf(
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫",
    "oint" to "∮", "iint" to "∬", "iiint" to "∭",
    "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
    "langle" to "⟨", "rangle" to "⟩",
    "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖",
    "sqrt" to "√", "infty" to "∞", "partial" to "∂", "nabla" to "∇",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬",
    "emptyset" to "∅", "varnothing" to "∅", "aleph" to "ℵ",
    "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂",
    "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
    "cup" to "∪", "cap" to "∩", "setminus" to "∖", "oplus" to "⊕",
    "ominus" to "⊖", "otimes" to "⊗", "odot" to "⊙", "dagger" to "†",
    "ddagger" to "‡", "cdot" to "⋅", "times" to "×", "div" to "÷",
    "pm" to "±", "mp" to "∓", "ast" to "∗", "star" to "⋆", "circ" to "∘",
    "bullet" to "•", "dots" to "…", "ldots" to "…", "cdots" to "⋯",
    "vdots" to "⋮", "ddots" to "⋱", "equiv" to "≡", "cong" to "≅",
    "approx" to "≈", "sim" to "∼", "simeq" to "≃", "propto" to "∝",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ll" to "≪", "gg" to "≫", "ne" to "≠", "neq" to "≠",
    "leftarrow" to "←", "rightarrow" to "→", "leftrightarrow" to "↔",
    "Leftarrow" to "⇐", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔",
    "uparrow" to "↑", "downarrow" to "↓", "mapsto" to "↦",
    "to" to "→", "gets" to "←", "longrightarrow" to "⟶",
    "longleftarrow" to "⟵", "longmapsto" to "⟼",
    "triangle" to "△", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
    "therefore" to "∴", "because" to "∵", "degree" to "°", "prime" to "′",
    "ell" to "ℓ", "hbar" to "ℏ", "imath" to "ı", "jmath" to "ȷ",
    "Re" to "ℜ", "Im" to "ℑ", "wp" to "℘", "clubsuit" to "♣",
    "diamondsuit" to "♦", "heartsuit" to "♥", "spadesuit" to "♠",
    "checkmark" to "✓", "circledR" to "®", "circledS" to "Ⓢ"
)

/** Single-letter math alphabets: \mathbb{R}, \mathcal{L}, \mathscr{M}, \mathrm{d} */
private val BLACKBOARD_BOLD: Map<Char, String> = mapOf(
    'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼",
    'F' to "𝔽", 'G' to "𝔾", 'H' to "ℍ", 'I' to "𝕀", 'J' to "𝕁",
    'K' to "𝕂", 'L' to "𝕃", 'M' to "𝕄", 'N' to "ℕ", 'O' to "𝕆",
    'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'S' to "𝕊", 'T' to "𝕋",
    'U' to "𝕌", 'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏", 'Y' to "𝕐", 'Z' to "ℤ"
)

private val SCRIPT_LETTERS: Map<Char, String> = mapOf(
    'A' to "𝒜", 'B' to "ℬ", 'C' to "𝒞", 'D' to "𝒟", 'E' to "ℰ",
    'F' to "ℱ", 'G' to "𝒢", 'H' to "ℋ", 'I' to "ℐ", 'J' to "𝒥",
    'K' to "𝒦", 'L' to "ℒ", 'M' to "ℳ", 'N' to "𝒩", 'O' to "𝒪",
    'P' to "𝒫", 'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "𝒯",
    'U' to "𝒰", 'V' to "𝒱", 'W' to "𝒲", 'X' to "𝒳", 'Y' to "𝒴", 'Z' to "𝒵"
)

private val SUBSCRIPTS = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'i' to 'ᵢ',
    'j' to 'ⱼ', 'n' to 'ₙ', 'm' to 'ₘ', 'k' to 'ₖ', 'l' to 'ₗ',
    'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'r' to 'ᵣ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'h' to 'ₕ', 'β' to 'ᵦ', 'γ' to 'ᵧ', 'ρ' to 'ᵨ',
    'φ' to 'ᵩ', 'χ' to 'ᵪ'
)

private val SUPERSCRIPTS = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ', 'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ',
    'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ',
    'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ',
    'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
    'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ'
)

/**
 * Convert a TeX expression to readable Unicode text.
 *
 * Handles: \sum, \prod, \lfloor/\rfloor, \lceil/\rceil, Greek letters,
 * subscripts `x_1`, superscripts `x^2`, fractions `\frac{a}{b}`,
 * `\sqrt{}`, `\text{}`, operators, and white space commands.
 */
internal fun latexToUnicode(tex: String): String {
    if (tex.isBlank()) return tex
    var s = tex
        .replace("\\left", "").replace("\\right", "")
        .replace("\\,", " ").replace("\\;", "  ").replace("\\quad", "  ")
        .replace("\\qquad", "    ").replace("\\!", "")
        .replace("\\ ", " ").replace("\\:", " ")
    s = s.replace("\\%", "%").replace("\\$", "$").replace("\\&", "&")
        .replace("\\_", "_").replace("\\#", "#")
        .replace("\\{", "{").replace("\\}", "}")

    // \text{...}
    s = Regex("\\\\text\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }
    // \mathrm{...} / \mathit{...} / \mathbf{...}
    s = Regex("\\\\mathrm\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }
    s = Regex("\\\\mathit\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }
    s = Regex("\\\\mathbf\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }

    // \mathbb{R} etc.
    s = Regex("\\\\mathbb\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { BLACKBOARD_BOLD[it] ?: it }.joinToString("")
    }
    // \mathcal{...} / \mathscr{...}
    s = Regex("\\\\mathcal\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { SCRIPT_LETTERS[it] ?: it }.joinToString("")
    }
    s = Regex("\\\\mathscr\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { SCRIPT_LETTERS[it] ?: it }.joinToString("")
    }

    // \frac{a}{b} -> a/b
    s = Regex("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}").replace(s) { m ->
        val num = latexToUnicode(m.groupValues[1])
        val den = latexToUnicode(m.groupValues[2])
        "$num/$den"
    }
    // \sqrt[3]{x} and \sqrt{x}
    s = Regex("\\\\sqrt\\[(\\d+)\\]\\{([^}]*)\\}").replace(s) { m ->
        "√[${m.groupValues[1]}](${latexToUnicode(m.groupValues[2])})"
    }
    s = Regex("\\\\sqrt\\{([^}]*)\\}").replace(s) { m ->
        "√(${latexToUnicode(m.groupValues[1])})"
    }

    // \sum_{i=1}^{n} style limits -> ∑ with sub/superscript
    s = Regex("\\\\(sum|prod|int|oint|iint|iiint|bigcup|bigcap)\\s*_(\\{[^}]*\\}|\\S)\\s*\\^(\\{[^}]*\\}|\\S)").replace(s) { m ->
        val op = TEX_SYMBOLS[m.groupValues[1]] ?: m.groupValues[1]
        val lower = m.groupValues[2].removeSurrounding("{", "}")
        val upper = m.groupValues[3].removeSurrounding("{", "}")
        "$op${toSubscript(lower)}${toSuperscript(upper)}"
    }

    // Named operators: \log_2 n, \lim_{x\to0}
    s = Regex("\\\\(log|ln|lim|max|min|sup|inf|arg|det|dim|gcd|exp|sin|cos|tan|sec|csc|cot|sinh|cosh|tanh|arcsin|arccos|arctan|Pr)\\s*").replace(s) { m ->
        m.groupValues[1]
    }

    // \bmod, \pmod, \mod
    s = s.replace("\\bmod", "mod").replace("\\mod", " mod ")
    s = Regex("\\\\pmod\\{([^}]*)\\}").replace(s) { " (mod ${it.groupValues[1]})" }

    // Generic subscripts: x_1, x_{i+1}
    s = Regex("_\\{([^}]*)\\}").replace(s) { m -> toSubscript(m.groupValues[1]) }
    s = Regex("_([0-9a-zA-Zα-ωΑ-Ω])").replace(s) { m -> toSubscript(m.groupValues[1]) }

    // Generic superscripts: x^2, x^{n+1}
    s = Regex("\\^\\{([^}]*)\\}").replace(s) { m -> toSuperscript(m.groupValues[1]) }
    s = Regex("\\^([0-9a-zA-Zα-ωΑ-Ω])").replace(s) { m -> toSuperscript(m.groupValues[1]) }

    // \lbrace/\rbrace -> real braces (protected from grouping-brace cleanup)
    s = s.replace("\\lbrace", "\u0001").replace("\\rbrace", "\u0002")

    // Remaining TeX commands: \theta -> θ, \sum -> ∑
    s = Regex("\\\\([A-Za-z]+)").replace(s) { m ->
        val name = m.groupValues[1]
        GREEK_LETTERS[name] ?: TEX_SYMBOLS[name] ?: name
    }

    // Braces that were only grouping
    s = s.replace("{", "").replace("}", "")
    s = s.replace("\u0001", "{").replace("\u0002", "}")

    // \atop, \over
    s = Regex("\\s*\\\\over\\s*").replace(s, "/")
    s = Regex("\\s*\\\\atop\\s*").replace(s, "/")

    return s
}

private fun toSubscript(inner: String): String =
    inner.map { SUBSCRIPTS[it] ?: it }.joinToString("")

private fun toSuperscript(inner: String): String =
    inner.map { SUPERSCRIPTS[it] ?: it }.joinToString("")

/** Inline math conversion — delegates to the full TeX→Unicode engine. */
internal fun formatLatexMath(rawTex: String): String =
    latexToUnicode(rawTex)

private fun inlineMarkdown(text: String, raw: String): AnnotatedString {
    return buildAnnotatedString {
        var last = 0
        for (m in INLINE_PATTERN.findAll(text)) {
            if (m.range.first > last) {
                append(text.substring(last, m.range.first))
            }
            val value = m.groupValues[1]
            when {
                value.isNotEmpty() && m.value.startsWith("[[") -> {
                    pushStringAnnotation(tag = "WIKILINK", annotation = value)
                    withStyle(SpanStyle(color = Color(0xFF2DD4BF), textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                        append(value)
                    }
                    pop()
                }
                m.groupValues[1].isEmpty() && m.groupValues[2].isNotEmpty() && m.value.startsWith("$$") -> {
                    val mathText = formatLatexMath(m.groupValues[2])
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFC084FC), fontWeight = FontWeight.SemiBold)) {
                        append("$$ $mathText $$")
                    }
                }
                m.groupValues[3].isNotEmpty() && m.value.startsWith("$") && !m.value.startsWith("$$") -> {
                    val mathText = formatLatexMath(m.groupValues[3])
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFC084FC))) {
                        append(mathText)
                    }
                }
                m.groupValues[4].isNotEmpty() -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = StitchCodeHighlight, background = StitchCodeBg)) {
                        append(m.groupValues[4])
                    }
                }
                m.groupValues[5].isNotEmpty() -> {
                    val label = m.groupValues[5]
                    val url = m.groupValues[6]
                    pushStringAnnotation(tag = "WIKILINK", annotation = url)
                    withStyle(SpanStyle(color = Color(0xFF3B82F6), textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                    pop()
                }
                m.groupValues[7].isNotEmpty() -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inlineMarkdown(m.groupValues[8], raw))
                    }
                }
                m.groupValues[9].isNotEmpty() -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inlineMarkdown(m.groupValues[10], raw))
                    }
                }
                m.groupValues[11].isNotEmpty() -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(m.groupValues[12])
                    }
                }
            }
            last = m.range.last + 1
        }
        if (last < text.length) {
            append(text.substring(last))
        }
    }
}

private fun inlinePlain(text: String): String {
    return INLINE_PATTERN.replace(text) { m ->
        when {
            m.value.startsWith("[[") -> m.groupValues[1]
            m.value.startsWith("$$") -> formatLatexMath(m.groupValues[2])
            m.value.startsWith("$") -> formatLatexMath(m.groupValues[3])
            m.value.startsWith("`") -> m.groupValues[4]
            m.value.startsWith("[") -> m.groupValues[5]
            m.groupValues[7].isNotEmpty() -> m.groupValues[8]
            m.groupValues[9].isNotEmpty() -> m.groupValues[10]
            m.groupValues[11].isNotEmpty() -> m.groupValues[12]
            else -> m.value
        }
    }
}
