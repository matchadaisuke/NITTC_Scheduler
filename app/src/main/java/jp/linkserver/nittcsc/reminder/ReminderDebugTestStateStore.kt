package jp.linkserver.nittcsc.reminder

import android.content.Context

data class ReminderDebugTestSnapshot(
    val testType: String = "",
    val startedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val alarmScheduled: Boolean = false,
    val receiverFired: Boolean = false,
    val workerEnqueued: Boolean = false,
    val workerStarted: Boolean = false,
    val notificationGenerated: Boolean = false,
    val notificationPosted: Boolean = false,
    val detail: String = ""
) {
    val isEmpty: Boolean get() = startedAtMillis <= 0L
}

internal enum class ReminderDebugTestStage(val preferenceKey: String) {
    ALARM_SCHEDULED("alarm_scheduled"),
    RECEIVER_FIRED("receiver_fired"),
    WORKER_ENQUEUED("worker_enqueued"),
    WORKER_STARTED("worker_started"),
    NOTIFICATION_GENERATED("notification_generated"),
    NOTIFICATION_POSTED("notification_posted")
}

internal object ReminderDebugTestStateStore {
    private const val PREFERENCES = "reminder_debug_test_state"
    private const val KEY_TEST_TYPE = "test_type"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_DETAIL = "detail"

    fun reset(context: Context, testType: String) {
        val now = System.currentTimeMillis()
        preferences(context).edit()
            .clear()
            .putString(KEY_TEST_TYPE, testType)
            .putLong(KEY_STARTED_AT, now)
            .putLong(KEY_UPDATED_AT, now)
            .commit()
        ReminderDebug.log("debug test state reset testType=$testType")
    }

    fun mark(
        context: Context,
        stage: ReminderDebugTestStage,
        detail: String = ""
    ) {
        val committed = preferences(context).edit()
            .putBoolean(stage.preferenceKey, true)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .putString(KEY_DETAIL, detail)
            .commit()
        if (!committed) {
            ReminderDebug.warn("debug test state commit failed stage=$stage")
        }
    }

    fun recordFailure(context: Context, detail: String) {
        preferences(context).edit()
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .putString(KEY_DETAIL, detail)
            .commit()
        ReminderDebug.warn("debug test failure detail=$detail")
    }

    fun read(context: Context): ReminderDebugTestSnapshot {
        val prefs = preferences(context)
        return ReminderDebugTestSnapshot(
            testType = prefs.getString(KEY_TEST_TYPE, "").orEmpty(),
            startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
            updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L),
            alarmScheduled = prefs.getBoolean(ReminderDebugTestStage.ALARM_SCHEDULED.preferenceKey, false),
            receiverFired = prefs.getBoolean(ReminderDebugTestStage.RECEIVER_FIRED.preferenceKey, false),
            workerEnqueued = prefs.getBoolean(ReminderDebugTestStage.WORKER_ENQUEUED.preferenceKey, false),
            workerStarted = prefs.getBoolean(ReminderDebugTestStage.WORKER_STARTED.preferenceKey, false),
            notificationGenerated = prefs.getBoolean(
                ReminderDebugTestStage.NOTIFICATION_GENERATED.preferenceKey,
                false
            ),
            notificationPosted = prefs.getBoolean(
                ReminderDebugTestStage.NOTIFICATION_POSTED.preferenceKey,
                false
            ),
            detail = prefs.getString(KEY_DETAIL, "").orEmpty()
        )
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
