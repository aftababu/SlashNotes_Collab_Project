package com.slashnote.app.security

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "slashnote_settings"
    private const val KEY_NOTE_TEMPLATE = "key_note_template"
    private const val KEY_IGNORED_PATTERNS = "key_ignored_patterns"
    private const val KEY_PINNED_NOTE_IDS = "key_pinned_note_ids"
    private const val KEY_FONT_SIZE_SP = "key_font_size_sp"
    private const val KEY_IS_RTL = "key_is_rtl"
    private const val KEY_CUSTOM_VAULT_PATH = "key_custom_vault_path"
    private const val KEY_SHOW_ALL_FILES = "key_show_all_files"
    private const val KEY_RECENTLY_VISITED_NOTES = "key_recently_visited_notes"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getRecentlyVisitedNotes(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_RECENTLY_VISITED_NOTES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun recordNoteVisited(context: Context, relativePath: String) {
        if (relativePath.isBlank()) return
        val current = getRecentlyVisitedNotes(context).toMutableList()
        current.remove(relativePath)
        current.add(0, relativePath)
        val trimmed = current.take(10)
        getPrefs(context).edit().putString(KEY_RECENTLY_VISITED_NOTES, trimmed.joinToString("|")).apply()
    }

    fun getShowAllFiles(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_ALL_FILES, false)
    }

    fun saveShowAllFiles(context: Context, showAll: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_ALL_FILES, showAll).apply()
    }

    fun getCustomVaultPath(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_VAULT_PATH, "") ?: ""
    }

    fun saveCustomVaultPath(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_VAULT_PATH, path).apply()
    }

    fun getNoteTemplate(context: Context): String {
        return getPrefs(context).getString(KEY_NOTE_TEMPLATE, "Note-{year}-{month}-{day}") ?: "Note-{year}-{month}-{day}"
    }

    fun saveNoteTemplate(context: Context, template: String) {
        getPrefs(context).edit().putString(KEY_NOTE_TEMPLATE, template).apply()
    }

    fun getIgnoredPatterns(context: Context): String {
        return getPrefs(context).getString(KEY_IGNORED_PATTERNS, ".git,node_modules,.trash,dist,target") ?: ".git,node_modules,.trash,dist,target"
    }

    fun saveIgnoredPatterns(context: Context, patterns: String) {
        getPrefs(context).edit().putString(KEY_IGNORED_PATTERNS, patterns).apply()
    }

    fun getPinnedNoteIds(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_PINNED_NOTE_IDS, emptySet()) ?: emptySet()
    }

    fun togglePinnedNoteId(context: Context, noteId: String): Boolean {
        val current = getPinnedNoteIds(context).toMutableSet()
        val isNowPinned = if (current.contains(noteId)) {
            current.remove(noteId)
            false
        } else {
            current.add(noteId)
            true
        }
        getPrefs(context).edit().putStringSet(KEY_PINNED_NOTE_IDS, current).apply()
        return isNowPinned
    }

    fun getFontSizeSp(context: Context): Int {
        return getPrefs(context).getInt(KEY_FONT_SIZE_SP, 16)
    }

    fun saveFontSizeSp(context: Context, sizeSp: Int) {
        getPrefs(context).edit().putInt(KEY_FONT_SIZE_SP, sizeSp).apply()
    }

    fun getIsRtl(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_RTL, false)
    }

    fun saveIsRtl(context: Context, isRtl: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_RTL, isRtl).apply()
    }
}
