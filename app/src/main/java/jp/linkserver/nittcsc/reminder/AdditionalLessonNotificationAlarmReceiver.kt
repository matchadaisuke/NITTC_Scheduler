package jp.linkserver.nittcsc.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class AdditionalLessonNotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_DATE).orEmpty()) }.getOrNull()
            ?: return
        val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        val triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY).orEmpty()
        if (slotIndex < 0 || triggerKey.isBlank()) return
        AdditionalLessonNotificationWorker.enqueueNow(context, date, slotIndex, triggerKey)
    }

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_SLOT_INDEX = "slot_index"
        const val EXTRA_TRIGGER_KEY = "trigger_key"
    }
}
