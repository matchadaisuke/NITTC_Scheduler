package jp.linkserver.nittcsc.reminder

internal object ReminderSchedulingPolicy {
    // DATE_CHANGED/boot/app launch rebuild the rolling window. Keeping this short prevents
    // lesson alarms from exhausting the per-UID AlarmManager quota needed by task reminders.
    const val LESSON_ALARM_HORIZON_DAYS = 3L
    const val MAX_PERIODS_PER_DAY = 12
    const val MAX_TRIGGERS_PER_LESSON = 4
    const val TASK_LATE_DELIVERY_GRACE_MILLIS = 15L * 60L * 1_000L

    const val WORST_CASE_LESSON_ALARM_COUNT =
        (LESSON_ALARM_HORIZON_DAYS.toInt() + 1) *
            MAX_PERIODS_PER_DAY *
            MAX_TRIGGERS_PER_LESSON
}
