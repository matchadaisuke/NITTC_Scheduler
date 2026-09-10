package jp.linkserver.nittcsc.reminder

import android.content.Context
import android.util.Log

/**
 * Single entry point for restoring reminder schedules.
 *
 * All future settings changes, imports and cloud sync restores should call this
 * coordinator instead of registering alarms directly.
 */
object NotificationRebuildCoordinator {
    private const val TAG = "NotificationRebuild"

    suspend fun rebuild(context: Context, reason: String) {
        Log.d(TAG, "Rebuilding notification schedules: $reason")

        // Intentionally centralized. Existing alarm registrations will be
        // migrated here one by one so imports, MEGA sync and settings changes
        // all share the same recovery path.
        LessonStartNotificationWorker.rescheduleAll(context)
    }
}
