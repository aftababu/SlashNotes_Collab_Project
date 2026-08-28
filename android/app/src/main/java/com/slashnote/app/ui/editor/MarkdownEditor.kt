package com.slashnote.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.StitchAccentCoral
import com.slashnote.app.ui.theme.StitchTextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private enum class SelectionHandle { START, END }

private data class HandleGeometry(
    val center: Offset,
    val side: SelectionHandle
)

/**
 * Mobile-Optimized Markdown Editor for Compose.
 *
 * All selection-handle dragging is owned by a single `pointerInput` on the
 * editor viewport running at `PointerEventPass.Initial`. This gives one
 * coordinate space (the editor viewport) and one owner, so the finger position
 * is tracked correctly even as it crosses the keyboard boundary. Handle
 * composables are visual-only and do not own their own gestures.
 *
 * Auto-scroll is continuous and distance-accelerated based on how far the
 * finger penetrates the keyboard (below the viewport) or the top edge.
 */
@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester = remember { FocusRequester() },
    placeholderText: String = "Start typing note content...",
    onTextLayout: ((TextLayoutResult) -> Unit)? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var internalTextLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var visibleHeightPx by remember { mutableFloatStateOf(0f) }

    var activeHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    var rawDragX by remember { mutableFloatStateOf(0f) }
    var rawDragY by remember { mutableFloatStateOf(0f) }
    var anchorOffset by remember { mutableIntStateOf(0) }

    // Handle centers in editor-viewport coordinates, recomputed on selection or
    // scroll changes and read by the single viewport pointer handler.
    var handleGeometries by remember { mutableStateOf<List<HandleGeometry>>(emptyList()) }

    val isImeVisible = WindowInsets.isImeVisible

    val touchRadiusPx = with(LocalDensity.current) { 44.dp.toPx() }

    // Keep the freshest values inside the long-lived drag loop and the
    // long-lived pointer handler without restarting them on every change.
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentLayout by rememberUpdatedState(internalTextLayoutResult)
    val currentVisibleHeight by rememberUpdatedState(visibleHeightPx)
    val currentHandleGeometries by rememberUpdatedState(handleGeometries)

    // Continuous 60fps distance-accelerated auto-scroll engine.
    LaunchedEffect(activeHandle) {
        if (activeHandle == null) return@LaunchedEffect
        while (isActive && activeHandle != null) {
            val layout = currentLayout ?: break
            val maxScroll = scrollState.maxValue.toFloat()
            val viewport = currentVisibleHeight

            // Bottom edge: finger past the viewport bottom (IME) or close to it.
            val pastBottom = (rawDragY - viewport).coerceAtLeast(0f)
            val nearBottom = if (rawDragY >= viewport - 64f) (viewport - rawDragY).coerceAtLeast(0f) else 0f

            // Top edge: finger above viewport top or close to it.
            val pastTop = (-rawDragY).coerceAtLeast(0f)
            val nearTop = if (rawDragY <= 48f) rawDragY.coerceAtLeast(0f) else 0f

            val wantDown = pastBottom > 0f || nearBottom > 0f
            val wantUp = pastTop > 0f || nearTop > 0f

            if (wantDown && scrollState.value.toFloat() < maxScroll) {
                val depth = (pastBottom + nearBottom).coerceIn(1f, 500f)
                scrollState.scrollBy(10f + depth * 0.14f)
            } else if (wantUp && scrollState.value > 0) {
                val depth = (pastTop + nearTop).coerceIn(1f, 500f)
                scrollState.scrollBy(-(10f + depth * 0.14f))
            }

            // Map viewport-local finger position into content coordinates.
            val contentY = (rawDragY + scrollState.value).coerceIn(0f, layout.size.height.toFloat())
            val movingOffset = layout.getOffsetForPosition(Offset(rawDragX, contentY))
            currentOnValueChange(
                currentValue.copy(selection = TextRange(anchorOffset, movingOffset))
            )

            delay(16)
        }
    }

    // Elevate the cursor / selection as it moves near the viewport edges.
    // Disabled while a custom handle drag is active: auto-scroll owns the
    // scroll position then, and bringIntoView would fight it every frame.
    LaunchedEffect(value.selection, isImeVisible, activeHandle) {
        if (activeHandle != null) return@LaunchedEffect
        val layout = internalTextLayoutResult ?: return@LaunchedEffect
        val selection = value.selection
        val textLength = value.text.length

        if (selection.collapsed) {
            val cursorOffset = selection.start.coerceIn(0, textLength)
            try {
                val cursorRect = layout.getCursorRect(cursorOffset)
                val bufferedRect = cursorRect.copy(
                    top = (cursorRect.top - 48f).coerceAtLeast(0f),
                    bottom = cursorRect.bottom + 48f
                )
                bringIntoViewRequester.bringIntoView(bufferedRect)
            } catch (_: Exception) {
                try { bringIntoViewRequester.bringIntoView() } catch (_: Exception) {}
            }
        } else {
            if (!isImeVisible) keyboardController?.hide()
            val start = selection.min.coerceIn(0, textLength)
            val end = selection.max.coerceIn(0, textLength)
            if (start < end) {
                try {
                    val bounds = layout.getPathForRange(start, end).getBounds()
                    val bufferedRect = bounds.copy(
                        top = (bounds.top - 48f).coerceAtLeast(0f),
                        bottom = bounds.bottom + 48f
                    )
                    bringIntoViewRequester.bringIntoView(bufferedRect)
                } catch (_: Exception) {
                    try { bringIntoViewRequester.bringIntoView() } catch (_: Exception) {}
                }
            }
        }
    }

    val flingInterceptor = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
        }
    }

    // Recompute handle centers in viewport coordinates whenever selection,
    // layout, or scroll position changes.
    val density = LocalDensity.current
    val horizontalPadPx = with(density) { 6.dp.toPx() }
    val verticalPadPx = with(density) { 4.dp.toPx() }
    handleGeometries = remember(value.selection, internalTextLayoutResult, scrollState.value) {
        val layout = internalTextLayoutResult
        if (layout == null || value.selection.collapsed) {
            emptyList()
        } else {
            val textLength = value.text.length
            val minIdx = value.selection.min.coerceIn(0, textLength)
            val maxIdx = value.selection.max.coerceIn(0, textLength)
            if (minIdx >= maxIdx) {
                emptyList()
            } else {
                val startRect = layout.getCursorRect(minIdx)
                val endRect = layout.getCursorRect(maxIdx)
                val scrollY = scrollState.value.toFloat()
                // Convert from Column content space into viewport space.
                listOf(
                    HandleGeometry(
                        center = Offset(startRect.left + horizontalPadPx, startRect.bottom + verticalPadPx - scrollY),
                        side = SelectionHandle.START
                    ),
                    HandleGeometry(
                        center = Offset(endRect.right + horizontalPadPx, endRect.bottom + verticalPadPx - scrollY),
                        side = SelectionHandle.END
                    )
                )
            }
        }
    }

    val isDarkTheme = isSystemInDarkTheme()
    val cursorHandleColor = if (isDarkTheme) StitchAccentCoral else Color.Black

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .onGloballyPositioned { coordinates ->
                visibleHeightPx = coordinates.size.height.toFloat()
            }
            // Single owner of selection dragging at Initial pass. Coordinates are
            // viewport-local, so they remain correct even as the finger crosses
            // into the keyboard area.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)

                    // Hit-test the down against current handle centers.
                    val hit = currentHandleGeometries.firstOrNull {
                        (down.position - it.center).getDistance() <= touchRadiusPx
                    }

                    if (hit != null) {
                        activeHandle = hit.side
                        anchorOffset = if (hit.side == SelectionHandle.START) currentValue.selection.max else currentValue.selection.min
                        rawDragX = down.position.x
                        rawDragY = down.position.y
                        down.consume()
                    }

                    var pointer = down.id
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) break

                        if (activeHandle != null) {
                            rawDragX = change.position.x
                            rawDragY = change.position.y
                            change.consume()
                        }
                    }

                    if (activeHandle != null) {
                        activeHandle = null
                    }
                }
            }
    ) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = Color.Transparent,
                backgroundColor = cursorHandleColor.copy(alpha = 0.25f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .nestedScroll(flingInterceptor)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    onTextLayout = { layoutResult ->
                        internalTextLayoutResult = layoutResult
                        onTextLayout?.invoke(layoutResult)
                    },
                    readOnly = false,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Default,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(cursorHandleColor),
                    visualTransformation = visualTransformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .magnifier(sourceCenter = { Offset.Unspecified }),
                    maxLines = Int.MAX_VALUE,
                    singleLine = false,
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = placeholderText,
                                    color = StitchTextMuted,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Default,
                                    lineHeight = 22.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        // Visual-only handles. They have no pointerInput of their own.
        handleGeometries.forEach { geometry ->
            val isActive = activeHandle == geometry.side
            CustomHandleVisual(
                position = geometry.center,
                color = cursorHandleColor,
                isStart = geometry.side == SelectionHandle.START,
                isActive = isActive
            )
        }

        DraggableEditorScrollbar(
            state = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun CustomHandleVisual(
    position: Offset,
    color: Color,
    isStart: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleWidth = 12.dp
    val handleHeight = 22.dp
    val handleWidthPx = with(density) { handleWidth.toPx() }
    val handleHeightPx = with(density) { handleHeight.toPx() }

    Box(
        modifier = modifier
            .size(handleWidth, handleHeight)
            .offset {
                IntOffset(
                    x = (position.x - handleWidthPx / 2f).roundToInt(),
                    y = (position.y - handleHeightPx).roundToInt()
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alpha = if (isActive) 1f else 0.85f
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (isStart) {
                    // Teardrop pointing up; anchor tip at bottom-center.
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h * 0.78f)
                    quadraticBezierTo(w * 0.5f, h, 0f, h * 0.78f)
                    close()
                } else {
                    // Teardrop pointing down; anchor tip at top-center.
                    moveTo(0f, h)
                    lineTo(w, h)
                    lineTo(w, h * 0.22f)
                    quadraticBezierTo(w * 0.5f, 0f, 0f, h * 0.22f)
                    close()
                }
            }
            drawPath(path, color = color.copy(alpha = alpha))
        }
    }
}
