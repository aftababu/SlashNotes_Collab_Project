use std::fs;
use std::panic::catch_unwind;
use std::path::Path;
use pulldown_cmark::{Event, HeadingLevel, OffsetIter, Options, Parser, Tag};

pub mod git;
pub mod vault;

use crate::vault::FolderItem;

// Setup UniFFI scaffolding
uniffi::setup_scaffolding!();

// Define UniFFI error type
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{msg}")]
    SyncError { msg: String },

    #[error("{msg}")]
    VaultError { msg: String },
}

#[derive(uniffi::Record)]
pub struct NoteHeader {
    pub id: String,
    pub title: String,
}

/// Compact numeric kind codes for markdown spans.
///
/// Using `u8` instead of `String` removes one heap allocation per span on both
/// the Rust and Kotlin sides and shrinks the UniFFI buffer substantially.
pub mod span_kind {
    pub const H1: u8 = 1;
    pub const H2: u8 = 2;
    pub const H3: u8 = 3;
    pub const H4: u8 = 4;
    pub const H5: u8 = 5;
    pub const H6: u8 = 6;
    pub const ITALIC: u8 = 10;
    pub const BOLD: u8 = 11;
    pub const STRIKE: u8 = 12;
    pub const INLINE_CODE: u8 = 20;
    pub const CODE_BLOCK: u8 = 21;
    pub const LINK: u8 = 30;
    pub const WIKILINK: u8 = 31;
    pub const INLINE_MATH: u8 = 40;
    pub const BLOCK_MATH: u8 = 41;
    pub const MERMAID: u8 = 50;
    pub const BLOCKQUOTE: u8 = 60;
    pub const TASK_CHECKED: u8 = 70;
    pub const TASK_UNCHECKED: u8 = 71;
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct MarkdownSpan {
    pub start: u32,
    pub end: u32,
    pub kind: u8,
}

#[uniffi::export]
pub fn get_core_version() -> String {
    "1.0.0".to_string()
}

/// Load (or refresh) the in-memory vault index and return cached note metadata.
#[uniffi::export]
pub fn load_vault_index(root_path: String) -> Vec<vault::CachedNoteHeader> {
    vault::load_vault_index(root_path)
}

/// Return note metadata from the in-memory index (indexing on first access).
#[uniffi::export]
pub fn get_cached_note_headers(root_path: String) -> Vec<vault::CachedNoteHeader> {
    vault::get_cached_note_headers(root_path)
}

#[uniffi::export]
pub fn sync_git_repository(
    repo_path: String,
    token: String,
    remote_url: String,
    branch: String,
) -> Result<String, CoreError> {
    git::perform_sync(&repo_path, &token, &remote_url, &branch)
        .map_err(|e| CoreError::SyncError { msg: e })
}

/// Initialize a git repository at the given POSIX directory path.
///
/// This is the explicit init step that must be called with a physical path
/// (never a `content://` URI) before syncing. It is idempotent: if a valid
/// repository already exists, it succeeds without reinitializing.
#[uniffi::export]
pub fn init_git_repository(repo_path: String) -> Result<(), CoreError> {
    git::git_init_native(Path::new(&repo_path)).map_err(|e| CoreError::SyncError { msg: e })
}

/// Verify connectivity to the configured git remote without mutating anything.
///
/// This performs a lightweight `git ls-remote` (or, if unavailable, a
/// credentials + host reachability probe) and reports whether the repo and
/// remote are valid. Intended for the "Test Connection" button in the
/// Git & Sync settings tab.
#[uniffi::export]
pub fn check_git_connection(repo_path: String, token: String, remote_url: String) -> Result<String, CoreError> {
    git::check_connection(&repo_path, &token, &remote_url)
        .map_err(|e| CoreError::SyncError { msg: e })
}

/// Expands note name template tags using local time
#[uniffi::export]
pub fn preview_note_name(template: String) -> String {
    if template.trim().is_empty() {
        return "Untitled".to_string();
    }

    use std::time::{SystemTime, UNIX_EPOCH};
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();

    let days = now / 86400;
    let secs_of_day = now % 86400;
    let hours = (secs_of_day / 3600) % 24;
    let mins = (secs_of_day / 60) % 60;
    let secs = secs_of_day % 60;

    let year = 1970 + (days / 365);
    let month = 1 + ((days % 365) / 30).min(11);
    let day = 1 + ((days % 365) % 30);

    let months = [
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    ];
    let weekdays = ["Thursday", "Friday", "Saturday", "Sunday", "Monday", "Tuesday", "Wednesday"];
    let weekday_name = weekdays[(days % 7) as usize];
    let month_name = months[(month - 1) as usize];

    // Single-pass template expansion (8 allocations → 1)
    use std::fmt::Write;
    let mut result = String::with_capacity(template.len() + 32);
    let mut pos = 0;
    while let Some(start) = template[pos..].find('{') {
        let abs_start = pos + start;
        result.push_str(&template[pos..abs_start]);
        if let Some(end) = template[abs_start..].find('}') {
            let token = &template[abs_start + 1..abs_start + end];
            match token {
                "timestamp" => { let _ = write!(result, "{}", now); }
                "date" => { let _ = write!(result, "{:04}-{:02}-{:02}", year, month, day); }
                "year" => { let _ = write!(result, "{:04}", year); }
                "month" => { let _ = write!(result, "{:02}", month); }
                "day" => { let _ = write!(result, "{:02}", day); }
                "time" => { let _ = write!(result, "{:02}-{:02}-{:02}", hours, mins, secs); }
                "monthName" => result.push_str(month_name),
                "weekday" => result.push_str(weekday_name),
                _ => {}
            }
            pos = abs_start + end + 1;
        } else {
            result.push_str(&template[pos..]);
            return result;
        }
    }
    result.push_str(&template[pos..]);
    result
}

#[uniffi::export]
pub fn parse_markdown_tokens(content: String) -> Vec<MarkdownSpan> {
    // Conservative initial capacity: most markdown has relatively few spans
    // relative to its length, so over-allocating by len/16 wastes memory.
    let mut spans = Vec::with_capacity(content.len() / 64);
    if content.is_empty() {
        return spans;
    }

    let mut options = Options::empty();
    options.insert(Options::ENABLE_STRIKETHROUGH);
    options.insert(Options::ENABLE_TASKLISTS);
    options.insert(Options::ENABLE_TABLES);

    let parser = Parser::new_ext(&content, options);
    let iter: OffsetIter = parser.into_offset_iter();

    for (event, range) in iter {
        let kind = match event {
            Event::Start(Tag::Heading { level, .. }) => match level {
                HeadingLevel::H1 => span_kind::H1,
                HeadingLevel::H2 => span_kind::H2,
                HeadingLevel::H3 => span_kind::H3,
                HeadingLevel::H4 => span_kind::H4,
                HeadingLevel::H5 => span_kind::H5,
                HeadingLevel::H6 => span_kind::H6,
            },
            Event::Start(Tag::Emphasis) => span_kind::ITALIC,
            Event::Start(Tag::Strong) => span_kind::BOLD,
            Event::Start(Tag::Strikethrough) => span_kind::STRIKE,
            Event::Start(Tag::CodeBlock(ref info)) => {
                match info {
                    pulldown_cmark::CodeBlockKind::Fenced(lang) => {
                        if lang.eq_ignore_ascii_case("mermaid") {
                            span_kind::MERMAID
                        } else {
                            span_kind::CODE_BLOCK
                        }
                    }
                    pulldown_cmark::CodeBlockKind::Indented => span_kind::CODE_BLOCK,
                }
            }

            Event::Start(Tag::BlockQuote(_)) => span_kind::BLOCKQUOTE,
            Event::Start(Tag::Link { .. }) => span_kind::LINK,
            Event::Code(_) => span_kind::INLINE_CODE,
            Event::TaskListMarker(checked) => {
                if checked {
                    span_kind::TASK_CHECKED
                } else {
                    span_kind::TASK_UNCHECKED
                }
            }
            _ => continue,
        };

        spans.push(MarkdownSpan {
            start: range.start as u32,
            end: range.end as u32,
            kind,
        });
    }

    // Manual scan for [[Wikilinks]] and $$Block Math$$
    let bytes = content.as_bytes();
    let len = bytes.len();
    let mut i = 0;

    while i < len {
        // [[Wikilink]]
        if i + 3 < len && bytes[i] == b'[' && bytes[i + 1] == b'[' {
            let start = i;
            let mut j = i + 2;
            let mut found_end = false;
            while j + 1 < len {
                if bytes[j] == b']' && bytes[j + 1] == b']' {
                    found_end = true;
                    break;
                }
                if bytes[j] == b'\n' {
                    break;
                }
                j += 1;
            }
            if found_end {
                let end = j + 2;
                spans.push(MarkdownSpan {
                    start: start as u32,
                    end: end as u32,
                    kind: span_kind::WIKILINK,
                });
                i = end;
                continue;
            }
        }

        // $$ Block Math $$
        if i + 3 < len && bytes[i] == b'$' && bytes[i + 1] == b'$' {
            let start = i;
            let mut j = i + 2;
            let mut found_end = false;
            while j + 1 < len {
                if bytes[j] == b'$' && bytes[j + 1] == b'$' {
                    found_end = true;
                    break;
                }
                j += 1;
            }
            if found_end {
                let end = j + 2;
                spans.push(MarkdownSpan {
                    start: start as u32,
                    end: end as u32,
                    kind: span_kind::BLOCK_MATH,
                });
                i = end;
                continue;
            }
        }

        // $ Inline Math $
        if bytes[i] == b'$' {
            let start = i;
            let mut j = i + 1;
            let mut found_end = false;
            while j < len && bytes[j] != b'\n' {
                if bytes[j] == b'$' {
                    found_end = true;
                    break;
                }
                j += 1;
            }
            if found_end && j > start + 1 {
                let end = j + 1;
                spans.push(MarkdownSpan {
                    start: start as u32,
                    end: end as u32,
                    kind: span_kind::INLINE_MATH,
                });
                i = end;
                continue;
            }
        }

        i += 1;
    }

    spans
}

#[uniffi::export]
pub fn list_notes(dir_path: String) -> Vec<NoteHeader> {
    // Force a fresh scan: this is called on vault load and explicit refresh,
    // where the in-memory cache may be stale (e.g. external file changes).
    vault::load_vault_index(dir_path)
        .into_iter()
        .map(|h| NoteHeader {
            id: h.relative_path.clone(),
            title: h.title,
        })
        .collect()
}

#[uniffi::export]
pub fn search_notes(dir_path: String, query: String) -> Vec<NoteHeader> {
    let headers = vault::get_cached_note_headers(dir_path);
    if query.trim().is_empty() {
        return headers.into_iter()
            .map(|h| NoteHeader { id: h.relative_path, title: h.title })
            .collect();
    }
    let clean_query = query.to_lowercase();
    headers
        .into_iter()
        .filter(|h| h.title.to_lowercase().contains(&clean_query) || h.relative_path.to_lowercase().contains(&clean_query))
        .map(|h| NoteHeader { id: h.relative_path, title: h.title })
        .collect()
}

#[uniffi::export]
pub fn list_folder_contents(root_path: String, relative_dir: String, show_all_files: bool) -> Vec<FolderItem> {
    vault::list_folder_contents_inner(Path::new(&root_path), &relative_dir, show_all_files)
}

#[uniffi::export]
pub fn create_folder(root_path: String, relative_dir: String, folder_name: String) -> bool {
    let base_path = Path::new(&root_path);
    let clean_folder = folder_name.trim();
    if clean_folder.is_empty() {
        return false;
    }

    let target_dir = if relative_dir.trim().is_empty() {
        base_path.join(clean_folder)
    } else {
        base_path.join(relative_dir.trim_matches('/')).join(clean_folder)
    };

    let result = fs::create_dir_all(target_dir).is_ok();
    vault::invalidate_on_mutation(&root_path, result)
}

#[uniffi::export]
pub fn rename_item(root_path: String, old_relative_path: String, new_name: String) -> bool {
    let base_path = Path::new(&root_path);
    let old_path = base_path.join(old_relative_path.trim_matches('/'));

    if !old_path.exists() {
        return false;
    }

    let parent = old_path.parent().unwrap_or(base_path);
    let clean_new = new_name.trim();

    let new_filename = if old_path.is_file() && !clean_new.ends_with(".md") {
        format!("{}.md", clean_new)
    } else {
        clean_new.to_string()
    };

    let new_path = parent.join(new_filename);
    if old_path == new_path {
        return true;
    }

    let result = if fs::rename(&old_path, &new_path).is_ok() {
        true
    } else {
        if old_path.is_file() {
            if fs::copy(&old_path, &new_path).is_ok() {
                let _ = fs::remove_file(&old_path);
                true
            } else {
                false
            }
        } else {
            false
        }
    };
    vault::invalidate_on_mutation(&root_path, result)
}

#[uniffi::export]
pub fn delete_item(root_path: String, relative_path: String) -> bool {
    let base_path = Path::new(&root_path);
    let target_path = base_path.join(relative_path.trim_matches('/'));

    if !target_path.exists() {
        return false;
    }

    let result = if target_path.is_dir() {
        fs::remove_dir_all(target_path).is_ok()
    } else {
        fs::remove_file(target_path).is_ok()
    };
    vault::invalidate_on_mutation(&root_path, result)
}

/// Move an item into the native `.trash/` directory.
#[uniffi::export]
pub fn move_to_trash(root_path: String, relative_path: String) -> Result<(), CoreError> {
    vault::move_to_trash(root_path, relative_path).map_err(|e| CoreError::VaultError { msg: e })
}

/// List the contents of the native `.trash/` directory.
#[uniffi::export]
pub fn list_trash_contents(root_path: String) -> Vec<FolderItem> {
    vault::list_trash_contents(root_path)
}

/// Restore an item from `.trash/` back to its original location.
#[uniffi::export]
pub fn restore_from_trash(root_path: String, relative_path: String) -> Result<(), CoreError> {
    vault::restore_from_trash(root_path, relative_path).map_err(|e| CoreError::VaultError { msg: e })
}

/// Permanently delete everything inside the native `.trash/` directory.
#[uniffi::export]
pub fn empty_trash(root_path: String) -> Result<(), CoreError> {
    vault::empty_trash(root_path).map_err(|e| CoreError::VaultError { msg: e })
}

/// Move an item (file or folder) from one directory to another.
#[uniffi::export]
pub fn move_item(root_path: String, source_relative_path: String, dest_relative_dir: String) -> Result<(), CoreError> {
    vault::move_item(root_path, source_relative_path, dest_relative_dir).map_err(|e| CoreError::VaultError { msg: e })
}

#[uniffi::export]
pub fn duplicate_note(dir_path: String, filename: String) -> String {
    let base_dir = Path::new(&dir_path);
    let src_path = base_dir.join(&filename);

    if !src_path.exists() || !src_path.is_file() {
        return String::new();
    }

    let stem = src_path.file_stem().unwrap_or_default().to_string_lossy();
    let mut counter = 1;
    let mut dst_filename = format!("{}-copy.md", stem);
    let mut dst_path = base_dir.join(&dst_filename);

    while dst_path.exists() {
        counter += 1;
        dst_filename = format!("{}-copy-{}.md", stem, counter);
        dst_path = base_dir.join(&dst_filename);
    }

    if fs::copy(src_path, dst_path).is_ok() {
        vault::invalidate_on_mutation(&dir_path, true);
        dst_filename
    } else {
        String::new()
    }
}

#[uniffi::export]
pub fn read_note(dir_path: String, filename: String) -> String {
    let res = catch_unwind(|| {
        let clean_filename = filename.trim_matches('/');
        let file_path = Path::new(&dir_path).join(clean_filename);
        if !file_path.exists() || !file_path.is_file() {
            return String::new();
        }
        fs::read_to_string(file_path).unwrap_or_default()
    });
    res.unwrap_or_default()
}

#[uniffi::export]
pub fn save_note(dir_path: String, filename: String, content: String) -> bool {
    let res = catch_unwind(|| {
        let dir = Path::new(&dir_path);
        if !dir.exists() {
            fs::create_dir_all(dir).ok();
        }
        let clean_filename = filename.trim_matches('/');
        let file_path = dir.join(clean_filename);
        fs::write(file_path, content).is_ok()
    });
    let result = res.unwrap_or(false);
    vault::invalidate_on_mutation(&dir_path, result)
}

#[uniffi::export]
pub fn create_note(dir_path: String, title_or_template: String) -> String {
    let dir = Path::new(&dir_path);
    if !dir.exists() {
        fs::create_dir_all(dir).ok();
    }

    let expanded = preview_note_name(title_or_template);
    let clean_title = expanded.trim();

    let base_name = if clean_title.ends_with(".md") {
        clean_title.trim_end_matches(".md").to_string()
    } else {
        clean_title.to_string()
    };

    let mut filename = format!("{}.md", base_name);
    let mut file_path = dir.join(&filename);
    let mut counter = 1;

    while file_path.exists() {
        filename = format!("{}-{}.md", base_name, counter);
        file_path = dir.join(&filename);
        counter += 1;
    }

    let initial_content = format!("# {}\n\n", base_name);
    fs::write(&file_path, initial_content).ok();
    vault::invalidate_on_mutation(&dir_path, true);

    filename
}

#[uniffi::export]
pub fn delete_note(dir_path: String, filename: String) -> bool {
    let file_path = Path::new(&dir_path).join(&filename);
    let result = if file_path.exists() {
        fs::remove_file(file_path).is_ok()
    } else {
        false
    };
    vault::invalidate_on_mutation(&dir_path, result)
}
