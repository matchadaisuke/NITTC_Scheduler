package jp.linkserver.nittcsc.reminder

internal fun reminderScheduleMatches(
    expectedReminderAtMillis: Long?,
    expectedUpdatedAtMillis: Long?,
    currentReminderAtMillis: Long,
    currentUpdatedAtMillis: Long
): Boolean = expectedReminderAtMillis == null ||
    expectedReminderAtMillis <= 0L ||
    (
        expectedReminderAtMillis == currentReminderAtMillis &&
            (expectedUpdatedAtMillis == null || expectedUpdatedAtMillis <= 0L ||
                expectedUpdatedAtMillis == currentUpdatedAtMillis)
        )

internal fun reminderDeliveryToken(
    taskId: Long,
    reminderAtMillis: Long,
    updatedAtMillis: Long
): String = "$taskId:$reminderAtMillis:$updatedAtMillis"

internal fun shouldCancelFallbackAfter(result: TaskReminderNotificationResult): Boolean =
    result != TaskReminderNotificationResult.STALE_SCHEDULE

internal fun isWithinLateDeliveryGrace(
    reminderAtMillis: Long,
    nowMillis: Long,
    graceMillis: Long
): Boolean {
    val lateness = nowMillis - reminderAtMillis
    return lateness in 0..graceMillis
}

internal enum class TaskReminderNotificationResult {
    POSTED,
    ALREADY_DELIVERED,
    TASK_NOT_FOUND,
    TASK_INELIGIBLE,
    STALE_SCHEDULE,
    NOTIFICATIONS_BLOCKED
}
