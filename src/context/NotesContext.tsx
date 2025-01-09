import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from "react";
import { listen } from "@tauri-apps/api/event";
import type { Note, NoteMetadata } from "../types/note";
import * as notesService from "../services/notes";
import type { SearchResult } from "../services/notes";

interface NotesContextValue {
  notes: NoteMetadata[];
  selectedNoteId: string | null;
  currentNote: Note | null;
  notesFolder: string | null;
  isLoading: boolean;
  error: string | null;
  searchQuery: string;
  searchResults: SearchResult[];
  isSearching: boolean;
  selectNote: (id: string) => Promise<void>;
  createNote: () => Promise<void>;
  saveNote: (content: string) => Promise<void>;
  deleteNote: (id: string) => Promise<void>;
  duplicateNote: (id: string) => Promise<void>;
  refreshNotes: () => Promise<void>;
  setNotesFolder: (path: string) => Promise<void>;
  search: (query: string) => Promise<void>;
  clearSearch: () => void;
}

const NotesContext = createContext<NotesContextValue | null>(null);

export function NotesProvider({ children }: { children: ReactNode }) {
  const [notes, setNotes] = useState<NoteMetadata[]>([]);
  const [selectedNoteId, setSelectedNoteId] = useState<string | null>(null);
  const [currentNote, setCurrentNote] = useState<Note | null>(null);
  const [notesFolder, setNotesFolderState] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);

  const refreshNotes = useCallback(async () => {
    if (!notesFolder) return;
    try {
      const notesList = await notesService.listNotes();
      setNotes(notesList);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load notes");
    }
  }, [notesFolder]);

  const selectNote = useCallback(async (id: string) => {
    try {
      // Set selected ID immediately for responsive UI
      setSelectedNoteId(id);
      const note = await notesService.readNote(id);
      setCurrentNote(note);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load note");
    }
  }, []);

  const createNote = useCallback(async () => {
    try {
      const note = await notesService.createNote();
      await refreshNotes();
      setCurrentNote(note);
      setSelectedNoteId(note.id);
      // Clear search when creating a new note
      setSearchQuery("");
      setSearchResults([]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create note");
    }
  }, [refreshNotes]);

  const saveNote = useCallback(
    async (content: string) => {
      if (!currentNote) return;
      try {
        const updated = await notesService.saveNote(currentNote.id, content);
        setCurrentNote(updated);
        await refreshNotes();
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to save note");
      }
    },
    [currentNote, refreshNotes]
  );

  const deleteNote = useCallback(
    async (id: string) => {
      try {
        await notesService.deleteNote(id);
        if (selectedNoteId === id) {
          setSelectedNoteId(null);
          setCurrentNote(null);
        }
        await refreshNotes();
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to delete note");
      }
    },
    [selectedNoteId, refreshNotes]
  );

  const duplicateNote = useCallback(
    async (id: string) => {
      try {
        const newNote = await notesService.duplicateNote(id);
        await refreshNotes();
        setCurrentNote(newNote);
        setSelectedNoteId(newNote.id);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to duplicate note");
      }
    },
    [refreshNotes]
  );

  const setNotesFolder = useCallback(async (path: string) => {
    try {
      await notesService.setNotesFolder(path);
      setNotesFolderState(path);
      // Start file watcher after setting folder
      await notesService.startFileWatcher();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to set notes folder"
      );
    }
  }, []);

  const search = useCallback(async (query: string) => {
    setSearchQuery(query);
    if (!query.trim()) {
      setSearchResults([]);
      return;
    }

    setIsSearching(true);
    try {
      const results = await notesService.searchNotes(query);
      setSearchResults(results);
    } catch (err) {
      console.error("Search failed:", err);
    } finally {
      setIsSearching(false);
    }
  }, []);

  const clearSearch = useCallback(() => {
    setSearchQuery("");
    setSearchResults([]);
  }, []);

  // Load initial state
  useEffect(() => {
    async function init() {
      try {
        const folder = await notesService.getNotesFolder();
        setNotesFolderState(folder);
        if (folder) {
          const notesList = await notesService.listNotes();
          setNotes(notesList);
          // Start file watcher
          await notesService.startFileWatcher();
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to initialize");
      } finally {
        setIsLoading(false);
      }
    }
    init();
  }, []);

  // Listen for file change events
  useEffect(() => {
    let unlisten: (() => void) | undefined;

    listen("file-change", () => {
      // Refresh notes when files change externally
      refreshNotes();
    }).then((fn) => {
      unlisten = fn;
    });

    return () => {
      if (unlisten) {
        unlisten();
      }
    };
  }, [refreshNotes]);

  // Refresh notes when folder changes
  useEffect(() => {
    if (notesFolder) {
      refreshNotes();
    }
  }, [notesFolder, refreshNotes]);

  return (
    <NotesContext.Provider
      value={{
        notes,
        selectedNoteId,
        currentNote,
        notesFolder,
        isLoading,
        error,
        searchQuery,
        searchResults,
        isSearching,
        selectNote,
        createNote,
        saveNote,
        deleteNote,
        duplicateNote,
        refreshNotes,
        setNotesFolder,
        search,
        clearSearch,
      }}
    >
      {children}
    </NotesContext.Provider>
  );
}

export function useNotes() {
  const context = useContext(NotesContext);
  if (!context) {
    throw new Error("useNotes must be used within a NotesProvider");
  }
  return context;
}
