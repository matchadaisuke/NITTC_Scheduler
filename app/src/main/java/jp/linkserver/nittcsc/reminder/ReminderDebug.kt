package jp.linkserver.nittcsc.reminder

import android.util.Log

internal object ReminderDebug {
    const val TAG = "ReminderDebug"

    fun log(message: String) {
        Log.i(TAG, "[ReminderDebug] $message")
    }

    fun warn(message: String, throwable: Throwable? = null) {
        Log.w(TAG, "[ReminderDebug] $message", throwable)
    }
}
