package jp.linkserver.nittcsc.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.LessonEntity
import jp.linkserver.nittcsc.data.LessonMode
import jp.linkserver.nittcsc.data.LessonNotificationCustomization
import jp.linkserver.nittcsc.data.LessonNotificationTrigger
import jp.linkserver.nittcsc.data.encodeLessonNotificationCustomization
import jp.linkserver.nittcsc.logic.academicYearForDate
import jp.linkserver.nittcsc.logic.timetableTermForDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** ADB-only setup for an end-to-end four-trigger notification test. */
class FourNotificationTestSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = LocalDateTime.now()
                val lessonStart = now.plusMinutes(4).withSecond(0).withNano(0)
                val date = lessonStart.toLocalDate()
                val dao = AppDatabase.getInstance(appContext).schedulerDao()
                val current = requireNotNull(dao.getSettings())
                val config = LessonNotificationCustomization(
                    startTriggers = listOf(
                        LessonNotificationTrigger(enabled = true, minutesBefore = 2),
                        LessonNotificationTrigger(enabled = true, minutesBefore = 1)
                    ),
                    endTriggers = listOf(
                        LessonNotificationTrigger(enabled = true, minutesBefore = 2),
                        LessonNotificationTrigger(enabled = true, minutesBefore = 1)
                    ),
                    startTitleTemplate = "4通知テスト・開始{minutes}分前",
                    startBodyTemplate = "{subject} 開始通知 {minutes}分前（{start_time}開始）",
                    endTitleTemplate = "4通知テスト・終了{minutes}分前",
                    endBodyTemplate = "{subject} 終了通知 {minutes}分前（{end_time}終了）"
                )
                dao.upsertSettings(
                    current.copy(
                        termStart = minOf(current.termStart, date),
                        termEnd = maxOf(current.termEnd, date),
                        periodsPerDay = 1,
                        periodDurationMin = 4,
                        breakBetweenPeriodsMin = 0,
                        lunchBreakMin = 0,
                        lunchAfterPeriod = 1,
                        firstPeriodStartHour = lessonStart.hour,
                        firstPeriodStartMinute = lessonStart.minute,
                        lessonStartNotificationEnabled = true,
                        lessonStartNotificationMinutesBefore = 2,
                        lessonStartNotificationLiveUpdatesEnabled = false,
                        lessonNotificationConfigJson = encodeLessonNotificationCustomization(config)
                    )
                )
                val term = timetableTermForDate(date, current.enableSemesterTimetables)
                val existing = dao.getLesson(
                    academicYearForDate(date),
                    term,
                    date.dayOfWeek.value,
                    0
                )
                dao.upsertLesson(
                    LessonEntity(
                        id = existing?.id ?: 0,
                        academicYear = academicYearForDate(date),
                        timetableTerm = term,
                        dayOfWeek = date.dayOfWeek.value,
                        slotIndex = 0,
                        mode = LessonMode.WEEKLY,
                        weeklySubject = "ADB_4_TRIGGER_TEST",
                        weeklyTeacher = "Codex",
                        weeklyLocation = "実機",
                        aSubject = "",
                        aTeacher = "",
                        bSubject = "",
                        bTeacher = ""
                    )
                )
                NotificationRebuildCoordinator.rebuild(appContext, "adb_four_trigger_test")
                Log.i(
                    TAG,
                    "configured date=$date start=$lessonStart end=${lessonStart.plusMinutes(4)} " +
                        "start1=${lessonStart.minusMinutes(2)} start2=${lessonStart.minusMinutes(1)} " +
                        "end1=${lessonStart.plusMinutes(2)} end2=${lessonStart.plusMinutes(3)}"
                )
            } catch (error: Throwable) {
                Log.e(TAG, "setup failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FourNotificationTest"
    }
}
