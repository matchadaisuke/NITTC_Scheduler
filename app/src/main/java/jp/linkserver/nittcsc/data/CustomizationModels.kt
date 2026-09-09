package jp.linkserver.nittcsc.data

import jp.linkserver.nittcsc.logic.PeriodLabelStyle
import org.json.JSONArray
import org.json.JSONObject

data class SchedulePreset(
    val id: String,
    val name: String,
    val periodsPerDay: Int,
    val periodDurationMin: Int,
    val breakBetweenPeriodsMin: Int,
    val lunchBreakMin: Int,
    val lunchAfterPeriod: Int,
    val firstPeriodStartHour: Int,
    val firstPeriodStartMinute: Int,
    val periodLabelStyle: PeriodLabelStyle,
    val arrivalHour: Int,
    val arrivalMinute: Int,
    val departureHour: Int,
    val departureMinute: Int
) {
    companion object {
        fun fromSettings(settings: SettingsEntity, id: String, name: String): SchedulePreset =
            SchedulePreset(
                id = id,
                name = name,
                periodsPerDay = settings.periodsPerDay,
                periodDurationMin = settings.periodDurationMin,
                breakBetweenPeriodsMin = settings.breakBetweenPeriodsMin,
                lunchBreakMin = settings.lunchBreakMin,
                lunchAfterPeriod = settings.lunchAfterPeriod,
                firstPeriodStartHour = settings.firstPeriodStartHour,
                firstPeriodStartMinute = settings.firstPeriodStartMinute,
                periodLabelStyle = settings.periodLabelStyle,
                arrivalHour = settings.arrivalHour,
                arrivalMinute = settings.arrivalMinute,
                departureHour = settings.departureHour,
                departureMinute = settings.departureMinute
            )
    }
}

data class LessonNotificationTrigger(
    val enabled: Boolean,
    val minutesBefore: Int
)

data class LessonNotificationCustomization(
    val startTriggers: List<LessonNotificationTrigger>,
    val endTriggers: List<LessonNotificationTrigger>,
    val startTitleTemplate: String,
    val startBodyTemplate: String,
    val endTitleTemplate: String,
    val endBodyTemplate: String
) {
    companion object {
        fun defaults(legacyStartMinutes: Int = 10) = LessonNotificationCustomization(
            startTriggers = listOf(
                LessonNotificationTrigger(true, legacyStartMinutes.coerceIn(0, 360)),
                LessonNotificationTrigger(false, 5)
            ),
            endTriggers = listOf(
                LessonNotificationTrigger(false, 10),
                LessonNotificationTrigger(false, 5)
            ),
            startTitleTemplate = "{subject} の開始前通知",
            startBodyTemplate = "あと{minutes}分で{subject}が始まります。\n次の授業: {next_subject}",
            endTitleTemplate = "{subject} の終了前通知",
            endBodyTemplate = "あと{minutes}分で{subject}が終わります。\n次の授業: {next_subject}"
        )
    }
}

data class LessonNotificationTemplateValues(
    val subject: String,
    val teacher: String = "",
    val location: String = "",
    val period: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val minutes: Int = 0,
    val nextSubject: String = "なし",
    val nextTeacher: String = "",
    val nextLocation: String = "",
    val nextPeriod: String = "",
    val nextStartTime: String = ""
)

fun SettingsEntity.schedulePresets(): List<SchedulePreset> = decodeSchedulePresets(schedulePresetsJson)

fun SettingsEntity.lessonNotificationCustomization(): LessonNotificationCustomization =
    decodeLessonNotificationCustomization(
        lessonNotificationConfigJson,
        lessonStartNotificationMinutesBefore
    )

fun encodeSchedulePresets(presets: List<SchedulePreset>): String {
    val array = JSONArray()
    presets.take(5).forEach { preset ->
        array.put(JSONObject().apply {
            put("id", preset.id)
            put("name", preset.name)
            put("periodsPerDay", preset.periodsPerDay)
            put("periodDurationMin", preset.periodDurationMin)
            put("breakBetweenPeriodsMin", preset.breakBetweenPeriodsMin)
            put("lunchBreakMin", preset.lunchBreakMin)
            put("lunchAfterPeriod", preset.lunchAfterPeriod)
            put("firstPeriodStartHour", preset.firstPeriodStartHour)
            put("firstPeriodStartMinute", preset.firstPeriodStartMinute)
            put("periodLabelStyle", preset.periodLabelStyle.name)
            put("arrivalHour", preset.arrivalHour)
            put("arrivalMinute", preset.arrivalMinute)
            put("departureHour", preset.departureHour)
            put("departureMinute", preset.departureMinute)
        })
    }
    return array.toString()
}

fun decodeSchedulePresets(json: String): List<SchedulePreset> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until minOf(array.length(), 5)) {
                val obj = array.optJSONObject(index) ?: continue
                val name = obj.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    SchedulePreset(
                        id = obj.optString("id").takeIf { it.isNotBlank() }
                            ?: "preset-$index",
                        name = name,
                        periodsPerDay = obj.optInt("periodsPerDay", 4).coerceIn(1, 12),
                        periodDurationMin = obj.optInt("periodDurationMin", 90).coerceIn(10, 240),
                        breakBetweenPeriodsMin = obj.optInt("breakBetweenPeriodsMin", 10).coerceIn(0, 180),
                        lunchBreakMin = obj.optInt("lunchBreakMin", 60).coerceIn(0, 240),
                        lunchAfterPeriod = obj.optInt("lunchAfterPeriod", 2).coerceIn(0, 12),
                        firstPeriodStartHour = obj.optInt("firstPeriodStartHour", 8).coerceIn(0, 23),
                        firstPeriodStartMinute = obj.optInt("firstPeriodStartMinute", 40).coerceIn(0, 59),
                        periodLabelStyle = runCatching {
                            PeriodLabelStyle.valueOf(obj.optString("periodLabelStyle"))
                        }.getOrDefault(PeriodLabelStyle.PAIR_KOSHI),
                        arrivalHour = obj.optInt("arrivalHour", 8).coerceIn(-1, 23),
                        arrivalMinute = obj.optInt("arrivalMinute", 30).coerceIn(-1, 59),
                        departureHour = obj.optInt("departureHour", -1).coerceIn(-1, 23),
                        departureMinute = obj.optInt("departureMinute", -1).coerceIn(-1, 59)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

fun encodeLessonNotificationCustomization(config: LessonNotificationCustomization): String {
    fun triggerArray(values: List<LessonNotificationTrigger>): JSONArray = JSONArray().apply {
        values.take(2).forEach { trigger ->
            put(JSONObject().apply {
                put("enabled", trigger.enabled)
                put("minutesBefore", trigger.minutesBefore.coerceIn(0, 360))
            })
        }
    }

    return JSONObject().apply {
        put("startTriggers", triggerArray(config.startTriggers))
        put("endTriggers", triggerArray(config.endTriggers))
        put("startTitleTemplate", config.startTitleTemplate)
        put("startBodyTemplate", config.startBodyTemplate)
        put("endTitleTemplate", config.endTitleTemplate)
        put("endBodyTemplate", config.endBodyTemplate)
    }.toString()
}

fun decodeLessonNotificationCustomization(
    json: String,
    legacyStartMinutes: Int = 10
): LessonNotificationCustomization {
    val defaults = LessonNotificationCustomization.defaults(legacyStartMinutes)
    if (json.isBlank()) return defaults
    return runCatching {
        val obj = JSONObject(json)
        fun triggers(key: String, fallback: List<LessonNotificationTrigger>): List<LessonNotificationTrigger> {
            val array = obj.optJSONArray(key) ?: return fallback
            return (0..1).map { index ->
                val fallbackTrigger = fallback[index]
                val item = array.optJSONObject(index)
                if (item == null) fallbackTrigger else LessonNotificationTrigger(
                    enabled = item.optBoolean("enabled", fallbackTrigger.enabled),
                    minutesBefore = item.optInt("minutesBefore", fallbackTrigger.minutesBefore).coerceIn(0, 360)
                )
            }
        }
        LessonNotificationCustomization(
            startTriggers = triggers("startTriggers", defaults.startTriggers),
            endTriggers = triggers("endTriggers", defaults.endTriggers),
            startTitleTemplate = obj.optString("startTitleTemplate", defaults.startTitleTemplate),
            startBodyTemplate = obj.optString("startBodyTemplate", defaults.startBodyTemplate),
            endTitleTemplate = obj.optString("endTitleTemplate", defaults.endTitleTemplate),
            endBodyTemplate = obj.optString("endBodyTemplate", defaults.endBodyTemplate)
        )
    }.getOrDefault(defaults)
}

fun renderLessonNotificationTemplate(
    template: String,
    values: LessonNotificationTemplateValues
): String {
    val replacements = linkedMapOf(
        "{subject}" to values.subject,
        "{teacher}" to values.teacher,
        "{location}" to values.location,
        "{period}" to values.period,
        "{start_time}" to values.startTime,
        "{end_time}" to values.endTime,
        "{minutes}" to values.minutes.toString(),
        "{next_subject}" to values.nextSubject,
        "{next_teacher}" to values.nextTeacher,
        "{next_location}" to values.nextLocation,
        "{next_period}" to values.nextPeriod,
        "{next_start_time}" to values.nextStartTime
    )
    return replacements.entries.fold(template) { text, (variable, value) ->
        text.replace(variable, value)
    }
}
