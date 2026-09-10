package jp.linkserver.nittcsc.sync

internal enum class CloudSyncDisposition {
    SUCCESS,
    RETRY,
    PERMANENT_FAILURE
}

internal data class CloudSyncExecutionResult(
    val disposition: CloudSyncDisposition,
    val message: String
)

internal const val CLOUD_SYNC_MAX_RETRY_COUNT = 5

internal fun shouldRetryCloudSync(runAttemptCount: Int): Boolean =
    runAttemptCount < CLOUD_SYNC_MAX_RETRY_COUNT

internal fun cloudSyncDispositionForMegaError(code: Int?): CloudSyncDisposition =
    when (code) {
        // Authentication/session errors require user action; retrying in the background is wasteful.
        -9, -15, -26 -> CloudSyncDisposition.PERMANENT_FAILURE
        else -> CloudSyncDisposition.RETRY
    }

internal fun nextCloudDirtyTimestamp(nowMillis: Long, previousMillis: Long): Long =
    maxOf(nowMillis, previousMillis + 1L)

internal enum class CloudSnapshotChoice {
    LOCAL,
    REMOTE
}

/**
 * Resolves a MEGA whole-snapshot conflict by update time.
 *
 * A remote snapshot is accepted only when it is strictly newer than the pending local change.
 * Equal timestamps deliberately keep LOCAL so an unsynced local edit is never discarded on a tie.
 */
internal fun chooseCloudSnapshot(
    localDirty: Boolean,
    localUpdatedAt: Long,
    remoteUpdatedAt: Long
): CloudSnapshotChoice {
    if (!localDirty) return CloudSnapshotChoice.REMOTE
    return if (remoteUpdatedAt > localUpdatedAt) {
        CloudSnapshotChoice.REMOTE
    } else {
        CloudSnapshotChoice.LOCAL
    }
}
