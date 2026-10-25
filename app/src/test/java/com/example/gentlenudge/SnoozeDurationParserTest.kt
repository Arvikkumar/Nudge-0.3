package com.example.gentlenudge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.gentlenudge.notification.NudgeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = GentleNudgeApp::class)
class SnoozeDurationParserTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("gentle_nudge_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // 1. Custom durations required: 10 minutes, 25 minutes, 45 minutes, 2 hours, and 1 hour 30 minutes
    @Test
    fun testCustomDurationsParsingAndScheduling() {
        val baseNow = 1700000000000L // arbitrary fixed epoch timestamp

        // 10 minutes
        assertEquals(10L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("10 minutes"))
        assertEquals(baseNow + (10 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("10 minutes", baseNow))

        // 25 minutes
        assertEquals(25L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("25 minutes"))
        assertEquals(baseNow + (25 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("25 minutes", baseNow))

        // 45 minutes
        assertEquals(45L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("45 minutes"))
        assertEquals(baseNow + (45 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("45 minutes", baseNow))

        // 2 hours
        assertEquals(120L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("2 hours"))
        assertEquals(baseNow + (120 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("2 hours", baseNow))

        // 1 hour 30 minutes
        assertEquals(90L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("1 hour 30 minutes"))
        assertEquals(baseNow + (90 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("1 hour 30 minutes", baseNow))

        // Additional variations
        assertEquals(210L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("3 hours and 30 minutes"))
        assertEquals(baseNow + (210 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("3 hours and 30 minutes", baseNow))

        assertEquals(90L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("1h 30m"))
        assertEquals(10L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("10m"))
    }

    // 2. Existing presets: 15 minutes, 30 minutes, 1 hour, Tomorrow, and tonight
    @Test
    fun testExistingPresets() {
        val baseNow = 1700000000000L

        // 15 minutes preset (and variations)
        assertEquals(15L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("15 minutes"))
        assertEquals(baseNow + (15 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("15 minutes", baseNow))
        assertEquals(baseNow + (15 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("15m", baseNow))
        assertEquals(baseNow + (15 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("in 15 min", baseNow))

        // 30 minutes preset (and variations)
        assertEquals(30L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("30 minutes"))
        assertEquals(baseNow + (30 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("30 minutes", baseNow))
        assertEquals(baseNow + (30 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("30m", baseNow))

        // 1 hour preset (and variations)
        assertEquals(60L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("1 hour"))
        assertEquals(baseNow + (60 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("1 hour", baseNow))
        assertEquals(baseNow + (60 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("1h", baseNow))
        assertEquals(baseNow + (60 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("60 minutes", baseNow))

        // Tomorrow preset: schedules for next day at 09:00:00
        val tomorrowMillis = NudgeAlarmScheduler.calculateSnoozeMillis("Tomorrow", baseNow)
        val expectedCal = Calendar.getInstance().apply {
            timeInMillis = baseNow
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, tomorrowMillis)

        // Tonight preset: if before 20:00, schedules for 20:00 today; if after 20:00, schedules now + 1 hour
        val morningNow = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 21, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val tonightMorningMillis = NudgeAlarmScheduler.calculateSnoozeMillis("tonight", morningNow)
        val expectedTonight = Calendar.getInstance().apply {
            timeInMillis = morningNow
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedTonight, tonightMorningMillis)

        val lateNightNow = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 21, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val tonightLateMillis = NudgeAlarmScheduler.calculateSnoozeMillis("tonight", lateNightNow)
        assertEquals(lateNightNow + (60 * 60 * 1000L), tonightLateMillis)
    }

    // 3. Edge cases: Empty and malformed inputs, zero, negative values, and overflow
    @Test
    fun testEmptyMalformedZeroNegativeAndOverflow() {
        val baseNow = 1700000000000L
        val defaultFallback = baseNow + (30 * 60 * 1000L) // 30 minutes fallback

        // Empty & blank
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes(""))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("", baseNow))
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("   "))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("   ", baseNow))

        // Malformed strings
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("invalid_duration_text"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("invalid_duration_text", baseNow))
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("someday"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("someday", baseNow))

        // Zero duration
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("0 minutes"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("0 minutes", baseNow))
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("0 hours"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("0 hours", baseNow))

        // Negative duration
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("-5 minutes"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("-5 minutes", baseNow))
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("-1 hour"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("-1 hour", baseNow))

        // Large overflow values (must not throw NumberFormatException, must not exceed 24h = 1440m)
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("99999999999999999999 hours"))
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("99999999999999999999 hours", baseNow))
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("25 hours")) // Exceeds 24 hours
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("25 hours", baseNow))
        assertNull(NudgeAlarmScheduler.parseSnoozeDurationMinutes("1500 minutes")) // Exceeds 1440 minutes
        assertEquals(defaultFallback, NudgeAlarmScheduler.calculateSnoozeMillis("1500 minutes", baseNow))

        // Maximum allowed duration: exactly 24 hours (1440 minutes)
        assertEquals(1440L, NudgeAlarmScheduler.parseSnoozeDurationMinutes("24 hours"))
        assertEquals(baseNow + (1440 * 60 * 1000L), NudgeAlarmScheduler.calculateSnoozeMillis("24 hours", baseNow))
    }

    // 4. Existing preference and backup/restore compatibility
    @Test
    fun testPreferenceAndStorageCompatibility() {
        val prefs = context.getSharedPreferences("gentle_nudge_prefs", Context.MODE_PRIVATE)

        // Store custom duration
        prefs.edit().putString("default_snooze", "45 minutes").commit()
        val retrieved = prefs.getString("default_snooze", "30 minutes")
        assertEquals("45 minutes", retrieved)

        // Schedule using retrieved preference
        val now = System.currentTimeMillis()
        val scheduled = NudgeAlarmScheduler.calculateSnoozeMillis(retrieved ?: "30 minutes", now)
        assertEquals(now + (45 * 60 * 1000L), scheduled)

        // Store compound duration
        prefs.edit().putString("default_snooze", "1 hour 30 minutes").commit()
        val retrievedCompound = prefs.getString("default_snooze", "30 minutes")
        assertEquals("1 hour 30 minutes", retrievedCompound)
        val scheduledCompound = NudgeAlarmScheduler.calculateSnoozeMillis(retrievedCompound ?: "30 minutes", now)
        assertEquals(now + (90 * 60 * 1000L), scheduledCompound)

        // Legacy preset compatibility
        prefs.edit().putString("default_snooze", "1 hour").commit()
        val retrievedLegacy = prefs.getString("default_snooze", "30 minutes")
        assertEquals("1 hour", retrievedLegacy)
        val scheduledLegacy = NudgeAlarmScheduler.calculateSnoozeMillis(retrievedLegacy ?: "30 minutes", now)
        assertEquals(now + (60 * 60 * 1000L), scheduledLegacy)
    }
}
