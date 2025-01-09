import { open } from "@tauri-apps/plugin-dialog";
import { useNotes } from "../../context/NotesContext";
import { Button } from "../ui";
import { FolderIcon } from "../icons";

export function FolderPicker() {
  const { setNotesFolder } = useNotes();

  const handleSelectFolder = async () => {
    try {
      const selected = await open({
        directory: true,
        multiple: false,
        title: "Choose Notes Folder",
      });

      if (selected && typeof selected === "string") {
        await setNotesFolder(selected);
      }
    } catch (err) {
      console.error("Failed to select folder:", err);
    }
  };

  return (
    <div className="h-full flex items-center justify-center bg-stone-50 dark:bg-stone-950">
      <div className="text-center p-8 max-w-md">
        <div className="mb-6">
          <FolderIcon className="w-20 h-20 mx-auto text-stone-300 dark:text-stone-700" />
        </div>

        <h1 className="text-2xl font-medium text-stone-800 dark:text-stone-200 mb-2">
          Welcome to Scratch
        </h1>
        <p className="text-stone-500 dark:text-stone-400 mb-6">
          Choose a folder to store your notes. Each note will be saved as a
          markdown file, making them portable and version-control friendly.
        </p>

        <Button onClick={handleSelectFolder} size="lg">
          Choose Notes Folder
        </Button>

        <p className="mt-4 text-xs text-stone-400 dark:text-stone-600">
          You can change this later in settings
        </p>
      </div>
    </div>
  );
}
