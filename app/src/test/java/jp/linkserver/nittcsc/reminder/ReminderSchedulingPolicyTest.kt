package jp.linkserver.nittcsc.reminder

import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulingPolicyTest {
    @Test
    fun lessonAlarmWindowLeavesCapacityForTaskAndPlanReminders() {
        assertTrue(ReminderSchedulingPolicy.WORST_CASE_LESSON_ALARM_COUNT <= 200)
    }
}
