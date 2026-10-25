package com.example.gentlenudge

import com.example.gentlenudge.data.model.NudgeTask
import com.example.gentlenudge.ui.components.validateTaskSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class PastTimeTaskValidationTest {

    private fun makeCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun testNewOneTimeTask_TodayPastTime_BlockedWithExactMessage() {
        // Current time is 2:00 PM (14:00) today
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "9:00 AM",
            repeatRule = "Does not repeat",
            existingTask = null,
            now = now
        )

        assertEquals(
            "This time has already passed today. Choose a future time or schedule it for tomorrow.",
            error
        )
    }

    @Test
    fun testNewOneTimeTask_TodayFutureTime_Allowed() {
        // Current time is 2:00 PM (14:00) today
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "3:00 PM",
            repeatRule = "Does not repeat",
            existingTask = null,
            now = now
        )

        assertNull("Future time on today must be allowed", error)
    }

    @Test
    fun testNewOneTimeTask_TomorrowAnyTime_Allowed() {
        // Current time is 2:00 PM today
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val error = validateTaskSchedule(
            dateLabel = "Tomorrow",
            timeLabel = "9:00 AM",
            repeatRule = "Does not repeat",
            existingTask = null,
            now = now
        )

        assertNull("Tomorrow 9:00 AM must be allowed", error)
    }

    @Test
    fun testRecurringTask_TodayPastTime_Allowed() {
        // Current time is 2:00 PM today; repeat rule is "Every day"
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "9:00 AM",
            repeatRule = "Every day",
            existingTask = null,
            now = now
        )

        assertNull("Recurring daily task with past time today must advance to tomorrow and be allowed", error)
    }

    @Test
    fun testExistingTask_TitleEditedWithoutScheduleChange_Allowed() {
        // Existing task created this morning for 9:00 AM Today
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val existing = NudgeTask(
            id = 42L,
            title = "Morning Coffee",
            dateLabel = "Today",
            timeLabel = "9:00 AM",
            repeat = "Does not repeat"
        )

        // User edits title/notes, but keeps dateLabel, timeLabel, and repeat unchanged
        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "9:00 AM",
            repeatRule = "Does not repeat",
            existingTask = existing,
            now = now
        )

        assertNull("Editing existing task with unchanged schedule must be allowed even if in past", error)
    }

    @Test
    fun testExistingTask_ScheduleChangedToPastTimeToday_Blocked() {
        // Existing task originally scheduled for 3:00 PM Today
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val existing = NudgeTask(
            id = 43L,
            title = "Check mail",
            dateLabel = "Today",
            timeLabel = "3:00 PM",
            repeat = "Does not repeat"
        )

        // User edits schedule to 9:00 AM Today (which has already passed)
        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "9:00 AM",
            repeatRule = "Does not repeat",
            existingTask = existing,
            now = now
        )

        assertEquals(
            "This time has already passed today. Choose a future time or schedule it for tomorrow.",
            error
        )
    }

    @Test
    fun testNewOneTimeTask_PastCustomDate_BlockedWithGeneralMessage() {
        // Current time is Oct 25, 2026
        val nowCal = makeCalendar(2026, 10, 25, 14, 0)
        val now = nowCal.timeInMillis

        val error = validateTaskSchedule(
            dateLabel = "Oct 20, 2026",
            timeLabel = "9:00 AM",
            repeatRule = "Does not repeat",
            existingTask = null,
            now = now
        )

        assertEquals(
            "Please choose a future time for this nudge.",
            error
        )
    }

    @Test
    fun testOneMinuteTolerance_Under60Seconds_Allowed() {
        // Current time is 14:00:30
        val nowCal = makeCalendar(2026, 10, 25, 14, 0).apply {
            set(Calendar.SECOND, 30)
        }
        val now = nowCal.timeInMillis

        // Selected time is 2:00 PM (14:00:00), exactly 30s ago (within 60s tolerance)
        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "2:00 PM",
            repeatRule = "Does not repeat",
            existingTask = null,
            now = now
        )

        assertNull("Time within 60s tolerance must be allowed", error)
    }

    @Test
    fun testOneMinuteTolerance_Over60Seconds_Blocked() {
        // Current time is 14:01:05
        val nowCal = makeCalendar(2026, 10, 25, 14, 1).apply {
            set(Calendar.SECOND, 5)
        }
        val now = nowCal.timeInMillis

        // Selected time is 2:00 PM (14:00:00), 65s ago (exceeds 60s tolerance)
        val error = validateTaskSchedule(
            dateLabel = "Today",
            timeLabel = "2:00 PM",
            repeatRule = "Does not repeat",
            existingTask = null,
            now = now
        )

        assertEquals(
            "This time has already passed today. Choose a future time or schedule it for tomorrow.",
            error
        )
    }
}
