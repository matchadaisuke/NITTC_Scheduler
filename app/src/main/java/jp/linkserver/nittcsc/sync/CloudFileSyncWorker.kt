package jp.linkserver.nittcsc.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class CloudFileSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "background sync started attempt=$runAttemptCount")
        return try {
            val outcome = CloudFileSyncManager.syncForBackground(applicationContext)
            Log.i(TAG, "background sync finished disposition=${outcome.disposition}")
            when (outcome.disposition) {
                CloudSyncDisposition.SUCCESS -> Result.success()
                CloudSyncDisposition.RETRY -> retryOrFinish(outcome.message)
                // Keep periodic work healthy, but do not enter an immediate retry loop for
                // authentication/configuration/data errors that require user action.
                CloudSyncDisposition.PERMANENT_FAILURE -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "background sync crashed attempt=$runAttemptCount", error)
            retryOrFinish(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun retryOrFinish(reason: String): Result {
        return if (shouldRetryCloudSync(runAttemptCount)) {
            Log.w(
                TAG,
                "background sync will retry retry=${runAttemptCount + 1}/$CLOUD_SYNC_MAX_RETRY_COUNT reason=$reason"
            )
            Result.retry()
        } else {
            Log.e(
                TAG,
                "background sync retry limit reached retries=$CLOUD_SYNC_MAX_RETRY_COUNT reason=$reason"
            )
            // Result.failure() would terminate periodic work entirely. Stop only this retry chain
            // so the next scheduled periodic sync can recover automatically.
            Result.success()
        }
    }

    companion object {
        private const val TAG = "MegaSync"
        internal const val PERIODIC_WORK_NAME = "mega_sync_periodic"
        internal const val IMMEDIATE_WORK_NAME = "mega_sync_immediate"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudFileSyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(PERIODIC_WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "periodic sync schedule requested workId=${request.id}")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(PERIODIC_WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
            Log.i(TAG, "periodic sync cancellation requested")
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudFileSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(IMMEDIATE_WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "immediate sync enqueue requested workId=${request.id}")
        }
    }
}
