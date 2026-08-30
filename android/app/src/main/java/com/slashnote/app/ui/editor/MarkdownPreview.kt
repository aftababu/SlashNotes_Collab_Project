package com.slashnote.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.StitchAccentCoral
import com.slashnote.app.ui.theme.StitchBackground
import com.slashnote.app.ui.theme.StitchBorder
import com.slashnote.app.ui.theme.StitchCardBg
import com.slashnote.app.ui.theme.StitchCodeBg
import com.slashnote.app.ui.theme.StitchCodeHighlight
import com.slashnote.app.ui.theme.StitchTextMuted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Interactive touch-draggable vertical fast-scroller overlay with stable thumb sizing.
 */
@Composable
fun DraggableScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbColor: Color = Color.Gray.copy(alpha = 0.7f),
    thumbWidth: Dp = 4.dp,
    trackWidth: Dp = 16.dp
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(trackWidth)
            .pointerInput(state) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val totalItems = state.layoutInfo.totalItemsCount
                        if (totalItems > 0) {
                            val dragFraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                            val targetItemIndex = (dragFraction * totalItems).toInt().coerceIn(0, totalItems - 1)
                            coroutineScope.launch {
                                state.scrollToItem(targetItemIndex)
                            }
                        }
                    }
                )
            }
    ) {
        val totalItems = state.layoutInfo.totalItemsCount
        if (totalItems > 1) {
            val firstVisibleIndex = state.firstVisibleItemIndex
            val thumbHeightRatio = (1f / totalItems.coerceAtLeast(1).toFloat()).coerceIn(0.12f, 0.25f)
            val thumbHeight = maxHeight * thumbHeightRatio
            val progress = (firstVisibleIndex.toFloat() / (totalItems - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val maxTravel = maxHeight - thumbHeight
            val offsetY = maxTravel * progress

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = offsetY)
                    .width(thumbWidth)
                    .height(height = thumbHeight)
                    .clip(CircleShape)
                    .background(if (isDragging) thumbColor.copy(alpha = 0.95f) else thumbColor)
            )
        }
    }
}

/**
 * Interactive touch-draggable vertical fast-scroller overlay for ScrollState (Edit mode).
 */
@Composable
fun DraggableEditorScrollbar(
    state: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    thumbColor: Color = Color.Gray.copy(alpha = 0.7f),
    thumbWidth: Dp = 4.dp,
    trackWidth: Dp = 16.dp
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(trackWidth)
            .pointerInput(state) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val maxValue = state.maxValue
                        if (maxValue > 0) {
                            val dragFraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                            val targetScrollY = (dragFraction * maxValue).toInt().coerceIn(0, maxValue)
                            coroutineScope.launch {
                                state.scrollTo(targetScrollY)
                            }
                        }
                    }
                )
            }
    ) {
        val maxValue = state.maxValue
        if (maxValue > 0) {
            val thumbHeightRatio = 0.15f
            val thumbHeight = maxHeight * thumbHeightRatio
            val progress = (state.value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
            val maxTravel = maxHeight - thumbHeight
            val offsetY = maxTravel * progress

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = offsetY)
                    .width(thumbWidth)
                    .height(height = thumbHeight)
                    .clip(CircleShape)
                    .background(if (isDragging) thumbColor.copy(alpha = 0.95f) else thumbColor)
            )
        }
    }
}

/**
 * Optimized Markdown Preview Renderer for Compose.
 *
 * Features:
 * - Character-Offset Character Range AST Blocks
 * - Synchronized Column Width GFM Table Grid Layout
 * - Interactive Touch-Draggable Scrollbar Overlay
 * - Soft Line Break Preservation (\n)
 * - Hardware Layer Caching (graphicsLayer)
 */
@Composable
fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
    headingAnchor: String? = null,
    initialScrollRatio: Float = 0f,
    listState: LazyListState = rememberLazyListState(),
    onScrollRatioChanged: ((Float) -> Unit)? = null,
    onWikilinkClick: ((String) -> Unit)? = null
) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }

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

    LaunchedEffect(listState, blocks) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisibleIndex ->
                if (blocks.isNotEmpty()) {
                    val ratio = if (blocks.size > 1) firstVisibleIndex.toFloat() / (blocks.size - 1).toFloat() else 0f
                    onScrollRatioChanged?.invoke(ratio)
                }
            }
    }

    val isDarkTheme = isSystemInDarkTheme()
    val cursorHandleColor = if (isDarkTheme) StitchAccentCoral else Color.Black

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = cursorHandleColor,
                backgroundColor = cursorHandleColor.copy(alpha = 0.25f)
            )
        ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .magnifier(
                        sourceCenter = { Offset.Unspecified }
                    )
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    flingBehavior = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
                ) {
                    itemsIndexed(
                        items = blocks,
                        key = { _, block -> block.id }
                    ) { _, block ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    clip = false
                                }
                        ) {
                            when (block) {
                                is MdBlock.Heading -> HeadingBlock(block, content)
                                is MdBlock.Paragraph -> ParagraphBlock(block, content, onWikilinkClick)
                                is MdBlock.ListBlock -> ListBlock(block, content, onWikilinkClick)
                                is MdBlock.Blockquote -> BlockquoteBlock(block, content, onWikilinkClick)
                                is MdBlock.CodeBlock -> CodeBlockView(block)
                                is MdBlock.MathBlock -> MathBlockView(block)
                                is MdBlock.TableBlock -> TableBlock(block, content, onWikilinkClick)
                                is MdBlock.HorizontalRule -> HorizontalDivider(color = StitchBorder.copy(alpha = 0.4f), thickness = 1.dp)
                                is MdBlock.ListItem -> Unit
                            }
                        }
                    }
                    if (blocks.isEmpty()) {
                        item(key = "empty_note") {
                            Text("Empty note", color = StitchTextMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        DisableSelection {
            DraggableScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing model with character offset ranges
// ---------------------------------------------------------------------------

internal sealed class MdBlock {
    abstract val id: String
    abstract val startOffset: Int
    abstract val endOffset: Int

    class Heading(override val id: String, override val startOffset: Int, override val endOffset: Int, val level: Int, val content: String) : MdBlock()
    class Paragraph(override val id: String, override val startOffset: Int, override val endOffset: Int, val content: String) : MdBlock()
    class ListBlock(
        override val id: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val ordered: Boolean,
        val items: List<ListItem>,
        val startNumber: Int
    ) : MdBlock()

    class ListItem(override val id: String, override val startOffset: Int, override val endOffset: Int, val checked: Boolean?, val content: String) : MdBlock()
    class Blockquote(override val id: String, override val startOffset: Int, override val endOffset: Int, val content: String) : MdBlock()
    class CodeBlock(override val id: String, override val startOffset: Int, override val endOffset: Int, val language: String, val code: String) : MdBlock()
    class MathBlock(override val id: String, override val startOffset: Int, override val endOffset: Int, val content: String) : MdBlock()
    class TableBlock(
        override val id: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<Align>
    ) : MdBlock()

    class HorizontalRule(override val id: String, override val startOffset: Int, override val endOffset: Int) : MdBlock()
}

internal enum class Align { LEFT, CENTER, RIGHT }

internal fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    val n = lines.size

    val lineOffsets = IntArray(n)
    var currCharOffset = 0
    for (idx in 0 until n) {
        lineOffsets[idx] = currCharOffset
        currCharOffset += lines[idx].length + 1
    }

    fun nextId(): String = "block_${blocks.size}"

    fun getOffsets(startLine: Int, endLine: Int): Pair<Int, Int> {
        val s = lineOffsets.getOrElse(startLine) { 0 }
        val eLine = endLine.coerceIn(0, n - 1)
        val e = lineOffsets[eLine] + lines[eLine].length
        return Pair(s, e)
    }

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

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        if (trimmed.matches(Regex("^(\\*{3,}|-{3,}|_{3,})$"))) {
            val (so, eo) = getOffsets(i, i)
            blocks.add(MdBlock.HorizontalRule(nextId(), so, eo))
            i++
            continue
        }

        if (isFenceStart(line)) {
            val startLine = i
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
            val (so, eo) = getOffsets(startLine, i - 1)
            blocks.add(MdBlock.CodeBlock(nextId(), so, eo, lang, codeLines.joinToString("\n")))
            if (!closed) break
            continue
        }

        if (line.startsWith("    ") || line.startsWith("\t")) {
            val startLine = i
            val codeLines = mutableListOf<String>()
            while (i < n && (lines[i].startsWith("    ") || lines[i].startsWith("\t"))) {
                codeLines.add(lines[i].trimStart(' ', '\t'))
                i++
            }
            val (so, eo) = getOffsets(startLine, i - 1)
            blocks.add(MdBlock.CodeBlock(nextId(), so, eo, "", codeLines.joinToString("\n")))
            continue
        }

        if (trimmed.startsWith("$$")) {
            val startLine = i
            val mathLines = mutableListOf<String>()
            if (trimmed.length > 2 && trimmed.endsWith("$$") && trimmed.length >= 4) {
                val inner = trimmed.substring(2, trimmed.length - 2)
                if (inner.isNotEmpty()) {
                    val (so, eo) = getOffsets(i, i)
                    blocks.add(MdBlock.MathBlock(nextId(), so, eo, inner))
                    i++
                    continue
                }
            }
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
                val (so, eo) = getOffsets(startLine, i - 1)
                blocks.add(MdBlock.MathBlock(nextId(), so, eo, content))
            }
            if (!closed) break
            continue
        }

        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val (so, eo) = getOffsets(i, i)
            blocks.add(MdBlock.Heading(nextId(), so, eo, level, headingMatch.groupValues[2]))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            val startLine = i
            val quoteLines = mutableListOf<String>()
            while (i < n && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            val (so, eo) = getOffsets(startLine, i - 1)
            blocks.add(MdBlock.Blockquote(nextId(), so, eo, quoteLines.joinToString("\n")))
            continue
        }

        if (trimmed.contains("|") && i + 1 < n) {
            val tableRes = tryParseTable(lines, i, lineOffsets)
            if (tableRes != null) {
                blocks.add(tableRes.first)
                i = tableRes.second
                continue
            }
        }

        val listMatch = Regex("^\\s*([-*+]|\\d+\\.)\\s+(.*)$").find(line)
        if (listMatch != null) {
            val startLine = i
            val ordered = listMatch.groupValues[1].any { it.isDigit() }
            val startNumber = if (ordered) listMatch.groupValues[1].dropLast(1).toIntOrNull() ?: 1 else 1
            val items = mutableListOf<MdBlock.ListItem>()
            val listBlockId = nextId()
            while (i < n) {
                val m = Regex("^\\s*([-*+]|\\d+\\.)\\s+(.*)$").find(lines[i])
                if (m == null) {
                    if (items.isNotEmpty() && (lines[i].startsWith("  ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                        val last = items[items.size - 1]
                        val (_, ieo) = getOffsets(i, i)
                        items[items.size - 1] = MdBlock.ListItem(
                            last.id,
                            last.startOffset,
                            ieo,
                            last.checked,
                            last.content + "\n" + lines[i].trim()
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
                val (iso, ieo) = getOffsets(i, i)
                items.add(MdBlock.ListItem("${listBlockId}_item_${items.size}", iso, ieo, checked, content))
                i++
            }
            val (so, eo) = getOffsets(startLine, i - 1)
            blocks.add(MdBlock.ListBlock(listBlockId, so, eo, ordered, items, startNumber))
            continue
        }

        val startLine = i
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
            val (so, eo) = getOffsets(startLine, i - 1)
            blocks.add(MdBlock.Paragraph(nextId(), so, eo, paraLines.joinToString("\n")))
        } else {
            i++
        }
    }

    return blocks
}

private fun tryParseTable(lines: List<String>, startIdx: Int, lineOffsets: IntArray): Pair<MdBlock.TableBlock, Int>? {
    val line = lines[startIdx].trim()
    if (!line.contains("|") || startIdx + 1 >= lines.size) return null

    val delimiterLine = lines[startIdx + 1].trim()
    if (!delimiterLine.contains("-") || !delimiterLine.contains("|")) return null

    val headerCols = splitTableRow(line)
    if (headerCols.isEmpty()) return null

    val alignCols = splitTableRow(delimiterLine)
    val alignments = headerCols.indices.map { idx ->
        val raw = alignCols.getOrNull(idx)?.trim() ?: ""
        when {
            raw.startsWith(":") && raw.endsWith(":") -> Align.CENTER
            raw.endsWith(":") -> Align.RIGHT
            raw.startsWith(":") -> Align.LEFT
            else -> Align.LEFT
        }
    }

    val rows = mutableListOf<List<String>>()
    var curr = startIdx + 2
    while (curr < lines.size) {
        val rowLine = lines[curr].trim()
        if (rowLine.isEmpty() || !rowLine.contains("|")) break
        val rowCells = splitTableRow(rowLine)
        if (rowCells.isNotEmpty()) {
            rows.add(rowCells)
        }
        curr++
    }

    val n = lines.size
    val so = lineOffsets.getOrElse(startIdx) { 0 }
    val endLineIdx = (curr - 1).coerceIn(0, n - 1)
    val eo = lineOffsets[endLineIdx] + lines[endLineIdx].length

    return Pair(MdBlock.TableBlock("table_$startIdx", so, eo, headerCols, rows, alignments), curr)
}

/** Split cells using unescaped pipe regex (?<!\\)\| */
private fun splitTableRow(row: String): List<String> {
    var s = row.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    val split = s.split(Regex("(?<!\\\\)\\|"))
    return split.map { cell ->
        cell.trim().replace("\\|", "|")
    }
}

// ---------------------------------------------------------------------------
// Rendering helpers
// ---------------------------------------------------------------------------

@Composable
private fun HeadingBlock(block: MdBlock.Heading, raw: String) {
    val annotated = remember(block.content, raw) {
        inlineMarkdown(block.content, raw)
    }
    val size = when (block.level) {
        1 -> 26.sp
        2 -> 22.sp
        3 -> 19.sp
        4 -> 17.sp
        else -> 15.sp
    }
    Text(
        text = annotated,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = size * 1.25f,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ClickableMarkdownText(
    annotatedString: AnnotatedString,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
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
            fontWeight = fontWeight,
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
    val annotated = remember(block.content, raw) {
        inlineMarkdown(block.content, raw)
    }
    ClickableMarkdownText(
        annotatedString = annotated,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurface,
        onWikilinkClick = onWikilinkClick,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ListBlock(block: MdBlock.ListBlock, raw: String, onWikilinkClick: ((String) -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        block.items.forEachIndexed { index, item ->
            val annotatedItem = remember(item.content, raw) {
                inlineMarkdown(item.content, raw)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.width(22.dp)) {
                    when {
                        item.checked != null -> {
                            Icon(
                                imageVector = if (item.checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (item.checked) StitchAccentCoral else StitchTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        block.ordered -> {
                            Text(
                                text = "${block.startNumber + index}.",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        else -> {
                            Text(
                                text = "•",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = StitchAccentCoral
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                ClickableMarkdownText(
                    annotatedString = annotatedItem,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
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
    val annotated = remember(block.content, raw) {
        inlineMarkdown(block.content, raw)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(StitchAccentCoral, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        ClickableMarkdownText(
            annotatedString = annotated,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            onWikilinkClick = onWikilinkClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboardManager = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = StitchCodeBg,
        border = BorderStroke(1.dp, StitchBorder),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifEmpty { "code" }.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = StitchTextMuted
                )
                DisableSelection {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(block.code))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            tint = StitchTextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = StitchCodeHighlight,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun MathBlockView(block: MdBlock.MathBlock) {
    val mathText = remember(block.content) {
        latexToUnicode(block.content)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = StitchCodeBg,
        border = BorderStroke(1.dp, Color(0xFFC084FC).copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            mathText.split("\n").forEach { lineText ->
                Text(
                    text = lineText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFC084FC),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.TableBlock, raw: String, onWikilinkClick: ((String) -> Unit)?) {
    val columnWidths = remember(block) {
        val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
        (0 until colCount).map { colIndex ->
            val maxLen = maxOf(
                block.headers.getOrNull(colIndex)?.length ?: 0,
                block.rows.maxOfOrNull { it.getOrNull(colIndex)?.length ?: 0 } ?: 0
            )
            (maxLen * 10).coerceIn(100, 260).dp
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = StitchCardBg,
        border = BorderStroke(1.dp, StitchBorder),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Column {
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    block.headers.forEachIndexed { colIndex, h ->
                        val width = columnWidths.getOrElse(colIndex) { 120.dp }
                        val annotatedHeader = remember(h, raw) { inlineMarkdown(h, raw) }
                        TableCell(
                            annotatedText = annotatedHeader,
                            isHeader = true,
                            width = width,
                            align = block.alignments.getOrElse(colIndex) { Align.LEFT },
                            onWikilinkClick = onWikilinkClick
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

                block.rows.forEachIndexed { rowIndex, row ->
                    Row {
                        block.headers.indices.forEach { colIndex ->
                            val cellText = row.getOrElse(colIndex) { "" }
                            val width = columnWidths.getOrElse(colIndex) { 120.dp }
                            val annotatedCell = remember(cellText, raw) { inlineMarkdown(cellText, raw) }
                            TableCell(
                                annotatedText = annotatedCell,
                                isHeader = false,
                                width = width,
                                align = block.alignments.getOrElse(colIndex) { Align.LEFT },
                                onWikilinkClick = onWikilinkClick
                            )
                        }
                    }
                    if (rowIndex < block.rows.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    annotatedText: AnnotatedString,
    isHeader: Boolean,
    width: Dp,
    align: Align,
    onWikilinkClick: ((String) -> Unit)?
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = when (align) {
            Align.LEFT -> Alignment.CenterStart
            Align.CENTER -> Alignment.Center
            Align.RIGHT -> Alignment.CenterEnd
        }
    ) {
        ClickableMarkdownText(
            annotatedString = annotatedText,
            fontSize = if (isHeader) 13.sp else 12.sp,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            onWikilinkClick = onWikilinkClick
        )
    }
}

// ---------------------------------------------------------------------------
// Inline markdown parsing
// ---------------------------------------------------------------------------

internal fun inlineMarkdown(text: String, _rawFull: String = ""): AnnotatedString {
    return buildAnnotatedString {
        var last = 0
        INLINE_PATTERN.findAll(text).forEach { m ->
            if (m.range.first > last) {
                append(text.substring(last, m.range.first))
            }
            val groups = m.groups
            when {
                groups[1] != null -> {
                    val rawTarget = groups[1]!!.value
                    val target = rawTarget.trim()
                    val label = if (target.contains("|")) target.substringAfter("|").trim() else target
                    pushStringAnnotation(tag = "WIKILINK", annotation = target)
                    withStyle(SpanStyle(color = Color(0xFF2DD4BF), fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                    pop()
                }
                groups[2] != null -> {
                    val math = latexToUnicode(groups[2]!!.value)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFC084FC))) {
                        append(math)
                    }
                }
                groups[3] != null -> {
                    val math = latexToUnicode(groups[3]!!.value)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFC084FC))) {
                        append(math)
                    }
                }
                groups[4] != null -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = StitchCodeHighlight, background = StitchCodeBg)) {
                        append(groups[4]!!.value)
                    }
                }
                groups[5] != null && groups[6] != null -> {
                    val label = groups[5]!!.value
                    val url = groups[6]!!.value
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(color = Color(0xFF3B82F6), textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                    pop()
                }
                groups[7] != null && groups[8] != null -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(groups[8]!!.value)
                    }
                }
                groups[9] != null && groups[10] != null -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(groups[10]!!.value)
                    }
                }
                groups[11] != null && groups[12] != null -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(groups[12]!!.value)
                    }
                }
                else -> append(m.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) {
            append(text.substring(last))
        }
    }
}

internal fun inlinePlain(text: String): String {
    return INLINE_PATTERN.replace(text) { m ->
        val g = m.groups
        when {
            g[1] != null -> g[1]!!.value
            g[2] != null -> latexToUnicode(g[2]!!.value)
            g[3] != null -> latexToUnicode(g[3]!!.value)
            g[4] != null -> g[4]!!.value
            g[5] != null -> g[5]!!.value
            g[8] != null -> g[8]!!.value
            g[10] != null -> g[10]!!.value
            g[12] != null -> g[12]!!.value
            else -> m.value
        }
    }
}

private val INLINE_PATTERN = Regex(
    "\\[\\[([^\\]]+)\\]\\]|" +
        "\\$\\$([^$]+)\\$\\$|" +
        "\\$([^$\\n]+)\\$|" +
        "`([^`]+)`|" +
        "\\[([^\\]]+)\\]\\(([^)]+)\\)|" +
        "(\\*\\*|__)(.+?)\\1|" +
        "(\\*|_)(.+?)\\1|" +
        "(~~)(.+?)\\1"
)

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
    'K' to "𝒦", 'L' to "ℒ", 'M' to "𝕄", 'N' to "𝒩", 'O' to "𝒪",
    'P' to "𝒫", 'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "ℛ",
    'U' to "𝕌", 'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏", 'Y' to "𝕐", 'Z' to "ℤ"
)

private val GREEK_MAP = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
    "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
    "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
    "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
)

private val SYMBOL_MAP = mapOf(
    "approx" to "≈", "times" to "×", "cdot" to "⋅", "pm" to "±", "mp" to "∓",
    "div" to "÷", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "infty" to "∞", "int" to "∫", "sum" to "∑",
    "prod" to "∏", "sqrt" to "√", "to" to "→", "rightarrow" to "→",
    "leftarrow" to "←", "Leftrightarrow" to "⇔", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
    "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
    "equiv" to "≡", "cong" to "≅", "sim" to "∼"
)

private val SUB_MAP = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'i' to 'ᵢ', 'j' to 'ⱼ', 'n' to 'ₙ', 'm' to 'ₘ', 'k' to 'ₖ',
    'x' to 'ₓ', 'y' to 'ᵧ', 'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ'
)

private val SUPER_MAP = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'a' to 'ᵃ', 'b' to 'ᵇ'
)

internal fun latexToUnicode(tex: String): String {
    if (tex.isBlank()) return tex
    var s = tex
        .replace("\\left", "").replace("\\right", "")
        .replace("\\,", " ").replace("\\;", "  ").replace("\\quad", "  ")
        .replace("\\qquad", "    ").replace("\\!", "")
        .replace("\\ ", " ").replace("\\:", " ")
    s = s.replace("\\%", "%").replace("\\$", "$").replace("\\&", "&")
        .replace("\\_", "_").replace("\\#", "#")

    val matrixRegex = Regex("\\\\begin\\{([a-zA-Z]*matrix)\\}(.*?)\\\\end\\{\\1\\}", RegexOption.DOT_MATCHES_ALL)
    s = matrixRegex.replace(s) { m ->
        val env = m.groupValues[1]
        val body = m.groupValues[2]
        formatLatexMatrix(env, body)
    }

    s = Regex("\\\\text\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }
    s = Regex("\\\\mathrm\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }
    s = Regex("\\\\mathit\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }
    s = Regex("\\\\mathbf\\{([^}]*)\\}").replace(s) { m -> m.groupValues[1] }

    s = Regex("\\\\mathbb\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { BLACKBOARD_BOLD[it] ?: it }.joinToString("")
    }
    s = Regex("\\\\mathit\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { SCRIPT_LETTERS[it] ?: it }.joinToString("")
    }
    s = Regex("\\\\mathscr\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { SCRIPT_LETTERS[it] ?: it }.joinToString("")
    }

    s = Regex("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}").replace(s) { m ->
        "${m.groupValues[1]}/${m.groupValues[2]}"
    }

    s = Regex("\\\\sqrt\\{([^}]*)\\}").replace(s) { m ->
        "√(${m.groupValues[1]})"
    }

    SYMBOL_MAP.forEach { (cmd, unicode) ->
        s = s.replace("\\$cmd ", "$unicode ").replace("\\$cmd", unicode)
    }
    GREEK_MAP.forEach { (cmd, unicode) ->
        s = s.replace("\\$cmd ", "$unicode ").replace("\\$cmd", unicode)
    }

    s = Regex("\\^\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { SUPER_MAP[it] ?: it }.joinToString("")
    }
    s = Regex("\\^([0-9a-z+-=])").replace(s) { m ->
        val c = m.groupValues[1].first()
        (SUPER_MAP[c] ?: c).toString()
    }

    s = Regex("_\\{([^}]*)\\}").replace(s) { m ->
        m.groupValues[1].map { SUB_MAP[it] ?: it }.joinToString("")
    }
    s = Regex("_([0-9a-z+-=])").replace(s) { m ->
        val c = m.groupValues[1].first()
        (SUB_MAP[c] ?: c).toString()
    }

    return s.replace(Regex("\\{([^}]*)\\}")) { m -> m.groupValues[1] }
}

private fun formatLatexMatrix(env: String, innerContent: String): String {
    val rows = innerContent.split("\\\\").map { row ->
        row.split("&").map { it.trim() }
    }.filter { row -> row.any { it.isNotEmpty() } }

    if (rows.isEmpty()) return ""

    val colCount = rows.maxOf { it.size }
    val colWidths = (0 until colCount).map { colIdx ->
        rows.maxOf { row -> row.getOrNull(colIdx)?.length ?: 0 }.coerceAtLeast(1)
    }

    val formattedRows = rows.map { row ->
        colWidths.indices.joinToString("  ") { colIdx ->
            val cell = row.getOrNull(colIdx) ?: ""
            cell.padStart(colWidths[colIdx])
        }
    }

    return when (env) {
        "pmatrix" -> {
            if (formattedRows.size == 1) "( ${formattedRows[0]} )"
            else formattedRows.mapIndexed { idx, r ->
                val left = if (idx == 0) "⎛ " else if (idx == formattedRows.size - 1) "⎝ " else "⎜ "
                val right = if (idx == 0) " ⎞" else if (idx == formattedRows.size - 1) " ⎠" else " ⎟"
                "$left$r$right"
            }.joinToString("\n")
        }
        "bmatrix" -> {
            if (formattedRows.size == 1) "[ ${formattedRows[0]} ]"
            else formattedRows.mapIndexed { idx, r ->
                val left = if (idx == 0) "⎡ " else if (idx == formattedRows.size - 1) "⎣ " else "⎢ "
                val right = if (idx == 0) " ⎤" else if (idx == formattedRows.size - 1) " ⎦" else " ⎥"
                "$left$r$right"
            }.joinToString("\n")
        }
        "Bmatrix" -> {
            if (formattedRows.size == 1) "{ ${formattedRows[0]} }"
            else formattedRows.mapIndexed { idx, r ->
                val left = if (idx == 0) "⎧ " else if (idx == formattedRows.size - 1) "⎩ " else "⎨ "
                val right = if (idx == 0) " ⎰" else if (idx == formattedRows.size - 1) " ⎱" else " ⎬"
                "$left$r$right"
            }.joinToString("\n")
        }
        "vmatrix" -> {
            formattedRows.joinToString("\n") { r -> "│ $r │" }
        }
        "Vmatrix" -> {
            formattedRows.joinToString("\n") { r -> "║ $r ║" }
        }
        else -> formattedRows.joinToString("\n")
    }
}
