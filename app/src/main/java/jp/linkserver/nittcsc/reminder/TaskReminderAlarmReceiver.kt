package jp.linkserver.nittcsc.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val expectedReminderAtMillis = intent.getLongExtra(EXTRA_REMINDER_AT_MILLIS, 0L)
        val expectedUpdatedAtMillis = intent.getLongExtra(EXTRA_UPDATED_AT_MILLIS, 0L)
        ReminderDebug.log(
            "alarm receiver fired taskId=$taskId action=${intent.action} " +
                "expectedReminderAtMillis=$expectedReminderAtMillis " +
                "expectedUpdatedAtMillis=$expectedUpdatedAtMillis"
        )
        if (taskId <= 0L) {
            ReminderDebug.log("alarm receiver ignored taskId=$taskId reason=invalid_task_id")
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = TaskReminderNotifier.notifyTask(
                    appContext,
                    taskId,
                    SOURCE_ALARM_RECEIVER,
                    expectedReminderAtMillis.takeIf { it > 0L },
                    expectedUpdatedAtMillis.takeIf { it > 0L }
                )
                if (shouldCancelFallbackAfter(result)) {
                    TaskReminderWorker.cancelFallbackWork(appContext, taskId)
                }
                ReminderDebug.log("alarm receiver finished taskId=$taskId result=$result")
            } catch (error: Throwable) {
                ReminderDebug.warn("alarm receiver failed taskId=$taskId", error)
                TaskReminderWorker.enqueueRecovery(
                    appContext,
                    taskId,
                    expectedReminderAtMillis.takeIf { it > 0L },
                    expectedUpdatedAtMillis.takeIf { it > 0L }
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_REMINDER_AT_MILLIS = "reminder_at_millis"
        const val EXTRA_UPDATED_AT_MILLIS = "updated_at_millis"
        private const val SOURCE_ALARM_RECEIVER = "alarm_receiver"
    }
}
