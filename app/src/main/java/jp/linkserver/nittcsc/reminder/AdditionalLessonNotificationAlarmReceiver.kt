package jp.linkserver.nittcsc.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class AdditionalLessonNotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_DATE).orEmpty()) }.getOrNull()
            ?: return
        val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        val triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY).orEmpty()
        if (slotIndex < 0 || triggerKey.isBlank()) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AdditionalLessonNotificationWorker.deliverAlarmNotification(
                    appContext,
                    date,
                    slotIndex,
                    triggerKey
                )
            } catch (error: Throwable) {
                ReminderDebug.warn(
                    "additional lesson alarm direct delivery failed date=$date " +
                        "slotIndex=$slotIndex triggerKey=$triggerKey",
                    error
                )
            } finally {
                AdditionalLessonNotificationWorker.enqueueNow(
                    appContext,
                    date,
                    slotIndex,
                    triggerKey
                )
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_SLOT_INDEX = "slot_index"
        const val EXTRA_TRIGGER_KEY = "trigger_key"
    }
}
