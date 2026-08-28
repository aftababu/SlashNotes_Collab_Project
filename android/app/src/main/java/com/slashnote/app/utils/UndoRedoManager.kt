package com.slashnote.app.utils

import androidx.compose.ui.text.input.TextFieldValue

enum class UndoActionType { NONE, INSERT, DELETE, PASTE_OR_BULK }

/**
 * Manages time-debounced and semantic action chunking for text undo and redo.
 * Groups continuous typing, continuous backspacing, word boundaries (Space/Enter),
 * and 600ms inactivity pauses into atomic undo steps.
 */
class UndoRedoManager(
    private val debounceTimeoutMs: Long = 600L,
    private val maxStackSize: Int = 50
) {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    private var lastEditTimestamp: Long = 0L
    private var lastActionType: UndoActionType = UndoActionType.NONE

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastEditTimestamp = 0L
        lastActionType = UndoActionType.NONE
    }

    fun registerChange(oldValue: TextFieldValue, newValue: TextFieldValue) {
        if (oldValue.text == newValue.text) return // Ignore cursor-only movements

        val now = System.currentTimeMillis()
        val deltaLen = kotlin.math.abs(newValue.text.length - oldValue.text.length)
        val isInsert = newValue.text.length > oldValue.text.length
        val isDelete = newValue.text.length < oldValue.text.length
        val actionType = when {
            deltaLen > 1 -> UndoActionType.PASTE_OR_BULK
            isInsert -> UndoActionType.INSERT
            isDelete -> UndoActionType.DELETE
            else -> UndoActionType.NONE
        }

        val isTimeout = (now - lastEditTimestamp) > debounceTimeoutMs
        val isTypeSwitch = actionType != lastActionType
        val isWordBoundary = isInsert && (newValue.text.lastOrNull()?.isWhitespace() == true)

        if (undoStack.isEmpty() || isTimeout || isTypeSwitch || isWordBoundary || actionType == UndoActionType.PASTE_OR_BULK) {
            // Push old state as new snapshot boundary
            if (undoStack.size >= maxStackSize) undoStack.removeFirst()
            undoStack.addLast(oldValue)
            redoStack.clear()
        }

        lastEditTimestamp = now
        lastActionType = actionType
    }

    fun onBlur() {
        lastActionType = UndoActionType.NONE
        lastEditTimestamp = 0L
    }

    fun undo(currentValue: TextFieldValue): TextFieldValue? {
        if (undoStack.isEmpty()) return null
        val previousState = undoStack.removeLast()
        redoStack.addLast(currentValue)
        lastActionType = UndoActionType.NONE
        return previousState
    }

    fun redo(currentValue: TextFieldValue): TextFieldValue? {
        if (redoStack.isEmpty()) return null
        val nextState = redoStack.removeLast()
        undoStack.addLast(currentValue)
        lastActionType = UndoActionType.NONE
        return nextState
    }
}
