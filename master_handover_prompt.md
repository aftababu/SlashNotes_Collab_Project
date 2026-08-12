### MASTER HANDOVER PROMPT FOR MOBILE AI AGENT

```markdown
# 📱 SLASHNOTE MOBILE (ANDROID) - MASTER TECHNICAL HANDOVER & ARCHITECTURE SPECIFICATION

You are the dedicated Mobile AI Agent responsible exclusively for the **SlashNote Android Client** (`android/` and UniFFI Rust core `crates/SlashNotes_Core`).

---

## 1. 🌟 PROJECT OVERVIEW & APPS SELLING POINTS

SlashNote is a fast, privacy-first, offline-first Markdown note-taking app built for maximum performance and ultra-low memory consumption across Desktop and Mobile platforms.

### Core Selling Points of SlashNote:
1. **Ultra-Low Memory Footprint (~40 MB RAM)**: Pure Jetpack Compose + Native Rust UniFFI engine. Zero WebViews, zero Electron bloat, zero heavy web runtimes on mobile.
2. **Instant Local Vault Performance**: Native POSIX file system access to local Markdown vaults on device storage.
3. **100% Offline-First & Privacy-First**: All notes, search indices, and configuration reside locally on the device without telemetry or forced cloud servers.
4. **Embedded Native Git Sync (`libgit2`)**: Built-in Git version control and remote sync without paid subscription fees.
5. **Seamless Desktop Parity**: Full syntax alignment with SlashNote Desktop (Tauri v2 + Rust) including Wikilinks `[[note]]`, Slash `/` command menu, LaTeX block math `$$...$$`, and Mermaid diagram rendering.

---

## 2. 🏛️ CURRENT SYSTEM ARCHITECTURE & UI DESIGN SYSTEM

### Technology Stack:
- **UI Framework**: Kotlin + Jetpack Compose (Material3 + Custom Stitch Design Tokens).
- **Native Engine**: Rust UniFFI `slash_notes_core` compiled to shared C-FFI / JNI bindings (`slash_notes_core.kt`).
- **Storage Access & SAF**: `StorageUtils.kt` resolves Android Storage Access Framework URIs (`content://...`) to POSIX paths (`/storage/emulated/0/...`) with automatic `MANAGE_EXTERNAL_STORAGE` runtime permission requests on Android 11+ (API 30+).
- **Settings Persistence**: Instant auto-save to `SharedPreferences` / `DataStore` (`AppSettings.kt` and `SecureStorage.kt`).

### Current Stitch UI/UX Design System:
- **Color Palette**: Dark mode background (`#141010`), surface card container (`#211E1D`), border (`#2A2423`), accent coral (`#D3737C`), selected card bg (`#2D2322`), muted text (`#A89F9D`).
- **Note Cards (`NoteCardItem`)**:
  - Left edge accent pill strip (`3.dp` width, rounded coral).
  - Dark icon container box (`#2D2827`, `36.dp` size) housing an outlined description vector icon.
  - Monospace title typography (`FontFamily.Monospace`, `13.sp`, `#FAFAF9`).
  - Subtitle metadata row (`11.sp`, `#D3737C` coral): `filename.md  •  Aug 27, 2026`.
- **Note Editor Top AppBar (`NoteEditorScreen.kt`)**:
  - `Column(modifier = Modifier.fillMaxWidth())` title block preventing right action icon overflow.
  - Parent directory breadcrumb on top line (11sp muted, e.g., `student related/`).
  - Bold note title on bottom line (15sp, white) with `TextOverflow.Ellipsis`.
- **Settings Screen (`SettingsScreen.kt`)**:
  - Dedicated tab view with no hamburger menu icon.
  - Instant auto-save on switch/field changes without manual "Save" button.

---

## 3. 🔍 ROOT-CAUSE TECHNICAL AUDIT: RECOMPOSITION & DRAWER LAG

### Root Cause Analysis of Remaining Frame Drops:
- **Issue**: Spawning `produceState(Dispatchers.IO)` inside individual `NoteCardItem` items in a `LazyColumn` executes asynchronous disk reads (`File.lastModified()`, `File.exists()`) for *every card rendered*.
- **Impact**: During drawer toggles or fast scrolling, Compose recompositions fire dozens of background thread IO requests, saturating the JNI bridge and context-switching on the main thread, leading to frame drops.

---

## 4. 🛠️ REQUIRED FEATURE SPECIFICATIONS FOR NEW MOBILE AGENT

As the new Mobile AI Agent, you are strictly tasked with implementing the following features and performance refactors in `crates/SlashNotes_Core` and `android/`:

### Task 1: In-Memory Vault Indexing (Rust Core Optimization)
- **Objective**: Eliminate disk I/O during UI scrolling and recompositions.
- **Rust Core (`crates/SlashNotes_Core/src/lib.rs`)**:
  - Create a thread-safe `RwLock<VaultIndex>` in Rust caching file metadata (`id`, `title`, `relative_path`, `last_modified_unix`) upon vault load.
  - Add UniFFI export `pub fn get_cached_note_headers() -> Vec<NoteHeader>` where `NoteHeader` includes `last_modified_unix: i64`.
- **Kotlin UI**:
  - Update `NoteCardItem` to accept `lastModifiedUnix: Long` directly from `NoteHeader`.
  - Remove all `produceState` / `File` calls inside `NoteCardItem`, rendering dates synchronously from the cached timestamp!

### Task 2: Native Trash System (`.trash/`)
- **Rust UniFFI Engine**:
  - Implement `pub fn move_to_trash(root_path: String, relative_path: String) -> Result<(), String>`.
  - Implement `pub fn list_trash_contents(root_path: String) -> Vec<FolderItem>`.
  - Implement `pub fn restore_from_trash(root_path: String, relative_path: String) -> Result<(), String>`.
  - Implement `pub fn empty_trash(root_path: String) -> Result<(), String>`.
  - Automatically filter out `.trash/` directory from `list_folder_contents` and `list_notes`.
- **Kotlin UI (`FolderTreeView.kt`)**:
  - Add a dedicated "Trash Bin" item/sheet allowing users to view, restore, or permanently delete trashed notes.

### Task 3: File/Folder Drag & Drop Movement
- **Rust UniFFI Engine**:
  - Implement `pub fn move_item(root_path: String, source_relative_path: String, dest_relative_dir: String) -> Result<(), String>`.
- **Kotlin UI (`FolderTreeView.kt`)**:
  - Implement `detectDragGesturesAfterLongPress` on tree item rows.
  - Provide visual drop target highlighting (`StitchAccentCoral` border) when hovering over target folders.
  - Wire drag release to execute `move_item` on `Dispatchers.IO` and refresh the tree view.

### Task 4: Compose Slot Reuse & Recomposition Polish
- **`LazyColumn` Optimizations**:
  - Add `contentType = { if (it.isDir) "folder" else "file" }` to `LazyColumn` item definitions in `FolderTreeView.kt` and `MainActivity.kt`.
  - Ensure all UniFFI calls continue to run strictly inside `withContext(Dispatchers.IO)`.

---

## 5. 🎯 AGENT RESPONSIBILITY BOUNDARY

- **Your Scope**: Mobile Android app (`android/`) and mobile UniFFI core (`crates/SlashNotes_Core/`).
- **Rule of Engagement**: Maintain zero-WebView architecture, preserve < 45 MB RAM usage, ensure 60fps Compose scrolling, and keep feature parity with SlashNote Desktop.
```
