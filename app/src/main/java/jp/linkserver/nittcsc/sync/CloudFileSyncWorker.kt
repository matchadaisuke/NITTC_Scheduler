package jp.linkserver.nittcsc.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CloudFileSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!CloudFileSyncManager.isConfigured(applicationContext)) return Result.success()
        return runCatching {
            CloudFileSyncManager.syncNow(applicationContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
