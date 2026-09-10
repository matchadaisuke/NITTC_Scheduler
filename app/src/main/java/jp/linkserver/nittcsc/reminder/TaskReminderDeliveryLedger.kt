package jp.linkserver.nittcsc.reminder

import android.content.Context

internal object TaskReminderDeliveryLedger {
    private const val PREFERENCES = "task_reminder_delivery_ledger"
    private val lock = Any()

    fun <T> withLock(block: () -> T): T = synchronized(lock, block)

    fun wasDelivered(context: Context, taskId: Long, token: String): Boolean =
        preferences(context).getString(taskId.toString(), null) == token

    fun markDelivered(context: Context, taskId: Long, token: String): Boolean =
        preferences(context).edit().putString(taskId.toString(), token).commit()

    fun clear(context: Context, taskId: Long) {
        preferences(context).edit().remove(taskId.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
