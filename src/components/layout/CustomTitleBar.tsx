import { getCurrentWindow } from "@tauri-apps/api/window";
import { useEffect, useState } from "react";

export function CustomTitleBar() {
  const [isMac, setIsMac] = useState(false);
  const [isDesktop, setIsDesktop] = useState(false);
  
  useEffect(() => {
    // Only render custom titlebar on Desktop (not web preview/mobile)
    const isDesktopApp = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
    setIsDesktop(isDesktopApp);
    setIsMac(navigator.userAgent.includes("Mac"));
  }, []);

  // macOS keeps its native overlay traffic lights, web/mobile doesn't need this
  if (!isDesktop || isMac) return null;

  const appWindow = getCurrentWindow();

  return (
    <div
      data-tauri-drag-region
      className="relative w-full h-7 shrink-0 flex items-center justify-between bg-bg-secondary border-b border-border z-50 select-none"
    >
      {/* Draggable Background Region */}
      <div className="flex-1 h-full" data-tauri-drag-region />

      {/* Absolutely Centered App Branding */}
      <div
        data-tauri-drag-region
        className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 flex items-center gap-1.5 pointer-events-none opacity-80"
      >
        <img
          src="/SlashNote.png"
          alt="SlashNote Logo"
          className="w-3.5 h-3.5 rounded-[2px] object-cover overflow-hidden"
        />
        <span className="text-[10px] font-semibold tracking-wider uppercase text-text">
          SLASHNOTE
        </span>
      </div>

      {/* Window Control Buttons (Far Right) */}
      <div className="flex h-full titlebar-no-drag text-text-muted pointer-events-auto relative z-10">
        <button
          className="px-3.5 flex items-center justify-center hover:bg-bg-muted transition-colors"
          onClick={() => appWindow.minimize()}
          title="Minimize"
        >
          <svg width="10" height="1" viewBox="0 0 10 1" fill="currentColor">
            <rect width="10" height="1" />
          </svg>
        </button>
        <button
          className="px-3.5 flex items-center justify-center hover:bg-bg-muted transition-colors"
          onClick={() => appWindow.toggleMaximize()}
          title="Maximize"
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor">
            <rect x="0.5" y="0.5" width="9" height="9" strokeWidth="1" />
          </svg>
        </button>
        <button
          className="px-3.5 flex items-center justify-center hover:bg-red-500 hover:text-white transition-colors"
          onClick={() => appWindow.close()}
          title="Close"
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor">
            <path d="M1 1L9 9M9 1L1 9" strokeWidth="1.2" />
          </svg>
        </button>
      </div>
    </div>
  );
}
