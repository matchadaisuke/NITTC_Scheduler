package jp.linkserver.nittcsc.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlanReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L)
        if (planId > 0L) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    PlanReminderWorker.deliverAlarmNotification(appContext, planId)
                } catch (error: Throwable) {
                    ReminderDebug.warn("plan alarm direct delivery failed planId=$planId", error)
                } finally {
                    PlanReminderWorker.enqueueNow(appContext, planId)
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_PLAN_ID = "plan_id"
    }
}
