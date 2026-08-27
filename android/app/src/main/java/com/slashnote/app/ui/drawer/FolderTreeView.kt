package com.slashnote.app.ui.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.StitchAccentCoral
import com.slashnote.app.ui.theme.StitchBackground
import com.slashnote.app.ui.theme.StitchBorder
import com.slashnote.app.ui.theme.StitchCardBg
import com.slashnote.app.ui.theme.StitchTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.slash_notes_core.*

@Composable
fun FolderTreeView(
    notesDir: String,
    currentRelativeDir: String,
    selectedFilename: String?,
    searchQuery: String = "",
    showAllFiles: Boolean = false,
    isRefreshing: Boolean = false,
    onRefreshNotes: (() -> Unit)? = null,
    onNavigateDir: (newRelativeDir: String) -> Unit,
    onSelectNote: (filename: String) -> Unit,
    onRenameClick: (item: FolderItem) -> Unit,
    onDeleteClick: (item: FolderItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    var expandedPaths by remember { mutableStateOf(setOf<String>()) }
    var rootItems by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<NoteHeader>>(emptyList()) }
    var isTreeLoading by remember { mutableStateOf(false) }

    // Lazily-loaded children per expanded folder path. Only expanded folders
    // are read from disk, and rows are flattened so the LazyColumn virtualizes.
    var childrenCache by remember { mutableStateOf<Map<String, List<FolderItem>>>(emptyMap()) }

    var itemToMove by remember { mutableStateOf<FolderItem?>(null) }
    var isTrashOpen by remember { mutableStateOf(false) }

    var draggedItem by remember { mutableStateOf<FolderItem?>(null) }
    var dropTargetFolder by remember { mutableStateOf<String?>(null) }

    val refreshFolderTree = remember(notesDir, currentRelativeDir, showAllFiles) {
        {
            scope.launch {
                isTreeLoading = true
                rootItems = withContext(Dispatchers.IO) {
                    listFolderContents(notesDir, currentRelativeDir, showAllFiles)
                }
                childrenCache = emptyMap()
                isTreeLoading = false
            }
        }
    }

    LaunchedEffect(notesDir, currentRelativeDir, showAllFiles) {
        refreshFolderTree()
    }

    LaunchedEffect(searchQuery, notesDir) {
        if (searchQuery.isNotBlank()) {
            searchResults = withContext(Dispatchers.IO) {
                searchNotes(notesDir, searchQuery)
            }
        } else {
            searchResults = emptyList()
        }
    }

    // Load children for a folder (called when expanding).
    val loadChildren: (String) -> Unit = remember {
        { folderPath ->
            scope.launch {
                val kids = withContext(Dispatchers.IO) {
                    listFolderContents(notesDir, folderPath, showAllFiles)
                }
                childrenCache = childrenCache + (folderPath to kids)
            }
        }
    }

    // Flatten the visible tree (only expanded folders contribute children).
    val visibleRows = remember(rootItems, childrenCache, expandedPaths) {
        val rows = mutableListOf<Pair<Int, FolderItem>>()
        fun walk(items: List<FolderItem>, depth: Int) {
            for (item in items) {
                rows.add(depth to item)
                if (item.isDir && expandedPaths.contains(item.relativePath)) {
                    childrenCache[item.relativePath]?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(rootItems, 0)
        rows
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (currentRelativeDir.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val parentDir = currentRelativeDir.substringBeforeLast('/', "")
                        onNavigateDir(parentDir)
                    },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Up directory",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ".. / $currentRelativeDir",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }

        if (visibleRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No files in directory",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (searchQuery.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No matching notes found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(searchResults, key = { it.id }) { note ->
                        Surface(
                            onClick = { onSelectNote(note.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(note.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(note.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        } else if (visibleRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = StitchTextMuted,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No notes found. Try refreshing.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            refreshFolderTree()
                            onRefreshNotes?.invoke()
                        },
                        enabled = !isRefreshing && !isTreeLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isRefreshing || isTreeLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refreshing...", fontSize = 13.sp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh Notes", fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    visibleRows,
                    key = { it.second.relativePath },
                    contentType = { it.second.isDir }
                ) { (depth, item) ->
                    TreeItemRow(
                        notesDir = notesDir,
                        item = item,
                        depth = depth,
                        selectedFilename = selectedFilename,
                        isExpanded = expandedPaths.contains(item.relativePath),
                        isHoveredTarget = item.isDir && dropTargetFolder == item.relativePath,
                        draggedItem = draggedItem,
                        onToggleExpand = { path ->
                            if (expandedPaths.contains(path)) {
                                expandedPaths = expandedPaths - path
                                childrenCache = childrenCache - path
                            } else {
                                expandedPaths = expandedPaths + path
                                loadChildren(path)
                            }
                        },
                        onNavigateDir = onNavigateDir,
                        onSelectNote = onSelectNote,
                        onRenameClick = onRenameClick,
                        onDeleteClick = onDeleteClick,
                        onMoveClick = { itemToMove = it },
                        onDragStart = { draggedItem = it },
                        onDragHover = { folderPath -> dropTargetFolder = folderPath },
                        onDragEnd = {
                            if (draggedItem != null && dropTargetFolder != null && draggedItem!!.relativePath != dropTargetFolder) {
                                val itemToRelocate = draggedItem!!
                                val targetDir = dropTargetFolder!!
                                scope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        try {
                                            moveItem(notesDir, itemToRelocate.relativePath, targetDir)
                                            true
                                        } catch (e: Exception) {
                                            false
                                        }
                                    }
                                    draggedItem = null
                                    dropTargetFolder = null
                                    if (success) refreshFolderTree()
                                }
                            } else {
                                draggedItem = null
                                dropTargetFolder = null
                            }
                        }
                    )
                }
            }
        }
    }

    if (isTrashOpen) {
        TrashBinSheet(
            notesDir = notesDir,
            onDismiss = { isTrashOpen = false },
            onChanged = { refreshFolderTree() }
        )
    }

    if (itemToMove != null) {
        MoveToDirectoryDialog(
            notesDir = notesDir,
            item = itemToMove!!,
            showAllFiles = showAllFiles,
            onDismiss = { itemToMove = null },
            onConfirmMove = { targetRelDir ->
                val movingItem = itemToMove!!
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            moveItem(notesDir, movingItem.relativePath, targetRelDir)
                        } catch (e: Exception) {
                            // Surface as no-op; tree refresh reflects the unchanged state.
                        }
                    }
                    itemToMove = null
                    refreshFolderTree()
                }
            }
        )
    }
}

@Composable
private fun TreeItemRow(
    notesDir: String,
    item: FolderItem,
    depth: Int,
    selectedFilename: String?,
    isExpanded: Boolean,
    isHoveredTarget: Boolean,
    draggedItem: FolderItem?,
    onToggleExpand: (String) -> Unit,
    onNavigateDir: (String) -> Unit,
    onSelectNote: (String) -> Unit,
    onRenameClick: (FolderItem) -> Unit,
    onDeleteClick: (FolderItem) -> Unit,
    onMoveClick: (FolderItem) -> Unit,
    onDragStart: (FolderItem) -> Unit,
    onDragHover: (String?) -> Unit,
    onDragEnd: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isSelected = selectedFilename == item.relativePath

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart(item) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDragHover(if (item.isDir) item.relativePath else null)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
            .clickable {
                if (item.isDir) {
                    onToggleExpand(item.relativePath)
                } else {
                    onSelectNote(item.relativePath)
                }
            },
        color = when {
            isHoveredTarget -> MaterialTheme.colorScheme.surfaceVariant
            isSelected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        border = if (isHoveredTarget) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = (12 + depth * 14).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (item.isDir) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                } else {
                    Spacer(modifier = Modifier.width(20.dp))
                }

                Icon(
                    imageVector = if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    fontWeight = if (item.isDir) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (item.isDir) {
                        DropdownMenuItem(
                            text = { Text("Open Folder") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = "Open Folder"
                                )
                            },
                            onClick = {
                                showMenu = false
                                onNavigateDir(item.relativePath)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Move To...") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move To") },
                        onClick = {
                            showMenu = false
                            onMoveClick(item)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRenameClick(item)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeleteClick(item)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MoveToDirectoryDialog(
    notesDir: String,
    item: FolderItem,
    showAllFiles: Boolean = true,
    onDismiss: () -> Unit,
    onConfirmMove: (targetRelDir: String) -> Unit
) {
    var availableDirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTarget by remember { mutableStateOf("") } // "" represents Root /

    LaunchedEffect(notesDir) {
        availableDirs = withContext(Dispatchers.IO) {
            val list = mutableListOf<String>("") // Add Root /
            fun collectDirs(dirRelPath: String) {
                val contents = listFolderContents(notesDir, dirRelPath, showAllFiles)
                contents.filter { it.isDir }.forEach { sub ->
                    list.add(sub.relativePath)
                    collectDirs(sub.relativePath)
                }
            }
            collectDirs("")
            list
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move '${item.name}' To...") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableDirs.forEach { dirPath ->
                    val isSelected = selectedTarget == dirPath
                    Surface(
                        onClick = { selectedTarget = dirPath },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) StitchAccentCoral.copy(alpha = 0.2f) else StitchCardBg,
                        border = BorderStroke(1.dp, if (isSelected) StitchAccentCoral else StitchBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (dirPath.isEmpty()) "Root (/)" else dirPath,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmMove(selectedTarget) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Text("Move Here")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashBinSheet(
    notesDir: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var trashItems by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var trashPath by remember { mutableStateOf("") } // relative to .trash/ for browsing nested folders
    val context = androidx.compose.ui.platform.LocalContext.current

    val refreshTrash = {
        scope.launch {
            trashItems = withContext(Dispatchers.IO) {
                if (trashPath.isEmpty()) {
                    listTrashContents(notesDir)
                } else {
                    listFolderContents(notesDir, ".trash/$trashPath", true)
                }
            }
        }
    }

    LaunchedEffect(notesDir, trashPath) {
        refreshTrash()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (trashPath.isEmpty()) "Trash Bin" else "Trash / $trashPath",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (trashPath.isNotEmpty()) {
                    TextButton(onClick = { trashPath = "" }) {
                        Text("Up to root", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }

            if (trashItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Trash is empty", color = StitchTextMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(trashItems, key = { it.relativePath }, contentType = { it.isDir }) { item ->
                        TrashItemRow(
                            item = item,
                            onOpenFolder = {
                                if (item.isDir) trashPath = item.relativePath
                            },
                            onRestore = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        try {
                                            restoreFromTrash(notesDir, item.relativePath)
                                            true
                                        } catch (e: Exception) {
                                            false
                                        }
                                    }
                                    if (ok) {
                                        android.widget.Toast.makeText(context, "Restored", android.widget.Toast.LENGTH_SHORT).show()
                                        onChanged()
                                        refreshTrash()
                                    } else {
                                        android.widget.Toast.makeText(context, "Could not restore", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = StitchBorder, modifier = Modifier.padding(vertical = 12.dp))

            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                emptyTrash(notesDir)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }
                        if (ok) {
                            android.widget.Toast.makeText(context, "Trash emptied", android.widget.Toast.LENGTH_SHORT).show()
                            onChanged()
                            refreshTrash()
                        }
                    }
                },
                enabled = trashItems.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                    disabledContainerColor = StitchCardBg,
                    disabledContentColor = StitchTextMuted
                )
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Empty Trash", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TrashItemRow(
    item: FolderItem,
    onOpenFolder: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(
        onClick = { if (item.isDir) onOpenFolder() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = item.name,
                fontSize = 13.sp,
                fontWeight = if (item.isDir) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!item.isDir) {
                TextButton(onClick = onRestore) {
                    Text("Restore", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }
    }
}
