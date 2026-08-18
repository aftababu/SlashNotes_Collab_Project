package com.slashnote.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.*
import uniffi.slash_notes_core.NoteHeader
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material.icons.outlined.FolderOpen

data class CommandPaletteAction(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: String,
    val onExecute: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    notes: List<NoteHeader>,
    onDismiss: () -> Unit,
    onSelectNote: (filename: String) -> Unit,
    onCreateNote: () -> Unit,
    onCreateFolder: () -> Unit,
    onTriggerGitSync: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onToggleSourceMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val baseActions = listOf(
    CommandPaletteAction("Create New Note", "Initialize a blank markdown file in the current vault", Icons.Default.Add, "ACTIONS", onCreateNote),
    CommandPaletteAction("Git Sync Now", "Commit and push current vault changes silently", Icons.Default.Refresh, "ACTIONS", onTriggerGitSync),
    CommandPaletteAction("Toggle Focus Mode", "Hide sidebar and toolbars for distraction-free writing", Icons.Outlined.FilterCenterFocus, "ACTIONS", onToggleFocusMode), // Replaced Info
    CommandPaletteAction("Toggle Source Mode", "View raw unformatted markdown source", Icons.Default.Edit, "ACTIONS", onToggleSourceMode),
    CommandPaletteAction("Create New Folder", "Organize notes into directory subfolders", Icons.Outlined.FolderOpen, "ACTIONS", onCreateFolder),
    CommandPaletteAction("Sync Settings", "Manage remote repositories and conflict resolution", Icons.Default.Settings, "PREFERENCES", onOpenSettings)
    )
    val filteredActions = remember(query) {
        if (query.trim().isEmpty()) baseActions else {
            baseActions.filter { it.title.lowercase().contains(query.lowercase()) || it.description.lowercase().contains(query.lowercase()) }
        }
    }

    val filteredNotes = remember(query, notes) {
        if (query.trim().isEmpty()) notes.take(6) else {
            notes.filter { it.title.lowercase().contains(query.lowercase()) || it.id.lowercase().contains(query.lowercase()) }.take(6)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StitchBackground,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        windowInsets = WindowInsets.ime,
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            // Drag handle centered top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(width = 36.dp, height = 4.dp),
                    shape = RoundedCornerShape(2.dp),
                    color = StitchBorder
                ) {}
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Outlined Search Input Box
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type a command...", color = StitchTextMuted, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = StitchTextMuted) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = StitchTextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StitchCardBg,
                        unfocusedContainerColor = StitchCardBg,
                        focusedBorderColor = StitchAccentCoral,
                        unfocusedBorderColor = StitchBorder
                    )
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val groupedActions = filteredActions.groupBy { it.category }

                    groupedActions.forEach { (category, actions) ->
                        item {
                            Text(
                                text = category.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StitchTextMuted,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(actions) { action ->
                            StitchCommandActionCard(
                                action = action,
                                onClick = {
                                    onDismiss()
                                    action.onExecute()
                                }
                            )
                        }
                    }

                    if (filteredNotes.isNotEmpty()) {
                        item {
                            Text(
                                text = "NOTES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StitchTextMuted,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }

                        items(filteredNotes) { note ->
                            Surface(
                                onClick = {
                                    onDismiss()
                                    onSelectNote(note.id)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = StitchCardBg,
                                border = BorderStroke(1.dp, StitchBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(note.title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer Bar matching Stitch Image 4
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = StitchSurfaceSecondary,
                border = BorderStroke(1.dp, StitchBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⌃ ⌄ to navigate", fontSize = 12.sp, color = StitchTextMuted)
                    Text("↵ to select", fontSize = 12.sp, color = StitchTextMuted)
                }
            }
        }
    }
}

@Composable
private fun StitchCommandActionCard(
    action: CommandPaletteAction,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = StitchCardBg,
        border = BorderStroke(1.dp, StitchBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left pink accent indicator bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .background(StitchAccentCoral)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon container box
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(action.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(action.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
