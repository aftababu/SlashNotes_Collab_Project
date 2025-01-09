import { useRef, useState, useEffect, useCallback } from "react";
import { Menu, MenuItem, PredefinedMenuItem } from "@tauri-apps/api/menu";
import { useNotes } from "../../context/NotesContext";
import { ListItem } from "../ui";

// Clean title - remove nbsp and other invisible characters
function cleanTitle(title: string | undefined): string {
  if (!title) return "Untitled";
  const cleaned = title
    .replace(/&nbsp;/g, " ")
    .replace(/\u00A0/g, " ")
    .replace(/\u200B/g, "")
    .trim();
  return cleaned || "Untitled";
}

function formatDate(timestamp: number): string {
  const date = new Date(timestamp * 1000);
  const now = new Date();
  const diff = now.getTime() - date.getTime();

  // Less than 24 hours
  if (diff < 86400000) {
    return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  // Less than 7 days
  if (diff < 604800000) {
    return date.toLocaleDateString([], { weekday: "short" });
  }

  // Otherwise show date
  return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

export function NoteList() {
  const {
    notes,
    selectedNoteId,
    selectNote,
    deleteNote,
    duplicateNote,
    isLoading,
    searchQuery,
    searchResults,
  } = useNotes();

  // Track previous selection to detect switches vs edits
  const prevSelectedIdRef = useRef<string | null>(null);
  const [enableAnimation, setEnableAnimation] = useState(false);

  const handleContextMenu = useCallback(async (e: React.MouseEvent, noteId: string) => {
    e.preventDefault();

    const menu = await Menu.new({
      items: [
        await MenuItem.new({
          text: "Duplicate",
          action: () => duplicateNote(noteId),
        }),
        await PredefinedMenuItem.new({ item: "Separator" }),
        await MenuItem.new({
          text: "Delete",
          action: () => deleteNote(noteId),
        }),
      ],
    });

    await menu.popup();
  }, [duplicateNote, deleteNote]);

  // Disable animation when switching notes, enable after a delay
  useEffect(() => {
    if (selectedNoteId !== prevSelectedIdRef.current) {
      // Switched to a different note - disable animation temporarily
      setEnableAnimation(false);
      prevSelectedIdRef.current = selectedNoteId;

      // Re-enable animation after a brief delay
      const timer = setTimeout(() => setEnableAnimation(true), 150);
      return () => clearTimeout(timer);
    }
  }, [selectedNoteId]);

  // Show search results if there's a search query
  const displayItems = searchQuery.trim()
    ? searchResults.map((r) => ({
        id: r.id,
        title: r.title,
        preview: r.preview,
        modified: r.modified,
      }))
    : notes;

  if (isLoading && notes.length === 0) {
    return (
      <div className="p-4 text-center text-stone-500 dark:text-stone-400">
        Loading...
      </div>
    );
  }

  if (searchQuery.trim() && displayItems.length === 0) {
    return (
      <div className="p-4 text-center text-stone-500 dark:text-stone-400">
        No results found
      </div>
    );
  }

  if (displayItems.length === 0) {
    return (
      <div className="p-4 text-center text-stone-500 dark:text-stone-400">
        No notes yet
      </div>
    );
  }

  return (
    <div className="divide-y divide-stone-100 dark:divide-stone-800">
      {displayItems.map((item) => (
        <ListItem
          key={item.id}
          title={cleanTitle(item.title)}
          subtitle={item.preview}
          meta={formatDate(item.modified)}
          isSelected={selectedNoteId === item.id}
          onClick={() => selectNote(item.id)}
          onContextMenu={(e) => handleContextMenu(e, item.id)}
          animateTitle={enableAnimation && selectedNoteId === item.id}
        />
      ))}
    </div>
  );
}
