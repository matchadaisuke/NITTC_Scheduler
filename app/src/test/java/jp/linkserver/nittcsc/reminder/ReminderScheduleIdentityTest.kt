package jp.linkserver.nittcsc.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderScheduleIdentityTest {
    @Test
    fun missingExpectedTimestampKeepsBackwardCompatibility() {
        assertTrue(reminderScheduleMatches(null, null, 2_000L, 20L))
        assertTrue(reminderScheduleMatches(0L, 0L, 2_000L, 20L))
    }

    @Test
    fun matchingTimestampAllowsNotification() {
        assertTrue(reminderScheduleMatches(2_000L, 20L, 2_000L, 20L))
    }

    @Test
    fun staleTimestampRejectsNotification() {
        assertFalse(reminderScheduleMatches(1_000L, 20L, 2_000L, 20L))
    }

    @Test
    fun staleEntityGenerationRejectsNotificationEvenAtSameTime() {
        assertFalse(reminderScheduleMatches(2_000L, 10L, 2_000L, 20L))
    }

    @Test
    fun deliveryTokenChangesWithEntityGeneration() {
        val first = reminderDeliveryToken(1L, 2_000L, 10L)
        val edited = reminderDeliveryToken(1L, 2_000L, 20L)

        assertFalse(first == edited)
    }

    @Test
    fun staleReceiverDoesNotCancelCurrentFallback() {
        assertFalse(shouldCancelFallbackAfter(TaskReminderNotificationResult.STALE_SCHEDULE))
        assertTrue(shouldCancelFallbackAfter(TaskReminderNotificationResult.POSTED))
        assertTrue(shouldCancelFallbackAfter(TaskReminderNotificationResult.ALREADY_DELIVERED))
    }

    @Test
    fun lateDeliveryGraceIncludesRecentMissButRejectsOldReminder() {
        val now = 1_000_000L
        val grace = 15L * 60L * 1_000L

        assertTrue(isWithinLateDeliveryGrace(now - grace, now, grace))
        assertFalse(isWithinLateDeliveryGrace(now - grace - 1L, now, grace))
        assertFalse(isWithinLateDeliveryGrace(now + 1L, now, grace))
    }
}
