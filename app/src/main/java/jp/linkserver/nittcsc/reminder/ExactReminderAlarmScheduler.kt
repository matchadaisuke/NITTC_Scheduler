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
        pendingIntent: PendingIntent
    ): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAtMillis = triggerAt
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        if (triggerAtMillis <= System.currentTimeMillis()) return false

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // Exact alarm permission is often disabled by default on Android 12+.
                // Use an inexact idle-safe alarm instead of silently dropping reminders.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            true
        }.onFailure {
            Log.w(TAG, "Cannot schedule reminder alarm", it)
        }.getOrDefault(false)
    }

    fun cancel(context: Context, pendingIntent: PendingIntent) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
    }
}
