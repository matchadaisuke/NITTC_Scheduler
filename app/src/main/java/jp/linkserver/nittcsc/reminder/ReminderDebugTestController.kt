package jp.linkserver.nittcsc.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.linkserver.nittcsc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime

object ReminderDebugTestController {
    private const val CHANNEL_ID = "reminder_debug"
    private const val NOTIFICATION_ID_IMMEDIATE = 91_001
    private const val NOTIFICATION_ID_ALARM = 91_002
    private const val ALARM_REQUEST_CODE = 91_003
    private const val ACTION_DEBUG_ALARM = "jp.linkserver.nittcsc.action.REMINDER_DEBUG_ALARM"

    fun sendImmediateNotification(context: Context): Boolean {
        ReminderDebugTestStateStore.reset(context, "notification")
        return postNotification(context.applicationContext, "immediate", NOTIFICATION_ID_IMMEDIATE)
    }

    fun scheduleAlarmTest(context: Context, delaySeconds: Int): Boolean {
        val safeDelaySeconds = delaySeconds.coerceIn(1, 300)
        ReminderDebugTestStateStore.reset(context, "alarm")
        val triggerAt = LocalDateTime.now().plusSeconds(safeDelaySeconds.toLong())
        ReminderDebug.log(
            "debug alarm schedule called delaySeconds=$safeDelaySeconds triggerAt=$triggerAt"
        )
        val scheduled = ExactReminderAlarmScheduler.schedule(
            context.applicationContext,
            triggerAt,
            debugAlarmPendingIntent(context.applicationContext),
            debugLabel = "debug_alarm"
        )
        ReminderDebug.log(
            "debug alarm scheduled=$scheduled delaySeconds=$safeDelaySeconds triggerAt=$triggerAt"
        )
        if (scheduled) {
            ReminderDebugTestStateStore.mark(
                context,
                ReminderDebugTestStage.ALARM_SCHEDULED,
                "triggerAt=$triggerAt"
            )
        } else {
            ReminderDebugTestStateStore.recordFailure(context, "alarm_schedule_failed")
        }
        return scheduled
    }

    fun readTestSnapshot(context: Context): ReminderDebugTestSnapshot =
        ReminderDebugTestStateStore.read(context.applicationContext)

    fun readEnvironment(context: Context): ReminderDebugEnvironment {
        val appContext = context.applicationContext
        val exactAlarmAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        return ReminderDebugEnvironment(
            notificationsAllowed = appContext.canPostAppNotifications(),
            taskChannelEnabled = TaskReminderNotifier.isNotificationChannelEnabled(appContext),
            exactAlarmAvailable = exactAlarmAvailable
        )
    }

    internal fun enqueueDebugWorker(context: Context) =
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ReminderDebugWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderDebugWorker>().build()
        )

    internal fun postAlarmNotification(context: Context): Boolean =
        postNotification(context.applicationContext, "debug_worker", NOTIFICATION_ID_ALARM)

    private fun debugAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderDebugAlarmReceiver::class.java).apply {
            action = ACTION_DEBUG_ALARM
            data = Uri.parse("nittcsc://reminder-debug/alarm")
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postNotification(context: Context, source: String, notificationId: Int): Boolean {
        ReminderDebug.log("debug notification generation source=$source notificationId=$notificationId")
        ReminderDebugTestStateStore.mark(
            context,
            ReminderDebugTestStage.NOTIFICATION_GENERATED,
            "source=$source"
        )
        createChannel(context)
        val notificationsAllowed = context.canPostAppNotifications()
        val channelEnabled = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        ReminderDebug.log(
            "debug notification conditions source=$source notificationsAllowed=$notificationsAllowed " +
                "channelEnabled=$channelEnabled"
        )
        if (!notificationsAllowed || !channelEnabled) {
            ReminderDebugTestStateStore.recordFailure(
                context,
                "notificationsAllowed=$notificationsAllowed channelEnabled=$channelEnabled"
            )
            return false
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.reminder_debug_notification_title))
            .setContentText(context.getString(R.string.reminder_debug_notification_body, source))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        ReminderDebug.log("debug notification notify called source=$source notificationId=$notificationId")
        val posted = NotificationManagerCompat.from(context)
            .notifyIfAllowed(context, notificationId, notification)
        ReminderDebug.log(
            "debug notification notify completed source=$source notificationId=$notificationId posted=$posted"
        )
        if (posted) {
            ReminderDebugTestStateStore.mark(
                context,
                ReminderDebugTestStage.NOTIFICATION_POSTED,
                "source=$source notificationId=$notificationId"
            )
        } else {
            ReminderDebugTestStateStore.recordFailure(context, "notification_post_failed source=$source")
        }
        return posted
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_debug_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.reminder_debug_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

data class ReminderDebugEnvironment(
    val notificationsAllowed: Boolean,
    val taskChannelEnabled: Boolean,
    val exactAlarmAvailable: Boolean
)

class ReminderDebugAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderDebug.log("debug alarm receiver fired action=${intent.action}")
        ReminderDebugTestStateStore.mark(
            context,
            ReminderDebugTestStage.RECEIVER_FIRED,
            "action=${intent.action}"
        )
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val operation = ReminderDebugTestController.enqueueDebugWorker(context.applicationContext)
                operation.result.get()
                ReminderDebug.log("debug worker enqueue persisted")
                ReminderDebugTestStateStore.mark(
                    context,
                    ReminderDebugTestStage.WORKER_ENQUEUED
                )
            } catch (error: Throwable) {
                ReminderDebug.warn("debug alarm receiver failed", error)
                ReminderDebugTestStateStore.recordFailure(
                    context,
                    "receiver_failed=${error.javaClass.simpleName}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class ReminderDebugWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        ReminderDebug.log("debug worker started runAttemptCount=$runAttemptCount")
        ReminderDebugTestStateStore.mark(
            applicationContext,
            ReminderDebugTestStage.WORKER_STARTED,
            "runAttemptCount=$runAttemptCount"
        )
        return runCatching {
            val posted = ReminderDebugTestController.postAlarmNotification(applicationContext)
            ReminderDebug.log("debug worker finished notificationPosted=$posted")
            if (posted) Result.success() else Result.failure()
        }.getOrElse { error ->
            ReminderDebug.warn("debug worker failed", error)
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "reminder_debug_alarm_worker"
    }
}
