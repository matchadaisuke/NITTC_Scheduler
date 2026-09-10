package jp.linkserver.nittcsc.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId

internal object ExactReminderAlarmScheduler {
    private const val TAG = "ExactReminderAlarm"

    fun schedule(
        context: Context,
        triggerAt: LocalDateTime,
        pendingIntent: PendingIntent,
        debugLabel: String = "unspecified"
    ): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            ReminderDebug.log("exact alarm unavailable label=$debugLabel reason=permission_or_policy")
            return false
        }
        val triggerAtMillis = triggerAt
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        if (triggerAtMillis <= System.currentTimeMillis()) {
            ReminderDebug.log("exact alarm unavailable label=$debugLabel triggerAt=$triggerAt reason=past_trigger")
            return false
        }

        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            ReminderDebug.log(
                "setExactAndAllowWhileIdle succeeded label=$debugLabel triggerAt=$triggerAt " +
                    "triggerAtMillis=$triggerAtMillis"
            )
            true
        }.onFailure {
            Log.w(TAG, "Cannot schedule exact reminder alarm", it)
            ReminderDebug.warn("setExactAndAllowWhileIdle failed label=$debugLabel triggerAt=$triggerAt", it)
        }.getOrDefault(false)
    }

    fun cancel(
        context: Context,
        pendingIntent: PendingIntent,
        debugLabel: String = "unspecified"
    ) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        ReminderDebug.log("exact alarm cancel requested label=$debugLabel")
    }
}
