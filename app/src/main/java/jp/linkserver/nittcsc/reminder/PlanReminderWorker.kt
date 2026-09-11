package jp.linkserver.nittcsc.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.linkserver.nittcsc.MainActivity
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.PlanEntity
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class PlanReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, 0L)
        if (planId <= 0L) return Result.success()

        deliverNotification(applicationContext, planId, source = "worker")
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "plan_reminders"
        private const val KEY_PLAN_ID = "plan_id"

        private fun uniqueWorkName(planId: Long): String = "plan_reminder_$planId"

        fun cancel(context: Context, planId: Long) {
            val appContext = context.applicationContext
            WorkManager.getInstance(appContext)
                .cancelUniqueWork(uniqueWorkName(planId))
            ExactReminderAlarmScheduler.cancel(appContext, alarmPendingIntent(appContext, planId))
            NotificationManagerCompat.from(appContext)
                .cancel(planReminderNotificationId(planId))
        }

        fun syncPlanReminder(context: Context, plan: PlanEntity) {
            if (!plan.reminderEnabled || plan.isCompleted || plan.reminderDate == null || plan.id <= 0L) {
                cancel(context, plan.id)
                return
            }

            val reminderAt = LocalDateTime.of(
                plan.reminderDate,
                java.time.LocalTime.of(plan.reminderHour, plan.reminderMinute)
            )
            val appContext = context.applicationContext
            val exactAlarmScheduled = ExactReminderAlarmScheduler.schedule(
                appContext,
                reminderAt,
                alarmPendingIntent(appContext, plan.id)
            )
            val fallbackAt = if (exactAlarmScheduled) reminderAt.plusMinutes(1) else reminderAt
            val delayMillis = Duration.between(
                LocalDateTime.now(ZoneId.systemDefault()),
                fallbackAt
            ).toMillis()

            if (delayMillis <= 0L) {
                cancel(context, plan.id)
                return
            }

            val request = OneTimeWorkRequestBuilder<PlanReminderWorker>()
                .setInputData(Data.Builder().putLong(KEY_PLAN_ID, plan.id).build())
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(uniqueWorkName(plan.id), ExistingWorkPolicy.REPLACE, request)
        }

        suspend fun rescheduleAll(context: Context) {
            val plans = AppDatabase.getInstance(context.applicationContext)
                .schedulerDao()
                .getPlansOnce()
            plans.forEach { syncPlanReminder(context, it) }
        }

        fun enqueueNow(context: Context, planId: Long) {
            val request = OneTimeWorkRequestBuilder<PlanReminderWorker>()
                .setInputData(Data.Builder().putLong(KEY_PLAN_ID, planId).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueWorkName(planId), ExistingWorkPolicy.REPLACE, request)
        }

        suspend fun deliverAlarmNotification(context: Context, planId: Long): Boolean =
            deliverNotification(context.applicationContext, planId, source = "alarm_receiver")

        private suspend fun deliverNotification(
            context: Context,
            planId: Long,
            source: String
        ): Boolean {
            val plan = AppDatabase.getInstance(context).schedulerDao().getPlanById(planId)
                ?: return false.also {
                    ReminderDebug.log("plan delivery skipped source=$source planId=$planId reason=not_found")
                }
            if (!plan.reminderEnabled || plan.isCompleted || plan.reminderDate == null) {
                ReminderDebug.log("plan delivery skipped source=$source planId=$planId reason=ineligible")
                return false
            }

            createNotificationChannel(context)
            val notificationId = planReminderNotificationId(plan.id)
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                plan.id.toInt(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.plan_reminder_notification_title))
                .setContentText(context.getString(R.string.plan_reminder_notification_body, plan.title))
                .setStyle(NotificationCompat.BigTextStyle().bigText(buildReminderBody(context, plan)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent)
                .build()
            val posted = NotificationManagerCompat.from(context)
                .notifyIfAllowed(context, notificationId, notification)
            if (posted) CloudFileSyncManager.requestSync(context)
            ReminderDebug.log("plan delivery finished source=$source planId=$planId posted=$posted")
            return posted
        }

        private fun buildReminderBody(context: Context, plan: PlanEntity): String {
            val dueText = context.getString(
                R.string.task_reminder_due_summary,
                plan.dueDate.toString(),
                String.format("%02d:%02d", plan.dueHour, plan.dueMinute)
            )
            return buildString {
                append(context.getString(R.string.plan_reminder_notification_body, plan.title))
                if (plan.subject.isNotBlank()) {
                    append("\n")
                    append(context.getString(R.string.task_reminder_subject_summary, plan.subject))
                }
                append("\n")
                append(dueText)
            }
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.plan_reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.plan_reminder_channel_desc)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }
            )
        }

        private fun alarmPendingIntent(context: Context, planId: Long): PendingIntent {
            val intent = Intent(context, PlanReminderAlarmReceiver::class.java).apply {
                data = Uri.parse("nittcsc://plan-reminder/$planId")
                putExtra(PlanReminderAlarmReceiver.EXTRA_PLAN_ID, planId)
            }
            return PendingIntent.getBroadcast(
                context,
                planReminderNotificationId(planId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun planReminderNotificationId(planId: Long): Int {
            return (planId % Int.MAX_VALUE).toInt() + 20_000
        }
    }
}
