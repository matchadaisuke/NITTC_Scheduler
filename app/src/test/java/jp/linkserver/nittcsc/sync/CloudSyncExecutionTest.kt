package jp.linkserver.nittcsc.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncExecutionTest {
    @Test
    fun authenticationFailuresRequireUserAction() {
        assertEquals(
            CloudSyncDisposition.PERMANENT_FAILURE,
            cloudSyncDispositionForMegaError(-9)
        )
        assertEquals(
            CloudSyncDisposition.PERMANENT_FAILURE,
            cloudSyncDispositionForMegaError(-15)
        )
        assertEquals(
            CloudSyncDisposition.PERMANENT_FAILURE,
            cloudSyncDispositionForMegaError(-26)
        )
    }

    @Test
    fun networkAndUnknownFailuresAreRetried() {
        assertEquals(CloudSyncDisposition.RETRY, cloudSyncDispositionForMegaError(-3))
        assertEquals(CloudSyncDisposition.RETRY, cloudSyncDispositionForMegaError(null))
    }

    @Test
    fun retryPolicyAllowsAtMostFiveRetriesAfterInitialAttempt() {
        assertEquals(true, shouldRetryCloudSync(0))
        assertEquals(true, shouldRetryCloudSync(4))
        assertEquals(false, shouldRetryCloudSync(5))
        assertEquals(false, shouldRetryCloudSync(6))
    }

    @Test
    fun dirtyTimestampIsMonotonicWithinSameMillisecond() {
        assertEquals(1_001L, nextCloudDirtyTimestamp(nowMillis = 1_000L, previousMillis = 1_000L))
        assertEquals(2_000L, nextCloudDirtyTimestamp(nowMillis = 2_000L, previousMillis = 1_000L))
    }

    @Test
    fun newerRemoteSnapshotWinsConflict() {
        assertEquals(
            CloudSnapshotChoice.REMOTE,
            chooseCloudSnapshot(
                localDirty = true,
                localUpdatedAt = 1_000L,
                remoteUpdatedAt = 2_000L
            )
        )
    }

    @Test
    fun newerLocalSnapshotWinsConflict() {
        assertEquals(
            CloudSnapshotChoice.LOCAL,
            chooseCloudSnapshot(
                localDirty = true,
                localUpdatedAt = 2_000L,
                remoteUpdatedAt = 1_000L
            )
        )
    }

    @Test
    fun equalTimestampKeepsUnsyncedLocalSnapshot() {
        assertEquals(
            CloudSnapshotChoice.LOCAL,
            chooseCloudSnapshot(
                localDirty = true,
                localUpdatedAt = 2_000L,
                remoteUpdatedAt = 2_000L
            )
        )
    }

    @Test
    fun cleanLocalAcceptsRemoteSnapshot() {
        assertEquals(
            CloudSnapshotChoice.REMOTE,
            chooseCloudSnapshot(
                localDirty = false,
                localUpdatedAt = 0L,
                remoteUpdatedAt = 1L
            )
        )
    }
}
