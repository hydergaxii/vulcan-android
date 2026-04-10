package com.vulcan.app.data.database.dao

import androidx.room.*
import com.vulcan.app.data.database.entities.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// APP DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface AppDao {
    @Query("SELECT * FROM installed_apps ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<AppEntity>>

    @Query("SELECT * FROM installed_apps ORDER BY installedAt DESC")
    suspend fun getAll(): List<AppEntity>

    @Query("SELECT * FROM installed_apps WHERE id = :id")
    suspend fun getById(id: String): AppEntity?

    @Query("SELECT * FROM installed_apps WHERE isAutoStart = 1")
    suspend fun getAutoStartApps(): List<AppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: AppEntity)

    @Update
    suspend fun update(app: AppEntity)

    @Delete
    suspend fun delete(app: AppEntity)

    @Query("DELETE FROM installed_apps WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE installed_apps SET lastStartedAt = :timestamp WHERE id = :id")
    suspend fun updateLastStarted(id: String, timestamp: Long)

    @Query("UPDATE installed_apps SET updateAvailable = :available, latestVersion = :version WHERE id = :id")
    suspend fun updateAvailability(id: String, available: Boolean, version: String?)

    @Query("SELECT COUNT(*) FROM installed_apps")
    suspend fun getCount(): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// SLOT DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface SlotDao {
    @Query("SELECT * FROM launcher_slots ORDER BY `index`")
    fun observeAll(): Flow<List<SlotEntity>>

    @Query("SELECT * FROM launcher_slots ORDER BY `index`")
    suspend fun getAll(): List<SlotEntity>

    @Query("SELECT * FROM launcher_slots WHERE `index` = :index")
    suspend fun getByIndex(index: Int): SlotEntity?

    @Query("SELECT * FROM launcher_slots WHERE appId = :appId")
    suspend fun getByAppId(appId: String): SlotEntity?

    @Query("SELECT * FROM launcher_slots WHERE appId IS NULL LIMIT 1")
    suspend fun getFirstFreeSlot(): SlotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: SlotEntity)

    @Update
    suspend fun update(slot: SlotEntity)

    @Query("UPDATE launcher_slots SET appId = NULL, appLabel = NULL, iconPath = NULL, isActive = 0 WHERE `index` = :index")
    suspend fun clearSlot(index: Int)

    @Query("UPDATE launcher_slots SET appId = NULL, appLabel = NULL, iconPath = NULL, isActive = 0 WHERE appId = :appId")
    suspend fun clearSlotByAppId(appId: String)

    // Initialize 10 empty slots on first run
    @Query("SELECT COUNT(*) FROM launcher_slots")
    suspend fun getCount(): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// METRICS DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface MetricsDao {
    @Query("SELECT * FROM metrics_history WHERE appId = :appId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getForApp(appId: String, limit: Int = 360): List<MetricsEntry>

    @Query("SELECT * FROM metrics_history WHERE appId = :appId AND timestamp > :since ORDER BY timestamp ASC")
    suspend fun getForAppSince(appId: String, since: Long): List<MetricsEntry>

    @Query("SELECT * FROM metrics_history WHERE timestamp > :since ORDER BY timestamp ASC")
    fun observeAllSince(since: Long): Flow<List<MetricsEntry>>

    @Insert
    suspend fun insert(entry: MetricsEntry)

    // Keep only last 24 hours of metrics — prune older records
    @Query("DELETE FROM metrics_history WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("SELECT AVG(cpuPercent) FROM metrics_history WHERE appId = :appId AND timestamp > :since")
    suspend fun getAvgCpu(appId: String, since: Long): Float?

    @Query("SELECT MAX(ramMB) FROM metrics_history WHERE appId = :appId AND timestamp > :since")
    suspend fun getMaxRam(appId: String, since: Long): Float?
}

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT LOG DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT 500")
    fun observeRecent(): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_log WHERE severity = 'critical' ORDER BY timestamp DESC LIMIT 50")
    suspend fun getCritical(): List<AuditEntry>

    @Insert
    suspend fun insert(entry: AuditEntry)

    // Keep only last 30 days in the audit log
    @Query("DELETE FROM audit_log WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
