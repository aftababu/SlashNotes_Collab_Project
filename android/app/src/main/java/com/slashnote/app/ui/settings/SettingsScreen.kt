package com.slashnote.app.ui.settings

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.security.AppSettings
import com.slashnote.app.security.SecureStorage
import com.slashnote.app.utils.StorageUtils
import com.slashnote.app.sync.GitSyncWorker
import com.slashnote.app.ui.components.BottomNavBar
import com.slashnote.app.ui.components.StitchBottomTab
import com.slashnote.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.slash_notes_core.previewNoteName
import uniffi.slash_notes_core.checkGitConnection
import uniffi.slash_notes_core.syncGitRepository
import uniffi.slash_notes_core.initGitRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    notesDir: String,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSelectBottomTab: (StitchBottomTab) -> Unit,
    onPickCustomVaultPath: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableStateOf(0) }

    var token by remember { mutableStateOf(SecureStorage.getGitToken(context)) }
    var remoteUrl by remember { mutableStateOf(SecureStorage.getRemoteUrl(context)) }
    var branch by remember { mutableStateOf(SecureStorage.getGitBranch(context)) }

    var customVaultPath by remember { mutableStateOf(AppSettings.getCustomVaultPath(context).ifEmpty { notesDir }) }
    var showAllFiles by remember { mutableStateOf(AppSettings.getShowAllFiles(context)) }
    var template by remember { mutableStateOf(AppSettings.getNoteTemplate(context)) }
    var ignored by remember { mutableStateOf(AppSettings.getIgnoredPatterns(context)) }
    var fontSizeSp by remember { mutableStateOf(AppSettings.getFontSizeSp(context)) }
    var isRtl by remember { mutableStateOf(AppSettings.getIsRtl(context)) }
    var autoSaveEnabled by remember { mutableStateOf(true) }
    var liveParseEnabled by remember { mutableStateOf(true) }

    var livePreview by remember { mutableStateOf("") }

    LaunchedEffect(template) {
        livePreview = withContext(Dispatchers.IO) {
            previewNoteName(template)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vault & Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            BottomNavBar(
                selectedTab = StitchBottomTab.SETTINGS,
                onSelectTab = onSelectBottomTab
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = MaterialTheme.colorScheme.onBackground,
                        height = 3.dp
                    )
                }
            ) {
                listOf("General", "Git & Sync", "Appearance", "Shortcuts").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                title,
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 14.sp,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTabIndex == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedTabIndex) {
                    0 -> GeneralStitchTab(
                        vaultPath = customVaultPath,
                        onVaultPathChange = {
                            customVaultPath = it
                            AppSettings.saveCustomVaultPath(context, it)
                        },
                        onPickCustomVaultPath = onPickCustomVaultPath,
                        showAllFiles = showAllFiles,
                        onToggleShowAllFiles = {
                            showAllFiles = !showAllFiles
                            AppSettings.saveShowAllFiles(context, showAllFiles)
                        },
                        template = template,
                        onTemplateChange = {
                            template = it
                            AppSettings.saveNoteTemplate(context, it)
                        },
                        ignored = ignored,
                        onIgnoredChange = {
                            ignored = it
                            AppSettings.saveIgnoredPatterns(context, it)
                        },
                        liveParseEnabled = liveParseEnabled,
                        onToggleLiveParse = { liveParseEnabled = !liveParseEnabled },
                        autoSaveEnabled = autoSaveEnabled,
                        onToggleAutoSave = { autoSaveEnabled = !autoSaveEnabled }
                    )
                    1 -> GitSyncTab(
                        notesDir = notesDir,
                        remoteUrl = remoteUrl,
                        onRemoteUrlChange = {
                            remoteUrl = it
                            SecureStorage.saveRemoteUrl(context, it)
                        },
                        token = token,
                        onTokenChange = {
                            token = it
                            SecureStorage.saveGitToken(context, it)
                        },
                        branch = branch,
                        onBranchChange = {
                            branch = it
                            SecureStorage.saveGitBranch(context, it)
                        },
                        onSyncNow = {
                            val tokenVal = SecureStorage.getGitToken(context)
                            val remoteVal = SecureStorage.getRemoteUrl(context)
                            val branchVal = SecureStorage.getGitBranch(context)
                            if (remoteVal.isEmpty() || tokenVal.isEmpty()) {
                                Toast.makeText(context, "Sync Failed: Remote URL or PAT token missing in Settings", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Syncing with GitHub...", Toast.LENGTH_SHORT).show()
                                StorageUtils.checkAndRequestStoragePermission(context)
                                val targetRepoDir = StorageUtils.getSafeVaultPath(context, notesDir)
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        initGitRepository(targetRepoDir)
                                        val msg = syncGitRepository(targetRepoDir, tokenVal, remoteVal, branchVal)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("GitSync", "Full Sync Error Details:", e)
                                        val errMsg = e.message ?: "Git sync failed"
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Sync Failed: $errMsg", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    )
                    2 -> AppearanceTab(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                        fontSizeSp = fontSizeSp,
                        onFontSizeChange = {
                            fontSizeSp = it
                            AppSettings.saveFontSizeSp(context, it)
                        },
                        isRtl = isRtl,
                        onToggleRtl = {
                            isRtl = !isRtl
                            AppSettings.saveIsRtl(context, isRtl)
                        }
                    )
                    3 -> ShortcutsTab()
                }
            }
        }
    }
}

@Composable
private fun GeneralStitchTab(
    vaultPath: String,
    onVaultPathChange: (String) -> Unit,
    onPickCustomVaultPath: () -> Unit,
    showAllFiles: Boolean,
    onToggleShowAllFiles: () -> Unit,
    template: String,
    onTemplateChange: (String) -> Unit,
    ignored: String,
    onIgnoredChange: (String) -> Unit,
    liveParseEnabled: Boolean,
    onToggleLiveParse: () -> Unit,
    autoSaveEnabled: Boolean,
    onToggleAutoSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Section 1: General Vault Configuration
        Text(
            text = "General Vault Configuration",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        StitchCardContainer {
            // Field 1: Local Vault Path
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Local Vault Path", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "The physical location of your markdown files on this device.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vaultPath,
                        onValueChange = onVaultPathChange,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Button(
                        onClick = onPickCustomVaultPath,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Browse", fontSize = 13.sp)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Field 2: Show All Files Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show All Files", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Display all file extensions alongside Markdown notes in the sidebar explorer",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = showAllFiles,
                    onCheckedChange = { onToggleShowAllFiles() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.background
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Field 3: Default Note Template
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Default Note Template", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Template applied to newly created notes in the root directory.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = template,
                    onValueChange = onTemplateChange,
                    singleLine = true,
                    trailingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedBorderColor = StitchBorder,
                        unfocusedBorderColor = StitchBorder
                    )
                )
            }

            HorizontalDivider(color = StitchBorder)

            // Field 4: Live Filename Preview Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Filename Preview", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Automatically parse H1 tags to display as filename in the explorer.",
                        fontSize = 12.sp,
                        color = StitchTextMuted
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = liveParseEnabled,
                    onCheckedChange = { onToggleLiveParse() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StitchAccentCoral,
                        uncheckedThumbColor = StitchTextMuted,
                        uncheckedTrackColor = StitchBackground
                    )
                )
            }
        }

        // Section 2: Indexing & Search
        Text(
            text = "Indexing & Search",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        StitchCardContainer {
            // Excluded Folders Chips
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Excluded Folders", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Paths to ignore during global search and indexing.",
                    fontSize = 12.sp,
                    color = StitchTextMuted
                )
                Spacer(modifier = Modifier.height(12.dp))

                val patterns = ignored.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    patterns.forEach { pat ->
                        StitchChipTag(path = pat, onRemove = {
                            val newPat = patterns.filter { it != pat }.joinToString(",")
                            onIgnoredChange(newPat)
                        })
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.clickable {
                            onIgnoredChange(if (ignored.isEmpty()) ".git/" else "$ignored,.new_path/")
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("+ Add Path", color = StitchAccentCoral, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = StitchBorder)

            // Aggressive Auto-Save Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Aggressive Auto-Save", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Save changes immediately on every keystroke rather than on blur.",
                        fontSize = 12.sp,
                        color = StitchTextMuted
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = autoSaveEnabled,
                    onCheckedChange = { onToggleAutoSave() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StitchAccentCoral,
                        uncheckedThumbColor = StitchTextMuted,
                        uncheckedTrackColor = StitchBackground
                    )
                )
            }
        }
    }
}

@Composable
private fun StitchCardContainer(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(content = content)
    }
}

@Composable
private fun StitchChipTag(path: String, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(path, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun GitSyncTab(
    notesDir: String,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    branch: String,
    onBranchChange: (String) -> Unit,
    onSyncNow: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }

    fun testConnection() {
        if (remoteUrl.isBlank()) {
            connectionState = ConnectionState.Error("Remote URL is empty")
            return
        }
        connectionState = ConnectionState.Testing
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val successMsg = checkGitConnection(
                        repoPath = notesDir,
                        token = token,
                        remoteUrl = remoteUrl
                    )
                    ConnectionState.Success(successMsg)
                } catch (e: Exception) {
                    // UniFFI wraps Rust errors as "msg=<actual>"; strip that prefix.
                    val raw = e.message ?: e.javaClass.simpleName
                    val clean = raw.removePrefix("msg=")
                    ConnectionState.Error(clean)
                }
            }.let { connectionState = it }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Embedded Git Engine Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)

        StitchCardContainer {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = remoteUrl,
                    onValueChange = onRemoteUrlChange,
                    label = { Text("Repository Remote URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text("Personal Access Token (PAT)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = branch,
                    onValueChange = onBranchChange,
                    label = { Text("Git Branch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Button(
                    onClick = onSyncNow,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync Repository Now")
                }

                HorizontalDivider(color = StitchBorder)

                // Connection status card
                Text("Repository Connection", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                when (connectionState) {
                    is ConnectionState.Idle -> Text(
                        "Not tested yet. Press \"Test Connection\" below to verify your remote.",
                        fontSize = 12.sp,
                        color = StitchTextMuted
                    )
                    is ConnectionState.Testing -> Text(
                        "Testing connection to remote...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is ConnectionState.Success -> {
                        val msg = (connectionState as ConnectionState.Success).message
                        Text(msg, fontSize = 12.sp, color = Color(0xFF2DD4BF), modifier = Modifier.padding(top = 4.dp))
                    }
                    is ConnectionState.Error -> {
                        val err = (connectionState as ConnectionState.Error).message
                        Text("Error: $err", fontSize = 12.sp, color = Color(0xFFEF4444), modifier = Modifier.padding(top = 4.dp))
                    }
                }

                Button(
                    onClick = { testConnection() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(Icons.Default.CloudDone, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Connection")
                }
            }
        }
    }
}

private sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Testing : ConnectionState()
    data class Success(val message: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Composable
private fun AppearanceTab(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    isRtl: Boolean,
    onToggleRtl: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Appearance & Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

        StitchCardContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dark Theme Mode", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Warm charcoal dark visual palette", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { onToggleTheme() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.background
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Column(modifier = Modifier.padding(16.dp)) {
                Text("Editor Font Size: ${fontSizeSp}sp", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Slider(
                    value = fontSizeSp.toFloat(),
                    onValueChange = { onFontSizeChange(it.toInt()) },
                    valueRange = 12f..24f,
                    steps = 12,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("RTL Text Direction", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Support Right-To-Left language layouts", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isRtl,
                    onCheckedChange = { onToggleRtl() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        }
    }
}

@Composable
private fun ShortcutsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Commands & Keyboard Shortcuts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

        ShortcutReferenceRow(trigger = "/", description = "Open Slash Command popup (H1-H3, Lists, Checkboxes, Tables, Math, Mermaid)")
        ShortcutReferenceRow(trigger = "[[", description = "Insert & autocomplete Wikilinks to existing vault notes")
        ShortcutReferenceRow(trigger = "$$", description = "Insert LaTeX Block Math rendering")
        ShortcutReferenceRow(trigger = "```mermaid", description = "Insert Mermaid diagram block")
        ShortcutReferenceRow(trigger = "Command Palette Tab", description = "Open Quick Action Sheet for fast command execution")
        ShortcutReferenceRow(trigger = "Swipe Right Edge", description = "Open VS Code style navigation sidebar drawer")
    }
}

@Composable
private fun ShortcutReferenceRow(trigger: String, description: String) {
    StitchCardContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = trigger,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
