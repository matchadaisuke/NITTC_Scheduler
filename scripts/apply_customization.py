from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace(rel, old, new, count=1):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual < count:
        raise RuntimeError(f"{rel}: expected at least {count} occurrence(s), found {actual}: {old[:100]!r}")
    path.write_text(text.replace(old, new, count), encoding="utf-8")

def replace_all(rel, old, new):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"{rel}: pattern not found: {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")

# Settings model + Room migration.
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/Entities.kt",
    "    val examArrivalHour: Int = 8,\n    val examArrivalMinute: Int = 30\n)",
    "    val examArrivalHour: Int = 8,\n    val examArrivalMinute: Int = 30,\n    val enableSaturdayClasses: Boolean = false,\n    val schedulePresetsJson: String = \"\",\n    val lessonNotificationConfigJson: String = \"\"\n)"
)
replace("app/src/main/java/jp/linkserver/nittcsc/data/AppDatabase.kt", "    version = 49,", "    version = 50,")
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/AppDatabase.kt",
    "        fun getInstance(context: Context): AppDatabase {",
    """        val MIGRATION_49_50 = object : androidx.room.migration.Migration(49, 50) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(\"ALTER TABLE settings ADD COLUMN enableSaturdayClasses INTEGER NOT NULL DEFAULT 0\")
                db.execSQL(\"ALTER TABLE settings ADD COLUMN schedulePresetsJson TEXT NOT NULL DEFAULT ''\")
                db.execSQL(\"ALTER TABLE settings ADD COLUMN lessonNotificationConfigJson TEXT NOT NULL DEFAULT ''\")
            }
        }

        fun getInstance(context: Context): AppDatabase {"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/AppDatabase.kt",
    "                .addMigrations(MIGRATION_48_49)\n                 .build()",
    "                .addMigrations(MIGRATION_48_49)\n                .addMigrations(MIGRATION_49_50)\n                 .build()"
)

# Full JSON backup carries the new settings. The SKTTP/local sync payload remains unchanged.
replace("app/src/main/java/jp/linkserver/nittcsc/data/SchedulerDataTransfer.kt", "const val CURRENT_EXPORT_VERSION = 15", "const val CURRENT_EXPORT_VERSION = 16")
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerDataTransfer.kt",
    "                s.put(\"examArrivalMinute\", settings.examArrivalMinute)\n",
    "                s.put(\"examArrivalMinute\", settings.examArrivalMinute)\n                s.put(\"enableSaturdayClasses\", settings.enableSaturdayClasses)\n                s.put(\"schedulePresetsJson\", settings.schedulePresetsJson)\n                s.put(\"lessonNotificationConfigJson\", settings.lessonNotificationConfigJson)\n"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerDataTransfer.kt",
    "                examArrivalMinute = s.optInt(\"examArrivalMinute\", 30).coerceIn(0, 59)\n            )",
    "                examArrivalMinute = s.optInt(\"examArrivalMinute\", 30).coerceIn(0, 59),\n                enableSaturdayClasses = s.optBoolean(\"enableSaturdayClasses\", false),\n                schedulePresetsJson = s.optString(\"schedulePresetsJson\", \"\"),\n                lessonNotificationConfigJson = s.optString(\"lessonNotificationConfigJson\", \"\")\n            )"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerDataTransfer.kt",
    "takeIf { it in 1..5 }",
    "takeIf { it in 1..6 }"
)

# Repository: presets, notification config, Saturday rows and holiday rules.
replace("app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt", "private const val CURRENT_EXPORT_VERSION = 15", "private const val CURRENT_EXPORT_VERSION = 16")
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    """    suspend fun updateLessonStartNotificationMinutesBefore(minutesBefore: Int) {
        val current = dao.getSettings() ?: return
        dao.upsertSettings(
            current.copy(
                lessonStartNotificationMinutesBefore = minutesBefore.coerceIn(0, 360)
            )
        )
    }
""",
    """    suspend fun updateLessonStartNotificationMinutesBefore(minutesBefore: Int) {
        val current = dao.getSettings() ?: return
        val normalized = minutesBefore.coerceIn(0, 360)
        val config = current.lessonNotificationCustomization()
        val starts = config.startTriggers.toMutableList().also { list ->
            if (list.isNotEmpty()) list[0] = list[0].copy(minutesBefore = normalized)
        }
        dao.upsertSettings(
            current.copy(
                lessonStartNotificationMinutesBefore = normalized,
                lessonNotificationConfigJson = encodeLessonNotificationCustomization(
                    config.copy(startTriggers = starts)
                )
            )
        )
    }

    suspend fun toggleSaturdayClasses(enabled: Boolean) {
        val current = dao.getSettings() ?: return
        dao.upsertSettings(current.copy(enableSaturdayClasses = enabled))
        if (enabled) {
            var date = current.termStart
            while (!date.isAfter(current.termEnd)) {
                if (date.dayOfWeek == DayOfWeek.SATURDAY) {
                    dao.upsertDayType(DayTypeEntity(date = date, dayType = DayType.A))
                }
                date = date.plusDays(1)
            }
        }
        ensureLessonRows()
        syncDayTypes()
    }

    suspend fun saveSchedulePreset(name: String) {
        val current = dao.getSettings() ?: return
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        val presets = current.schedulePresets().toMutableList()
        val existingIndex = presets.indexOfFirst { it.name.equals(normalizedName, ignoreCase = true) }
        val id = presets.getOrNull(existingIndex)?.id ?: java.util.UUID.randomUUID().toString()
        val preset = SchedulePreset.fromSettings(current, id, normalizedName)
        if (existingIndex >= 0) {
            presets[existingIndex] = preset
        } else if (presets.size < 5) {
            presets += preset
        } else {
            return
        }
        dao.upsertSettings(current.copy(schedulePresetsJson = encodeSchedulePresets(presets)))
    }

    suspend fun deleteSchedulePreset(id: String) {
        val current = dao.getSettings() ?: return
        dao.upsertSettings(
            current.copy(
                schedulePresetsJson = encodeSchedulePresets(current.schedulePresets().filterNot { it.id == id })
            )
        )
    }

    suspend fun applySchedulePreset(id: String) {
        val current = dao.getSettings() ?: return
        val preset = current.schedulePresets().firstOrNull { it.id == id } ?: return
        dao.upsertSettings(
            current.copy(
                periodsPerDay = preset.periodsPerDay,
                periodDurationMin = preset.periodDurationMin,
                breakBetweenPeriodsMin = preset.breakBetweenPeriodsMin,
                lunchBreakMin = preset.lunchBreakMin,
                lunchAfterPeriod = preset.lunchAfterPeriod.coerceIn(0, preset.periodsPerDay),
                firstPeriodStartHour = preset.firstPeriodStartHour,
                firstPeriodStartMinute = preset.firstPeriodStartMinute,
                periodLabelStyle = preset.periodLabelStyle,
                useKosenMode = preset.periodLabelStyle != PeriodLabelStyle.SINGLE_KOSHI,
                arrivalHour = preset.arrivalHour,
                arrivalMinute = preset.arrivalMinute,
                departureHour = preset.departureHour,
                departureMinute = preset.departureMinute
            )
        )
        ensureLessonRows()
    }

    suspend fun updateLessonNotificationCustomization(config: LessonNotificationCustomization) {
        val current = dao.getSettings() ?: return
        val normalizedStarts = (0..1).map { index ->
            config.startTriggers.getOrNull(index)
                ?: LessonNotificationCustomization.defaults(current.lessonStartNotificationMinutesBefore).startTriggers[index]
        }
        val normalizedEnds = (0..1).map { index ->
            config.endTriggers.getOrNull(index)
                ?: LessonNotificationCustomization.defaults(current.lessonStartNotificationMinutesBefore).endTriggers[index]
        }
        val normalized = config.copy(startTriggers = normalizedStarts, endTriggers = normalizedEnds)
        dao.upsertSettings(
            current.copy(
                lessonStartNotificationMinutesBefore = normalizedStarts.first().minutesBefore.coerceIn(0, 360),
                lessonNotificationConfigJson = encodeLessonNotificationCustomization(normalized)
            )
        )
    }
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    "            val autoHoliday = isAutoHoliday(date, breakRanges)",
    "            val autoHoliday = isAutoHoliday(date, breakRanges, settings.enableSaturdayClasses)"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    """    private fun isAutoHoliday(date: LocalDate, breakRanges: List<ClosedRange<LocalDate>>): Boolean {
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val longBreak = breakRanges.any { date in it }
        return weekend || longBreak || JapaneseHolidayCalculator.isHoliday(date)
    }
""",
    """    private fun isAutoHoliday(
        date: LocalDate,
        breakRanges: List<ClosedRange<LocalDate>>,
        enableSaturdayClasses: Boolean
    ): Boolean {
        val nonLessonWeekend = date.dayOfWeek == DayOfWeek.SUNDAY ||
            (date.dayOfWeek == DayOfWeek.SATURDAY && !enableSaturdayClasses)
        val longBreak = breakRanges.any { date in it }
        return nonLessonWeekend || longBreak || JapaneseHolidayCalculator.isHoliday(date)
    }
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    """            val currentDayType = existing?.dayType ?: if (
                date.dayOfWeek == DayOfWeek.SATURDAY ||
                date.dayOfWeek == DayOfWeek.SUNDAY ||
                JapaneseHolidayCalculator.isHoliday(date)
            ) {
""",
    """            val saturdayDisabled = date.dayOfWeek == DayOfWeek.SATURDAY &&
                dao.getSettings()?.enableSaturdayClasses != true
            val currentDayType = existing?.dayType ?: if (
                saturdayDisabled ||
                date.dayOfWeek == DayOfWeek.SUNDAY ||
                JapaneseHolidayCalculator.isHoliday(date)
            ) {
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    """                val startDate = when (today.dayOfWeek) {
                    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    else -> today
                }
                val weekStart = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weekEnd = weekStart.plusDays(4)
""",
    """                val startDate = when (today.dayOfWeek) {
                    DayOfWeek.SUNDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    DayOfWeek.SATURDAY -> if (settings.enableSaturdayClasses) today else today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    else -> today
                }
                val weekStart = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weekEnd = weekStart.plusDays(if (settings.enableSaturdayClasses) 5 else 4)
"""
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    "if (date.dayOfWeek.value !in 1..5)",
    "if (date.dayOfWeek == DayOfWeek.SUNDAY)"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    "if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) continue",
    "if (date.dayOfWeek == DayOfWeek.SUNDAY) continue"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    "if (startDate.dayOfWeek.value in 1..5 && dayType != DayType.HOLIDAY)",
    "if (startDate.dayOfWeek != DayOfWeek.SUNDAY && dayType != DayType.HOLIDAY)"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    "        val periodsPerDay = dao.getSettings()?.periodsPerDay ?: 4\n        val existing = dao.getLessonsOnce().associateBy { it.lessonKey() }",
    "        val periodsPerDay = dao.getSettings()?.periodsPerDay ?: 4\n        val existing = dao.getLessonsOnce().associateBy { it.lessonKey() }"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/data/SchedulerRepository.kt",
    "for (day in 1..5)",
    "for (day in 1..6)"
)

# ViewModel callbacks and Saturday resolution.
replace(
    "app/src/main/java/jp/linkserver/nittcsc/viewmodel/SchedulerViewModel.kt",
    """    fun updateLessonStartNotificationMinutesBefore(minutesBefore: Int) {
        launchRepositoryUpdate { repository.updateLessonStartNotificationMinutesBefore(minutesBefore) }
    }
""",
    """    fun updateLessonStartNotificationMinutesBefore(minutesBefore: Int) {
        launchRepositoryUpdate { repository.updateLessonStartNotificationMinutesBefore(minutesBefore) }
    }

    fun toggleSaturdayClasses(enabled: Boolean) {
        launchRepositoryUpdate { repository.toggleSaturdayClasses(enabled) }
    }

    fun saveSchedulePreset(name: String) {
        launchRepositoryUpdate { repository.saveSchedulePreset(name) }
    }

    fun applySchedulePreset(id: String) {
        launchRepositoryUpdate { repository.applySchedulePreset(id) }
    }

    fun deleteSchedulePreset(id: String) {
        launchRepositoryUpdate { repository.deleteSchedulePreset(id) }
    }

    fun updateLessonNotificationCustomization(config: jp.linkserver.nittcsc.data.LessonNotificationCustomization) {
        launchRepositoryUpdate { repository.updateLessonNotificationCustomization(config) }
    }
"""
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/viewmodel/SchedulerViewModel.kt",
    "if (date.dayOfWeek.value !in 1..5)",
    "if (date.dayOfWeek == DayOfWeek.SUNDAY)"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/viewmodel/SchedulerViewModel.kt",
    "val weekend = date.dayOfWeek.value >= DayOfWeek.SATURDAY.value",
    "val weekend = date.dayOfWeek == DayOfWeek.SUNDAY"
)

# Timetable editor and week output show Saturday when enabled.
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    """    val dayLabels = listOf(
        DayOfWeek.MONDAY.value to R.string.weekday_monday,
        DayOfWeek.TUESDAY.value to R.string.weekday_tuesday,
        DayOfWeek.WEDNESDAY.value to R.string.weekday_wednesday,
        DayOfWeek.THURSDAY.value to R.string.weekday_thursday,
        DayOfWeek.FRIDAY.value to R.string.weekday_friday
    )
    val dayButtonLabels = listOf(
        stringResource(R.string.weekday_monday),
        stringResource(R.string.weekday_tuesday),
        stringResource(R.string.weekday_wednesday),
        stringResource(R.string.weekday_thursday),
        stringResource(R.string.weekday_friday)
    )
""",
    """    val dayLabels = buildList {
        add(DayOfWeek.MONDAY.value to R.string.weekday_monday)
        add(DayOfWeek.TUESDAY.value to R.string.weekday_tuesday)
        add(DayOfWeek.WEDNESDAY.value to R.string.weekday_wednesday)
        add(DayOfWeek.THURSDAY.value to R.string.weekday_thursday)
        add(DayOfWeek.FRIDAY.value to R.string.weekday_friday)
        if (state.settings?.enableSaturdayClasses == true) {
            add(DayOfWeek.SATURDAY.value to R.string.weekday_saturday)
        }
    }
    val dayButtonLabels = dayLabels.map { (_, labelRes) -> stringResource(labelRes) }
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    """    val defaultWeekFocusDate = remember(today) {
        when (today.dayOfWeek) {
            DayOfWeek.SATURDAY -> today.plusDays(2)
            DayOfWeek.SUNDAY -> today.plusDays(1)
            else -> today
        }
    }
""",
    """    val saturdayClassesEnabled = state.settings?.enableSaturdayClasses == true
    val defaultWeekFocusDate = remember(today, saturdayClassesEnabled) {
        when {
            today.dayOfWeek == DayOfWeek.SUNDAY -> today.plusDays(1)
            today.dayOfWeek == DayOfWeek.SATURDAY && !saturdayClassesEnabled -> today.plusDays(2)
            else -> today
        }
    }
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    "    val isTodayWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY",
    "    val isTodayWeekend = today.dayOfWeek == DayOfWeek.SUNDAY || (today.dayOfWeek == DayOfWeek.SATURDAY && !saturdayClassesEnabled)"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    "    val weekDates = remember(weekDisplayReferenceDate) { (0L..4L).map { weekStart.plusDays(it) } }",
    "    val weekDates = remember(weekDisplayReferenceDate, saturdayClassesEnabled) { (0L..if (saturdayClassesEnabled) 5L else 4L).map { weekStart.plusDays(it) } }"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    """                            val pageWeekDates = remember(pageDate) {
                                (0L..4L).map { pageWeekStart.plusDays(it) }
                            }
""",
    """                            val pageWeekDates = remember(pageDate, saturdayClassesEnabled) {
                                (0L..if (saturdayClassesEnabled) 5L else 4L).map { pageWeekStart.plusDays(it) }
                            }
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    "mutableStateOf(currentOverrideDayOfWeek ?: date.dayOfWeek.value.coerceIn(1, 5))",
    "mutableStateOf(currentOverrideDayOfWeek ?: date.dayOfWeek.value.coerceIn(1, 6))"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    "listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)",
    "listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    "                    onUpdateScheduleSettings = viewModel::updateScheduleSettingsSilently,",
    "                    onToggleSaturdayClasses = viewModel::toggleSaturdayClasses,\n                    onSaveSchedulePreset = viewModel::saveSchedulePreset,\n                    onApplySchedulePreset = viewModel::applySchedulePreset,\n                    onDeleteSchedulePreset = viewModel::deleteSchedulePreset,\n                    onUpdateLessonNotificationCustomization = viewModel::updateLessonNotificationCustomization,\n                    onUpdateScheduleSettings = viewModel::updateScheduleSettingsSilently,"
)

# Settings screen integration.
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt",
    "import jp.linkserver.nittcsc.data.LessonStartNotificationChipMode\n",
    "import jp.linkserver.nittcsc.data.LessonStartNotificationChipMode\nimport jp.linkserver.nittcsc.data.LessonNotificationCustomization\n"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt",
    """    onDeleteLessonNotificationExclusion: (LessonNotificationExclusionEntity) -> Unit = {},
    tutorialFirstTimeCheckDisabledForTesting: Boolean = false,
""",
    """    onDeleteLessonNotificationExclusion: (LessonNotificationExclusionEntity) -> Unit = {},
    onToggleSaturdayClasses: (Boolean) -> Unit = {},
    onSaveSchedulePreset: (String) -> Unit = {},
    onApplySchedulePreset: (String) -> Unit = {},
    onDeleteSchedulePreset: (String) -> Unit = {},
    onUpdateLessonNotificationCustomization: (LessonNotificationCustomization) -> Unit = {},
    tutorialFirstTimeCheckDisabledForTesting: Boolean = false,
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt",
    """        // ── 通知設定 ──────────────────────────────────────────
""",
    """        CustomizationScheduleSettingsContent(
            settings = s,
            onToggleSaturdayClasses = onToggleSaturdayClasses,
            onSaveSchedulePreset = onSaveSchedulePreset,
            onApplySchedulePreset = onApplySchedulePreset,
            onDeleteSchedulePreset = onDeleteSchedulePreset
        )

        // ── 通知設定 ──────────────────────────────────────────
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt",
    """                onAddExclusion = onAddLessonNotificationExclusion,
                onDeleteExclusion = onDeleteLessonNotificationExclusion
            )
        }

        // ── 表示設定""",
    """                onAddExclusion = onAddLessonNotificationExclusion,
                onDeleteExclusion = onDeleteLessonNotificationExclusion
            )
            CustomizationNotificationSettingsContent(
                settings = s,
                onUpdate = onUpdateLessonNotificationCustomization
            )
        }

        // ── 表示設定"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt",
    """        // ── 設定データの移行 ───────────────────────────────────────
""",
    """        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettingsCategory(title = \"クラウド同期\")
            CloudFileSyncSettingsContent()
        }

        // ── 設定データの移行 ───────────────────────────────────────
"""
)

# Start the cloud observer with the Activity lifecycle and refresh on resume.
replace(
    "app/src/main/java/jp/linkserver/nittcsc/MainActivity.kt",
    "import jp.linkserver.nittcsc.sync.LocalSyncManager\n",
    "import jp.linkserver.nittcsc.sync.CloudFileSyncManager\nimport jp.linkserver.nittcsc.sync.LocalSyncManager\n"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/MainActivity.kt",
    "        // WorkManager による定期ウィジェット更新をスケジュール\n        WidgetUpdateWorker.schedule(this)",
    "        CloudFileSyncManager.start(this)\n        // WorkManager による定期ウィジェット更新をスケジュール\n        WidgetUpdateWorker.schedule(this)"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/MainActivity.kt",
    "            viewModel.runAutoSync()\n",
    "            viewModel.runAutoSync()\n            if (CloudFileSyncManager.isConfigured(this@MainActivity)) {\n                CloudFileSyncManager.syncNow(this@MainActivity)\n            }\n"
)

# Existing primary start notification keeps Android 16 Live Updates but uses the custom first trigger and templates.
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    "import jp.linkserver.nittcsc.data.LessonNotificationExclusionEntity\n",
    "import jp.linkserver.nittcsc.data.LessonNotificationExclusionEntity\nimport jp.linkserver.nittcsc.data.LessonNotificationCustomization\nimport jp.linkserver.nittcsc.data.LessonNotificationTemplateValues\nimport jp.linkserver.nittcsc.data.lessonNotificationCustomization\nimport jp.linkserver.nittcsc.data.renderLessonNotificationTemplate\n"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    ") : CoroutineWorker(appContext, params) {\n\n    override suspend fun doWork(): Result {",
    ") : CoroutineWorker(appContext, params) {\n\n    private var customConfig: LessonNotificationCustomization? = null\n    private var nextLessonForTemplate: NextLessonSnapshot? = null\n\n    override suspend fun doWork(): Result {"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    """        val settings = dao.getSettings() ?: return Result.success()
        if (!settings.lessonStartNotificationEnabled) return Result.success()
""",
    """        val settings = dao.getSettings() ?: return Result.success()
        if (!settings.lessonStartNotificationEnabled) return Result.success()
        customConfig = settings.lessonNotificationCustomization()
        if (customConfig?.startTriggers?.firstOrNull()?.enabled != true) return Result.success()
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    "        if (isExcluded(lesson, dao.getLessonNotificationExclusionsOnce())) return Result.success()\n\n        createNotificationChannel()",
    "        if (isExcluded(lesson, dao.getLessonNotificationExclusionsOnce())) return Result.success()\n        nextLessonForTemplate = AdditionalLessonNotificationWorker.findNextLesson(applicationContext, date, slotIndex)\n\n        createNotificationChannel()"
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    """    private fun buildStandardNotification(
        minutesBefore: Int,
        lesson: ResolvedLesson,
        slot: ClassSlot,
        pendingIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(applicationContext.getString(R.string.lesson_start_notification_title))
            .setContentText(
                buildLessonStartText(minutesBefore, lesson.subject)
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildNotificationBody(minutesBefore, lesson, slot)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }
""",
    """    private fun buildStandardNotification(
        minutesBefore: Int,
        lesson: ResolvedLesson,
        slot: ClassSlot,
        pendingIntent: PendingIntent
    ): Notification {
        val next = nextLessonForTemplate
        val values = LessonNotificationTemplateValues(
            subject = lesson.subject,
            teacher = lesson.teacher,
            location = lesson.location.orEmpty(),
            period = slot.label,
            startTime = slot.start.toString(),
            endTime = slot.end.toString(),
            minutes = minutesBefore,
            nextSubject = next?.subject ?: \"なし\",
            nextTeacher = next?.teacher.orEmpty(),
            nextLocation = next?.location.orEmpty(),
            nextPeriod = next?.period.orEmpty(),
            nextStartTime = next?.start?.toString().orEmpty()
        )
        val config = customConfig ?: LessonNotificationCustomization.defaults(minutesBefore)
        val title = renderLessonNotificationTemplate(config.startTitleTemplate, values)
            .ifBlank { applicationContext.getString(R.string.lesson_start_notification_title) }
        val body = renderLessonNotificationTemplate(config.startBodyTemplate, values)
            .ifBlank { buildNotificationBody(minutesBefore, lesson, slot) }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    """        suspend fun rescheduleAll(context: Context) {
            val appContext = context.applicationContext
            rescheduleMutex.withLock {
""",
    """        suspend fun rescheduleAll(context: Context) {
            val appContext = context.applicationContext
            AdditionalLessonNotificationWorker.rescheduleAll(appContext)
            rescheduleMutex.withLock {
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    """            val settings = dao.getSettings() ?: return
            if (!settings.lessonStartNotificationEnabled) return

            val today = LocalDate.now()
""",
    """            val settings = dao.getSettings() ?: return
            if (!settings.lessonStartNotificationEnabled) return
            val primaryTrigger = settings.lessonNotificationCustomization().startTriggers.firstOrNull() ?: return
            if (!primaryTrigger.enabled) return

            val today = LocalDate.now()
"""
)
replace(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    "            val minutesBefore = settings.lessonStartNotificationMinutesBefore.coerceIn(0, 360).toLong()",
    "            val minutesBefore = primaryTrigger.minutesBefore.coerceIn(0, 360).toLong()"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    "if (date.dayOfWeek.value !in 1..5)",
    "if (date.dayOfWeek == DayOfWeek.SUNDAY)"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/reminder/LessonStartNotificationWorker.kt",
    "val weekend = date.dayOfWeek.value >= DayOfWeek.SATURDAY.value",
    "val weekend = date.dayOfWeek == DayOfWeek.SUNDAY"
)

# Widgets can resolve Saturday when the generated day type says it is a lesson day.
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/widget/WidgetDataHelper.kt",
    "if (date.dayOfWeek.value !in 1..5)",
    "if (date.dayOfWeek == DayOfWeek.SUNDAY)"
)
replace_all(
    "app/src/main/java/jp/linkserver/nittcsc/widget/WidgetDataHelper.kt",
    "val weekend = date.dayOfWeek.value >= DayOfWeek.SATURDAY.value",
    "val weekend = date.dayOfWeek == DayOfWeek.SUNDAY"
)

# Register the custom exact-alarm receiver.
replace(
    "app/src/main/AndroidManifest.xml",
    """        <receiver
            android:name=\".reminder.LessonStartNotificationAlarmReceiver\"
            android:exported=\"false\" />
""",
    """        <receiver
            android:name=\".reminder.LessonStartNotificationAlarmReceiver\"
            android:exported=\"false\" />

        <receiver
            android:name=\".reminder.AdditionalLessonNotificationAlarmReceiver\"
            android:exported=\"false\" />
"""
)

# Document the customization branch for future maintenance.
readme = ROOT / "README.md"
text = readme.read_text(encoding="utf-8")
marker = "## Custom fork features"
if marker not in text:
    text += """

## Custom fork features

This branch adds provider-neutral cloud-file sync via Android's Storage Access Framework (Google Drive / Dropbox / OneDrive compatible without app-specific OAuth setup), up to five schedule presets, two start + two end lesson notifications with editable templates/variables, and optional Saturday classes. Full cloud backups include detailed schedule times and these customization settings; the existing SKTTP/local sync protocol is left unchanged.
"""
    readme.write_text(text, encoding="utf-8")

print("Customization transformation applied")
