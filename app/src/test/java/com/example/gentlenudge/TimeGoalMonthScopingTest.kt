package com.example.gentlenudge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.gentlenudge.backup.NudgeBackupManager
import com.example.gentlenudge.data.db.AppDatabase
import com.example.gentlenudge.data.db.TimeGoalDao
import com.example.gentlenudge.data.model.TimeGoal
import com.example.gentlenudge.data.model.TimeGoalCalculations
import com.example.gentlenudge.data.model.TimeGoalRecord
import com.example.gentlenudge.data.repository.NudgeRepository
import com.example.gentlenudge.export.MonthReportData
import com.example.gentlenudge.export.TimeGoalsPdfExporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowTestPdfDocument::class])
class TimeGoalMonthScopingTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: TimeGoalDao
    private lateinit var repository: NudgeRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.timeGoalDao()
        repository = NudgeRepository(db.nudgeTaskDao(), dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAddPursuitInOctober2026_belongsOnlyToOctober2026() = runBlocking {
        // Create "Grammar Course" in October 2026
        val octGoal = TimeGoal(
            name = "Grammar Course",
            dailyTargetMinutes = 60,
            colorHex = "#7C4DFF",
            iconName = "book",
            startDate = "2026-10-01",
            yearMonth = "2026-10",
            orderIndex = 0
        )
        val octGoalId = dao.insertGoal(octGoal)
        assertTrue(octGoalId > 0)

        // Query October 2026 -> Appears
        val octGoals = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(1, octGoals.size)
        assertEquals("Grammar Course", octGoals[0].name)
        assertEquals("2026-10", octGoals[0].yearMonth)

        // Query September 2026 -> Must NOT appear
        val sepGoals = dao.getActiveGoalsForMonth("2026-09").first()
        assertTrue(sepGoals.isEmpty())

        // Query November 2026 -> Must NOT appear
        val novGoals = dao.getActiveGoalsForMonth("2026-11").first()
        assertTrue(novGoals.isEmpty())
    }

    @Test
    fun testIndependentPursuitsPerMonth() = runBlocking {
        // October: Grammar Course
        dao.insertGoal(
            TimeGoal(
                name = "Grammar Course",
                dailyTargetMinutes = 60,
                startDate = "2026-10-01",
                yearMonth = "2026-10"
            )
        )

        // November: Advanced Vocabulary
        dao.insertGoal(
            TimeGoal(
                name = "Advanced Vocabulary",
                dailyTargetMinutes = 45,
                startDate = "2026-11-01",
                yearMonth = "2026-11"
            )
        )

        val octGoals = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(1, octGoals.size)
        assertEquals("Grammar Course", octGoals[0].name)

        val novGoals = dao.getActiveGoalsForMonth("2026-11").first()
        assertEquals(1, novGoals.size)
        assertEquals("Advanced Vocabulary", novGoals[0].name)

        // Neither leaks to August 2026
        val augGoals = dao.getActiveGoalsForMonth("2026-08").first()
        assertTrue(augGoals.isEmpty())
    }

    @Test
    fun testMonthlyProgressCalculations_isolatedByMonth() {
        val octGoal = TimeGoal(
            id = 10,
            name = "Grammar Course",
            dailyTargetMinutes = 60,
            startDate = "2026-10-01",
            yearMonth = "2026-10"
        )

        val novGoal = TimeGoal(
            id = 20,
            name = "Advanced Vocabulary",
            dailyTargetMinutes = 90,
            startDate = "2026-11-01",
            yearMonth = "2026-11"
        )

        val octYm = YearMonth.of(2026, 10)
        val novYm = YearMonth.of(2026, 11)

        // October progress uses only October goals
        val octProgress = TimeGoalCalculations.calculateMonthlyOverallProgress(
            goals = listOf(octGoal),
            yearMonth = octYm,
            allRecords = emptyList(),
            today = LocalDate.of(2026, 10, 15)
        )
        // October has 31 days * 60 = 1860 planned minutes
        assertEquals(1860, octProgress.totalPlannedMinutes)

        // November progress uses only November goals
        val novProgress = TimeGoalCalculations.calculateMonthlyOverallProgress(
            goals = listOf(novGoal),
            yearMonth = novYm,
            allRecords = emptyList(),
            today = LocalDate.of(2026, 11, 15)
        )
        // November has 30 days * 90 = 2700 planned minutes
        assertEquals(2700, novProgress.totalPlannedMinutes)
    }

    @Test
    fun testHistoricalRecordsPreservation_strictMonthIsolation() = runBlocking {
        // A pursuit created with yearMonth "2026-08", but with records logged in "2026-09"
        val goalId = dao.insertGoal(
            TimeGoal(
                name = "Reading Marathon",
                dailyTargetMinutes = 60,
                startDate = "2026-08-01",
                yearMonth = "2026-08"
            )
        )

        // Record logged in September 2026
        dao.insertOrUpdateRecord(
            TimeGoalRecord(
                goalId = goalId,
                date = "2026-09-12",
                actualMinutes = 60,
                note = "Chapter 4"
            )
        )

        // August sees it because yearMonth == "2026-08"
        val augGoals = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(1, augGoals.size)
        assertEquals("Reading Marathon", augGoals[0].name)

        // September must NOT see the pursuit despite records existing in September (strict month isolation)
        val sepGoals = dao.getActiveGoalsForMonth("2026-09").first()
        assertTrue(sepGoals.isEmpty())

        val sepAllGoals = dao.getAllGoalsForMonth("2026-09").first()
        assertTrue(sepAllGoals.isEmpty())

        // The September time record itself is preserved and intact
        val sepRecords = dao.getRecordsForMonth("2026-09").first()
        assertEquals(1, sepRecords.size)
        assertEquals(goalId, sepRecords[0].goalId)
        assertEquals(60, sepRecords[0].actualMinutes)
        assertEquals("Chapter 4", sepRecords[0].note)

        // October has no records and yearMonth is not October -> Remains absent from October
        val octGoals = dao.getActiveGoalsForMonth("2026-10").first()
        assertTrue(octGoals.isEmpty())
    }

    @Test
    fun testOctoberPursuitWithCrossMonthRecords_visibleOnlyInOctober() = runBlocking {
        val octGoalId = dao.insertGoal(
            TimeGoal(
                name = "Grammar Course",
                dailyTargetMinutes = 60,
                startDate = "2026-10-01",
                yearMonth = "2026-10"
            )
        )

        // Add records in August, September, and November for this goal
        dao.insertOrUpdateRecord(TimeGoalRecord(goalId = octGoalId, date = "2026-08-10", actualMinutes = 30))
        dao.insertOrUpdateRecord(TimeGoalRecord(goalId = octGoalId, date = "2026-09-15", actualMinutes = 45))
        dao.insertOrUpdateRecord(TimeGoalRecord(goalId = octGoalId, date = "2026-11-02", actualMinutes = 60))

        // Appears in October
        val octGoals = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(1, octGoals.size)
        assertEquals("Grammar Course", octGoals[0].name)

        // Does NOT appear in August, September, or November merely because records exist
        assertTrue(dao.getActiveGoalsForMonth("2026-08").first().isEmpty())
        assertTrue(dao.getActiveGoalsForMonth("2026-09").first().isEmpty())
        assertTrue(dao.getActiveGoalsForMonth("2026-11").first().isEmpty())

        // Also check getAllGoalsListForMonth and getActiveGoalsListForMonth
        assertTrue(dao.getActiveGoalsListForMonth("2026-08").isEmpty())
        assertTrue(dao.getAllGoalsListForMonth("2026-08").isEmpty())

        // Verify the records themselves still exist intact
        val augRecords = dao.getRecordsForMonth("2026-08").first()
        assertEquals(1, augRecords.size)
        assertEquals(30, augRecords[0].actualMinutes)

        val sepRecords = dao.getRecordsForMonth("2026-09").first()
        assertEquals(1, sepRecords.size)
        assertEquals(45, sepRecords[0].actualMinutes)

        val novRecords = dao.getRecordsForMonth("2026-11").first()
        assertEquals(1, novRecords.size)
        assertEquals(60, novRecords[0].actualMinutes)
    }

    @Test
    fun testLegacyRowWithBlankYearMonth_resolvesUsingStartDate() = runBlocking {
        // Legacy pursuit with blank yearMonth and startDate in August 2026
        dao.insertGoal(
            TimeGoal(
                name = "Legacy Painting",
                dailyTargetMinutes = 30,
                startDate = "2026-08-15",
                yearMonth = ""
            )
        )

        // August sees it via fallback to substr(startDate, 1, 7)
        val augGoals = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(1, augGoals.size)
        assertEquals("Legacy Painting", augGoals[0].name)

        // September does not see it
        val sepGoals = dao.getActiveGoalsForMonth("2026-09").first()
        assertTrue(sepGoals.isEmpty())
    }

    @Test
    fun testBackupAndRestore_preservesYearMonthAndHandlesLegacy() {
        val backupJson = JSONObject().apply {
            put("version", 2)
            put("appVersion", "2.0")
            put("exportTimestamp", System.currentTimeMillis())
            put("tasks", JSONArray())

            val goalsArr = JSONArray()
            // Modern goal with explicit yearMonth
            goalsArr.put(JSONObject().apply {
                put("id", 101L)
                put("name", "Grammar Course")
                put("dailyTargetMinutes", 60)
                put("colorHex", "#7C4DFF")
                put("iconName", "book")
                put("startDate", "2026-10-01")
                put("yearMonth", "2026-10")
                put("isArchived", false)
                put("createdAt", 1600000000000L)
                put("orderIndex", 0)
            })
            // Legacy goal without yearMonth (must fallback to startDate substring)
            goalsArr.put(JSONObject().apply {
                put("id", 102L)
                put("name", "Legacy Piano")
                put("dailyTargetMinutes", 45)
                put("colorHex", "#1E88E5")
                put("iconName", "music")
                put("startDate", "2026-07-01")
                put("isArchived", false)
                put("createdAt", 1600000000000L)
                put("orderIndex", 1)
            })
            put("timeGoals", goalsArr)
            put("timeGoalRecords", JSONArray())
            put("timeGoalMonthColors", JSONArray())
            put("preferences", JSONObject())
        }

        val payloadBytes = backupJson.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val manifest = JSONObject().apply {
            put("formatVersion", NudgeBackupManager.BACKUP_FORMAT_VERSION)
            put("app", NudgeBackupManager.APP_IDENTIFIER)
            put("schemaVersion", NudgeBackupManager.CURRENT_SCHEMA_VERSION)
            put("timestamp", System.currentTimeMillis())
            put("createdAtIso", "2026-10-15T00:00:00Z")
            put("payloadChecksum", checksum)
            put("tasksCount", 0)
            put("timeGoalsCount", 2)
            put("timeGoalRecordsCount", 0)
            put("attachmentsCount", 0)
        }

        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zos ->
            zos.putNextEntry(ZipEntry(NudgeBackupManager.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(NudgeBackupManager.PAYLOAD_ENTRY_NAME))
            zos.write(payloadBytes)
            zos.closeEntry()
        }

        val validation = NudgeBackupManager.validateBackupBytes(byteOut.toByteArray())
        assertTrue(validation.isValid)

        val parsedGoals = validation.timeGoals
        assertEquals(2, parsedGoals.size)

        val modernGoal = parsedGoals.first { it.id == 101L }
        assertEquals("2026-10", modernGoal.yearMonth)

        val legacyGoal = parsedGoals.first { it.id == 102L }
        assertEquals("2026-07", legacyGoal.yearMonth)
    }

    @Test
    fun testMultiMonthPdfExport_eachMonthHasOwnGoals() {
        val octGoal = TimeGoal(
            id = 1,
            name = "Grammar Course",
            dailyTargetMinutes = 60,
            startDate = "2026-10-01",
            yearMonth = "2026-10"
        )
        val novGoal = TimeGoal(
            id = 2,
            name = "Advanced Vocabulary",
            dailyTargetMinutes = 45,
            startDate = "2026-11-01",
            yearMonth = "2026-11"
        )

        val octData = MonthReportData(
            yearMonth = YearMonth.of(2026, 10),
            goals = listOf(octGoal),
            records = emptyList()
        )
        val novData = MonthReportData(
            yearMonth = YearMonth.of(2026, 11),
            goals = listOf(novGoal),
            records = emptyList()
        )

        assertEquals("Grammar Course", octData.goals.single().name)
        assertEquals("Advanced Vocabulary", novData.goals.single().name)

        val pdfFile = TimeGoalsPdfExporter.generateMultiMonthPdf(
            context = context,
            monthsData = listOf(octData, novData)
        )
        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)
    }

    @Test
    fun testMonthSpecificPursuitDeletion_preservesOtherMonthsAndRecords() = runBlocking {
        // 1. Create August pursuit with name "Reading Marathon"
        val augGoal = TimeGoal(
            name = "Reading Marathon",
            dailyTargetMinutes = 60,
            startDate = "2026-08-01",
            yearMonth = "2026-08"
        )
        val augGoalId = dao.insertGoal(augGoal)
        val savedAugGoal = augGoal.copy(id = augGoalId)

        // Add daily record for August pursuit
        dao.insertOrUpdateRecord(
            TimeGoalRecord(
                goalId = augGoalId,
                date = "2026-08-10",
                actualMinutes = 30,
                note = "August reading"
            )
        )

        // Create an unrelated pursuit in August
        val augUnrelatedGoal = TimeGoal(
            name = "Morning Jog",
            dailyTargetMinutes = 20,
            startDate = "2026-08-01",
            yearMonth = "2026-08"
        )
        val augUnrelatedId = dao.insertGoal(augUnrelatedGoal)
        dao.insertOrUpdateRecord(
            TimeGoalRecord(
                goalId = augUnrelatedId,
                date = "2026-08-11",
                actualMinutes = 20
            )
        )

        // 2. Create an independent October pursuit with the EXACT SAME TITLE "Reading Marathon"
        val octGoal = TimeGoal(
            name = "Reading Marathon",
            dailyTargetMinutes = 90,
            startDate = "2026-10-01",
            yearMonth = "2026-10"
        )
        val octGoalId = dao.insertGoal(octGoal)

        // Add daily record for October pursuit
        dao.insertOrUpdateRecord(
            TimeGoalRecord(
                goalId = octGoalId,
                date = "2026-10-15",
                actualMinutes = 45,
                note = "October reading"
            )
        )

        // Create an unrelated pursuit in October
        val octUnrelatedGoal = TimeGoal(
            name = "Yoga",
            dailyTargetMinutes = 30,
            startDate = "2026-10-01",
            yearMonth = "2026-10"
        )
        val octUnrelatedId = dao.insertGoal(octUnrelatedGoal)
        dao.insertOrUpdateRecord(
            TimeGoalRecord(
                goalId = octUnrelatedId,
                date = "2026-10-16",
                actualMinutes = 30
            )
        )

        // Verify initial state:
        // August sees both August pursuits
        val augGoalsBefore = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(2, augGoalsBefore.size)
        assertTrue(augGoalsBefore.any { it.id == augGoalId })
        assertTrue(augGoalsBefore.any { it.id == augUnrelatedId })

        // October sees both October pursuits
        val octGoalsBefore = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(2, octGoalsBefore.size)
        assertTrue(octGoalsBefore.any { it.id == octGoalId })
        assertTrue(octGoalsBefore.any { it.id == octUnrelatedId })

        // 3. Delete the August pursuit via repository
        repository.deleteTimeGoal(savedAugGoal)

        // 4. Confirm August no longer displays its deleted pursuit
        val augGoalsAfter = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(1, augGoalsAfter.size)
        assertEquals(augUnrelatedId, augGoalsAfter[0].id)
        assertEquals("Morning Jog", augGoalsAfter[0].name)

        // Confirm August deleted pursuit's records were removed
        val augRecordsForDeletedGoal = dao.getRecordsForGoal(augGoalId).first()
        assertTrue(augRecordsForDeletedGoal.isEmpty())

        // Confirm August unrelated pursuit's records remain intact
        val augUnrelatedRecords = dao.getRecordsForGoal(augUnrelatedId).first()
        assertEquals(1, augUnrelatedRecords.size)
        assertEquals(20, augUnrelatedRecords[0].actualMinutes)

        // 5. Confirm the October pursuit with same title and all its records remain INTACT
        val octGoalsAfter = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(2, octGoalsAfter.size)
        val octSameNameGoal = octGoalsAfter.first { it.id == octGoalId }
        assertEquals("Reading Marathon", octSameNameGoal.name)
        assertEquals("2026-10", octSameNameGoal.yearMonth)
        assertEquals(90, octSameNameGoal.dailyTargetMinutes)

        val octRecords = dao.getRecordsForGoal(octGoalId).first()
        assertEquals(1, octRecords.size)
        assertEquals(45, octRecords[0].actualMinutes)
        assertEquals("October reading", octRecords[0].note)
        assertEquals("2026-10-15", octRecords[0].date)

        // Confirm October unrelated pursuit remains intact
        val octUnrelatedGoalAfter = octGoalsAfter.first { it.id == octUnrelatedId }
        assertEquals("Yoga", octUnrelatedGoalAfter.name)
        val octUnrelatedRecords = dao.getRecordsForGoal(octUnrelatedId).first()
        assertEquals(1, octUnrelatedRecords.size)

        // 6. Confirm Step 1's strict month-specific visibility behavior still passes:
        // October pursuit never appears in August or September
        assertTrue(dao.getActiveGoalsForMonth("2026-08").first().none { it.name == "Reading Marathon" })
        assertTrue(dao.getActiveGoalsForMonth("2026-09").first().isEmpty())
    }

    @Test
    fun testRestoreSchemaV7LegacyBackup_multiMonthPursuitVisibilityRegression() = runBlocking {
        // 1. Build a schema-v7 legacy backup payload
        // Legacy pursuit: "Exercise" (startDate: "2026-08-01", NO yearMonth property in JSON)
        // Modern pursuit: "Grammar Course" (startDate: "2026-10-01", explicit yearMonth: "2026-10")
        val backupJson = JSONObject().apply {
            put("tasks", JSONArray())

            val goalsArr = JSONArray()
            // Legacy pursuit (schema-v7) without yearMonth
            goalsArr.put(JSONObject().apply {
                put("id", 1L)
                put("name", "Exercise")
                put("dailyTargetMinutes", 30)
                put("colorHex", "#4CAF50")
                put("iconName", "fitness")
                put("startDate", "2026-08-01")
                put("isArchived", false)
                put("createdAt", 1722470400000L)
                put("orderIndex", 0)
                // Note: No "yearMonth" field in schema-v7
            })
            // Modern October pursuit with explicit yearMonth
            goalsArr.put(JSONObject().apply {
                put("id", 2L)
                put("name", "Grammar Course")
                put("dailyTargetMinutes", 60)
                put("colorHex", "#7C4DFF")
                put("iconName", "book")
                put("startDate", "2026-10-01")
                put("yearMonth", "2026-10")
                put("isArchived", false)
                put("createdAt", 1727740800000L)
                put("orderIndex", 1)
            })
            put("timeGoals", goalsArr)

            // Records: Exercise has records spanning August, September, and October (all pointing to goalId 1)
            val recordsArr = JSONArray()
            recordsArr.put(JSONObject().apply {
                put("id", 101L)
                put("goalId", 1L)
                put("date", "2026-08-15")
                put("actualMinutes", 30)
                put("note", "August workout")
                put("updatedAt", 1723723200000L)
            })
            recordsArr.put(JSONObject().apply {
                put("id", 102L)
                put("goalId", 1L)
                put("date", "2026-09-15")
                put("actualMinutes", 35)
                put("note", "September workout")
                put("updatedAt", 1726401600000L)
            })
            recordsArr.put(JSONObject().apply {
                put("id", 103L)
                put("goalId", 1L)
                put("date", "2026-10-15")
                put("actualMinutes", 40)
                put("note", "October workout")
                put("updatedAt", 1729000000000L)
            })
            // Record for modern October pursuit
            recordsArr.put(JSONObject().apply {
                put("id", 104L)
                put("goalId", 2L)
                put("date", "2026-10-20")
                put("actualMinutes", 60)
                put("note", "Grammar Lesson 1")
                put("updatedAt", 1729432000000L)
            })
            put("timeGoalRecords", recordsArr)

            put("timeGoalMonthColors", JSONArray())
            put("preferences", JSONObject())
        }

        val payloadBytes = backupJson.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val manifest = JSONObject().apply {
            put("formatVersion", NudgeBackupManager.BACKUP_FORMAT_VERSION)
            put("app", NudgeBackupManager.APP_IDENTIFIER)
            put("schemaVersion", 7) // Schema version 7 legacy backup
            put("timestamp", System.currentTimeMillis())
            put("createdAtIso", "2026-10-15T00:00:00Z")
            put("payloadChecksum", checksum)
            put("tasksCount", 0)
            put("timeGoalsCount", 2)
            put("timeGoalRecordsCount", 4)
            put("attachmentsCount", 0)
        }

        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zos ->
            zos.putNextEntry(ZipEntry(NudgeBackupManager.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(NudgeBackupManager.PAYLOAD_ENTRY_NAME))
            zos.write(payloadBytes)
            zos.closeEntry()
        }

        // 2. Validate and restore via NudgeBackupManager
        val validation = NudgeBackupManager.validateBackupBytes(byteOut.toByteArray())
        assertTrue("Legacy schema-v7 backup must validate successfully", validation.isValid)

        val prefs = context.getSharedPreferences("test_scoping_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        NudgeBackupManager.restoreValidatedBackup(context, validation, db, prefs)

        // 3. Verify records across all three months are preserved intact in database (No Data Loss)
        val augRecords = dao.getRecordsForMonth("2026-08").first()
        assertEquals(1, augRecords.size)
        assertEquals("2026-08-15", augRecords[0].date)
        assertEquals(30, augRecords[0].actualMinutes)

        val sepRecords = dao.getRecordsForMonth("2026-09").first()
        assertEquals(1, sepRecords.size)
        assertEquals("2026-09-15", sepRecords[0].date)
        assertEquals(35, sepRecords[0].actualMinutes)

        val octRecords = dao.getRecordsForMonth("2026-10").first()
        assertEquals(2, octRecords.size)

        // 4. Verify modern October pursuit isolation (Step 1 behavior preserved)
        // Modern October pursuit must appear in October, but NEVER in August or September
        val augGoals = dao.getActiveGoalsForMonth("2026-08").first()
        assertTrue("Modern October pursuit must NOT appear in August", augGoals.none { it.name == "Grammar Course" })

        val sepGoals = dao.getActiveGoalsForMonth("2026-09").first()
        assertTrue("Modern October pursuit must NOT appear in September", sepGoals.none { it.name == "Grammar Course" })

        val octGoals = dao.getActiveGoalsForMonth("2026-10").first()
        assertTrue("Modern October pursuit must appear in October", octGoals.any { it.name == "Grammar Course" })

        // 5. Verify legacy pursuit visibility in its start month (August 2026)
        assertTrue("Legacy pursuit 'Exercise' must appear in August 2026", augGoals.any { it.name == "Exercise" })

        // 6. REGRESSION ASSERTIONS:
        // In the intended post-fix behavior, the legacy pursuit 'Exercise' must also be visible
        // in September and October 2026 because it has active time records in those months.
        // UNDER THE CURRENT IMPLEMENTATION, THIS FAILS:
        // NudgeBackupManager assigned yearMonth="2026-08" to Exercise, so getActiveGoalsForMonth("2026-09")
        // returns empty list, and getActiveGoalsForMonth("2026-10") returns only Grammar Course.
        assertTrue(
            "REGRESSION BUG: Legacy pursuit 'Exercise' with September records must be visible in September 2026",
            sepGoals.any { it.name == "Exercise" }
        )
        assertTrue(
            "REGRESSION BUG: Legacy pursuit 'Exercise' with October records must be visible in October 2026",
            octGoals.any { it.name == "Exercise" }
        )
    }

    @Test
    fun testLegacyMultiMonthPursuit_deletionIsolation_deletingOctoberPursuitPreservesAugustAndSeptember() = runBlocking {
        // Setup legacy backup with "Exercise" spanning Aug/Sep/Oct and "Grammar Course" in Oct
        val backupJson = JSONObject().apply {
            put("tasks", JSONArray())
            val goalsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1L)
                    put("name", "Exercise")
                    put("dailyTargetMinutes", 30)
                    put("colorHex", "#4CAF50")
                    put("iconName", "fitness")
                    put("startDate", "2026-08-01")
                    put("isArchived", false)
                    put("createdAt", 1722470400000L)
                    put("orderIndex", 0)
                })
                put(JSONObject().apply {
                    put("id", 2L)
                    put("name", "Grammar Course")
                    put("dailyTargetMinutes", 60)
                    put("colorHex", "#7C4DFF")
                    put("iconName", "book")
                    put("startDate", "2026-10-01")
                    put("yearMonth", "2026-10")
                    put("isArchived", false)
                    put("createdAt", 1727740800000L)
                    put("orderIndex", 1)
                })
            }
            put("timeGoals", goalsArr)

            val recordsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 101L)
                    put("goalId", 1L)
                    put("date", "2026-08-15")
                    put("actualMinutes", 30)
                    put("note", "August workout")
                    put("updatedAt", 1723723200000L)
                })
                put(JSONObject().apply {
                    put("id", 102L)
                    put("goalId", 1L)
                    put("date", "2026-09-15")
                    put("actualMinutes", 35)
                    put("note", "September workout")
                    put("updatedAt", 1726401600000L)
                })
                put(JSONObject().apply {
                    put("id", 103L)
                    put("goalId", 1L)
                    put("date", "2026-10-15")
                    put("actualMinutes", 40)
                    put("note", "October workout")
                    put("updatedAt", 1729000000000L)
                })
                put(JSONObject().apply {
                    put("id", 104L)
                    put("goalId", 2L)
                    put("date", "2026-10-20")
                    put("actualMinutes", 60)
                    put("note", "Lesson 1")
                    put("updatedAt", 1729432000000L)
                })
            }
            put("timeGoalRecords", recordsArr)
            put("timeGoalMonthColors", JSONArray())
            put("preferences", JSONObject())
        }

        val payloadBytes = backupJson.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val manifest = JSONObject().apply {
            put("formatVersion", NudgeBackupManager.BACKUP_FORMAT_VERSION)
            put("app", NudgeBackupManager.APP_IDENTIFIER)
            put("schemaVersion", 7)
            put("timestamp", System.currentTimeMillis())
            put("payloadChecksum", checksum)
            put("tasksCount", 0)
            put("timeGoalsCount", 2)
            put("timeGoalRecordsCount", 4)
            put("attachmentsCount", 0)
        }

        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zos ->
            zos.putNextEntry(ZipEntry(NudgeBackupManager.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(NudgeBackupManager.PAYLOAD_ENTRY_NAME))
            zos.write(payloadBytes)
            zos.closeEntry()
        }

        val validation = NudgeBackupManager.validateBackupBytes(byteOut.toByteArray())
        val prefs = context.getSharedPreferences("test_isolation_prefs", Context.MODE_PRIVATE)
        NudgeBackupManager.restoreValidatedBackup(context, validation, db, prefs)

        // 1. Initial verification: October has both Grammar Course and Exercise
        val octGoalsInitial = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(2, octGoalsInitial.size)
        val grammarGoal = octGoalsInitial.first { it.name == "Grammar Course" }
        val octExerciseGoal = octGoalsInitial.first { it.name == "Exercise" }

        // 2. Delete October's "Grammar Course" via repository
        repository.deleteTimeGoal(grammarGoal)

        // Confirm Grammar Course is removed from October, Exercise still present
        val octGoalsAfterGrammarDelete = dao.getActiveGoalsForMonth("2026-10").first()
        assertEquals(1, octGoalsAfterGrammarDelete.size)
        assertEquals("Exercise", octGoalsAfterGrammarDelete[0].name)

        // Confirm August and September still have Exercise intact with their records
        val augGoals = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(1, augGoals.size)
        assertEquals("Exercise", augGoals[0].name)
        val augRecords = dao.getRecordsForMonth("2026-08").first()
        assertEquals(1, augRecords.size)
        assertEquals(30, augRecords[0].actualMinutes)

        val sepGoals = dao.getActiveGoalsForMonth("2026-09").first()
        assertEquals(1, sepGoals.size)
        assertEquals("Exercise", sepGoals[0].name)
        val sepRecords = dao.getRecordsForMonth("2026-09").first()
        assertEquals(1, sepRecords.size)
        assertEquals(35, sepRecords[0].actualMinutes)

        // 3. Delete October's "Exercise" via repository
        repository.deleteTimeGoal(octExerciseGoal)

        // Confirm October now has NO active pursuits
        val octGoalsAfterExerciseDelete = dao.getActiveGoalsForMonth("2026-10").first()
        assertTrue(octGoalsAfterExerciseDelete.isEmpty())

        // Confirm August and September Exercise and records STILL remain intact (isolated deletion)
        val augGoalsFinal = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(1, augGoalsFinal.size)
        assertEquals("Exercise", augGoalsFinal[0].name)
        val augRecordsFinal = dao.getRecordsForMonth("2026-08").first()
        assertEquals(1, augRecordsFinal.size)
        assertEquals("August workout", augRecordsFinal[0].note)

        val sepGoalsFinal = dao.getActiveGoalsForMonth("2026-09").first()
        assertEquals(1, sepGoalsFinal.size)
        assertEquals("Exercise", sepGoalsFinal[0].name)
        val sepRecordsFinal = dao.getRecordsForMonth("2026-09").first()
        assertEquals(1, sepRecordsFinal.size)
        assertEquals("September workout", sepRecordsFinal[0].note)
    }

    @Test
    fun testModernBackupRestore_preservesDataWithoutDuplicationOrIdReassignment() = runBlocking {
        // Modern backup with schemaVersion = 8 and explicit yearMonth
        val backupJson = JSONObject().apply {
            put("tasks", JSONArray())
            val goalsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 10L)
                    put("name", "August Swimming")
                    put("dailyTargetMinutes", 45)
                    put("colorHex", "#00BCD4")
                    put("iconName", "fitness")
                    put("startDate", "2026-08-01")
                    put("yearMonth", "2026-08")
                    put("isArchived", false)
                    put("createdAt", 1722470400000L)
                    put("orderIndex", 0)
                })
                put(JSONObject().apply {
                    put("id", 20L)
                    put("name", "October Coding")
                    put("dailyTargetMinutes", 120)
                    put("colorHex", "#FF5722")
                    put("iconName", "code")
                    put("startDate", "2026-10-01")
                    put("yearMonth", "2026-10")
                    put("isArchived", false)
                    put("createdAt", 1727740800000L)
                    put("orderIndex", 1)
                })
            }
            put("timeGoals", goalsArr)

            val recordsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 101L)
                    put("goalId", 10L)
                    put("date", "2026-08-10")
                    put("actualMinutes", 45)
                    put("note", "Freestyle drills")
                    put("updatedAt", 1723300000000L)
                })
                put(JSONObject().apply {
                    put("id", 102L)
                    put("goalId", 20L)
                    put("date", "2026-10-15")
                    put("actualMinutes", 120)
                    put("note", "Kotlin coroutines")
                    put("updatedAt", 1729000000000L)
                })
            }
            put("timeGoalRecords", recordsArr)

            val monthColorsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("goalId", 10L)
                    put("yearMonth", "2026-08")
                    put("colorHex", "#00BCD4")
                    put("updatedAt", 1722470400000L)
                })
                put(JSONObject().apply {
                    put("goalId", 20L)
                    put("yearMonth", "2026-10")
                    put("colorHex", "#FF5722")
                    put("updatedAt", 1727740800000L)
                })
            }
            put("timeGoalMonthColors", monthColorsArr)
            put("preferences", JSONObject())
        }

        val payloadBytes = backupJson.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val manifest = JSONObject().apply {
            put("formatVersion", NudgeBackupManager.BACKUP_FORMAT_VERSION)
            put("app", NudgeBackupManager.APP_IDENTIFIER)
            put("schemaVersion", NudgeBackupManager.CURRENT_SCHEMA_VERSION)
            put("timestamp", System.currentTimeMillis())
            put("payloadChecksum", checksum)
            put("tasksCount", 0)
            put("timeGoalsCount", 2)
            put("timeGoalRecordsCount", 2)
            put("attachmentsCount", 0)
        }

        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zos ->
            zos.putNextEntry(ZipEntry(NudgeBackupManager.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(NudgeBackupManager.PAYLOAD_ENTRY_NAME))
            zos.write(payloadBytes)
            zos.closeEntry()
        }

        val validation = NudgeBackupManager.validateBackupBytes(byteOut.toByteArray())
        val prefs = context.getSharedPreferences("test_modern_prefs", Context.MODE_PRIVATE)
        NudgeBackupManager.restoreValidatedBackup(context, validation, db, prefs)

        // Verify goals count, IDs, and yearMonth
        val allGoalsInDb = dao.getAllGoalsList()
        assertEquals("Modern backup must not produce duplicate goals", 2, allGoalsInDb.size)
        val g10 = allGoalsInDb.first { it.id == 10L }
        assertEquals("August Swimming", g10.name)
        assertEquals("2026-08", g10.yearMonth)

        val g20 = allGoalsInDb.first { it.id == 20L }
        assertEquals("October Coding", g20.name)
        assertEquals("2026-10", g20.yearMonth)

        // Verify records preserve exact goalId and values
        val allRecordsInDb = dao.getAllRecordsList()
        assertEquals(2, allRecordsInDb.size)
        val r101 = allRecordsInDb.first { it.id == 101L }
        assertEquals(10L, r101.goalId)
        assertEquals(45, r101.actualMinutes)
        assertEquals("Freestyle drills", r101.note)

        val r102 = allRecordsInDb.first { it.id == 102L }
        assertEquals(20L, r102.goalId)
        assertEquals(120, r102.actualMinutes)
        assertEquals("Kotlin coroutines", r102.note)

        // Verify month colors preserve exact goalId
        val allColorsInDb = dao.getAllMonthColorsList()
        assertEquals(2, allColorsInDb.size)
        assertTrue(allColorsInDb.any { it.goalId == 10L && it.yearMonth == "2026-08" && it.colorHex == "#00BCD4" })
        assertTrue(allColorsInDb.any { it.goalId == 20L && it.yearMonth == "2026-10" && it.colorHex == "#FF5722" })
    }

    @Test
    fun testRepeatingLegacyRestore_idempotentWithoutDuplicationOrRecordLoss() = runBlocking {
        // Construct realistic legacy backup with 2 goals spanning across 3 months
        val backupJson = JSONObject().apply {
            put("tasks", JSONArray())
            val goalsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1L)
                    put("name", "Deep Work")
                    put("dailyTargetMinutes", 120)
                    put("colorHex", "#3F51B5")
                    put("iconName", "work")
                    put("startDate", "2026-07-01")
                    put("isArchived", false)
                    put("createdAt", 1720000000000L)
                    put("orderIndex", 0)
                })
                put(JSONObject().apply {
                    put("id", 2L)
                    put("name", "Reading")
                    put("dailyTargetMinutes", 30)
                    put("colorHex", "#4CAF50")
                    put("iconName", "book")
                    put("startDate", "2026-08-01")
                    put("isArchived", false)
                    put("createdAt", 1722470400000L)
                    put("orderIndex", 1)
                })
            }
            put("timeGoals", goalsArr)

            val recordsArr = JSONArray().apply {
                // Deep Work has records in July, August, and September
                put(JSONObject().apply {
                    put("id", 1001L)
                    put("goalId", 1L)
                    put("date", "2026-07-10")
                    put("actualMinutes", 120)
                    put("note", "July sprint")
                    put("updatedAt", 1720600000000L)
                })
                put(JSONObject().apply {
                    put("id", 1002L)
                    put("goalId", 1L)
                    put("date", "2026-08-10")
                    put("actualMinutes", 90)
                    put("note", "August focus")
                    put("updatedAt", 1723300000000L)
                })
                put(JSONObject().apply {
                    put("id", 1003L)
                    put("goalId", 1L)
                    put("date", "2026-09-10")
                    put("actualMinutes", 110)
                    put("note", "September refactor")
                    put("updatedAt", 1725900000000L)
                })
                // Reading has records in August and September, plus a month color in September
                put(JSONObject().apply {
                    put("id", 1004L)
                    put("goalId", 2L)
                    put("date", "2026-08-20")
                    put("actualMinutes", 30)
                    put("note", "August book")
                    put("updatedAt", 1724100000000L)
                })
                put(JSONObject().apply {
                    put("id", 1005L)
                    put("goalId", 2L)
                    put("date", "2026-09-20")
                    put("actualMinutes", 45)
                    put("note", "September book")
                    put("updatedAt", 1726800000000L)
                })
            }
            put("timeGoalRecords", recordsArr)

            val monthColorsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("goalId", 2L)
                    put("yearMonth", "2026-09")
                    put("colorHex", "#E91E63")
                    put("updatedAt", 1726800000000L)
                })
            }
            put("timeGoalMonthColors", monthColorsArr)
            put("preferences", JSONObject())
        }

        val payloadBytes = backupJson.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val manifest = JSONObject().apply {
            put("formatVersion", NudgeBackupManager.BACKUP_FORMAT_VERSION)
            put("app", NudgeBackupManager.APP_IDENTIFIER)
            put("schemaVersion", 7)
            put("timestamp", System.currentTimeMillis())
            put("payloadChecksum", checksum)
            put("tasksCount", 0)
            put("timeGoalsCount", 2)
            put("timeGoalRecordsCount", 5)
            put("attachmentsCount", 0)
        }

        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zos ->
            zos.putNextEntry(ZipEntry(NudgeBackupManager.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(NudgeBackupManager.PAYLOAD_ENTRY_NAME))
            zos.write(payloadBytes)
            zos.closeEntry()
        }

        val backupBytes = byteOut.toByteArray()
        val prefs = context.getSharedPreferences("test_repeat_prefs", Context.MODE_PRIVATE)

        // First restore
        val val1 = NudgeBackupManager.validateBackupBytes(backupBytes)
        assertTrue(val1.isValid)
        NudgeBackupManager.restoreValidatedBackup(context, val1, db, prefs)

        val recordsCountAfterFirst = dao.getAllRecordsList().size
        assertEquals(5, recordsCountAfterFirst)

        // Switching between months: check queries
        val julGoals = dao.getActiveGoalsForMonth("2026-07").first()
        assertEquals(1, julGoals.size)
        assertEquals("Deep Work", julGoals[0].name)

        val augGoals = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(2, augGoals.size)
        assertTrue(augGoals.any { it.name == "Deep Work" })
        assertTrue(augGoals.any { it.name == "Reading" })

        val sepGoals = dao.getActiveGoalsForMonth("2026-09").first()
        assertEquals(2, sepGoals.size)
        assertTrue(sepGoals.any { it.name == "Deep Work" })
        assertTrue(sepGoals.any { it.name == "Reading" })

        // Check month color was preserved and associated with September Reading goal
        val sepReadingGoal = sepGoals.first { it.name == "Reading" }
        val sepColor = dao.getMonthColor(sepReadingGoal.id, "2026-09")
        assertNotNull(sepColor)
        assertEquals("#E91E63", sepColor?.colorHex)

        // Second restore (repeating restore with same backup)
        val val2 = NudgeBackupManager.validateBackupBytes(backupBytes)
        assertTrue(val2.isValid)
        NudgeBackupManager.restoreValidatedBackup(context, val2, db, prefs)

        // Verify no duplicate records created after second restore
        val recordsCountAfterSecond = dao.getAllRecordsList().size
        assertEquals(5, recordsCountAfterSecond)

        // Verify month scoping still behaves consistently
        val julGoals2 = dao.getActiveGoalsForMonth("2026-07").first()
        assertEquals(1, julGoals2.size)
        assertEquals("Deep Work", julGoals2[0].name)

        val augGoals2 = dao.getActiveGoalsForMonth("2026-08").first()
        assertEquals(2, augGoals2.size)

        val sepGoals2 = dao.getActiveGoalsForMonth("2026-09").first()
        assertEquals(2, sepGoals2.size)
    }
}

