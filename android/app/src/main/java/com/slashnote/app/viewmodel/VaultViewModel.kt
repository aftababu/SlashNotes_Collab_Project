package com.slashnote.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slashnote.app.security.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.slash_notes_core.FolderItem
import uniffi.slash_notes_core.getCachedNoteHeaders
import uniffi.slash_notes_core.listFolderContents

sealed class FileTreeNodeState {
    object Loading : FileTreeNodeState()
    data class Success(
        val notesDir: String,
        val currentRelativeDir: String,
        val items: List<FolderItem>
    ) : FileTreeNodeState()
    data class Error(val message: String) : FileTreeNodeState()
}

class VaultViewModel : ViewModel() {
    private val _directoryTreeState = MutableStateFlow<FileTreeNodeState>(FileTreeNodeState.Loading)
    val directoryTreeState: StateFlow<FileTreeNodeState> = _directoryTreeState.asStateFlow()

    private val _recentNotesState = MutableStateFlow<List<uniffi.slash_notes_core.CachedNoteHeader>>(emptyList())
    val recentNotesState: StateFlow<List<uniffi.slash_notes_core.CachedNoteHeader>> = _recentNotesState.asStateFlow()

    private var currentNotesDir: String = ""
    private var currentSubDir: String = ""
    private var showAllFiles: Boolean = false

    fun setVault(context: Context, notesDir: String, subDir: String = "", showAll: Boolean = false) {
        currentNotesDir = notesDir
        currentSubDir = subDir
        showAllFiles = showAll
        refreshDirectoryTree(context)
    }

    fun refreshDirectoryTree(context: Context) {
        if (currentNotesDir.isBlank()) {
            _directoryTreeState.value = FileTreeNodeState.Success("", "", emptyList())
            _recentNotesState.value = emptyList()
            return
        }

        viewModelScope.launch {
            _directoryTreeState.value = FileTreeNodeState.Loading
            try {
                val items = withContext(Dispatchers.IO) {
                    listFolderContents(currentNotesDir, currentSubDir, showAllFiles)
                }
                val allFetched = withContext(Dispatchers.IO) {
                    getCachedNoteHeaders(currentNotesDir)
                }
                // Explicit visit history query: only include notes that the user has opened/visited
                val history = AppSettings.getRecentlyVisitedNotes(context)
                val recent = history.mapNotNull { relPath ->
                    allFetched.find { it.relativePath == relPath }
                }.take(10)

                _recentNotesState.value = recent
                _directoryTreeState.value = FileTreeNodeState.Success(currentNotesDir, currentSubDir, items)
            } catch (e: Exception) {
                _directoryTreeState.value = FileTreeNodeState.Error(e.message ?: "Failed to scan vault directory")
            }
        }
    }

    fun recordNoteOpened(context: Context, relativePath: String) {
        if (relativePath.isBlank()) return
        AppSettings.recordNoteVisited(context, relativePath)
        refreshDirectoryTree(context)
    }
}
