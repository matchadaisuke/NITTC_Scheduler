package jp.linkserver.nittcsc.logic

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LessonWeekTest {
    @Test
    fun lessonWeekdays_excludesSaturdayWhenDisabled() {
        assertEquals(
            listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            ),
            lessonWeekdays(saturdayClassesEnabled = false)
        )
    }

    @Test
    fun lessonWeekdays_includesSaturdayWhenEnabled() {
        assertEquals(DayOfWeek.SATURDAY, lessonWeekdays(saturdayClassesEnabled = true).last())
        assertEquals(6, lessonWeekdays(saturdayClassesEnabled = true).size)
    }

    @Test
    fun lessonWeekDates_endsOnConfiguredSchoolWeekBoundary() {
        val monday = LocalDate.of(2026, 9, 7)

        assertEquals(LocalDate.of(2026, 9, 11), lessonWeekDates(monday, false).last())
        assertEquals(LocalDate.of(2026, 9, 12), lessonWeekDates(monday, true).last())
    }
}
