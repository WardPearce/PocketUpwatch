package com.pocketupwatch

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "services")
data class ServiceEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val url: String, val intervalSeconds: Int, val enabled: Boolean = true, val expectedCode: Int = 0, val bodyType: String = "None", val expectedBody: String = "")
@Entity(tableName = "checks", foreignKeys = [ForeignKey(entity = ServiceEntry::class, parentColumns = ["id"], childColumns = ["serviceId"], onDelete = ForeignKey.CASCADE)], indices = [Index("serviceId")])
data class CheckEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val serviceId: Long, val time: Long, val up: Boolean, val responseMs: Long, val detail: String)
@Dao interface MonitorDao {
    @Query("SELECT * FROM services ORDER BY id DESC") fun services(): Flow<List<ServiceEntry>>
    @Query("SELECT * FROM services") suspend fun allServices(): List<ServiceEntry>
    @Insert suspend fun add(service: ServiceEntry)
    @Delete suspend fun delete(service: ServiceEntry)
    @Update suspend fun update(service: ServiceEntry)
    @Query("SELECT * FROM checks WHERE serviceId = :id ORDER BY time DESC, id DESC LIMIT 500") fun history(id: Long): Flow<List<CheckEntry>>
    @Query("SELECT * FROM checks WHERE serviceId = :id ORDER BY time DESC, id DESC LIMIT 1") suspend fun latest(id: Long): CheckEntry?
    @Insert suspend fun record(check: CheckEntry)
    @Query("SELECT * FROM services WHERE id = :id") suspend fun getService(id: Long): ServiceEntry?
    @Transaction suspend fun recordIfUnchanged(service: ServiceEntry, check: CheckEntry): Boolean {
        if (getService(service.id) != service || !service.enabled) return false
        record(check)
        return true
    }
}
@Database(entities = [ServiceEntry::class, CheckEntry::class], version = 3, exportSchema = false)
abstract class MonitorDb : RoomDatabase() {
    abstract fun dao(): MonitorDao
    companion object {
        @Volatile private var instance: MonitorDb? = null
        fun get(context: Context): MonitorDb = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, MonitorDb::class.java, "monitor.db").addMigrations(object : androidx.room.migration.Migration(1, 2) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE services ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1"); db.execSQL("ALTER TABLE services ADD COLUMN expectedCode INTEGER NOT NULL DEFAULT 0"); db.execSQL("ALTER TABLE services ADD COLUMN bodyType TEXT NOT NULL DEFAULT 'None'"); db.execSQL("ALTER TABLE services ADD COLUMN expectedBody TEXT NOT NULL DEFAULT ''") } }, object : androidx.room.migration.Migration(2, 3) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("UPDATE services SET intervalSeconds = 300 WHERE intervalSeconds < 300") } }).build().also { instance = it } }
    }
}
