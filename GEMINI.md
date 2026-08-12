# SlashNote - Gemini AI Development & Context Guide

## Project Overview

SlashNote is a fast, privacy-first, offline-first Markdown note-taking app built for maximum performance and ultra-low memory consumption across Desktop and Mobile platforms.

- **Desktop Client:** Tauri v2 (Rust backend) + React / TypeScript / Tailwind + TipTap editor + Tantivy search.
- **Mobile Client (Android):** Kotlin + Jetpack Compose UI powered by a shared native Rust engine (`slash_notes_core`) compiled via Mozilla UniFFI.
- **Shared Core (`crates/SlashNotes_Core`):** Plain-text Markdown parsing (`pulldown-cmark`), file I/O, directory indexing, and native embedded Git sync (`libgit2`).

---

## Build & Development Commands

### Desktop (Tauri)
```bash
pnpm install          # Install dependencies
pnpm dev              # Start Vite dev server
pnpm tauri dev        # Start full Tauri app in dev mode
pnpm tauri build      # Production Tauri build

