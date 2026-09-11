package jp.linkserver.nittcsc.logic

import java.time.DayOfWeek
import java.time.LocalDate

fun lessonWeekdays(saturdayClassesEnabled: Boolean): List<DayOfWeek> = buildList {
    add(DayOfWeek.MONDAY)
    add(DayOfWeek.TUESDAY)
    add(DayOfWeek.WEDNESDAY)
    add(DayOfWeek.THURSDAY)
    add(DayOfWeek.FRIDAY)
    if (saturdayClassesEnabled) add(DayOfWeek.SATURDAY)
}

fun lessonWeekDates(weekStart: LocalDate, saturdayClassesEnabled: Boolean): List<LocalDate> =
    lessonWeekdays(saturdayClassesEnabled).map { dayOfWeek ->
        weekStart.plusDays((dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    }
