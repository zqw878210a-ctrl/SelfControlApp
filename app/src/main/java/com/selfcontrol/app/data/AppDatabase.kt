package com.selfcontrol.app.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executors

@Database(entities = [GateEvent::class, ControlledAppConfig::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gateEventDao(): GateEventDao
    abstract fun controlledAppConfigDao(): ControlledAppConfigDao

    companion object {
        const val LOG_TAG = "SELF_CONTROL_DATA"
        const val CONFIG_LOG_TAG = "SELF_CONTROL_CONFIG"
        // Application-scoped queue: leaving an Activity/Service does not cancel a write.
        val executor = Executors.newSingleThreadExecutor()
        @Volatile private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS controlled_app_configs (
                        packageName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        intentGateEnabled INTEGER NOT NULL,
                        PRIMARY KEY(packageName)
                    )
                """.trimIndent())
                insertDefaultControlledApp(db)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE controlled_app_configs ADD COLUMN dailyQuotaMinutes INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE controlled_app_configs ADD COLUMN quotaEnforcementMode TEXT NOT NULL DEFAULT 'MODERATE'")
            }
        }

        private val CREATE_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertDefaultControlledApp(db)
            }
        }

        private fun insertDefaultControlledApp(db: SupportSQLiteDatabase) {
            // Preserve an existing row, including any disabled flags.
            db.execSQL("""
                INSERT OR IGNORE INTO controlled_app_configs
                    (packageName, displayName, enabled, intentGateEnabled)
                VALUES ('com.ss.android.ugc.aweme', '抖音', 1, 1)
            """.trimIndent())
        }

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "self_control.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(CREATE_CALLBACK)
                .build().also { instance = it }
        }

        fun recordGateEvent(context: Context, event: GateEvent) {
            try {
                val appContext = context.applicationContext
                executor.execute {
                    try {
                        val id = getInstance(appContext).gateEventDao().insertGateEvent(event)
                        Log.i(LOG_TAG, "GateEvent saved id=$id action=${event.action} eventTime=${event.eventTime}")
                    } catch (error: Exception) {
                        Log.e(LOG_TAG, "GateEvent write failed action=${event.action} eventTime=${event.eventTime}", error)
                    }
                }
            } catch (error: Exception) {
                Log.e(LOG_TAG, "GateEvent enqueue failed", error)
            }
        }
    }
}
