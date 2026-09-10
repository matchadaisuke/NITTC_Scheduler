package jp.linkserver.nittcsc.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.TaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor

class TaskReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, 0L)
        val source = inputData.getString(KEY_SOURCE) ?: SOURCE_FALLBACK_WORKER
        val expectedReminderAtMillis = inputData.getLong(KEY_REMINDER_AT_MILLIS, 0L)
        val expectedUpdatedAtMillis = inputData.getLong(KEY_UPDATED_AT_MILLIS, 0L)
        ReminderDebug.log(
            "worker started source=$source taskId=$taskId runAttemptCount=$runAttemptCount " +
                "expectedReminderAtMillis=$expectedReminderAtMillis " +
                "expectedUpdatedAtMillis=$expectedUpdatedAtMillis"
        )
        if (taskId <= 0L) {
            ReminderDebug.log("worker finished source=$source taskId=$taskId reason=invalid_task_id")
            return Result.success()
        }

        return try {
            val notificationResult = TaskReminderNotifier.notifyTask(
                applicationContext,
                taskId,
                source,
                expectedReminderAtMillis.takeIf { it > 0L },
                expectedUpdatedAtMillis.takeIf { it > 0L }
            )
            if (source == SOURCE_RECOVERY_WORKER && shouldCancelFallbackAfter(notificationResult)) {
                cancelFallbackWork(applicationContext, taskId)
            }
            ReminderDebug.log(
                "worker finished source=$source taskId=$taskId result=$notificationResult"
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ReminderDebug.warn("worker failed source=$source taskId=$taskId", error)
            if (runAttemptCount < MAX_RETRY_COUNT) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_SOURCE = "source"
        private const val KEY_REMINDER_AT_MILLIS = "reminder_at_millis"
        private const val KEY_UPDATED_AT_MILLIS = "updated_at_millis"
        private const val SOURCE_FALLBACK_WORKER = "fallback_worker"
        private const val SOURCE_RECOVERY_WORKER = "receiver_recovery"
        private const val MAX_RETRY_COUNT = 2
        private const val ACTION_TASK_REMINDER = "jp.linkserver.nittcsc.action.TASK_REMINDER"
        private const val WORK_TAG = "task_reminder"
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
        private val schedulingMutex = Mutex()

        fun uniqueWorkName(taskId: Long): String = "task_reminder_$taskId"

        suspend fun schedule(context: Context, task: TaskEntity) {
            syncTaskReminder(context, task)
        }

        fun cancel(context: Context, taskId: Long) {
            if (taskId <= 0L) return
            val appContext = context.applicationContext
            cancelScheduledReminder(appContext, taskId)
            NotificationManagerCompat.from(appContext)
                .cancel(TaskReminderNotifier.notificationId(taskId))
            TaskReminderNotifier.clearDeliveryRecord(appContext, taskId)
            ReminderDebug.log("reminder cancelled taskId=$taskId includeNotification=true")
        }

        internal fun cancelFallbackWork(context: Context, taskId: Long) {
            if (taskId <= 0L) return
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(taskId))
            ReminderDebug.log("fallback work cancel requested taskId=$taskId")
        }

        suspend fun syncTaskReminder(context: Context, candidate: TaskEntity) {
            if (candidate.id <= 0L) {
                syncPersistedTaskReminder(context, candidate)
                return
            }
            schedulingMutex.withLock {
                val persistedTask = AppDatabase.getInstance(context.applicationContext)
                    .schedulerDao()
                    .getTaskById(candidate.id)
                if (persistedTask == null) {
                    cancelScheduledReminder(context.applicationContext, candidate.id)
                    ReminderDebug.log(
                        "schedule skipped taskId=${candidate.id} reason=task_not_found_after_save"
                    )
                    return@withLock
                }
                if (persistedTask.updatedAt != candidate.updatedAt || persistedTask != candidate) {
                    ReminderDebug.log(
                        "schedule input refreshed from database taskId=${candidate.id} " +
                            "candidateUpdatedAt=${candidate.updatedAt} " +
                            "persistedUpdatedAt=${persistedTask.updatedAt}"
                    )
                }
                syncPersistedTaskReminder(context, persistedTask)
            }
        }

        private fun syncPersistedTaskReminder(context: Context, task: TaskEntity) {
            ReminderDebug.log(
                "schedule called taskId=${task.id} reminderEnabled=${task.reminderEnabled} " +
                    "isCompleted=${task.isCompleted} reminderDate=${task.reminderDate} " +
                    "reminderTime=" +
                    String.format(Locale.US, "%02d:%02d", task.reminderHour, task.reminderMinute)
            )
            if (task.id <= 0L) {
                ReminderDebug.log("schedule skipped taskId=${task.id} reason=task_not_persisted")
                return
            }

            val appContext = context.applicationContext
            if (!task.reminderEnabled || task.isCompleted || task.reminderDate == null) {
                cancelScheduledReminder(appContext, task.id)
                ReminderDebug.log("schedule skipped taskId=${task.id} reason=task_conditions")
                return
            }

            val reminderAt = LocalDateTime.of(
                task.reminderDate,
                java.time.LocalTime.of(task.reminderHour, task.reminderMinute)
            )
            val reminderAtMillis = reminderAt.atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val nowMillis = System.currentTimeMillis()
            if (reminderAtMillis <= nowMillis) {
                if (isWithinLateDeliveryGrace(
                        reminderAtMillis,
                        nowMillis,
                        ReminderSchedulingPolicy.TASK_LATE_DELIVERY_GRACE_MILLIS
                    )
                ) {
                    // Remove every older generation before creating an immediate recovery job.
                    // The delivery token still protects against an alarm that was already dispatched.
                    cancelScheduledReminder(appContext, task.id)
                    ReminderDebug.log(
                        "late reminder recovery requested taskId=${task.id} " +
                            "reminderAtMillis=$reminderAtMillis nowMillis=$nowMillis"
                    )
                    enqueueRecovery(appContext, task.id, reminderAtMillis, task.updatedAt)
                } else {
                    cancelScheduledReminder(appContext, task.id)
                    ReminderDebug.log(
                        "schedule skipped taskId=${task.id} reason=past_grace_window " +
                            "reminderAtMillis=$reminderAtMillis nowMillis=$nowMillis"
                    )
                }
                return
            }
            val exactAlarmScheduled = ExactReminderAlarmScheduler.schedule(
                appContext,
                reminderAt,
                alarmPendingIntent(appContext, task.id, reminderAtMillis, task.updatedAt),
                debugLabel = "taskId=${task.id}"
            )
            ReminderDebug.log(
                "alarm scheduled taskId=${task.id} triggerAt=$reminderAt " +
                    "alarmScheduled=$exactAlarmScheduled"
            )

            // WorkManager is only a safety net. Exact alarms notify directly in the receiver.
            val fallbackAt = if (exactAlarmScheduled) reminderAt.plusMinutes(1) else reminderAt
            val delayMillis = Duration.between(
                LocalDateTime.now(ZoneId.systemDefault()),
                fallbackAt
            ).toMillis()
            if (delayMillis <= 0L) {
                cancelScheduledReminder(appContext, task.id)
                ReminderDebug.log(
                    "fallback work skipped taskId=${task.id} fallbackAt=$fallbackAt reason=past_trigger"
                )
                return
            }

            val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
                .setInputData(
                    workerInput(
                        task.id,
                        SOURCE_FALLBACK_WORKER,
                        reminderAtMillis,
                        task.updatedAt
                    )
                )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .addTag(taskWorkTag(task.id))
                .build()
            val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName(task.id),
                ExistingWorkPolicy.REPLACE,
                request
            )
            logEnqueueResult(operation, "fallback", task.id, request.id.toString())
            ReminderDebug.log(
                "fallback work enqueued taskId=${task.id} workId=${request.id} " +
                    "fallbackAt=$fallbackAt delayMillis=$delayMillis"
            )
        }

        suspend fun rescheduleAll(context: Context) {
            val tasks = AppDatabase.getInstance(context.applicationContext)
                .schedulerDao()
                .getTasksOnce()
            ReminderDebug.log("rescheduleAll started taskCount=${tasks.size}")
            tasks.forEach { syncTaskReminder(context, it) }
            ReminderDebug.log("rescheduleAll finished taskCount=${tasks.size}")
        }

        fun enqueueRecovery(
            context: Context,
            taskId: Long,
            expectedReminderAtMillis: Long?,
            expectedUpdatedAtMillis: Long?
        ) {
            if (taskId <= 0L) {
                ReminderDebug.log("recovery enqueue skipped taskId=$taskId reason=invalid_task_id")
                return
            }
            val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
                .setInputData(
                    workerInput(
                        taskId,
                        SOURCE_RECOVERY_WORKER,
                        expectedReminderAtMillis,
                        expectedUpdatedAtMillis
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WORK_TAG)
                .addTag(taskWorkTag(taskId))
                .build()
            val operation = WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    recoveryWorkName(taskId, expectedReminderAtMillis, expectedUpdatedAtMillis),
                    ExistingWorkPolicy.KEEP,
                    request
                )
            logEnqueueResult(operation, "recovery", taskId, request.id.toString())
            ReminderDebug.log("recovery worker enqueue requested taskId=$taskId workId=${request.id}")
        }

        private fun cancelScheduledReminder(context: Context, taskId: Long) {
            WorkManager.getInstance(context).cancelAllWorkByTag(taskWorkTag(taskId))
            ExactReminderAlarmScheduler.cancel(
                context,
                alarmPendingIntent(context, taskId),
                debugLabel = "taskId=$taskId"
            )
            ReminderDebug.log("scheduled reminder cancel requested taskId=$taskId")
        }

        private fun workerInput(
            taskId: Long,
            source: String,
            expectedReminderAtMillis: Long? = null,
            expectedUpdatedAtMillis: Long? = null
        ): Data = Data.Builder()
            .putLong(KEY_TASK_ID, taskId)
            .putString(KEY_SOURCE, source)
            .putLong(KEY_REMINDER_AT_MILLIS, expectedReminderAtMillis ?: 0L)
            .putLong(KEY_UPDATED_AT_MILLIS, expectedUpdatedAtMillis ?: 0L)
            .build()

        private fun alarmPendingIntent(
            context: Context,
            taskId: Long,
            reminderAtMillis: Long = 0L,
            updatedAtMillis: Long = 0L
        ): PendingIntent {
            val intent = Intent(context, TaskReminderAlarmReceiver::class.java).apply {
                action = ACTION_TASK_REMINDER
                data = Uri.parse("nittcsc://task-reminder/$taskId")
                putExtra(TaskReminderAlarmReceiver.EXTRA_TASK_ID, taskId)
                putExtra(TaskReminderAlarmReceiver.EXTRA_REMINDER_AT_MILLIS, reminderAtMillis)
                putExtra(TaskReminderAlarmReceiver.EXTRA_UPDATED_AT_MILLIS, updatedAtMillis)
            }
            return PendingIntent.getBroadcast(
                context,
                TaskReminderNotifier.notificationId(taskId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun taskWorkTag(taskId: Long): String = "task_reminder_task_$taskId"

        private fun recoveryWorkName(
            taskId: Long,
            reminderAtMillis: Long?,
            updatedAtMillis: Long?
        ): String = "task_reminder_recovery_${taskId}_${reminderAtMillis ?: 0L}_${updatedAtMillis ?: 0L}"

        private fun logEnqueueResult(
            operation: Operation,
            kind: String,
            taskId: Long,
            workId: String
        ) {
            operation.result.addListener(
                {
                    runCatching { operation.result.get() }
                        .onSuccess {
                            ReminderDebug.log(
                                "$kind work enqueue persisted taskId=$taskId workId=$workId"
                            )
                        }
                        .onFailure { error ->
                            ReminderDebug.warn(
                                "$kind work enqueue failed taskId=$taskId workId=$workId",
                                error
                            )
                        }
                },
                DIRECT_EXECUTOR
            )
        }
    }
}
