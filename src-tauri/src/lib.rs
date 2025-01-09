use anyhow::Result;
use notify::{Config, RecommendedWatcher, RecursiveMode, Watcher};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, State};

// Note metadata for list display
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NoteMetadata {
    pub id: String,
    pub title: String,
    pub preview: String,
    pub modified: i64,
}

// Full note content
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Note {
    pub id: String,
    pub title: String,
    pub content: String,
    pub path: String,
    pub modified: i64,
}

// App settings
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Settings {
    pub notes_folder: Option<String>,
    pub theme: String,
}

// Search result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
    pub id: String,
    pub title: String,
    pub preview: String,
    pub modified: i64,
    pub score: f32,
}

// File watcher state - we just need to keep the watcher alive
pub struct FileWatcherState {
    #[allow(dead_code)]
    watcher: RecommendedWatcher,
}

// App state
pub struct AppState {
    pub settings: Mutex<Settings>,
    pub notes_cache: Mutex<HashMap<String, NoteMetadata>>,
    pub file_watcher: Mutex<Option<FileWatcherState>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            settings: Mutex::new(Settings {
                notes_folder: None,
                theme: "system".to_string(),
            }),
            notes_cache: Mutex::new(HashMap::new()),
            file_watcher: Mutex::new(None),
        }
    }
}

// Utility: Sanitize filename from title
fn sanitize_filename(title: &str) -> String {
    let sanitized: String = title
        .chars()
        .filter(|c| *c != '\u{00A0}' && *c != '\u{FEFF}') // Remove nbsp and BOM
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '-',
            _ => c,
        })
        .collect();

    let trimmed = sanitized.trim().to_string();
    if trimmed.is_empty() || is_effectively_empty(&trimmed) {
        "untitled".to_string()
    } else {
        trimmed
    }
}

// Utility: Check if a string is effectively empty (whitespace + nbsp)
fn is_effectively_empty(s: &str) -> bool {
    s.chars().all(|c| c.is_whitespace() || c == '\u{00A0}' || c == '\u{FEFF}')
}

// Utility: Extract title from markdown content (first # heading or first line)
fn extract_title(content: &str) -> String {
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("# ") {
            let title = trimmed[2..].trim();
            if !is_effectively_empty(title) {
                return title.to_string();
            }
        }
        if !is_effectively_empty(trimmed) {
            return trimmed.chars().take(50).collect();
        }
    }
    "Untitled".to_string()
}

// Utility: Generate preview from content
fn generate_preview(content: &str) -> String {
    let mut preview = String::new();
    for line in content.lines().skip(1) {
        let trimmed = line.trim();
        if !trimmed.is_empty() && !trimmed.starts_with('#') {
            preview = trimmed.chars().take(100).collect();
            break;
        }
    }
    preview
}

// Get settings file path
fn get_settings_path(app: &AppHandle) -> Result<PathBuf> {
    let app_data = app.path().app_data_dir()?;
    fs::create_dir_all(&app_data)?;
    Ok(app_data.join("settings.json"))
}

// Load settings from disk
fn load_settings(app: &AppHandle) -> Settings {
    let path = match get_settings_path(app) {
        Ok(p) => p,
        Err(_) => return Settings::default(),
    };

    if path.exists() {
        match fs::read_to_string(&path) {
            Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
            Err(_) => Settings::default(),
        }
    } else {
        Settings::default()
    }
}

// Save settings to disk
fn save_settings(app: &AppHandle, settings: &Settings) -> Result<()> {
    let path = get_settings_path(app)?;
    let content = serde_json::to_string_pretty(settings)?;
    fs::write(path, content)?;
    Ok(())
}

// TAURI COMMANDS

#[tauri::command]
fn get_notes_folder(state: State<AppState>) -> Option<String> {
    state.settings.lock().unwrap().notes_folder.clone()
}

#[tauri::command]
fn set_notes_folder(app: AppHandle, path: String, state: State<AppState>) -> Result<(), String> {
    let path_buf = PathBuf::from(&path);

    // Verify it's a valid directory
    if !path_buf.exists() {
        fs::create_dir_all(&path_buf).map_err(|e| e.to_string())?;
    }

    // Create assets folder
    let assets = path_buf.join("assets");
    fs::create_dir_all(&assets).map_err(|e| e.to_string())?;

    // Update settings
    {
        let mut settings = state.settings.lock().unwrap();
        settings.notes_folder = Some(path);
    }

    // Save to disk
    let settings = state.settings.lock().unwrap().clone();
    save_settings(&app, &settings).map_err(|e| e.to_string())?;

    Ok(())
}

#[tauri::command]
fn list_notes(state: State<AppState>) -> Result<Vec<NoteMetadata>, String> {
    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;

    let path = PathBuf::from(folder);
    if !path.exists() {
        return Ok(vec![]);
    }

    let mut notes: Vec<NoteMetadata> = vec![];

    let entries = fs::read_dir(&path).map_err(|e| e.to_string())?;
    for entry in entries.flatten() {
        let file_path = entry.path();
        if file_path.extension().map_or(false, |ext| ext == "md") {
            if let Ok(content) = fs::read_to_string(&file_path) {
                if let Ok(metadata) = entry.metadata() {
                    let modified = metadata
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_secs() as i64)
                        .unwrap_or(0);

                    let id = file_path
                        .file_stem()
                        .and_then(|s| s.to_str())
                        .unwrap_or("unknown")
                        .to_string();

                    notes.push(NoteMetadata {
                        id,
                        title: extract_title(&content),
                        preview: generate_preview(&content),
                        modified,
                    });
                }
            }
        }
    }

    // Sort by modified date, newest first
    notes.sort_by(|a, b| b.modified.cmp(&a.modified));

    // Update cache
    {
        let mut cache = state.notes_cache.lock().unwrap();
        cache.clear();
        for note in &notes {
            cache.insert(note.id.clone(), note.clone());
        }
    }

    Ok(notes)
}

#[tauri::command]
fn read_note(id: String, state: State<AppState>) -> Result<Note, String> {
    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;

    let file_path = PathBuf::from(folder).join(format!("{}.md", id));
    if !file_path.exists() {
        return Err("Note not found".to_string());
    }

    let content = fs::read_to_string(&file_path).map_err(|e| e.to_string())?;
    let metadata = fs::metadata(&file_path).map_err(|e| e.to_string())?;

    let modified = metadata
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    Ok(Note {
        id,
        title: extract_title(&content),
        content,
        path: file_path.to_string_lossy().to_string(),
        modified,
    })
}

#[tauri::command]
fn save_note(
    id: Option<String>,
    content: String,
    state: State<AppState>,
) -> Result<Note, String> {
    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;
    let folder_path = PathBuf::from(folder);

    let title = extract_title(&content);

    // Determine the file ID and path
    let (note_id, file_path) = if let Some(existing_id) = id {
        (existing_id.clone(), folder_path.join(format!("{}.md", existing_id)))
    } else {
        // Generate new ID from title
        let base_id = sanitize_filename(&title);
        let mut final_id = base_id.clone();
        let mut counter = 1;

        while folder_path.join(format!("{}.md", final_id)).exists() {
            final_id = format!("{}-{}", base_id, counter);
            counter += 1;
        }

        (final_id.clone(), folder_path.join(format!("{}.md", final_id)))
    };

    // Write the file
    fs::write(&file_path, &content).map_err(|e| e.to_string())?;

    let metadata = fs::metadata(&file_path).map_err(|e| e.to_string())?;
    let modified = metadata
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    Ok(Note {
        id: note_id,
        title,
        content,
        path: file_path.to_string_lossy().to_string(),
        modified,
    })
}

#[tauri::command]
fn delete_note(id: String, state: State<AppState>) -> Result<(), String> {
    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;

    let file_path = PathBuf::from(folder).join(format!("{}.md", id));
    if file_path.exists() {
        fs::remove_file(&file_path).map_err(|e| e.to_string())?;
    }

    Ok(())
}

#[tauri::command]
fn create_note(state: State<AppState>) -> Result<Note, String> {
    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;
    let folder_path = PathBuf::from(folder);

    // Generate unique ID
    let base_id = "untitled";
    let mut final_id = base_id.to_string();
    let mut counter = 1;

    while folder_path.join(format!("{}.md", final_id)).exists() {
        final_id = format!("{}-{}", base_id, counter);
        counter += 1;
    }

    let content = "# Untitled\n\n".to_string();
    let file_path = folder_path.join(format!("{}.md", final_id));

    fs::write(&file_path, &content).map_err(|e| e.to_string())?;

    let modified = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    Ok(Note {
        id: final_id,
        title: "Untitled".to_string(),
        content,
        path: file_path.to_string_lossy().to_string(),
        modified,
    })
}

#[tauri::command]
fn get_settings(state: State<AppState>) -> Settings {
    state.settings.lock().unwrap().clone()
}

#[tauri::command]
fn update_settings(app: AppHandle, new_settings: Settings, state: State<AppState>) -> Result<(), String> {
    {
        let mut settings = state.settings.lock().unwrap();
        *settings = new_settings;
    }

    let settings = state.settings.lock().unwrap().clone();
    save_settings(&app, &settings).map_err(|e| e.to_string())?;

    Ok(())
}

// Simple fuzzy-ish search: check if query words appear in title or content
fn calculate_score(query: &str, title: &str, content: &str) -> f32 {
    let query_lower = query.to_lowercase();
    let title_lower = title.to_lowercase();
    let content_lower = content.to_lowercase();

    let mut score: f32 = 0.0;

    // Exact title match gets highest score
    if title_lower == query_lower {
        score += 100.0;
    }
    // Title contains query
    else if title_lower.contains(&query_lower) {
        score += 50.0;
    }
    // Title starts with query
    else if title_lower.starts_with(&query_lower) {
        score += 40.0;
    }

    // Check each word in query
    for word in query_lower.split_whitespace() {
        if word.len() < 2 {
            continue;
        }
        if title_lower.contains(word) {
            score += 20.0;
        }
        if content_lower.contains(word) {
            score += 5.0;
        }
    }

    score
}

#[tauri::command]
fn search_notes(query: String, state: State<AppState>) -> Result<Vec<SearchResult>, String> {
    if query.trim().is_empty() {
        return Ok(vec![]);
    }

    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;

    let path = PathBuf::from(folder);
    if !path.exists() {
        return Ok(vec![]);
    }

    let mut results: Vec<SearchResult> = vec![];

    let entries = fs::read_dir(&path).map_err(|e| e.to_string())?;
    for entry in entries.flatten() {
        let file_path = entry.path();
        if file_path.extension().map_or(false, |ext| ext == "md") {
            if let Ok(content) = fs::read_to_string(&file_path) {
                let title = extract_title(&content);
                let score = calculate_score(&query, &title, &content);

                if score > 0.0 {
                    if let Ok(metadata) = entry.metadata() {
                        let modified = metadata
                            .modified()
                            .ok()
                            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                            .map(|d| d.as_secs() as i64)
                            .unwrap_or(0);

                        let id = file_path
                            .file_stem()
                            .and_then(|s| s.to_str())
                            .unwrap_or("unknown")
                            .to_string();

                        results.push(SearchResult {
                            id,
                            title,
                            preview: generate_preview(&content),
                            modified,
                            score,
                        });
                    }
                }
            }
        }
    }

    // Sort by score, highest first
    results.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));

    // Limit to top 20 results
    results.truncate(20);

    Ok(results)
}

// File watcher event payload
#[derive(Clone, Serialize)]
struct FileChangeEvent {
    kind: String,
    path: String,
}

fn setup_file_watcher(app: AppHandle, notes_folder: &str) -> Result<FileWatcherState, String> {
    let folder_path = PathBuf::from(notes_folder);
    let app_handle = app.clone();
    let debounce_map: std::sync::Arc<Mutex<HashMap<PathBuf, Instant>>> =
        std::sync::Arc::new(Mutex::new(HashMap::new()));

    let watcher = RecommendedWatcher::new(
        move |res: Result<notify::Event, notify::Error>| {
            if let Ok(event) = res {
                // Only handle markdown files
                for path in event.paths.iter() {
                    if path.extension().map_or(false, |ext| ext == "md") {
                        // Debounce: ignore events within 500ms of each other for same file
                        let mut map = debounce_map.lock().unwrap();
                        let now = Instant::now();
                        if let Some(last) = map.get(path) {
                            if now.duration_since(*last) < Duration::from_millis(500) {
                                continue;
                            }
                        }
                        map.insert(path.clone(), now);

                        let kind = match event.kind {
                            notify::EventKind::Create(_) => "created",
                            notify::EventKind::Modify(_) => "modified",
                            notify::EventKind::Remove(_) => "deleted",
                            _ => continue,
                        };

                        let _ = app_handle.emit(
                            "file-change",
                            FileChangeEvent {
                                kind: kind.to_string(),
                                path: path.to_string_lossy().to_string(),
                            },
                        );
                    }
                }
            }
        },
        Config::default(),
    )
    .map_err(|e| e.to_string())?;

    let mut watcher = watcher;
    watcher
        .watch(&folder_path, RecursiveMode::NonRecursive)
        .map_err(|e| e.to_string())?;

    Ok(FileWatcherState { watcher })
}

#[tauri::command]
fn start_file_watcher(app: AppHandle, state: State<AppState>) -> Result<(), String> {
    let settings = state.settings.lock().unwrap();
    let folder = settings.notes_folder.as_ref().ok_or("Notes folder not set")?;

    let watcher_state = setup_file_watcher(app, folder)?;

    let mut file_watcher = state.file_watcher.lock().unwrap();
    *file_watcher = Some(watcher_state);

    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // Load settings on startup
            let settings = load_settings(app.handle());
            let state = AppState {
                settings: Mutex::new(settings),
                notes_cache: Mutex::new(HashMap::new()),
                file_watcher: Mutex::new(None),
            };
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_notes_folder,
            set_notes_folder,
            list_notes,
            read_note,
            save_note,
            delete_note,
            create_note,
            get_settings,
            update_settings,
            search_notes,
            start_file_watcher,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
