use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, RwLock};
use std::time::UNIX_EPOCH;

/// Name of the native trash directory inside a vault.
pub const TRASH_DIR: &str = ".trash";

/// Directory or file entry returned by folder listing APIs.
#[derive(uniffi::Record, Clone, Debug)]
pub struct FolderItem {
    pub name: String,
    pub relative_path: String,
    pub is_dir: bool,
}

/// Per-note metadata cached in memory so UI scrolling never hits the disk.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct CachedNoteHeader {
    pub title: String,
    pub relative_path: String,
    pub last_modified_unix: i64,
}

/// In-memory index of note metadata for one vault root.
#[derive(Debug, Default)]
struct VaultIndex {
    root_path: String,
    notes: Vec<CachedNoteHeader>,
}

/// Lazily-built per-root vault indexes, guarded for concurrent access.
static VAULT_INDEXES: LazyLock<RwLock<HashMap<String, VaultIndex>>> =
    LazyLock::new(|| RwLock::new(HashMap::new()));

/// Skip any path segment that should be treated as hidden, including `.trash`.
fn is_ignored_segment(segment: &str) -> bool {
    segment == TRASH_DIR || segment.starts_with('.')
}

/// Recursively walk `dir` and collect markdown note metadata relative to `root`.
fn scan_vault(root: &Path, dir: &Path, notes: &mut Vec<CachedNoteHeader>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let entry_path = entry.path();
        let Some(name) = entry_path.file_name().map(|n| n.to_string_lossy().to_string()) else {
            continue;
        };
        if is_ignored_segment(&name) {
            continue;
        }
        // Use a single stat call via DirEntry::metadata(). DirEntry::file_type()
        // relies on readdir's d_type, which is unreliable on some Android
        // SAF/FUSE filesystems (returns unknown for directories), causing
        // subfolders to be skipped entirely.
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if metadata.is_dir() {
            scan_vault(root, &entry_path, notes);
        } else if metadata.is_file() {
            let ext = entry_path.extension().and_then(|s| s.to_str()).unwrap_or("").to_lowercase();
            if ext != "md" && ext != "markdown" {
                continue;
            }
            let rel_path = entry_path
                .strip_prefix(root)
                .map(|p| p.to_string_lossy().to_string())
                .unwrap_or_else(|_| name.clone());
            let title = entry_path.file_stem().unwrap_or_default().to_string_lossy().to_string();
            let mtime = metadata
                .modified()
                .map(|t| t.duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0))
                .unwrap_or(0);
            notes.push(CachedNoteHeader {
                title,
                relative_path: rel_path,
                last_modified_unix: mtime,
            });
        }
    }
}

fn build_index(root_path: &str) -> VaultIndex {
    let root = Path::new(root_path);
    let mut notes = Vec::new();
    if root.is_dir() {
        scan_vault(root, root, &mut notes);
        notes.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()));
    }
    VaultIndex {
        root_path: root_path.to_string(),
        notes,
    }
}

/// Load (or refresh) the in-memory index for a vault and return the cached headers.
///
/// All reads of `last_modified_unix` during scrolling should come from this cache
/// instead of touching the file system per item.
pub fn load_vault_index(root_path: String) -> Vec<CachedNoteHeader> {
    let index = build_index(&root_path);
    let headers = index.notes.clone();
    if let Ok(mut guard) = VAULT_INDEXES.write() {
        insert_with_eviction(&mut guard, root_path, index);
    }
    headers
}

/// Keep at most `MAX_VAULT_INDEXES` vaults cached to bound native memory.
const MAX_VAULT_INDEXES: usize = 4;

fn insert_with_eviction(cache: &mut HashMap<String, VaultIndex>, root_path: String, index: VaultIndex) {
    if cache.len() >= MAX_VAULT_INDEXES && !cache.contains_key(&root_path) {
        // Evict the oldest entry. HashMap has no guaranteed order, so simply
        // remove an arbitrary non-matching key; vault switching is rare enough
        // that this is sufficient to prevent unbounded growth.
        if let Some(old) = cache.keys().next().map(|k| k.clone()) {
            cache.remove(&old);
        }
    }
    cache.insert(root_path, index);
}

/// Return the cached note headers for a vault, indexing it on first access.
pub fn get_cached_note_headers(root_path: String) -> Vec<CachedNoteHeader> {
    let guard = VAULT_INDEXES.read().unwrap_or_else(|p| p.into_inner());
    if let Some(index) = guard.get(&root_path) {
        if index.root_path == root_path {
            return index.notes.clone();
        }
    }
    drop(guard);
    load_vault_index(root_path)
}

/// Invalidate the cached index for a vault (called after any mutation).
pub(crate) fn invalidate_vault_index(root_path: &str) {
    if let Ok(mut guard) = VAULT_INDEXES.write() {
        guard.remove(root_path);
    }
}

/// Best-effort helper for simple core operations that return booleans.
pub(crate) fn invalidate_on_mutation(root_path: &str, result: bool) -> bool {
    if result {
        invalidate_vault_index(root_path);
    }
    result
}

// ---------------------------------------------------------------------------
// Native Trash System (.trash/)
// ---------------------------------------------------------------------------

fn trash_dir(root_path: &str) -> PathBuf {
    Path::new(root_path).join(TRASH_DIR)
}

/// Preserve an item's folder structure inside the trash so it can be restored
/// to its original location. Returns the relative path of the trashed item
/// (including any generated suffix) and the original relative path.
fn stage_trash_entry(root_path: &str, relative_path: &str) -> io::Result<(String, String)> {
    let root = Path::new(root_path);
    let src = root.join(relative_path.trim_matches('/'));
    if !src.exists() {
        return Err(io::Error::new(io::ErrorKind::NotFound, "Source item does not exist"));
    }

    let rel = relative_path.trim_matches('/').to_string();
    let parent_rel = Path::new(&rel).parent().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();

    let trash_root = trash_dir(root_path);
    let target_parent = trash_root.join(&parent_rel);
    fs::create_dir_all(&target_parent)?;

    let file_name = src.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let mut target = target_parent.join(&file_name);
    let mut counter = 1;
    while target.exists() {
        target = target_parent.join(format!("{}-{}{}", file_name, counter, if src.is_dir() { "" } else { "" }));
        counter += 1;
    }

    fs::rename(&src, &target)?;
    let trash_rel = target
        .strip_prefix(&trash_root)
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_else(|_| file_name);
    Ok((trash_rel, rel))
}

/// Move an item into the native `.trash/` directory.
pub fn move_to_trash(root_path: String, relative_path: String) -> Result<(), String> {
    let result = stage_trash_entry(&root_path, &relative_path)
        .map(|_| ())
        .map_err(|e| e.to_string());
    invalidate_vault_index(&root_path);
    result
}

/// List the contents of the native `.trash/` directory.
pub fn list_trash_contents(root_path: String) -> Vec<FolderItem> {
    let trash_root = trash_dir(&root_path);
    if !trash_root.is_dir() {
        return Vec::new();
    }
    list_folder_contents_inner(&trash_root, "", true)
}

/// Restore an item from `.trash/` back to its original location.
pub fn restore_from_trash(root_path: String, relative_path: String) -> Result<(), String> {
    let trash_root = trash_dir(&root_path);
    let src = trash_root.join(relative_path.trim_matches('/'));
    if !src.exists() {
        return Err(format!("Trashed item not found: {}", relative_path));
    }

    let original_rel = relative_path.trim_matches('/');
    let dest = Path::new(&root_path).join(original_rel);
    if dest.exists() {
        return Err(format!("A file or folder already exists at '{}'", original_rel));
    }
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }

    fs::rename(&src, &dest).map_err(|e| e.to_string())?;
    invalidate_vault_index(&root_path);
    Ok(())
}

/// Permanently delete everything inside the native `.trash/` directory.
pub fn empty_trash(root_path: String) -> Result<(), String> {
    let trash_root = trash_dir(&root_path);
    if !trash_root.exists() {
        return Ok(());
    }
    fs::remove_dir_all(&trash_root).map_err(|e| e.to_string())?;
    fs::create_dir_all(&trash_root).map_err(|e| e.to_string())?;
    invalidate_vault_index(&root_path);
    Ok(())
}

// ---------------------------------------------------------------------------
// File / Folder Movement
// ---------------------------------------------------------------------------

/// Move an item (file or folder) from `source_relative_path` into the folder
/// `dest_relative_dir` (empty string means the vault root).
pub fn move_item(root_path: String, source_relative_path: String, dest_relative_dir: String) -> Result<(), String> {
    let root = Path::new(&root_path);
    let src = root.join(source_relative_path.trim_matches('/'));
    if !src.exists() {
        return Err(format!("Source item does not exist: {}", source_relative_path));
    }

    let dest_dir = if dest_relative_dir.trim().is_empty() {
        root.to_path_buf()
    } else {
        root.join(dest_relative_dir.trim_matches('/'))
    };
    if !dest_dir.is_dir() {
        return Err(format!("Destination folder does not exist: {}", dest_relative_dir));
    }

    // Refuse to move a folder into itself or one of its own descendants.
    if src.is_dir() {
        let src_canon = src.canonicalize().unwrap_or_else(|_| src.clone());
        let dest_canon = dest_dir.canonicalize().unwrap_or_else(|_| dest_dir.clone());
        if dest_canon.starts_with(&src_canon) {
            return Err("Cannot move a folder into itself".to_string());
        }
    }

    let file_name = src.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    if file_name.is_empty() {
        return Err("Cannot move an item without a name".to_string());
    }

    let mut dest = dest_dir.join(&file_name);
    if src.is_dir() && dest.exists() {
        return Err(format!("A folder named '{}' already exists in the destination", file_name));
    }
    let mut counter = 1;
    while dest.exists() {
        let stem = Path::new(&file_name)
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| file_name.clone());
        let ext = Path::new(&file_name)
            .extension()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default();
        dest = if ext.is_empty() {
            dest_dir.join(format!("{}-{}", stem, counter))
        } else {
            dest_dir.join(format!("{}-{}.{}", stem, counter, ext))
        };
        counter += 1;
    }

    fs::rename(&src, &dest).map_err(|e| e.to_string())?;
    invalidate_vault_index(&root_path);
    Ok(())
}

// ---------------------------------------------------------------------------
// Shared folder listing (used by both the vault browser and the trash view)
// ---------------------------------------------------------------------------

/// Read a directory relative to `base`, filtering hidden entries and the `.trash/`
/// directory, and return the items with `relative_path` computed against `base`.
pub(crate) fn list_folder_contents_inner(base: &Path, relative_dir: &str, show_all_files: bool) -> Vec<FolderItem> {
    let target_dir = if relative_dir.trim().is_empty() {
        base.to_path_buf()
    } else {
        base.join(relative_dir.trim_matches('/'))
    };

    if !target_dir.is_dir() {
        return Vec::new();
    }

    let mut items = Vec::new();
    if let Ok(entries) = fs::read_dir(&target_dir) {
        for entry in entries.flatten() {
            let entry_path = entry.path();
            let Some(name) = entry_path.file_name().map(|n| n.to_string_lossy().to_string()) else {
                continue;
            };
            if is_ignored_segment(&name) {
                continue;
            }

            let rel_path = if relative_dir.trim().is_empty() {
                name.clone()
            } else {
                format!("{}/{}", relative_dir.trim_matches('/'), name)
            };

            let Ok(metadata) = entry.metadata() else {
                continue;
            };
            let is_dir = metadata.is_dir();
            let ext = entry_path.extension().and_then(|s| s.to_str()).unwrap_or("").to_lowercase();
            let is_md = ext == "md" || ext == "markdown";

            if is_dir || show_all_files || is_md {
                items.push(FolderItem {
                    name,
                    relative_path: rel_path,
                    is_dir,
                });
            }
        }
    }

    items.sort_by(|a, b| {
        if a.is_dir != b.is_dir {
            b.is_dir.cmp(&a.is_dir)
        } else {
            a.name.to_lowercase().cmp(&b.name.to_lowercase())
        }
    });

    items
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_vault() -> tempfile::TempDir {
        let dir = tempfile::tempdir().expect("tempdir");
        let root = dir.path();
        fs::create_dir_all(root.join("sub")).unwrap();
        fs::write(root.join("a.md"), "# A\n").unwrap();
        fs::write(root.join("sub/b.md"), "# B\n").unwrap();
        fs::create_dir_all(root.join(TRASH_DIR)).unwrap();
        fs::write(root.join(TRASH_DIR).join("old.md"), "# Old\n").unwrap();
        dir
    }

    fn headers_in(vault: &std::path::Path) -> Vec<CachedNoteHeader> {
        get_cached_note_headers(vault.to_string_lossy().to_string())
    }

    #[test]
    fn cache_indexes_only_notes_and_skips_trash_and_hidden() {
        let dir = make_vault();
        let root = dir.path();
        let headers = headers_in(root);
        assert_eq!(headers.len(), 2);
        assert!(headers.iter().all(|h| !h.relative_path.starts_with(TRASH_DIR)));
        let a = headers.iter().find(|h| h.relative_path == "a.md").unwrap();
        assert!(a.last_modified_unix > 0);
        let b = headers.iter().find(|h| h.relative_path == "sub/b.md").unwrap();
        assert!(b.last_modified_unix > 0);
    }

    #[test]
    fn folder_listing_filters_trash() {
        let dir = make_vault();
        let root = dir.path();
        let items = list_folder_contents_inner(root, "", true);
        let names: Vec<&str> = items.iter().map(|i| i.name.as_str()).collect();
        assert!(names.contains(&"a.md"));
        assert!(names.contains(&"sub"));
        assert!(!names.contains(&TRASH_DIR));
    }

    #[test]
    fn move_to_trash_and_restore_roundtrip() {
        let dir = make_vault();
        let root = dir.path().to_string_lossy().to_string();

        // The fixture pre-seeds .trash/old.md, so account for it.
        let trash_before = list_trash_contents(root.clone());
        let before_count = trash_before.len();

        move_to_trash(root.clone(), "a.md".to_string()).unwrap();
        assert!(!dir.path().join("a.md").exists());
        let trash = list_trash_contents(root.clone());
        assert_eq!(trash.len(), before_count + 1);
        assert!(trash.iter().any(|t| t.name == "a.md"));
        // Index must be invalidated after the move.
        assert!(headers_in(dir.path()).iter().all(|h| h.relative_path != "a.md"));

        let trashed = trash.iter().find(|t| t.name == "a.md").unwrap();
        restore_from_trash(root.clone(), trashed.relative_path.clone()).unwrap();
        assert!(dir.path().join("a.md").exists());
        assert!(list_trash_contents(root.clone()).iter().all(|t| t.name != "a.md"));
    }

    #[test]
    fn move_to_trash_preserves_subfolder_path() {
        let dir = make_vault();
        let root = dir.path().to_string_lossy().to_string();

        move_to_trash(root.clone(), "sub/b.md".to_string()).unwrap();
        // Top level of trash shows the preserved "sub" folder.
        let trash = list_trash_contents(root.clone());
        assert!(trash.iter().any(|t| t.relative_path == "sub"));

        // Browsing into the trashed folder reveals the file with its original path.
        let sub = trash.iter().find(|t| t.relative_path == "sub").unwrap();
        assert!(sub.is_dir);
        let nested = list_folder_contents_inner(dir.path().join(TRASH_DIR).as_path(), &sub.relative_path, true);
        assert!(nested.iter().any(|t| t.relative_path == "sub/b.md"));

        let trashed = nested.iter().find(|t| t.relative_path == "sub/b.md").unwrap();
        restore_from_trash(root.clone(), trashed.relative_path.clone()).unwrap();
        assert!(dir.path().join("sub/b.md").exists());
    }

    #[test]
    fn empty_trash_removes_everything() {
        let dir = make_vault();
        let root = dir.path().to_string_lossy().to_string();

        empty_trash(root.clone()).unwrap();
        assert!(list_trash_contents(root.clone()).is_empty());
    }

    #[test]
    fn move_item_between_folders() {
        let dir = make_vault();
        let root = dir.path().to_string_lossy().to_string();

        move_item(root.clone(), "a.md".to_string(), "sub".to_string()).unwrap();
        assert!(dir.path().join("sub/a.md").exists());
        assert!(!dir.path().join("a.md").exists());

        move_item(root.clone(), "sub/a.md".to_string(), "".to_string()).unwrap();
        assert!(dir.path().join("a.md").exists());
    }

    #[test]
    fn cannot_move_folder_into_itself() {
        let dir = make_vault();
        let root = dir.path().to_string_lossy().to_string();

        let err = move_item(root.clone(), "sub".to_string(), "sub".to_string()).unwrap_err();
        assert!(err.contains("into itself"));
    }

    #[test]
    fn move_item_creates_unique_name_on_collision() {
        let dir = make_vault();
        let root = dir.path().to_string_lossy().to_string();
        fs::write(dir.path().join("sub/a.md"), "# existing\n").unwrap();

        move_item(root.clone(), "a.md".to_string(), "sub".to_string()).unwrap();
        assert!(dir.path().join("sub/a-1.md").exists());
        assert!(dir.path().join("sub/a.md").exists());
    }
}
