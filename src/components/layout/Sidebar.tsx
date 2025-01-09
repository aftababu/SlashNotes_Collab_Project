import { useCallback, useEffect, useRef, useState } from "react";
import { useNotes } from "../../context/NotesContext";
import { NoteList } from "../notes/NoteList";
import { IconButton, Input } from "../ui";
import { PlusIcon, XIcon, SpinnerIcon } from "../icons";

export function Sidebar() {
  const { createNote, notes, search, searchQuery, clearSearch, isSearching } =
    useNotes();
  const [inputValue, setInputValue] = useState(searchQuery);
  const debounceRef = useRef<number | null>(null);

  // Sync input with search query
  useEffect(() => {
    setInputValue(searchQuery);
  }, [searchQuery]);

  const handleSearchChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const value = e.target.value;
      setInputValue(value);

      // Debounce search
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }

      debounceRef.current = window.setTimeout(() => {
        search(value);
      }, 150);
    },
    [search]
  );

  const handleClearSearch = useCallback(() => {
    setInputValue("");
    clearSearch();
  }, [clearSearch]);

  return (
    <div className="w-64 h-full bg-stone-50 dark:bg-stone-900 border-r border-stone-200 dark:border-stone-800 flex flex-col">
      {/* Drag region */}
      <div className="h-10 shrink-0" data-tauri-drag-region />

      {/* Scrollable area with search and notes */}
      <div className="flex-1 overflow-y-auto">
        {/* Search - sticky at top */}
        <div className="sticky top-0 z-10 bg-stone-50 dark:bg-stone-900 px-4 py-2">
          <div className="relative">
            <Input
              type="text"
              value={inputValue}
              onChange={handleSearchChange}
              placeholder="Search notes..."
              className="h-9 pr-8 text-sm"
            />
            {inputValue && !isSearching && (
              <button
                onClick={handleClearSearch}
                tabIndex={-1}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-stone-400 hover:text-stone-600 dark:hover:text-stone-300"
              >
                <XIcon />
              </button>
            )}
            {isSearching && (
              <div className="absolute right-2 top-1/2 -translate-y-1/2 text-stone-400">
                <SpinnerIcon />
              </div>
            )}
          </div>
        </div>

        {/* Note list */}
        <NoteList />
      </div>

      {/* Footer with new note button */}
      <div className="px-4 py-2 border-t border-stone-200 dark:border-stone-800 flex items-center justify-between">
        <span className="text-xs text-stone-500">
          {notes.length} {notes.length === 1 ? "note" : "notes"}
        </span>
        <IconButton onClick={createNote} title="New Note (⌘N)">
          <PlusIcon />
        </IconButton>
      </div>
    </div>
  );
}
