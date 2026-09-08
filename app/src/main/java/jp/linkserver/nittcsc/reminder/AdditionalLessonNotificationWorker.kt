package jp.linkserver.nittcsc.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.linkserver.nittcsc.MainActivity
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.ChangedLessonEntity
import jp.linkserver.nittcsc.data.DayType
import jp.linkserver.nittcsc.data.DayTypeEntity
import jp.linkserver.nittcsc.data.ExamLessonEntity
import jp.linkserver.nittcsc.data.HolidaySpecialLabel
import jp.linkserver.nittcsc.data.LessonEntity
import jp.linkserver.nittcsc.data.LessonMode
import jp.linkserver.nittcsc.data.LessonNotificationExclusionEntity
import jp.linkserver.nittcsc.data.LessonNotificationTemplateValues
import jp.linkserver.nittcsc.data.LessonNotificationTrigger
import jp.linkserver.nittcsc.data.ResolvedLesson
import jp.linkserver.nittcsc.data.SettingsEntity
import jp.linkserver.nittcsc.data.hasEnteredContent
import jp.linkserver.nittcsc.data.lessonKey
import jp.linkserver.nittcsc.data.lessonNotificationCustomization
import jp.linkserver.nittcsc.data.renderLessonNotificationTemplate
import jp.linkserver.nittcsc.logic.ClassSlot
import jp.linkserver.nittcsc.logic.JapaneseHolidayCalculator
import jp.linkserver.nittcsc.logic.LessonKey
import jp.linkserver.nittcsc.logic.academicYearForDate
import jp.linkserver.nittcsc.logic.applyChangedLesson
import jp.linkserver.nittcsc.logic.forTimetable
import jp.linkserver.nittcsc.logic.formatExamPeriodLabel
import jp.linkserver.nittcsc.logic.generateClassSlots
import jp.linkserver.nittcsc.logic.timetableTermForDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal enum class AdditionalNotificationMoment { START, END }

internal data class NextLessonSnapshot(
    val subject: String,
    val teacher: String,
    val location: String?,
    val period: String,
    val start: LocalTime
)

private data class AdditionalTriggerSpec(
    val key: String,
    val moment: AdditionalNotificationMoment,
    val index: Int,
    val ordinal: Int,
    val trigger: LessonNotificationTrigger
)

private data class ScheduledLesson(
    val slot: ClassSlot,
    val lesson: ResolvedLesson
)

private data class NotificationScheduleSnapshot(
    val settings: SettingsEntity,
    val dayTypes: Map<LocalDate, DayTypeEntity>,
    val lessons: Map<LessonKey, LessonEntity>,
    val changedLessons: Map<Pair<LocalDate, Int>, ChangedLessonEntity>,
    val cancelledLessons: Set<Pair<LocalDate, Int>>,
    val examLessonsByDate: Map<LocalDate, List<ExamLessonEntity>>,
    val examScheduleDates: Set<LocalDate>,
    val exclusions: List<LessonNotificationExclusionEntity>
)

class AdditionalLessonNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val date = runCatching { LocalDate.parse(inputData.getString(KEY_DATE).orEmpty()) }.getOrNull()
            ?: return Result.success()
        val slotIndex = inputData.getInt(KEY_SLOT_INDEX, -1)
        val triggerKey = inputData.getString(KEY_TRIGGER).orEmpty()
        if (slotIndex < 0 || triggerKey.isBlank()) return Result.success()

        val snapshot = loadSnapshot(applicationContext) ?: return Result.success()
        if (!snapshot.settings.lessonStartNotificationEnabled) return Result.success()
        val spec = triggerSpecs(snapshot.settings).firstOrNull { it.key == triggerKey }
            ?: return Result.success()
        if (!spec.trigger.enabled) return Result.success()
        val entry = entriesForDate(date, snapshot).firstOrNull { it.slot.index == slotIndex }
            ?: return Result.success()
        if (entry.lesson.subject.isBlank() || isExcludedAdditional(entry.lesson, snapshot.exclusions)) {
            return Result.success()
        }

        val target = LocalDateTime.of(
            date,
            if (spec.moment == AdditionalNotificationMoment.START) entry.slot.start else entry.slot.end
        )
        val notificationAt = target.minusMinutes(spec.trigger.minutesBefore.coerceIn(0, 360).toLong())
        val waitMillis = Duration.between(LocalDateTime.now(ZoneId.systemDefault()), notificationAt).toMillis()
        if (waitMillis > 0L) delay(waitMillis)
        if (Duration.between(LocalDateTime.now(ZoneId.systemDefault()), target).toMillis() < -60_000L) {
            return Result.success()
        }

        val next = findNextLesson(applicationContext, date, slotIndex)
        val values = templateValues(entry, spec.trigger.minutesBefore, next)
        val config = snapshot.settings.lessonNotificationCustomization()
        val titleTemplate = if (spec.moment == AdditionalNotificationMoment.START) {
            config.startTitleTemplate
        } else {
            config.endTitleTemplate
        }
        val bodyTemplate = if (spec.moment == AdditionalNotificationMoment.START) {
            config.startBodyTemplate
        } else {
            config.endBodyTemplate
        }
        val title = renderLessonNotificationTemplate(titleTemplate, values).ifBlank {
            if (spec.moment == AdditionalNotificationMoment.START) "授業開始前通知" else "授業終了前通知"
        }
        val body = renderLessonNotificationTemplate(bodyTemplate, values).ifBlank {
            if (spec.moment == AdditionalNotificationMoment.START) {
                "あと${spec.trigger.minutesBefore}分で${entry.lesson.subject}が始まります。"
            } else {
                "あと${spec.trigger.minutesBefore}分で${entry.lesson.subject}が終わります。"
            }
        }

        createNotificationChannel(applicationContext)
        val notificationId = notificationId(date, slotIndex, spec.ordinal)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Notification permission may be revoked independently.
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "lesson_start_notifications"
        private const val WORK_TAG = "additional_lesson_notifications"
        private const val KEY_DATE = "date"
        private const val KEY_SLOT_INDEX = "slot_index"
        private const val KEY_TRIGGER = "trigger"
        private const val HORIZON_DAYS = 30L
        private const val ALARM_PREFS = "additional_lesson_notification_alarms"
        private const val ALARM_KEYS = "scheduled_alarm_keys"
        private val rescheduleMutex = Mutex()

        suspend fun rescheduleAll(context: Context) {
            val appContext = context.applicationContext
            rescheduleMutex.withLock {
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(appContext).cancelAllWorkByTag(WORK_TAG).result.get()
                    cancelScheduledAlarms(appContext)
                }
                val snapshot = loadSnapshot(appContext) ?: return@withLock
                if (!snapshot.settings.lessonStartNotificationEnabled) return@withLock
                val specs = triggerSpecs(snapshot.settings).filter { it.trigger.enabled }
                if (specs.isEmpty()) return@withLock
                val today = LocalDate.now()
                val now = LocalDateTime.now(ZoneId.systemDefault())
                val endDate = minOf(snapshot.settings.termEnd, today.plusDays(HORIZON_DAYS))
                if (endDate.isBefore(today)) return@withLock

                for (date in dateRange(today, endDate)) {
                    entriesForDate(date, snapshot).forEach { entry ->
                        if (entry.lesson.subject.isBlank() || isExcludedAdditional(entry.lesson, snapshot.exclusions)) {
                            return@forEach
                        }
                        specs.forEach { spec ->
                            val target = LocalDateTime.of(
                                date,
                                if (spec.moment == AdditionalNotificationMoment.START) entry.slot.start else entry.slot.end
                            )
                            val notificationAt = target.minusMinutes(spec.trigger.minutesBefore.toLong())
                            if (!notificationAt.isAfter(now)) return@forEach
                            val exact = scheduleExactAlarm(
                                appContext,
                                date,
                                entry.slot.index,
                                spec.key,
                                spec.ordinal,
                                notificationAt
                            )
                            val fallback = if (exact) notificationAt.plusMinutes(1) else notificationAt
                            val delayMillis = Duration.between(now, fallback).toMillis().coerceAtLeast(0L)
                            val request = OneTimeWorkRequestBuilder<AdditionalLessonNotificationWorker>()
                                .setInputData(workData(date, entry.slot.index, spec.key))
                                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                                .addTag(WORK_TAG)
                                .build()
                            WorkManager.getInstance(appContext).enqueueUniqueWork(
                                uniqueWorkName(date, entry.slot.index, spec.key),
                                ExistingWorkPolicy.REPLACE,
                                request
                            )
                        }
                    }
                }
            }
        }

        fun enqueueNow(context: Context, date: LocalDate, slotIndex: Int, triggerKey: String) {
            val request = OneTimeWorkRequestBuilder<AdditionalLessonNotificationWorker>()
                .setInputData(workData(date, slotIndex, triggerKey))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueWorkName(date, slotIndex, triggerKey),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        suspend fun findNextLesson(
            context: Context,
            date: LocalDate,
            slotIndex: Int
        ): NextLessonSnapshot? {
            val snapshot = loadSnapshot(context.applicationContext) ?: return null
            val endDate = minOf(snapshot.settings.termEnd, date.plusDays(14))
            for (candidateDate in dateRange(date, endDate)) {
                for (entry in entriesForDate(candidateDate, snapshot)) {
                    if (candidateDate == date && entry.slot.index <= slotIndex) continue
                    if (entry.lesson.subject.isBlank()) continue
                    return NextLessonSnapshot(
                        subject = entry.lesson.subject,
                        teacher = entry.lesson.teacher,
                        location = entry.lesson.location,
                        period = entry.slot.label,
                        start = entry.slot.start
                    )
                }
            }
            return null
        }

        private fun triggerSpecs(settings: SettingsEntity): List<AdditionalTriggerSpec> {
            val config = settings.lessonNotificationCustomization()
            return listOfNotNull(
                config.startTriggers.getOrNull(1)?.let {
                    AdditionalTriggerSpec("start2", AdditionalNotificationMoment.START, 1, 1, it)
                },
                config.endTriggers.getOrNull(0)?.let {
                    AdditionalTriggerSpec("end1", AdditionalNotificationMoment.END, 0, 2, it)
                },
                config.endTriggers.getOrNull(1)?.let {
                    AdditionalTriggerSpec("end2", AdditionalNotificationMoment.END, 1, 3, it)
                }
            )
        }

        private fun workData(date: LocalDate, slotIndex: Int, triggerKey: String): Data =
            Data.Builder()
                .putString(KEY_DATE, date.toString())
                .putInt(KEY_SLOT_INDEX, slotIndex)
                .putString(KEY_TRIGGER, triggerKey)
                .build()

        private fun scheduleExactAlarm(
            context: Context,
            date: LocalDate,
            slotIndex: Int,
            triggerKey: String,
            ordinal: Int,
            notificationAt: LocalDateTime
        ): Boolean {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) return false
            val triggerAtMillis = notificationAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (triggerAtMillis <= System.currentTimeMillis()) return false
            return runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    alarmPendingIntent(context, date, slotIndex, triggerKey, ordinal)
                )
                val preferences = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
                val keys = preferences.getStringSet(ALARM_KEYS, emptySet()).orEmpty().toMutableSet()
                keys += "$date|$slotIndex|$triggerKey|$ordinal"
                preferences.edit().putStringSet(ALARM_KEYS, keys).apply()
                true
            }.getOrDefault(false)
        }

        private fun cancelScheduledAlarms(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val preferences = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
            preferences.getStringSet(ALARM_KEYS, emptySet()).orEmpty().forEach { key ->
                val parts = key.split('|')
                val date = parts.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                val slotIndex = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val triggerKey = parts.getOrNull(2) ?: return@forEach
                val ordinal = parts.getOrNull(3)?.toIntOrNull() ?: 1
                alarmManager.cancel(alarmPendingIntent(context, date, slotIndex, triggerKey, ordinal))
            }
            preferences.edit().remove(ALARM_KEYS).apply()
        }

        private fun alarmPendingIntent(
            context: Context,
            date: LocalDate,
            slotIndex: Int,
            triggerKey: String,
            ordinal: Int
        ): PendingIntent {
            val intent = Intent(context, AdditionalLessonNotificationAlarmReceiver::class.java).apply {
                data = Uri.parse("nittcsc://lesson-custom-notification/$date/$slotIndex/$triggerKey")
                putExtra(AdditionalLessonNotificationAlarmReceiver.EXTRA_DATE, date.toString())
                putExtra(AdditionalLessonNotificationAlarmReceiver.EXTRA_SLOT_INDEX, slotIndex)
                putExtra(AdditionalLessonNotificationAlarmReceiver.EXTRA_TRIGGER_KEY, triggerKey)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId(date, slotIndex, ordinal),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun uniqueWorkName(date: LocalDate, slotIndex: Int, triggerKey: String) =
            "additional_lesson_notification_${date}_${slotIndex}_$triggerKey"

        private fun notificationId(date: LocalDate, slotIndex: Int, ordinal: Int): Int =
            (150_000 + (date.toEpochDay() % 10_000).toInt() * 50 + slotIndex * 4 + ordinal)
                .coerceAtLeast(150_000)
    }
}

private suspend fun loadSnapshot(context: Context): NotificationScheduleSnapshot? {
    val dao = AppDatabase.getInstance(context).schedulerDao()
    val settings = dao.getSettings() ?: return null
    val dayTypes = dao.getDayTypesOnce().associateBy { it.date }
    val lessons = dao.getLessonsOnce().map { it.forTimetable(settings.enableAbTimetable) }.associateBy { it.lessonKey() }
    val changed = dao.getChangedLessonsOnce().associateBy { it.date to it.slotIndex }
    val cancelled = dao.getCancelledLessonsOnce().mapTo(mutableSetOf()) { it.date to it.slotIndex }
    val examLessonsByDate = dao.getExamLessonsOnce().groupBy { it.date }
    val examScheduleDates = dao.getExamDaySchedulesOnce()
        .map { it.date }
        .filterTo(mutableSetOf()) { date ->
            settings.enableExamTimetable && examLessonsByDate[date].orEmpty().any { it.hasEnteredContent() } &&
                when (dayTypes[date]?.holidaySpecialLabel) {
                    HolidaySpecialLabel.MIDTERM, HolidaySpecialLabel.FINAL -> true
                    else -> false
                }
        }
    return NotificationScheduleSnapshot(
        settings = settings,
        dayTypes = dayTypes,
        lessons = lessons,
        changedLessons = changed,
        cancelledLessons = cancelled,
        examLessonsByDate = examLessonsByDate,
        examScheduleDates = examScheduleDates,
        exclusions = dao.getLessonNotificationExclusionsOnce()
    )
}

private fun entriesForDate(
    date: LocalDate,
    snapshot: NotificationScheduleSnapshot
): List<ScheduledLesson> {
    if (date in snapshot.examScheduleDates) {
        return snapshot.examLessonsByDate[date].orEmpty()
            .sortedBy { it.slotIndex }
            .mapNotNull { exam ->
                if (exam.subject.isBlank()) return@mapNotNull null
                ScheduledLesson(
                    slot = ClassSlot(
                        index = exam.slotIndex,
                        label = formatExamPeriodLabel(exam.slotIndex, snapshot.settings.periodLabelStyle),
                        start = LocalTime.of(exam.startHour, exam.startMinute),
                        end = LocalTime.of(exam.endHour, exam.endMinute)
                    ),
                    lesson = ResolvedLesson(exam.subject, exam.teacher, exam.location.takeIf { it.isNotBlank() })
                )
            }
    }
    if (date.dayOfWeek == DayOfWeek.SUNDAY) return emptyList()
    val dayTypeEntity = snapshot.dayTypes[date]
    val dayType = dayTypeEntity?.dayType ?: defaultDayTypeAdditional(date)
    if (dayType == DayType.HOLIDAY) return emptyList()
    val lessonDay = dayTypeEntity?.overrideLessonDayOfWeek ?: date.dayOfWeek.value
    val lessonDayType = dayTypeEntity?.overrideLessonDayType ?: dayType
    val term = timetableTermForDate(date, snapshot.settings.enableSemesterTimetables)
    return snapshot.settings.classSlotsAdditional().mapNotNull { slot ->
        if ((date to slot.index) in snapshot.cancelledLessons) return@mapNotNull null
        val base = snapshot.lessons[
            LessonKey(academicYearForDate(date), term, lessonDay, slot.index)
        ]?.let { resolveLessonAdditional(lessonDayType, it) }
        val resolved = applyChangedLesson(base, snapshot.changedLessons[date to slot.index]) ?: return@mapNotNull null
        ScheduledLesson(slot, resolved)
    }
}

private fun resolveLessonAdditional(dayType: DayType, lesson: LessonEntity): ResolvedLesson? =
    when (lesson.mode) {
        LessonMode.WEEKLY -> if (lesson.weeklySubject.isBlank()) null else
            ResolvedLesson(lesson.weeklySubject, lesson.weeklyTeacher, lesson.weeklyLocation)
        LessonMode.ALTERNATING -> when (dayType) {
            DayType.A -> if (lesson.aSubject.isBlank()) null else ResolvedLesson(lesson.aSubject, lesson.aTeacher, lesson.aLocation)
            DayType.B -> if (lesson.bSubject.isBlank()) null else ResolvedLesson(lesson.bSubject, lesson.bTeacher, lesson.bLocation)
            DayType.HOLIDAY -> null
        }
    }

private fun defaultDayTypeAdditional(date: LocalDate): DayType =
    if (date.dayOfWeek == DayOfWeek.SUNDAY || JapaneseHolidayCalculator.isHoliday(date)) DayType.HOLIDAY else DayType.A

private fun SettingsEntity.classSlotsAdditional(): List<ClassSlot> = generateClassSlots(
    periodsPerDay = periodsPerDay,
    periodDurationMin = periodDurationMin,
    breakBetweenPeriodsMin = breakBetweenPeriodsMin,
    lunchBreakMin = lunchBreakMin,
    firstPeriodStartHour = firstPeriodStartHour,
    firstPeriodStartMinute = firstPeriodStartMinute,
    periodLabelStyle = periodLabelStyle,
    lunchAfterPeriod = lunchAfterPeriod
)

private fun templateValues(
    entry: ScheduledLesson,
    minutes: Int,
    next: NextLessonSnapshot?
): LessonNotificationTemplateValues = LessonNotificationTemplateValues(
    subject = entry.lesson.subject,
    teacher = entry.lesson.teacher,
    location = entry.lesson.location.orEmpty(),
    period = entry.slot.label,
    startTime = entry.slot.start.toString(),
    endTime = entry.slot.end.toString(),
    minutes = minutes,
    nextSubject = next?.subject ?: "なし",
    nextTeacher = next?.teacher.orEmpty(),
    nextLocation = next?.location.orEmpty(),
    nextPeriod = next?.period.orEmpty(),
    nextStartTime = next?.start?.toString().orEmpty()
)

private fun dateRange(start: LocalDate, end: LocalDate): Sequence<LocalDate> =
    generateSequence(start) { current -> current.plusDays(1).takeIf { !it.isAfter(end) } }

private fun isExcludedAdditional(
    lesson: ResolvedLesson,
    exclusions: List<LessonNotificationExclusionEntity>
): Boolean = exclusions.any { exclusion ->
    if (!lesson.subject.trim().equals(exclusion.subject.trim(), ignoreCase = true)) return@any false
    if (!exclusion.matchTeacher) return@any true
    val expected = exclusion.teacher?.trim().orEmpty()
    if (expected.isBlank()) return@any true
    lesson.teacher
        .replace('，', '、')
        .replace(',', '、')
        .replace('　', ' ')
        .split('、', ' ')
        .map(String::trim)
        .any { it.equals(expected, ignoreCase = true) }
}

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID_SHARED, "授業通知", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "授業開始・終了前の通知"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
    )
}

private const val CHANNEL_ID_SHARED = "lesson_start_notifications"
