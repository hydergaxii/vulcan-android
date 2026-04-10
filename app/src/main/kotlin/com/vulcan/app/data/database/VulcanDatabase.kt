package com.vulcan.app.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vulcan.app.data.database.dao.*
import com.vulcan.app.data.database.entities.*

@Database(
    entities = [
        AppEntity::class,
        SlotEntity::class,
        MetricsEntry::class,
        AuditEntry::class
    ],
    version = 1,
    exportSchema = true
)
abstract class VulcanDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao
    abstract fun slotDao(): SlotDao
    abstract fun metricsDao(): MetricsDao
    abstract fun auditDao(): AuditDao

    companion object {
        private const val DB_NAME = "vulcan.db"

        @Volatile
        private var INSTANCE: VulcanDatabase? = null

        fun getInstance(context: Context): VulcanDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VulcanDatabase::class.java,
                    DB_NAME
                )
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Initialize 10 empty launcher slots on first create
            for (i in 0..9) {
                db.execSQL(
                    "INSERT INTO launcher_slots (`index`, appId, appLabel, iconPath, isActive) " +
                    "VALUES ($i, NULL, NULL, NULL, 0)"
                )
            }
        }
    }
}
