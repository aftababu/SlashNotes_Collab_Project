package com.slashnote.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.slashnote.app.security.AppSettings
import com.slashnote.app.security.SecureStorage
import com.slashnote.app.sync.GitSyncWorker
import com.slashnote.app.utils.StorageUtils
import com.slashnote.app.ui.components.BottomNavBar
import com.slashnote.app.ui.components.CommandPaletteSheet
import com.slashnote.app.ui.components.StitchBottomTab
import com.slashnote.app.ui.components.UnifiedSearchOverlay
import com.slashnote.app.ui.drawer.FolderTreeView
import com.slashnote.app.ui.editor.InNoteSearchBar
import com.slashnote.app.ui.editor.MarkdownPreview
import com.slashnote.app.ui.editor.MarkdownSpanCache
import uniffi.slash_notes_core.syncGitRepository
import uniffi.slash_notes_core.initGitRepository
import com.slashnote.app.ui.editor.MarkdownVisualTransformation
import com.slashnote.app.ui.editor.markdownHash
import com.slashnote.app.ui.settings.SettingsScreen
import com.slashnote.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.slash_notes_core.*
import java.io.File

/**
 * Resolve a SAF `content://` tree/document URI into a normalized physical POSIX
 * path. Android exposes `primary` storage at `/storage/emulated/0`; other
 * volumes map to `/storage/<volume>`.
 *
 * libgit2 runs in native Rust and cannot accept `content://` URIs, so this must
 * be called before the value is passed through the UniFFI bridge.
 */
private fun Uri.toAbsolutePath(): String {
    val raw = path ?: return toString()
    val decoded = Uri.decode(raw)
    val (volume, subPath) = when {
        decoded.contains(":") -> {
            val head = decoded.substringBefore(":").substringAfterLast("/")
            val tail = decoded.substringAfter(":")
            head to tail
        }
        decoded.contains("/tree/") -> "primary" to decoded.substringAfter("/tree/")
        decoded.contains("/document/") -> "primary" to decoded.substringAfter("/document/")
        else -> "primary" to decoded
    }

    val base = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }

    return File(base, subPath.trim('/')).absolutePath
}

private const val TAG = "SlashNoteVault"

class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("slash_notes_core")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Schedule silent background git sync
        GitSyncWorker.schedulePeriodicSync(this)

        setContent {
            var darkTheme by remember { mutableStateOf(true) }

            SlashNoteTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StitchBackground
                ) {
                    SlashNoteApp(
                        isDarkTheme = darkTheme,
                        onToggleTheme = { darkTheme = !darkTheme }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlashNoteApp(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var customVaultPathState by remember { mutableStateOf(AppSettings.getCustomVaultPath(context)) }
    var showAllFilesState by remember { mutableStateOf(AppSettings.getShowAllFiles(context)) }

    val notesDir = remember(customVaultPathState) {
        val custom = customVaultPathState.trim()
        val resolved = if (custom.isNotEmpty()) {
            val file = File(custom)
            if (!file.exists()) file.mkdirs()
            if (file.exists() && file.isDirectory && file.canWrite()) {
                file.absolutePath
            } else {
                File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }.absolutePath
            }
        } else {
            File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }.absolutePath
        }
        Log.i(TAG, "Notes directory (POSIX): $resolved")
        resolved
    }

    var selectedFilename by remember { mutableStateOf<String?>(null) }
    var currentRelativeDir by remember { mutableStateOf("") }
    var activeBottomTab by remember { mutableStateOf(StitchBottomTab.EDITOR) }

    var isSettingsOpen by remember { mutableStateOf(false) }
    var isCreateNoteOpen by remember { mutableStateOf(false) }
    var isCreateFolderOpen by remember { mutableStateOf(false) }
    var isCommandPaletteOpen by remember { mutableStateOf(false) }
    var isUnifiedSearchOpen by remember { mutableStateOf(false) }
    var itemToRename by remember { mutableStateOf<FolderItem?>(null) }

    var isFocusMode by remember { mutableStateOf(false) }
    var isSourceMode by remember { mutableStateOf(false) }
    var isGlobalPreviewMode by remember { mutableStateOf(false) }
    var syncErrorDialogText by remember { mutableStateOf<String?>(null) }
    val noteBackStack = remember { mutableStateListOf<String>() }
    var activeHeadingAnchor by remember { mutableStateOf<String?>(null) }

    var allNotesList by remember { mutableStateOf<List<NoteHeader>>(emptyList()) }
    var pinnedIds by remember { mutableStateOf(AppSettings.getPinnedNoteIds(context)) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val refreshNotesList = remember {
        {
            scope.launch {
                allNotesList = withContext(Dispatchers.IO) {
                    listNotes(notesDir)
                }
                pinnedIds = AppSettings.getPinnedNoteIds(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        StorageUtils.checkAndRequestStoragePermission(context)
    }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist SAF URI permission", e)
            }

            StorageUtils.checkAndRequestStoragePermission(context)

            // Convert content:// tree URI to a physical POSIX path that libgit2 can use.
            val resolvedPath = uri.toAbsolutePath()
            Log.i(TAG, "SAF vault resolved to POSIX path: $resolvedPath")

            // Ensure the directory physically exists and is writable before it
            // ever reaches the Rust core / libgit2.
            val vaultDir = File(resolvedPath)
            val created = if (!vaultDir.exists()) vaultDir.mkdirs() else true
            Log.i(TAG, "Vault directory exists=${vaultDir.exists()}, created=$created, canWrite=${vaultDir.canWrite()}")

            if (!vaultDir.exists() || !vaultDir.isDirectory) {
                Toast.makeText(context, "Invalid vault directory: $resolvedPath", Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            if (!vaultDir.canWrite()) {
                Toast.makeText(context, "No write permission for vault: $resolvedPath", Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }

            AppSettings.saveCustomVaultPath(context, resolvedPath)
            customVaultPathState = resolvedPath
            Toast.makeText(context, "Vault: $resolvedPath", Toast.LENGTH_SHORT).show()
            refreshNotesList()
        }
    }

    LaunchedEffect(notesDir) {
        refreshNotesList()
    }

    val handleBottomTabSelect: (StitchBottomTab) -> Unit = remember {
        { tab ->
            activeBottomTab = tab
            when (tab) {
                StitchBottomTab.EDITOR -> {
                    isSettingsOpen = false
                    selectedFilename = null
                }
                StitchBottomTab.SEARCH -> isUnifiedSearchOpen = true
                StitchBottomTab.SETTINGS -> isSettingsOpen = true
            }
        }
    }

    if (isSettingsOpen) {
        SettingsScreen(
            notesDir = notesDir,
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            onSelectBottomTab = handleBottomTabSelect,
            onPickCustomVaultPath = { safFolderLauncher.launch(null) }
        )
    } else if (selectedFilename == null) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = StitchSurfaceSecondary,
                    modifier = Modifier.width(320.dp)
                ) {
                    val vaultName = remember(notesDir) { File(notesDir).name.ifEmpty { "Vault" } }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = StitchAccentCoral,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = vaultName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Row {
                            IconButton(
                                onClick = { isCreateNoteOpen = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "New File", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { isCreateFolderOpen = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = "New Folder", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    HorizontalDivider(color = StitchBorder)

                    // Virtualized Nested Folder Tree View
                    Box(modifier = Modifier.weight(1f)) {
                        FolderTreeView(
                            notesDir = notesDir,
                            currentRelativeDir = currentRelativeDir,
                            selectedFilename = selectedFilename,
                            showAllFiles = showAllFilesState,
                            onNavigateDir = { currentRelativeDir = it },
                            onSelectNote = { relPath ->
                                scope.launch {
                                    drawerState.close()
                                    noteBackStack.clear()
                                    activeHeadingAnchor = null
                                    selectedFilename = relPath
                                }
                            },
                            onRenameClick = { item -> itemToRename = item },
                            onDeleteClick = { item ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        deleteItem(notesDir, item.relativePath)
                                    }
                                    refreshNotesList()
                                }
                            }
                        )
                    }

                    HorizontalDivider(color = StitchBorder)

                    // Split Footer Bar (Git Sync on Bottom-Left, Settings Gear on Bottom-Right)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = StitchBackground
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val gitBranch = remember { SecureStorage.getGitBranch(context).ifEmpty { "main" } }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        val token = SecureStorage.getGitToken(context)
                                        val remoteUrl = SecureStorage.getRemoteUrl(context)
                                        val branch = SecureStorage.getGitBranch(context)
                                        if (remoteUrl.isEmpty() || token.isEmpty()) {
                                            Toast.makeText(context, "Sync Failed: Remote URL or PAT token missing in Settings", Toast.LENGTH_LONG).show()
                                        } else {
                                             Toast.makeText(context, "Syncing with GitHub...", Toast.LENGTH_SHORT).show()
                                             StorageUtils.checkAndRequestStoragePermission(context)
                                             val targetRepoDir = StorageUtils.getSafeVaultPath(context, notesDir)
                                             scope.launch(Dispatchers.IO) {
                                                 try {
                                                     initGitRepository(targetRepoDir)
                                                     val msg = syncGitRepository(targetRepoDir, token, remoteUrl, branch)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                        refreshNotesList()
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("GitSync", "Full Sync Error Details:", e)
                                                    val errMsg = e.message ?: e.toString()
                                                    withContext(Dispatchers.Main) {
                                                        syncErrorDialogText = errMsg
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Sync Vault",
                                    tint = StitchAccentCoral,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = gitBranch,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StitchTextMuted,
                                    fontSize = 12.sp
                                )
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        drawerState.close()
                                        activeBottomTab = StitchBottomTab.SETTINGS
                                        isSettingsOpen = true
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                bottomBar = {
                    BottomNavBar(
                        selectedTab = activeBottomTab,
                        onSelectTab = handleBottomTabSelect
                    )
                },
                containerColor = StitchBackground
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    NoteListScreen(
                        notesDir = notesDir,
                        pinnedIds = pinnedIds,
                        activeBottomTab = activeBottomTab,
                        onSelectBottomTab = handleBottomTabSelect,
                        onNoteSelected = { relPath ->
                            noteBackStack.clear()
                            activeHeadingAnchor = null
                            selectedFilename = relPath
                        },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSearch = { isUnifiedSearchOpen = true },
                        onCreateNote = { isCreateNoteOpen = true },
                        onOpenVaultPicker = { safFolderLauncher.launch(null) },
                        onTogglePin = { noteId ->
                            AppSettings.togglePinnedNoteId(context, noteId)
                            refreshNotesList()
                        },
                        onDuplicate = { filename ->
                            scope.launch {
                                val duplicated = withContext(Dispatchers.IO) {
                                    duplicateNote(notesDir, filename)
                                }
                                if (duplicated.isNotEmpty()) {
                                    Toast.makeText(context, "Duplicated: $duplicated", Toast.LENGTH_SHORT).show()
                                    refreshNotesList()
                                }
                            }
                        },
                        onCopyPath = { filename ->
                            val fullPath = File(notesDir, filename).absolutePath
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("File Path", fullPath))
                            Toast.makeText(context, "Copied filepath to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onTriggerSync = {
                            val token = SecureStorage.getGitToken(context)
                            val remoteUrl = SecureStorage.getRemoteUrl(context)
                            val branch = SecureStorage.getGitBranch(context)
                            if (remoteUrl.isEmpty() || token.isEmpty()) {
                                Toast.makeText(context, "Sync Failed: Remote URL or PAT token missing in Settings", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Syncing with GitHub...", Toast.LENGTH_SHORT).show()
                                StorageUtils.checkAndRequestStoragePermission(context)
                                val targetRepoDir = StorageUtils.getSafeVaultPath(context, notesDir)
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        initGitRepository(targetRepoDir)
                                        val msg = syncGitRepository(targetRepoDir, token, remoteUrl, branch)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            refreshNotesList()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("GitSync", "Full Sync Error Details:", e)
                                        val errMsg = e.message ?: e.toString()
                                        withContext(Dispatchers.Main) {
                                            syncErrorDialogText = errMsg
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    } else {
        NoteEditorScreen(
            notesDir = notesDir,
            filename = selectedFilename!!,
            headingAnchor = activeHeadingAnchor,
            isDarkTheme = isDarkTheme,
            isFocusMode = isFocusMode,
            isSourceMode = isSourceMode,
            isPreviewMode = isGlobalPreviewMode,
            onTogglePreviewMode = { isGlobalPreviewMode = !isGlobalPreviewMode },
            onToggleFocusMode = { isFocusMode = !isFocusMode },
            onToggleSourceMode = { isSourceMode = !isSourceMode },
            onOpenCommandPalette = { isCommandPaletteOpen = true },
            onBack = {
                if (noteBackStack.isNotEmpty()) {
                    val prevNote = noteBackStack.removeAt(noteBackStack.size - 1)
                    activeHeadingAnchor = null
                    selectedFilename = prevNote
                } else {
                    activeHeadingAnchor = null
                    selectedFilename = null
                    refreshNotesList()
                }
            },
            onNavigateToWikilink = { rawLink ->
                scope.launch {
                    val parts = rawLink.split("#", limit = 2)
                    val rawPath = parts[0].trim()
                    val headingTag = if (parts.size > 1) parts[1].trim().removePrefix("#").trim() else null

                    if (rawPath.isNotEmpty()) {
                        val targetFileName = if (rawPath.endsWith(".md", ignoreCase = true)) rawPath else "$rawPath.md"
                        val allNotes = withContext(Dispatchers.IO) {
                            getCachedNoteHeaders(notesDir)
                        }

                        val existingNote = allNotes.find { note ->
                            note.relativePath.equals(targetFileName, ignoreCase = true) ||
                            note.relativePath.equals(rawPath, ignoreCase = true) ||
                            note.title.equals(rawPath, ignoreCase = true) ||
                            note.id.equals(rawPath, ignoreCase = true) ||
                            note.id.equals(targetFileName, ignoreCase = true)
                        }

                        if (existingNote != null) {
                            selectedFilename?.let { current ->
                                if (current != existingNote.relativePath) {
                                    noteBackStack.add(current)
                                }
                            }
                            activeHeadingAnchor = headingTag
                            selectedFilename = existingNote.relativePath
                        } else {
                            Toast.makeText(context, "Note not found: $rawPath", Toast.LENGTH_SHORT).show()
                        }
                    } else if (headingTag != null) {
                        activeHeadingAnchor = headingTag
                    }
                }
            }
        )
    }

    if (isUnifiedSearchOpen) {
        UnifiedSearchOverlay(
            notesDir = notesDir,
            onDismiss = {
                isUnifiedSearchOpen = false
                activeBottomTab = StitchBottomTab.EDITOR
            },
            onSelectNote = { filename ->
                selectedFilename = filename
            }
        )
    }

    if (isCommandPaletteOpen) {
        CommandPaletteSheet(
            notes = allNotesList,
            onDismiss = {
                isCommandPaletteOpen = false
                activeBottomTab = StitchBottomTab.EDITOR
            },
            onSelectNote = { filename ->
                selectedFilename = filename
            },
            onCreateNote = { isCreateNoteOpen = true },
            onCreateFolder = { isCreateFolderOpen = true },
            onTriggerGitSync = {
                val token = SecureStorage.getGitToken(context)
                val remoteUrl = SecureStorage.getRemoteUrl(context)
                val branch = SecureStorage.getGitBranch(context)
                if (remoteUrl.isEmpty() || token.isEmpty()) {
                    Toast.makeText(context, "Sync Failed: Remote URL or PAT token missing in Settings", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Syncing with GitHub...", Toast.LENGTH_SHORT).show()
                    StorageUtils.checkAndRequestStoragePermission(context)
                    val targetRepoDir = StorageUtils.getSafeVaultPath(context, notesDir)
                    scope.launch(Dispatchers.IO) {
                        try {
                            initGitRepository(targetRepoDir)
                            val msg = syncGitRepository(targetRepoDir, token, remoteUrl, branch)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                refreshNotesList()
                            }
                        } catch (e: Exception) {
                            Log.e("GitSync", "Full Sync Error Details:", e)
                            val errMsg = e.message ?: e.toString()
                            withContext(Dispatchers.Main) {
                                syncErrorDialogText = errMsg
                            }
                        }
                    }
                }
            },
            onToggleTheme = onToggleTheme,
            onToggleFocusMode = { isFocusMode = !isFocusMode },
            onToggleSourceMode = { isSourceMode = !isSourceMode },
            onOpenSettings = {
                isSettingsOpen = true
                activeBottomTab = StitchBottomTab.SETTINGS
            }
        )
    }

    if (isCreateNoteOpen) {
        CreateNoteDialog(
            currentDir = currentRelativeDir,
            defaultTemplate = AppSettings.getNoteTemplate(context),
            onDismiss = { isCreateNoteOpen = false },
            onCreate = { titleOrTemplate ->
                scope.launch {
                    val fullPath = if (currentRelativeDir.isEmpty()) titleOrTemplate else "$currentRelativeDir/$titleOrTemplate"
                    val filename = withContext(Dispatchers.IO) {
                        createNote(notesDir, fullPath)
                    }
                    isCreateNoteOpen = false
                    if (filename.isNotEmpty()) {
                        selectedFilename = filename
                        refreshNotesList()
                    }
                }
            }
        )
    }

    if (isCreateFolderOpen) {
        CreateFolderDialog(
            currentDir = currentRelativeDir,
            onDismiss = { isCreateFolderOpen = false },
            onCreate = { folderName ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        createFolder(notesDir, currentRelativeDir, folderName)
                    }
                    isCreateFolderOpen = false
                    refreshNotesList()
                }
            }
        )
    }

    if (itemToRename != null) {
        RenameItemDialog(
            item = itemToRename!!,
            onDismiss = { itemToRename = null },
            onRename = { newName ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        renameItem(notesDir, itemToRename!!.relativePath, newName)
                    }
                    itemToRename = null
                    refreshNotesList()
                }
            }
        )
    }

    if (syncErrorDialogText != null) {
        AlertDialog(
            onDismissRequest = { syncErrorDialogText = null },
            title = {
                Text(
                    text = "Git Sync Error Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = syncErrorDialogText!!,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { syncErrorDialogText = null }) {
                    Text("Close", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Git Error", syncErrorDialogText))
                    Toast.makeText(context, "Copied error to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy Error", color = StitchAccentCoral)
                }
            },
            containerColor = StitchCardBg
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    notesDir: String,
    pinnedIds: Set<String>,
    activeBottomTab: StitchBottomTab,
    onSelectBottomTab: (StitchBottomTab) -> Unit,
    onNoteSelected: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onCreateNote: () -> Unit,
    onOpenVaultPicker: () -> Unit,
    onTogglePin: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onTriggerSync: () -> Unit
) {
    var notes by remember { mutableStateOf<List<CachedNoteHeader>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val refreshNotes = {
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                getCachedNoteHeaders(notesDir)
            }
            notes = fetched.sortedByDescending { it.lastModifiedUnix }
        }
    }

    LaunchedEffect(notesDir, pinnedIds) {
        val fetched = withContext(Dispatchers.IO) {
            getCachedNoteHeaders(notesDir)
        }
        notes = fetched.sortedByDescending { it.lastModifiedUnix }
    }

    val context = LocalContext.current
    val recentVisitedPaths = remember(notes) { AppSettings.getRecentlyVisitedNotes(context) }
    val recentNotes = remember(notes, recentVisitedPaths) {
        val map = notes.associateBy { it.relativePath }
        recentVisitedPaths.mapNotNull { map[it] }.take(10)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("SlashNote", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onTriggerSync) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync Vault", tint = Color.White)
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = StitchBackground
                )
            )
        },
        containerColor = StitchBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Workspace Landing Hero Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = StitchCardBg,
                    border = BorderStroke(1.dp, StitchBorder)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = StitchBackground,
                            border = BorderStroke(1.dp, StitchBorder),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = StitchAccentCoral
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Your Workspace",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Select a file to begin writing or create a new note.",
                            fontSize = 12.sp,
                            color = StitchTextMuted
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onCreateNote,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StitchAccentCoral,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("NEW NOTE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onOpenVaultPicker,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, StitchBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = StitchTextMuted, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("OPEN VAULT", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            // 2. Recent Notes Section (Top 10 Recently Modified)
            if (recentNotes.isNotEmpty()) {
                item {
                    Text(
                        text = "RECENT NOTES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StitchTextMuted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(recentNotes, key = { it.id }, contentType = { "note" }) { note ->
                    val isPinned = pinnedIds.contains(note.id)
                    NoteCardItem(
                        note = note,
                        isPinned = isPinned,
                        onClick = { onNoteSelected(note.id) },
                        onTogglePin = { onTogglePin(note.id) },
                        onDuplicate = { onDuplicate(note.id) },
                        onCopyPath = { onCopyPath(note.id) },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    deleteNote(notesDir, note.id)
                                }
                                refreshNotes()
                            }
                        }
                    )
                }
            }
        }
    }
}

private val dateFormatSdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())

@Composable
fun NoteCardItem(
    note: CachedNoteHeader,
    isPinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    // Rendered synchronously from the cached index timestamp — no disk I/O per card.
    val formattedDate = remember(note.lastModifiedUnix) {
        dateFormatSdf.format(java.util.Date(note.lastModifiedUnix * 1000L))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (isPinned) StitchCardSelected else StitchCardBg,
        border = BorderStroke(1.dp, StitchBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Accent Bar / Pill
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(StitchAccentCoral, shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Rounded Icon Container Box (#2D2827)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2D2827),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = null,
                                tint = StitchAccentCoral,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title & Subtitle Row
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPinned) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Pinned",
                                    tint = StitchAccentCoral,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = note.title,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFFFAFAF9),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "${note.id}  •  $formattedDate",
                            fontSize = 11.sp,
                            color = Color(0xFFD3737C),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = StitchTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isPinned) "Unpin Note" else "Pin Note") },
                            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onTogglePin()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate Note") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy Filepath") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onCopyPath()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteEditorScreen(
    notesDir: String,
    filename: String,
    headingAnchor: String? = null,
    isDarkTheme: Boolean,
    isFocusMode: Boolean,
    isSourceMode: Boolean,
    isPreviewMode: Boolean,
    onTogglePreviewMode: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onToggleSourceMode: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onBack: () -> Unit,
    onNavigateToWikilink: (targetTitle: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var textFieldValue by remember(filename) { mutableStateOf(TextFieldValue(text = "")) }
    val noteText by remember { derivedStateOf { textFieldValue.text } }
    var initialLoadedContent by remember(filename) { mutableStateOf<String?>(null) }
    var isDirty by remember(filename) { mutableStateOf(false) }
    var saveStatus by remember(filename) { mutableStateOf("Saved just now") }

    var isSlashMenuVisible by remember { mutableStateOf(false) }
    var isWikilinkMenuVisible by remember { mutableStateOf(false) }
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableStateOf(0) }

    var allNotesList by remember { mutableStateOf<List<NoteHeader>>(emptyList()) }

    // Shared span cache so the visual transformation only does hash lookups.
    val spanCache = remember { MarkdownSpanCache() }

    // Debounced background parse: typing never blocks the UI thread.
    LaunchedEffect(noteText, filename) {
        if (noteText.isEmpty()) return@LaunchedEffect
        delay(120)
        val text = noteText
        val hash = markdownHash(text)
        if (spanCache.get(hash) != null) return@LaunchedEffect
        val spans = withContext(Dispatchers.IO) {
            try {
                parseMarkdownTokens(text)
            } catch (e: Exception) {
                emptyList()
            }
        }
        spanCache.put(hash, spans)
    }

    val currentText by rememberUpdatedState(noteText)

    val matches = remember(noteText, searchQuery, isSearchOpen) {
        if (!isSearchOpen || searchQuery.trim().isEmpty()) emptyList() else {
            val list = mutableListOf<Int>()
            var idx = noteText.indexOf(searchQuery, ignoreCase = true)
            while (idx >= 0) {
                list.add(idx)
                idx = noteText.indexOf(searchQuery, idx + searchQuery.length, ignoreCase = true)
            }
            list
        }
    }

    val handleBack: () -> Unit = remember {
        {
            scope.launch {
                if (isDirty) {
                    saveStatus = "Saving..."
                    withContext(Dispatchers.IO) {
                        saveNote(notesDir, filename, currentText)
                    }
                    isDirty = false
                    saveStatus = "Saved just now"
                }
                onBack()
            }
        }
    }

    BackHandler {
        handleBack()
    }

    val context = LocalContext.current

    val editorScrollState = rememberScrollState()
    var savedScrollRatio by remember(filename) { mutableFloatStateOf(0f) }

    LaunchedEffect(isPreviewMode) {
        spanCache.clear()
        if (!isPreviewMode && savedScrollRatio > 0f && editorScrollState.maxValue > 0) {
            val targetScroll = (editorScrollState.maxValue * savedScrollRatio).toInt()
            editorScrollState.scrollTo(targetScroll)
        }
    }

    // Lazy load file body strictly on demand when editor opens
    LaunchedEffect(filename) {
        AppSettings.recordNoteVisited(context, filename)
        val content = withContext(Dispatchers.IO) {
            readNote(notesDir, filename)
        }
        val notes = withContext(Dispatchers.IO) {
            listNotes(notesDir)
        }
        textFieldValue = TextFieldValue(text = content)
        initialLoadedContent = content
        allNotesList = notes
        isDirty = false
        saveStatus = "Saved just now"
    }

    // Auto-save debounced 400ms on user typing pause
    LaunchedEffect(noteText) {
        if (initialLoadedContent == null) return@LaunchedEffect

        if (noteText == initialLoadedContent && !isDirty) return@LaunchedEffect

        isDirty = true
        saveStatus = "Editing..."
        delay(400)
        saveStatus = "Saving..."
        val success = withContext(Dispatchers.IO) {
            saveNote(notesDir, filename, noteText)
        }
        if (success) {
            isDirty = false
            saveStatus = "Saved just now"
        } else {
            saveStatus = "Error saving"
        }
    }

    DisposableEffect(filename) {
        onDispose {
            if (isDirty) {
                saveNote(notesDir, filename, currentText)
            }
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = !isFocusMode) {
                Column {
                    TopAppBar(
                        title = {
                            val parentDir = if (filename.contains("/")) filename.substringBeforeLast("/") + "/" else ""
                            val noteName = if (filename.contains("/")) filename.substringAfterLast("/") else filename
                            val cleanTitle = noteName.removeSuffix(".md")

                            Column(
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (parentDir.isNotEmpty()) {
                                    Text(
                                        text = parentDir,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = StitchTextMuted,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = cleanTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = true)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isPreviewMode) "Preview" else saveStatus,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = StitchAccentCoral,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        softWrap = false
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = handleBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearchOpen = !isSearchOpen }) {
                                Icon(Icons.Default.Search, contentDescription = "Find in note", tint = Color.White)
                            }

                            // Single Edit / Preview Toggle Button
                            IconButton(onClick = {
                                if (!isPreviewMode && editorScrollState.maxValue > 0) {
                                    savedScrollRatio = editorScrollState.value.toFloat() / editorScrollState.maxValue.toFloat()
                                }
                                spanCache.clear()
                                onTogglePreviewMode()
                            }) {
                                Icon(
                                    imageVector = if (isPreviewMode) Icons.Default.Edit else Icons.Outlined.Visibility,
                                    contentDescription = if (isPreviewMode) "Switch to Edit Mode" else "Switch to Preview Mode",
                                    tint = if (isPreviewMode) Color.White else StitchAccentCoral
                                )
                            }

                            IconButton(onClick = onOpenCommandPalette) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Command Palette", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = StitchBackground
                        )
                    )
                }
            }
        },
        containerColor = StitchBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 5.dp, vertical = 4.dp)
        ) {
            if (isPreviewMode) {
                // Rendered preview: proper headings, tables, math, code, wikilinks
                MarkdownPreview(
                    content = noteText,
                    headingAnchor = headingAnchor,
                    initialScrollRatio = savedScrollRatio,
                    onScrollRatioChanged = { ratio -> savedScrollRatio = ratio },
                    onWikilinkClick = { title -> onNavigateToWikilink(title) }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(editorScrollState)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newTfv ->
                            val oldText = textFieldValue.text
                            val newText = newTfv.text
                            val typedChar = if (newText.length == oldText.length + 1) newText.last() else null
                            textFieldValue = newTfv
                            if (typedChar == '/') {
                                isSlashMenuVisible = true
                            }
                            if (typedChar == '[') {
                                if (newText.endsWith("[[")) {
                                    isWikilinkMenuVisible = true
                                }
                            }
                        },
                        readOnly = false,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Default,
                            lineHeight = 22.sp
                        ),
                        cursorBrush = SolidColor(StitchAccentCoral),
                        visualTransformation = remember(isSourceMode, isDarkTheme, spanCache) {
                            if (isSourceMode) {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            } else {
                                MarkdownVisualTransformation(isDarkTheme = isDarkTheme, cache = spanCache)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = Int.MAX_VALUE,
                        singleLine = false,
                        decorationBox = remember {
                            { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (textFieldValue.text.isEmpty()) {
                                        Text(
                                            text = "Start typing note content...",
                                            color = StitchTextMuted,
                                            fontSize = 15.sp,
                                            fontFamily = FontFamily.Default,
                                            lineHeight = 22.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    )
                }
            }

            // Floating Action Button (FAB) for '/' Slash Menu aligned at BottomEnd with imePadding
            if (!isPreviewMode && !isSearchOpen) {
                FloatingActionButton(
                    onClick = {
                        textFieldValue = TextFieldValue(text = "${textFieldValue.text}/")
                        isSlashMenuVisible = true
                    },
                    containerColor = StitchAccentCoral,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Text(
                        text = "/",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Compact Floating In-Note Search Bar attached above keyboard
            if (isSearchOpen) {
                InNoteSearchBar(
                    query = searchQuery,
                    matchCount = matches.size,
                    currentMatchIndex = currentMatchIndex,
                    onQueryChange = {
                        searchQuery = it
                        currentMatchIndex = 0
                    },
                    onNextMatch = {
                        if (matches.isNotEmpty()) {
                            currentMatchIndex = (currentMatchIndex + 1) % matches.size
                        }
                    },
                    onPrevMatch = {
                        if (matches.isNotEmpty()) {
                            currentMatchIndex = if (currentMatchIndex - 1 < 0) matches.size - 1 else currentMatchIndex - 1
                        }
                    },
                    onClose = {
                        isSearchOpen = false
                        searchQuery = ""
                    }
                )
            }

            // Slash Command Menu Popup
            if (isSlashMenuVisible && !isPreviewMode) {
                SlashCommandPopup(
                    onDismiss = { isSlashMenuVisible = false },
                    onSelectOption = { prefix, suffix ->
                        isSlashMenuVisible = false
                        var text = textFieldValue.text
                        if (text.endsWith("/")) {
                            text = text.dropLast(1)
                        }
                        textFieldValue = TextFieldValue(text = "$text$prefix$suffix")
                    }
                )
            }

            // Wikilink Autocomplete Popup
            if (isWikilinkMenuVisible && !isPreviewMode) {
                WikilinkAutocompletePopup(
                    notes = allNotesList,
                    onDismiss = { isWikilinkMenuVisible = false },
                    onSelectNote = { title ->
                        isWikilinkMenuVisible = false
                        val text = textFieldValue.text
                        textFieldValue = TextFieldValue(text = "${text.dropLast(2)}[[$title]]")
                    }
                )
            }
        }
    }
}

@Composable
fun SlashCommandPopup(
    onDismiss: () -> Unit,
    onSelectOption: (prefix: String, suffix: String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StitchCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Slash Commands", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color.White)
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Close", tint = StitchTextMuted)
                }
            }

            HorizontalDivider(color = StitchBorder)

            val commands = listOf(
                "Heading 1" to ("# " to ""),
                "Heading 2" to ("## " to ""),
                "Heading 3" to ("### " to ""),
                "Heading 4" to ("#### " to ""),
                "Bullet List" to ("- " to ""),
                "Numbered List" to ("1. " to ""),
                "Task Checklist" to ("- [ ] " to ""),
                "Fenced Code Block" to ("```\n" to "\n```"),
                "Mermaid Diagram" to ("```mermaid\ngraph TD\n  A[Start] --> B[End]\n" to "```"),
                "Block Math ($$)" to ("$$\n" to "\n$$"),
                "Blockquote" to ("> " to ""),
                "Horizontal Rule" to ("---\n" to ""),
                "Table (3x3)" to ("| Header 1 | Header 2 | Header 3 |\n| --- | --- | --- |\n| Cell 1 | Cell 2 | Cell 3 |\n" to "")
            )

            commands.forEach { (name, format) ->
                TextButton(
                    onClick = { onSelectOption(format.first, format.second) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Text(name, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun WikilinkAutocompletePopup(
    notes: List<NoteHeader>,
    onDismiss: () -> Unit,
    onSelectNote: (title: String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StitchCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Insert Wikilink [[...]]", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color.White)
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Close", tint = StitchTextMuted)
                }
            }

            HorizontalDivider(color = StitchBorder)

            if (notes.isEmpty()) {
                Text("No existing notes found.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = StitchTextMuted)
            } else {
                notes.take(6).forEach { note ->
                    TextButton(
                        onClick = { onSelectNote(note.title) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = StitchAccentCoral, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(note.title, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun CreateNoteDialog(
    currentDir: String,
    defaultTemplate: String,
    onDismiss: () -> Unit,
    onCreate: (titleOrTemplate: String) -> Unit
) {
    var input by remember { mutableStateOf(defaultTemplate) }
    var preview by remember { mutableStateOf("") }

    LaunchedEffect(input) {
        preview = withContext(Dispatchers.IO) {
            previewNoteName(input)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (currentDir.isEmpty()) "Create New Note" else "Create Note in '$currentDir'") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Note Title or Template") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Filename Preview: $preview.md",
                    style = MaterialTheme.typography.labelSmall,
                    color = StitchAccentCoral
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onCreate(input)
                    }
                },
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = StitchAccentCoral)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CreateFolderDialog(
    currentDir: String,
    onDismiss: () -> Unit,
    onCreate: (folderName: String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (currentDir.isEmpty()) "Create New Folder" else "Create Folder in '$currentDir'") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder Name") },
                placeholder = { Text("Projects") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onCreate(folderName)
                    }
                },
                enabled = folderName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = StitchAccentCoral)
            ) {
                Text("Create Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RenameItemDialog(
    item: FolderItem,
    onDismiss: () -> Unit,
    onRename: (newName: String) -> Unit
) {
    var newName by remember { mutableStateOf(item.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${if (item.isDir) "Folder" else "File"}") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onRename(newName)
                    }
                },
                enabled = newName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = StitchAccentCoral)
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
