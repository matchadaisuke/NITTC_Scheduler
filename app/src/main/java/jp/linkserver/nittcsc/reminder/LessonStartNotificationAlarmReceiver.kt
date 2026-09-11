package jp.linkserver.nittcsc.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class LessonStartNotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = runCatching {
            LocalDate.parse(intent.getStringExtra(EXTRA_DATE).orEmpty())
        }.getOrNull() ?: return
        val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        if (slotIndex < 0) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // AlarmManager is the time-critical path. Post once from the receiver so
                // expedited-work quota or JobScheduler latency cannot make the reminder
                // miss the worker's late-delivery window. The worker remains responsible
                // for Live Updates and acts as a second delivery attempt.
                LessonStartNotificationWorker.deliverAlarmNotification(
                    appContext,
                    date,
                    slotIndex
                )
            } catch (error: Throwable) {
                ReminderDebug.warn(
                    "lesson alarm direct delivery failed date=$date slotIndex=$slotIndex",
                    error
                )
            } finally {
                LessonStartNotificationWorker.enqueueNow(appContext, date, slotIndex)
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_SLOT_INDEX = "slot_index"
    }
}
