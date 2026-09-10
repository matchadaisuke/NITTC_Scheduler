package jp.linkserver.nittcsc.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import jp.linkserver.nittcsc.MainActivity
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.TaskEntity
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

internal object TaskReminderNotifier {
    private const val CHANNEL_ID = "task_reminders"

    suspend fun notifyTask(
        context: Context,
        taskId: Long,
        source: String,
        expectedReminderAtMillis: Long? = null,
        expectedUpdatedAtMillis: Long? = null
    ): TaskReminderNotificationResult {
        val appContext = context.applicationContext
        ReminderDebug.log("task load started source=$source taskId=$taskId")
        val task = AppDatabase.getInstance(appContext).schedulerDao().getTaskById(taskId)
        if (task == null) {
            ReminderDebug.log("task loaded source=$source taskId=$taskId found=false")
            return TaskReminderNotificationResult.TASK_NOT_FOUND
        }

        ReminderDebug.log(
            "task loaded source=$source taskId=${task.id} " +
                "reminderEnabled=${task.reminderEnabled} isCompleted=${task.isCompleted} " +
                "reminderDate=${task.reminderDate} reminderTime=" +
                String.format(Locale.US, "%02d:%02d", task.reminderHour, task.reminderMinute)
        )
        if (!task.reminderEnabled || task.isCompleted || task.reminderDate == null) {
            ReminderDebug.log("notification skipped source=$source taskId=${task.id} reason=task_conditions")
            return TaskReminderNotificationResult.TASK_INELIGIBLE
        }

        val currentReminderAtMillis = LocalDateTime.of(
            task.reminderDate,
            LocalTime.of(task.reminderHour, task.reminderMinute)
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (!reminderScheduleMatches(
                expectedReminderAtMillis,
                expectedUpdatedAtMillis,
                currentReminderAtMillis,
                task.updatedAt
            )
        ) {
            ReminderDebug.log(
                "notification skipped source=$source taskId=${task.id} reason=stale_schedule " +
                    "expectedReminderAtMillis=$expectedReminderAtMillis " +
                    "currentReminderAtMillis=$currentReminderAtMillis " +
                    "expectedUpdatedAtMillis=$expectedUpdatedAtMillis currentUpdatedAtMillis=${task.updatedAt}"
            )
            return TaskReminderNotificationResult.STALE_SCHEDULE
        }

        initializeNotificationChannel(appContext)
        val notificationsAllowed = appContext.canPostAppNotifications()
        val channelEnabled = isNotificationChannelEnabled(appContext)
        ReminderDebug.log(
            "notification conditions source=$source taskId=${task.id} " +
                "notificationsAllowed=$notificationsAllowed channelEnabled=$channelEnabled"
        )
        if (!notificationsAllowed || !channelEnabled) {
            ReminderDebug.log("notification skipped source=$source taskId=${task.id} reason=notification_settings")
            return TaskReminderNotificationResult.NOTIFICATIONS_BLOCKED
        }

        val notificationId = notificationId(task.id)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.task_reminder_notification_title))
            .setContentText(appContext.getString(R.string.task_reminder_notification_body, task.title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildReminderBody(appContext, task)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(appContext, task.id))
            .build()

        val deliveryToken = reminderDeliveryToken(
            task.id,
            currentReminderAtMillis,
            task.updatedAt
        )
        return TaskReminderDeliveryLedger.withLock {
            if (TaskReminderDeliveryLedger.wasDelivered(appContext, task.id, deliveryToken)) {
                ReminderDebug.log(
                    "notification skipped source=$source taskId=${task.id} reason=already_delivered"
                )
                return@withLock TaskReminderNotificationResult.ALREADY_DELIVERED
            }

            ReminderDebug.log(
                "notification notify called source=$source taskId=${task.id} notificationId=$notificationId"
            )
            val posted = NotificationManagerCompat.from(appContext)
                .notifyIfAllowed(appContext, notificationId, notification)
            if (!posted) {
                ReminderDebug.log(
                    "notification notify rejected source=$source taskId=${task.id} " +
                        "notificationId=$notificationId"
                )
                return@withLock TaskReminderNotificationResult.NOTIFICATIONS_BLOCKED
            }
            val ledgerCommitted = TaskReminderDeliveryLedger.markDelivered(
                appContext,
                task.id,
                deliveryToken
            )
            if (!ledgerCommitted) {
                ReminderDebug.warn(
                    "notification delivery ledger commit failed source=$source taskId=${task.id}"
                )
            }
            ReminderDebug.log(
                "notification notify completed source=$source taskId=${task.id} " +
                    "notificationId=$notificationId ledgerCommitted=$ledgerCommitted"
            )
            CloudFileSyncManager.requestSync(appContext)
            TaskReminderNotificationResult.POSTED
        }
    }

    fun notificationId(taskId: Long): Int = (taskId % Int.MAX_VALUE).toInt()

    fun clearDeliveryRecord(context: Context, taskId: Long) {
        TaskReminderDeliveryLedger.clear(context.applicationContext, taskId)
    }

    fun initializeNotificationChannel(context: Context) {
        createNotificationChannel(context.applicationContext)
    }

    fun isNotificationChannelEnabled(context: Context): Boolean {
        return context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
            ?.importance
            ?.let { it != NotificationManager.IMPORTANCE_NONE }
            ?: false
    }

    private fun openAppPendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildReminderBody(context: Context, task: TaskEntity): String {
        val dueText = context.getString(
            R.string.task_reminder_due_summary,
            task.dueDate.toString(),
            String.format(Locale.US, "%02d:%02d", task.dueHour, task.dueMinute)
        )
        return buildString {
            append(context.getString(R.string.task_reminder_notification_body, task.title))
            if (task.subject.isNotBlank()) {
                append("\n")
                append(context.getString(R.string.task_reminder_subject_summary, task.subject))
            }
            append("\n")
            append(dueText)
        }
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.task_reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.task_reminder_channel_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
