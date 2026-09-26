package com.selfcontrol.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ControlledAppConfigDao {
    @Query("SELECT * FROM controlled_app_configs WHERE packageName = :packageName AND enabled = 1 AND intentGateEnabled = 1 LIMIT 1")
    fun getEnabledConfig(packageName: String): ControlledAppConfig?

    @Query("SELECT * FROM controlled_app_configs ORDER BY packageName")
    fun getAllControlledApps(): List<ControlledAppConfig>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertConfigIfAbsent(config: ControlledAppConfig): Long

    @Query("UPDATE controlled_app_configs SET enabled = :enabled WHERE packageName = :packageName")
    fun updateEnabled(packageName: String, enabled: Boolean)

    @Query("UPDATE controlled_app_configs SET dailyQuotaMinutes = :dailyQuotaMinutes WHERE packageName = :packageName")
    fun updateDailyQuotaMinutes(packageName: String, dailyQuotaMinutes: Int?): Int

    @Query("UPDATE controlled_app_configs SET quotaEnforcementMode = :mode WHERE packageName = :packageName")
    fun updateQuotaEnforcementMode(packageName: String, mode: String): Int

    @Transaction
    fun setControlledAppEnabled(packageName: String, displayName: String, enabled: Boolean) {
        if (enabled) {
            insertConfigIfAbsent(ControlledAppConfig(packageName, displayName, true, true))
        }
        // Existing rows retain their displayName and intentGateEnabled; disabling never deletes.
        updateEnabled(packageName, enabled)
    }
}
