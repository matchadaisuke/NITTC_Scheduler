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
        val triggerAtMillis = triggerAt
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        if (triggerAtMillis <= System.currentTimeMillis()) {
            ReminderDebug.log("exact alarm unavailable label=$debugLabel triggerAt=$triggerAt reason=past_trigger")
            return false
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                ReminderDebug.log(
                    "setExactAndAllowWhileIdle succeeded label=$debugLabel triggerAt=$triggerAt " +
                        "triggerAtMillis=$triggerAtMillis"
                )
            } else {
                // Exact alarm permission is often disabled by default on Android 12+.
                // Use an inexact idle-safe alarm instead of silently dropping reminders.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                ReminderDebug.log(
                    "setAndAllowWhileIdle fallback scheduled label=$debugLabel triggerAt=$triggerAt " +
                        "triggerAtMillis=$triggerAtMillis"
                )
            }
            true
        }.onFailure {
            Log.w(TAG, "Cannot schedule reminder alarm", it)
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
