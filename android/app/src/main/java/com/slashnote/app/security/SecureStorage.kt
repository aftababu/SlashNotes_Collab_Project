package com.slashnote.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureStorage {
    private const val PREFS_FILENAME = "slashnote_secure_prefs"
    private const val KEY_GIT_TOKEN = "git_pat_token"
    private const val KEY_REMOTE_URL = "git_remote_url"
    private const val KEY_GIT_BRANCH = "git_branch"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"

    // Cache the SharedPreferences instance so we don't rebuild the MasterKey +
    // EncryptedSharedPreferences on every getter (keystore lookups are expensive).
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        val prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILENAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for older API or test environments
            context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
        }
        cachedPrefs = prefs
        return prefs
    }

    fun getGitToken(context: Context): String {
        return getPrefs(context).getString(KEY_GIT_TOKEN, "") ?: ""
    }

    fun saveGitToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_GIT_TOKEN, token.trim()).apply()
    }

    fun getRemoteUrl(context: Context): String {
        val saved = getPrefs(context).getString(KEY_REMOTE_URL, "") ?: ""
        return saved
    }

    fun saveRemoteUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_REMOTE_URL, url.trim()).apply()
    }

    fun getGitBranch(context: Context): String {
        return getPrefs(context).getString(KEY_GIT_BRANCH, "main") ?: "main"
    }

    fun saveGitBranch(context: Context, branch: String) {
        val cleanBranch = branch.trim().ifEmpty { "main" }
        getPrefs(context).edit().putString(KEY_GIT_BRANCH, cleanBranch).apply()
    }

    fun isAutoSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }
}
