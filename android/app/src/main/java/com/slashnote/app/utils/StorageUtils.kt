package com.slashnote.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File

object StorageUtils {
    private const val TAG = "SlashNoteStorage"

    fun checkAndRequestStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d(TAG, "External storage manager permission not granted. Launching settings intent.")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open app-specific permission intent, trying generic intent", e)
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } else {
                Log.d(TAG, "External storage manager permission is already granted.")
            }
        }
    }

    fun resolveSafUriToAbsolutePath(context: Context, uri: Uri): String {
        Log.d(TAG, "Resolving SAF URI: $uri, rawPath=${uri.path}")

        val rawPath = uri.path ?: return uri.toString()
        val decodedPath = Uri.decode(rawPath)
        Log.d(TAG, "Decoded SAF Path: $decodedPath")

        val subPath = when {
            decodedPath.contains(":") -> decodedPath.substringAfter(":")
            decodedPath.contains("/tree/") -> decodedPath.substringAfter("/tree/")
            decodedPath.contains("/document/") -> decodedPath.substringAfter("/document/")
            else -> decodedPath
        }.trimStart('/')

        val volume = if (decodedPath.contains(":")) {
            decodedPath.substringBefore(":").substringAfterLast("/")
        } else "primary"

        Log.d(TAG, "Extracted volume='$volume', subPath='$subPath'")

        val baseDir = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }

        val targetFile = File(baseDir, subPath)
        Log.d(TAG, "Resolved target file: ${targetFile.absolutePath}, exists=${targetFile.exists()}, isDir=${targetFile.isDirectory}, canRead=${targetFile.canRead()}")

        if (!targetFile.exists()) {
            val created = targetFile.mkdirs()
            Log.d(TAG, "Directory creation attempted: $created for path ${targetFile.absolutePath}")
        }

        return if (targetFile.exists()) targetFile.absolutePath else baseDir.absolutePath
    }

    fun getSafeVaultPath(context: Context, requestedPath: String): String {
        val appExternalDir = context.getExternalFilesDir(null)
        val defaultPath = File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }.absolutePath

        if (requestedPath.isBlank()) {
            return defaultPath
        }

        // If requested path is in public shared storage (e.g. /storage/emulated/0/...)
        if (requestedPath.startsWith("/storage/emulated/0/") || requestedPath.startsWith("/sdcard/")) {
            // Check if full external file management access is granted
            val hasFullAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            if (!hasFullAccess) {
                // Fallback to app-specific external directory: /storage/emulated/0/Android/data/com.slashnote.app/files/Vault
                val fallbackVault = File(appExternalDir, "Vault").apply { if (!exists()) mkdirs() }
                Log.w(TAG, "Public storage FUSE restricted for path $requestedPath. Falling back to POSIX-safe app path: ${fallbackVault.absolutePath}")
                return fallbackVault.absolutePath
            }
        }

        val targetDir = File(requestedPath)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return if (targetDir.exists()) targetDir.absolutePath else defaultPath
    }
}
