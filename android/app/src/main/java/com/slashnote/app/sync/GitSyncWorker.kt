package com.slashnote.app.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.slashnote.app.security.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.slash_notes_core.syncGitRepository
import java.io.File
import java.util.concurrent.TimeUnit

class GitSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val notesDir = File(applicationContext.filesDir, "notes").apply {
            if (!exists()) mkdirs()
        }.absolutePath

        val token = SecureStorage.getGitToken(applicationContext)
        val remoteUrl = SecureStorage.getRemoteUrl(applicationContext)
        val branch = SecureStorage.getGitBranch(applicationContext)

        if (remoteUrl.isEmpty() || token.isEmpty()) {
            Log.d(TAG, "Sync skipped: Remote URL or Token is missing.")
            return@withContext Result.success()
        }

        return@withContext try {
            val resultMessage = syncGitRepository(
                repoPath = notesDir,
                token = token,
                remoteUrl = remoteUrl,
                branch = branch
            )
            Log.i(TAG, "Git sync success: $resultMessage")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Git sync error: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "GitSyncWorker"
        private const val PERIODIC_WORK_NAME = "SlashNotePeriodicGitSync"
        private const val ONE_TIME_WORK_NAME = "SlashNoteOneTimeGitSync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<GitSyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeWorkRequest = OneTimeWorkRequestBuilder<GitSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTimeWorkRequest
            )
        }
    }
}
