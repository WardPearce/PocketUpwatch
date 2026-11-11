package com.pocketupwatch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitorDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, MonitorDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private val template = ServiceEntry(name = "API", url = "https://example.com", intervalSeconds = 300)
    @After fun close() { db.close() }
    @Test fun longRangeStatisticsIncludeChecksOlderThanFiveHundredSamples() = runBlocking {
        val id = dao.add(template)
        repeat(600) { index -> dao.record(CheckEntry(serviceId = id, time = index * 300_000L, up = index >= 50, responseMs = 10, detail = "HTTP 200")) }
        assertEquals(500, dao.history(id).first().size)
        assertEquals(600L, dao.stats(id, 0).first().let { it.upCount + it.downCount })
        assertEquals(50L, dao.stats(id, 0).first().downCount)
        assertEquals(600L, dao.buckets(id, 0, 3_600_000).first().sumOf { it.upCount + it.downCount })
        assertEquals(50, dao.page(id, 0, 50, 550).size)
    }
    @Test fun incidentsAndRollupsAreTransactionalAndUnknownIsExcluded() = runBlocking {
        val id = dao.add(template)
        val service = dao.getService(id)!!
        fun check(up: Boolean, time: Long, skipped: Boolean = false) = CheckEntry(serviceId = id, time = time, up = up, responseMs = 10, detail = "test", skipped = skipped)
        assertNull(dao.recordIfUnchanged(service, check(false, 1_000)))
        assertEquals("is down", dao.recordIfUnchanged(service, check(false, 2_000)))
        assertNull(dao.recordIfUnchanged(service, check(false, 3_000, true)))
        val ongoing = dao.incidents(id).first().single()
        assertEquals(1_000L, ongoing.startedAt)
        assertEquals(2, ongoing.downChecks)
        assertEquals(1, ongoing.unknownChecks)
        assertEquals("recovered", dao.recordIfUnchanged(service, check(true, 4_000)))
        assertEquals(4_000L, dao.incidents(id).first().single().endedAt)
        val stats = dao.stats(id, 0).first()
        assertEquals(1L, stats.upCount)
        assertEquals(2L, stats.downCount)
        assertEquals(1L, stats.skippedCount)
        assertEquals(4L, dao.rollups(id, 0).first().sumOf { it.upCount + it.downCount + it.skippedCount })
    }
    @Test fun editsAndDeletesRejectResultsAndCascadeHistory() = runBlocking {
        val id = dao.add(template)
        val old = dao.getService(id)!!
        dao.update(old.copy(name = "Changed"))
        assertNull(dao.recordIfUnchanged(old, CheckEntry(serviceId = id, time = 1_000, up = false, responseMs = 10, detail = "old config")))
        assertNull(dao.latest(id))
        val current = dao.getService(id)!!
        dao.recordIfUnchanged(current, CheckEntry(serviceId = id, time = 2_000, up = true, responseMs = 10, detail = "new config"))
        dao.delete(current)
        assertNull(dao.latest(id))
        assertTrue(dao.rollups(id, 0).first().isEmpty())
    }
    @Test fun retentionKeepsLatestResultAndSummaries() = runBlocking {
        val id = dao.add(template)
        val service = dao.getService(id)!!
        dao.recordIfUnchanged(service, CheckEntry(serviceId = id, time = 1_000, up = true, responseMs = 10, detail = "one"))
        dao.recordIfUnchanged(service, CheckEntry(serviceId = id, time = 2_000, up = true, responseMs = 20, detail = "two"))
        dao.pruneChecks(5_000)
        assertEquals(1, dao.history(id).first().size)
        assertEquals(2L, dao.rollups(id, 0).first().single().upCount)
    }
    @Test fun migrationFromVersionThreePreservesMonitorsAndHistory() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            sql.execSQL("CREATE TABLE services (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, intervalSeconds INTEGER NOT NULL, enabled INTEGER NOT NULL, expectedCode INTEGER NOT NULL, bodyType TEXT NOT NULL, expectedBody TEXT NOT NULL)")
            sql.execSQL("CREATE TABLE checks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, serviceId INTEGER NOT NULL, time INTEGER NOT NULL, up INTEGER NOT NULL, responseMs INTEGER NOT NULL, detail TEXT NOT NULL, FOREIGN KEY(serviceId) REFERENCES services(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            sql.execSQL("CREATE INDEX index_checks_serviceId ON checks(serviceId)")
            sql.execSQL("INSERT INTO services VALUES (1,'Existing','https://example.com',300,1,0,'None','')")
            sql.execSQL("INSERT INTO checks VALUES (1,1,1000,1,42,'HTTP 200')")
            sql.version = 3
        }
        val migrated = Room.databaseBuilder(context, MonitorDb::class.java, name).addMigrations(MonitorDb.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals("Existing", migrated.dao().getService(1)!!.name)
            assertEquals(2, migrated.dao().getService(1)!!.failureThreshold)
            assertEquals(42L, migrated.dao().latest(1)!!.responseMs)
            assertEquals(1L, migrated.dao().rollups(1, 0).first().single().upCount)
            assertEquals(0, migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.count })
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationChainsFromVersionsOneAndTwoPreserveAndNormalizeData() = runBlocking {
        for (version in 1..2) {
            val name = "migration-chain-$version.db"
            context.deleteDatabase(name)
            val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
                sql.execSQL("CREATE TABLE services (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, intervalSeconds INTEGER NOT NULL)")
                sql.execSQL("CREATE TABLE checks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, serviceId INTEGER NOT NULL, time INTEGER NOT NULL, up INTEGER NOT NULL, responseMs INTEGER NOT NULL, detail TEXT NOT NULL, FOREIGN KEY(serviceId) REFERENCES services(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                sql.execSQL("CREATE INDEX index_checks_serviceId ON checks(serviceId)")
                sql.execSQL("INSERT INTO services VALUES (1,'Original','https://example.com',60)")
                sql.execSQL("INSERT INTO checks VALUES (1,1,1000,1,12,'HTTP 200')")
                if (version == 2) {
                    sql.execSQL("ALTER TABLE services ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                    sql.execSQL("ALTER TABLE services ADD COLUMN expectedCode INTEGER NOT NULL DEFAULT 0")
                    sql.execSQL("ALTER TABLE services ADD COLUMN bodyType TEXT NOT NULL DEFAULT 'None'")
                    sql.execSQL("ALTER TABLE services ADD COLUMN expectedBody TEXT NOT NULL DEFAULT ''")
                }
                sql.version = version
            }
            val migrated = Room.databaseBuilder(context, MonitorDb::class.java, name).addMigrations(MonitorDb.MIGRATION_1_2, MonitorDb.MIGRATION_2_3, MonitorDb.MIGRATION_3_4).allowMainThreadQueries().build()
            try {
                val service = migrated.dao().getService(1)!!
                assertEquals(300, service.intervalSeconds)
                assertTrue(service.enabled)
                assertEquals(0, service.expectedCode)
                assertEquals(12L, migrated.dao().latest(1)!!.responseMs)
                assertEquals(1L, migrated.dao().rollups(1, 0).first().single().upCount)
            } finally { migrated.close(); context.deleteDatabase(name) }
        }
    }

}
