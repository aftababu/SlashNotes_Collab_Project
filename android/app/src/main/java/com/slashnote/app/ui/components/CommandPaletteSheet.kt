package com.slashnote.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.StitchAccentCoral

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
    onDismiss: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onExportPdf: () -> Unit,
    onShareNote: () -> Unit,
    onTriggerGitSync: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val baseActions = remember(onToggleFocusMode, onExportPdf, onShareNote, onTriggerGitSync, onOpenSettings) {
        listOf(
            CommandPaletteAction(
                title = "Toggle Focus Mode",
                description = "Hide headers and toolbars for distraction-free writing",
                icon = Icons.Outlined.FilterCenterFocus,
                category = "ACTIONS",
                onExecute = onToggleFocusMode
            ),
            CommandPaletteAction(
                title = "Git Sync Now",
                description = "Commit and push current vault changes to GitHub remote",
                icon = Icons.Default.Sync,
                category = "ACTIONS",
                onExecute = onTriggerGitSync
            ),
            CommandPaletteAction(
                title = "Sync Settings",
                description = "Configure remote repositories and Personal Access Tokens",
                icon = Icons.Default.Settings,
                category = "PREFERENCES",
                onExecute = onOpenSettings
            ),
            CommandPaletteAction(
                title = "Export as PDF",
                description = "Save current note as PDF document to Downloads folder",
                icon = Icons.Default.PictureAsPdf,
                category = "ACTIONS",
                onExecute = onExportPdf
            ),
            CommandPaletteAction(
                title = "Share Note",
                description = "Share note text via Android system share sheet",
                icon = Icons.Default.Share,
                category = "ACTIONS",
                onExecute = onShareNote
            )
        )
    }

    val filteredActions = remember(query, baseActions) {
        if (query.trim().isEmpty()) baseActions else {
            baseActions.filter {
                it.title.lowercase().contains(query.lowercase()) ||
                    it.description.lowercase().contains(query.lowercase())
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        windowInsets = WindowInsets.ime,
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
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
                    color = MaterialTheme.colorScheme.outlineVariant
                ) {}
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Outlined Search Input Box
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type a command...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val groupedActions = filteredActions.groupBy { it.category }

                    groupedActions.forEach { (category, actions) ->
                        item(key = category) {
                            Text(
                                text = category.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(actions, key = { it.title }) { action ->
                            StitchCommandActionCard(
                                action = action,
                                onClick = {
                                    onDismiss()
                                    action.onExecute()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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
