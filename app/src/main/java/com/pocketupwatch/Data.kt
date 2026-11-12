package com.pocketupwatch

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "services")
data class ServiceEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val url: String, val intervalSeconds: Int,
    val enabled: Boolean = true, val expectedCode: Int = 0,
    val bodyType: String = "None", val expectedBody: String = "",
    @ColumnInfo(defaultValue = "'GET'") val method: String = "GET",
    @ColumnInfo(defaultValue = "''") val requestBody: String = "",
    @ColumnInfo(defaultValue = "''") val headersCiphertext: String = "",
    @ColumnInfo(defaultValue = "''") val jsonPath: String = "",
    @ColumnInfo(defaultValue = "20") val timeoutSeconds: Int = 20,
    @ColumnInfo(defaultValue = "1") val followRedirects: Boolean = true,
    // The legacy SQL default remains unchanged; new monitors explicitly insert true.
    @ColumnInfo(defaultValue = "0") val requireValidatedInternet: Boolean = true,
    // Preserve the v4 SQL schema; every new monitor explicitly inserts the application default of 1.
    @ColumnInfo(defaultValue = "2") val failureThreshold: Int = 1,
    @ColumnInfo(defaultValue = "1") val recoveryThreshold: Int = 1,
    @ColumnInfo(defaultValue = "1") val notifyInitialDown: Boolean = true,
    @ColumnInfo(defaultValue = "0") val repeatMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val maintenanceUntil: Long = 0,
    @ColumnInfo(defaultValue = "30") val certificateWarnDays: Int = 30,
    @ColumnInfo(defaultValue = "''") val dnsExpected: String = "",
    @ColumnInfo(defaultValue = "''") val tags: String = ""
)
@Entity(tableName = "checks", foreignKeys = [ForeignKey(entity = ServiceEntry::class, parentColumns = ["id"], childColumns = ["serviceId"], onDelete = ForeignKey.CASCADE)], indices = [Index("serviceId"), Index(value = ["serviceId", "time", "id"])])
data class CheckEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val serviceId: Long, val time: Long, val up: Boolean, val responseMs: Long, val detail: String, @ColumnInfo(defaultValue = "0") val skipped: Boolean = false)
@Entity(tableName = "alert_states", foreignKeys = [ForeignKey(entity = ServiceEntry::class, parentColumns = ["id"], childColumns = ["serviceId"], onDelete = ForeignKey.CASCADE)])
data class AlertState(@PrimaryKey val serviceId: Long, val confirmed: String = "UNKNOWN", val downStreak: Int = 0, val upStreak: Int = 0, val candidateSince: Long = 0, val lastAlertAt: Long = 0)
@Entity(tableName = "incidents", foreignKeys = [ForeignKey(entity = ServiceEntry::class, parentColumns = ["id"], childColumns = ["serviceId"], onDelete = ForeignKey.CASCADE)], indices = [Index("serviceId")])
data class Incident(@PrimaryKey(autoGenerate = true) val id: Long = 0, val serviceId: Long, val startedAt: Long, val endedAt: Long? = null, val reason: String, val downChecks: Int = 1, val unknownChecks: Int = 0)
@Entity(tableName = "hourly_stats", primaryKeys = ["serviceId", "hour"], foreignKeys = [ForeignKey(entity = ServiceEntry::class, parentColumns = ["id"], childColumns = ["serviceId"], onDelete = ForeignKey.CASCADE)])
data class HourlyStats(val serviceId: Long, val hour: Long, val upCount: Long = 0, val downCount: Long = 0, val skippedCount: Long = 0, val responseTotal: Long = 0, val responseMax: Long = 0)
data class PeriodStats(val upCount: Long = 0, val downCount: Long = 0, val skippedCount: Long = 0)
data class ChartBucket(val time: Long, val upCount: Long, val downCount: Long, val skippedCount: Long, val responseMs: Long)
data class ServiceWidgetData(val service: ServiceEntry?, val latest: CheckEntry?, val buckets: List<ChartBucket>)

data class HistoryVersion(val total: Int = 0, val latestId: Long = 0)
data class HistoryPage(val checks: List<CheckEntry>, val total: Int, val page: Int, val anchorId: Long) {
    val pageCount: Int get() = maxOf(1, (total + 49) / 50)
}

@Dao interface MonitorDao {
    @Query("SELECT * FROM services ORDER BY id DESC") fun services(): Flow<List<ServiceEntry>>
    @Query("SELECT * FROM services") suspend fun allServices(): List<ServiceEntry>
    @Insert suspend fun add(service: ServiceEntry): Long
    @Delete suspend fun delete(service: ServiceEntry)
    @Update suspend fun update(service: ServiceEntry)
    @Query("UPDATE services SET enabled = :enabled") suspend fun setAllEnabled(enabled: Boolean)
    @Query("SELECT * FROM checks WHERE serviceId = :id ORDER BY time DESC, id DESC LIMIT 500") fun history(id: Long): Flow<List<CheckEntry>>
    @Query("SELECT * FROM checks WHERE serviceId = :id ORDER BY time DESC, id DESC LIMIT 1") fun latestFlow(id: Long): Flow<CheckEntry?>
    @Query("SELECT * FROM checks WHERE serviceId = :id ORDER BY time DESC, id DESC LIMIT 1") suspend fun latest(id: Long): CheckEntry?
    @Query("SELECT * FROM checks WHERE serviceId = :id ORDER BY time, id") suspend fun exportHistory(id: Long): List<CheckEntry>
    @Query("SELECT * FROM checks WHERE serviceId = :id AND time >= :since ORDER BY time DESC, id DESC LIMIT :limit OFFSET :offset") suspend fun page(id: Long, since: Long, limit: Int = 50, offset: Int = 0): List<CheckEntry>
    @Query("SELECT COUNT(*) AS total, COALESCE(MAX(id),0) AS latestId FROM checks WHERE serviceId = :id AND (:anchorId = 0 OR id <= :anchorId)") fun historyVersion(id: Long, anchorId: Long = 0): Flow<HistoryVersion>
    @Query("SELECT COALESCE(MAX(id),0) FROM checks WHERE serviceId = :id") suspend fun historyAnchor(id: Long): Long
    @Query("SELECT COUNT(*) FROM checks WHERE serviceId = :id AND id <= :anchorId") suspend fun historyCount(id: Long, anchorId: Long): Int
    @Query("SELECT * FROM checks WHERE serviceId = :id AND id <= :anchorId ORDER BY time DESC, id DESC LIMIT 50 OFFSET :offset") suspend fun historyRows(id: Long, anchorId: Long, offset: Int): List<CheckEntry>
    @Transaction suspend fun historyPage(id: Long, requestedPage: Int, anchorId: Long = 0): HistoryPage {
        val anchor = if (anchorId == 0L) historyAnchor(id) else anchorId
        val total = historyCount(id, anchor)
        val page = requestedPage.coerceIn(0, ((total - 1).coerceAtLeast(0)) / 50)
        return HistoryPage(historyRows(id, anchor, page * 50), total, page, anchor)
    }
    @Query("SELECT COALESCE(SUM(CASE WHEN up = 1 AND skipped = 0 THEN 1 ELSE 0 END),0) AS upCount, COALESCE(SUM(CASE WHEN up = 0 AND skipped = 0 THEN 1 ELSE 0 END),0) AS downCount, COALESCE(SUM(skipped),0) AS skippedCount FROM checks WHERE serviceId = :id AND time >= :since") fun stats(id: Long, since: Long): Flow<PeriodStats>
    @Query("SELECT (time / :bucketMs) * :bucketMs AS time, SUM(CASE WHEN up = 1 AND skipped = 0 THEN 1 ELSE 0 END) AS upCount, SUM(CASE WHEN up = 0 AND skipped = 0 THEN 1 ELSE 0 END) AS downCount, SUM(skipped) AS skippedCount, CAST(AVG(CASE WHEN skipped = 0 THEN responseMs END) AS INTEGER) AS responseMs FROM checks WHERE serviceId = :id AND time >= :since GROUP BY time / :bucketMs ORDER BY time") fun buckets(id: Long, since: Long, bucketMs: Long): Flow<List<ChartBucket>>
    @Query("SELECT (time / :bucketMs) * :bucketMs AS time, SUM(CASE WHEN up = 1 AND skipped = 0 THEN 1 ELSE 0 END) AS upCount, SUM(CASE WHEN up = 0 AND skipped = 0 THEN 1 ELSE 0 END) AS downCount, SUM(skipped) AS skippedCount, COALESCE(CAST(AVG(CASE WHEN skipped = 0 THEN responseMs END) AS INTEGER),0) AS responseMs FROM checks WHERE serviceId = :id AND time >= :since AND time <= :until GROUP BY time / :bucketMs ORDER BY time") suspend fun widgetBuckets(id: Long, since: Long, until: Long, bucketMs: Long): List<ChartBucket>
    @Transaction suspend fun widgetData(id: Long, since: Long, until: Long, bucketMs: Long): ServiceWidgetData {
        val service = getService(id)
        return if (service == null) ServiceWidgetData(null, null, emptyList())
        else ServiceWidgetData(service, latest(id), widgetBuckets(id, since, until, bucketMs))
    }
    @Query("SELECT * FROM incidents WHERE serviceId = :id ORDER BY startedAt DESC LIMIT 100") fun incidents(id: Long): Flow<List<Incident>>
    @Query("SELECT * FROM incidents WHERE serviceId = :id ORDER BY startedAt") suspend fun exportIncidents(id: Long): List<Incident>
    @Query("SELECT * FROM hourly_stats WHERE serviceId = :id AND hour >= :since ORDER BY hour") fun rollups(id: Long, since: Long): Flow<List<HourlyStats>>
    @Query("SELECT checks.* FROM services JOIN checks ON checks.id = (SELECT id FROM checks WHERE serviceId = services.id ORDER BY time DESC, id DESC LIMIT 1)") fun latestChecks(): Flow<List<CheckEntry>>
    @Insert suspend fun record(check: CheckEntry)
    @Query("SELECT * FROM services WHERE id = :id") suspend fun getService(id: Long): ServiceEntry?
    @Query("SELECT * FROM alert_states WHERE serviceId = :id") suspend fun alertState(id: Long): AlertState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAlertState(state: AlertState)
    @Insert suspend fun openIncident(incident: Incident)
    @Query("UPDATE incidents SET endedAt = :time WHERE serviceId = :id AND endedAt IS NULL") suspend fun closeIncidents(id: Long, time: Long)
    @Query("UPDATE incidents SET downChecks = downChecks + 1 WHERE serviceId = :id AND endedAt IS NULL") suspend fun countDown(id: Long)
    @Query("UPDATE incidents SET unknownChecks = unknownChecks + 1 WHERE serviceId = :id AND endedAt IS NULL") suspend fun countUnknown(id: Long)
    @Query("SELECT * FROM hourly_stats WHERE serviceId = :id AND hour = :hour") suspend fun hour(id: Long, hour: Long): HourlyStats?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveHour(hour: HourlyStats)
    @Query("DELETE FROM checks WHERE time < :before AND id NOT IN (SELECT MAX(id) FROM checks GROUP BY serviceId)") suspend fun pruneChecks(before: Long)
    @Query("DELETE FROM hourly_stats WHERE hour < :before") suspend fun pruneHours(before: Long)
    @Transaction suspend fun importServices(services: List<ServiceEntry>) { services.forEach { add(it.copy(id = 0, enabled = false)) } }
    @Transaction suspend fun recordIfUnchanged(service: ServiceEntry, check: CheckEntry): String? {
        if (getService(service.id) != service || !service.enabled) return null
        record(check)
        val hour = check.time / 3_600_000 * 3_600_000
        val oldHour = hour(service.id, hour) ?: HourlyStats(service.id, hour)
        saveHour(oldHour.copy(upCount = oldHour.upCount + if (check.up && !check.skipped) 1 else 0, downCount = oldHour.downCount + if (!check.up && !check.skipped) 1 else 0, skippedCount = oldHour.skippedCount + if (check.skipped) 1 else 0, responseTotal = oldHour.responseTotal + if (check.skipped) 0 else check.responseMs, responseMax = maxOf(oldHour.responseMax, if (check.skipped) 0 else check.responseMs)))
        val old = alertState(service.id) ?: AlertState(service.id)
        val decision = AlertPolicy.evaluate(old, service, check)
        saveAlertState(decision.state)
        if (check.skipped) countUnknown(service.id)
        else if (!check.up && old.confirmed == "DOWN") countDown(service.id)
        if (old.confirmed != "DOWN" && decision.state.confirmed == "DOWN") openIncident(Incident(serviceId = service.id, startedAt = decision.state.candidateSince, reason = check.detail, downChecks = decision.state.downStreak))
        if (old.confirmed == "DOWN" && decision.state.confirmed == "UP") closeIncidents(service.id, check.time)
        return decision.notification
    }
}

@Database(entities = [ServiceEntry::class, CheckEntry::class, AlertState::class, Incident::class, HourlyStats::class], version = 4, exportSchema = true)
abstract class MonitorDb : RoomDatabase() {
    abstract fun dao(): MonitorDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE services ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1"); db.execSQL("ALTER TABLE services ADD COLUMN expectedCode INTEGER NOT NULL DEFAULT 0"); db.execSQL("ALTER TABLE services ADD COLUMN bodyType TEXT NOT NULL DEFAULT 'None'"); db.execSQL("ALTER TABLE services ADD COLUMN expectedBody TEXT NOT NULL DEFAULT ''") } }
        val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("UPDATE services SET intervalSeconds = 300 WHERE intervalSeconds < 300") } }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                mapOf("method" to "TEXT NOT NULL DEFAULT 'GET'", "requestBody" to "TEXT NOT NULL DEFAULT ''", "headersCiphertext" to "TEXT NOT NULL DEFAULT ''", "jsonPath" to "TEXT NOT NULL DEFAULT ''", "timeoutSeconds" to "INTEGER NOT NULL DEFAULT 20", "followRedirects" to "INTEGER NOT NULL DEFAULT 1", "requireValidatedInternet" to "INTEGER NOT NULL DEFAULT 0", "failureThreshold" to "INTEGER NOT NULL DEFAULT 2", "recoveryThreshold" to "INTEGER NOT NULL DEFAULT 1", "notifyInitialDown" to "INTEGER NOT NULL DEFAULT 1", "repeatMinutes" to "INTEGER NOT NULL DEFAULT 0", "maintenanceUntil" to "INTEGER NOT NULL DEFAULT 0", "certificateWarnDays" to "INTEGER NOT NULL DEFAULT 30", "dnsExpected" to "TEXT NOT NULL DEFAULT ''", "tags" to "TEXT NOT NULL DEFAULT ''").forEach { (name, sql) -> db.execSQL("ALTER TABLE services ADD COLUMN $name $sql") }
                db.execSQL("ALTER TABLE checks ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_checks_serviceId_time_id ON checks(serviceId,time,id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS alert_states (serviceId INTEGER NOT NULL, confirmed TEXT NOT NULL, downStreak INTEGER NOT NULL, upStreak INTEGER NOT NULL, candidateSince INTEGER NOT NULL, lastAlertAt INTEGER NOT NULL, PRIMARY KEY(serviceId), FOREIGN KEY(serviceId) REFERENCES services(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, serviceId INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, reason TEXT NOT NULL, downChecks INTEGER NOT NULL, unknownChecks INTEGER NOT NULL, FOREIGN KEY(serviceId) REFERENCES services(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incidents_serviceId ON incidents(serviceId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS hourly_stats (serviceId INTEGER NOT NULL, hour INTEGER NOT NULL, upCount INTEGER NOT NULL, downCount INTEGER NOT NULL, skippedCount INTEGER NOT NULL, responseTotal INTEGER NOT NULL, responseMax INTEGER NOT NULL, PRIMARY KEY(serviceId,hour), FOREIGN KEY(serviceId) REFERENCES services(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO hourly_stats SELECT serviceId, (time / 3600000) * 3600000, SUM(CASE WHEN up = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN up = 0 THEN 1 ELSE 0 END), 0, SUM(responseMs), MAX(responseMs) FROM checks GROUP BY serviceId, time / 3600000")
            }
        }
        @Volatile private var instance: MonitorDb? = null
        fun get(context: Context): MonitorDb = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, MonitorDb::class.java, "monitor.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it } }
    }
}
