package com.selfcontrol.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "controlled_app_configs")
data class ControlledAppConfig(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    val intentGateEnabled: Boolean,
    val dailyQuotaMinutes: Int? = null,
    @ColumnInfo(defaultValue = "'MODERATE'")
    val quotaEnforcementMode: String = "MODERATE"
)
